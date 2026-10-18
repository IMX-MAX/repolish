package dev.imxmax.repolish.client.capture;

import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.capture.PixelPack;
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
		if (this.process == null) {
			this.width = width;
			this.height = height;
			this.audio = this.wantAudio && (FfmpegInstaller.isWindows() || monitorAvailable());
			start();
		}
		if (width != this.width || height != this.height) {
			throw new IOException("Frame size changed from " + this.width + "x" + this.height + " to " + width + "x" + height);
		}
		if (!this.process.isAlive()) {
			if (this.audio) {
				Repolish.LOGGER.warn("ffmpeg exited while opening game audio. Retrying video only. {}", this.errors);
				this.audio = false;
				start();
			} else {
				throw new IOException("ffmpeg exited. " + this.errors);
			}
		}
		this.stdin.write(PixelPack.abgrToRgba(abgr, width * height));
	}

	@Override
	public void close() throws IOException {
		if (this.stdin != null) {
			this.stdin.close();
			this.stdin = null;
		}
		if (this.process != null) {
			try {
				if (!this.process.waitFor(30, TimeUnit.SECONDS)) {
					this.process.destroyForcibly();
					throw new IOException("ffmpeg did not finish. " + this.errors);
				}
				if (this.process.exitValue() != 0) {
					throw new IOException("ffmpeg failed. " + this.errors);
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
		command.add("rawvideo");
		command.add("-pix_fmt");
		command.add("rgba");
		command.add("-s");
		command.add(this.width + "x" + this.height);
		command.add("-r");
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
			command.add("-c:a");
			command.add("aac");
			command.add("-b:a");
			command.add("192k");
		}
		command.add("-c:v");
		command.add("libx264");
		command.add("-preset");
		command.add("veryfast");
		command.add("-crf");
		command.add("18");
		command.add("-pix_fmt");
		command.add("yuv420p");
		command.add(this.output.toString());

		this.errors.setLength(0);
		this.process = new ProcessBuilder(command).start();
		this.stdin = this.process.getOutputStream();
		Thread drain = new Thread(() -> drain(this.process), "repolish-ffmpeg");
		drain.setDaemon(true);
		drain.start();
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
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return false;
		}
	}
}
