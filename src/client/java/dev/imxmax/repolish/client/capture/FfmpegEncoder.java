package dev.imxmax.repolish.client.capture;

import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.capture.JpegFrames;
import dev.imxmax.repolish.client.FfmpegInstaller;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class FfmpegEncoder implements FrameEncoder {
	private final Path output;
	private final int fps;
	private final boolean wantAudio;
	private final String binary;
	private Process process;
	private OutputStream stdin;
	private boolean audio;
	private int width;
	private int height;
	private final StringBuilder errors = new StringBuilder();
	private byte[] lastJpeg;
	private Thread drain;

	public FfmpegEncoder(Path output, int fps, boolean wantAudio, String binary) {
		this.output = output;
		this.fps = fps;
		this.wantAudio = wantAudio;
		this.binary = binary;
	}

	public static String binary() {
		Path installed = FfmpegInstaller.installedBinary();
		if (installed != null && commandWorks(installed.toAbsolutePath().toString(), "-version")) {
			return installed.toAbsolutePath().toString();
		}
		if (commandWorks("ffmpeg", "-version")) {
			return "ffmpeg";
		}
		return null;
	}

	public static boolean available() {
		return binary() != null;
	}

	@Override
	public void write(int[] abgr, int width, int height) throws IOException {
		this.lastJpeg = JpegFrames.fromAbgr(abgr, width, height, JpegFrames.CAPTURE_QUALITY);
		writeBytes(this.lastJpeg, width, height);
	}

	@Override
	public void repeatLast(int times) throws IOException {
		if (this.lastJpeg == null || times <= 0) {
			return;
		}
		for (int i = 0; i < times; i++) {
			writeBytes(this.lastJpeg, this.width, this.height);
		}
	}

	private void writeBytes(byte[] jpeg, int width, int height) throws IOException {
		if (width != this.width || height != this.height) {
			if (this.process != null) {
				throw new IOException("Frame size changed from " + this.width + "x" + this.height + " to " + width + "x" + height);
			}
			this.width = width;
			this.height = height;
		}
		for (int attempt = 0; attempt < 3; attempt++) {
			if (this.process == null || !this.process.isAlive()) {
				if (this.process == null) {
					this.audio = this.wantAudio && (FfmpegInstaller.isWindows() || monitorAvailable());
					start();
				} else {
					recoverOrThrow();
				}
			}
			try {
				this.stdin.write(jpeg);
				return;
			} catch (IOException exception) {
				if (attempt == 2) {
					throw new IOException("ffmpeg exited. " + errorText(), exception);
				}
				recoverOrThrow();
			}
		}
	}

	private void recoverOrThrow() throws IOException {
		String stderr = errorText();
		if (this.audio) {
			Repolish.LOGGER.warn("ffmpeg exited while opening game audio. Saving the picture without audio. {}", stderr);
			this.audio = false;
			start();
			return;
		}
		throw new IOException("ffmpeg exited. " + stderr);
	}

	@Override
	public void close() throws IOException {
		if (this.stdin != null) {
			try {
				this.stdin.close();
			} catch (IOException exception) {
				Repolish.LOGGER.warn("ffmpeg pipe closed early", exception);
			}
			this.stdin = null;
		}
		if (this.process != null) {
			try {
				if (!this.process.waitFor(30, TimeUnit.SECONDS)) {
					this.process.destroyForcibly();
					throw new IOException("ffmpeg did not finish. " + errorText());
				}
				awaitErrors();
				if (this.process.exitValue() != 0) {
					throw new IOException("ffmpeg failed. " + errorText());
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while finishing the video", exception);
			}
		}
	}

	@Override
	public Path output() {
		return this.output;
	}

	@Override
	public boolean capturedAudio() {
		return this.audio;
	}

	private void start() throws IOException {
		if (this.process != null) {
			this.process.destroyForcibly();
		}
		List<String> command = new ArrayList<>();
		command.add(this.binary);
		command.add("-y");
		command.add("-loglevel");
		command.add("error");
		command.add("-f");
		command.add("mjpeg");
		command.add("-framerate");
		command.add(Integer.toString(this.fps));
		command.add("-i");
		command.add("pipe:0");
		if (this.audio) {
			if (FfmpegInstaller.isWindows()) {
				command.add("-f");
				command.add("wasapi");
				command.add("-loopback");
				command.add("1");
				command.add("-i");
				command.add("default");
			} else {
				command.add("-f");
				command.add("pulse");
				command.add("-i");
				command.add(monitorName());
			}
			command.add("-map");
			command.add("0:v:0");
			command.add("-map");
			command.add("1:a:0");
			command.add("-c:a");
			command.add("pcm_s16le");
			command.add("-shortest");
		} else {
			command.add("-an");
		}
		command.add("-c:v");
		command.add("copy");
		command.add(this.output.toString());

		this.errors.setLength(0);
		this.process = new ProcessBuilder(command).start();
		this.stdin = this.process.getOutputStream();
		this.drain = new Thread(() -> drain(this.process), "repolish-ffmpeg");
		this.drain.setDaemon(true);
		this.drain.start();
	}

	private void awaitErrors() {
		if (this.drain == null) {
			return;
		}
		try {
			this.drain.join(1_000L);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private String errorText() {
		awaitErrors();
		synchronized (this.errors) {
			return this.errors.toString().trim();
		}
	}

	private void drain(Process process) {
		try {
			byte[] buffer = process.getErrorStream().readAllBytes();
			synchronized (this.errors) {
				this.errors.append(new String(buffer, StandardCharsets.UTF_8));
			}
		} catch (IOException exception) {
			Repolish.LOGGER.debug("Could not read ffmpeg output", exception);
		}
	}

	private static boolean monitorAvailable() {
		return commandWorks("pactl", "info");
	}

	private static String monitorName() {
		try {
			Process process = new ProcessBuilder("pactl", "get-default-sink").start();
			String sink = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 && !sink.isEmpty()) {
				return sink + ".monitor";
			}
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
		}
		return "default.monitor";
	}

	private static boolean commandWorks(String... command) {
		return commandOutput(command) != null;
	}

	private static String commandOutput(String... command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
				return output;
			}
			return null;
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return null;
		}
	}
}
