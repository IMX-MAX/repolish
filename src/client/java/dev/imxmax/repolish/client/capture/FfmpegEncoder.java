package dev.imxmax.repolish.client.capture;

import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.capture.EncoderChoice;
import dev.imxmax.repolish.capture.PixelPack;
import dev.imxmax.repolish.client.FfmpegInstaller;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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
	private String encoderName;
	private byte[] lastRgba;
	private Thread drain;
	private static final ConcurrentHashMap<String, String> ENCODERS = new ConcurrentHashMap<>();

	public FfmpegEncoder(Path output, int fps, boolean wantAudio, String binary) {
		this.output = output;
		this.fps = fps;
		this.wantAudio = wantAudio;
		this.binary = binary;
	}

	public static String binary() {
		String installed = null;
		String installedEncoder = null;
		Path installedPath = FfmpegInstaller.installedBinary();
		if (installedPath != null) {
			installed = installedPath.toAbsolutePath().toString();
			installedEncoder = videoEncoder(installed);
		}
		String pathEncoder = videoEncoder("ffmpeg");
		if ("libx264".equals(pathEncoder) && !"libx264".equals(installedEncoder)) {
			return "ffmpeg";
		}
		if (installedEncoder != null) {
			return installed;
		}
		if (pathEncoder != null) {
			return "ffmpeg";
		}
		return null;
	}

	public static String videoEncoder(String binary) {
		String cached = ENCODERS.get(binary);
		if (cached != null) {
			return cached.isEmpty() ? null : cached;
		}
		String listing = commandOutput(binary, "-hide_banner", "-encoders");
		if (listing == null) {
			return null;
		}
		String chosen = EncoderChoice.choose(listing);
		ENCODERS.put(binary, chosen == null ? "" : chosen);
		return chosen;
	}

	public static boolean available() {
		return binary() != null;
	}

	@Override
	public void write(int[] abgr, int width, int height) throws IOException {
		byte[] rgba = PixelPack.abgrToRgba(abgr, width * height);
		this.lastRgba = rgba;
		writeBytes(rgba, width, height);
	}

	@Override
	public void repeatLast(int times) throws IOException {
		if (this.lastRgba == null || times <= 0) {
			return;
		}
		for (int i = 0; i < times; i++) {
			writeBytes(this.lastRgba, this.width, this.height);
		}
	}

	private void writeBytes(byte[] rgba, int width, int height) throws IOException {
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
					this.encoderName = videoEncoder(this.binary);
					if (this.encoderName == null) {
						throw new IOException("ffmpeg has no usable video encoder.");
					}
					start();
				} else {
					recoverOrThrow();
				}
			}
			try {
				this.stdin.write(rgba);
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
			Repolish.LOGGER.warn("ffmpeg exited while opening game audio. Retrying video only. {}", stderr);
			this.audio = false;
			start();
			return;
		}
		if (!"mpeg4".equals(this.encoderName)) {
			Repolish.LOGGER.warn("ffmpeg encoder {} failed. Retrying mpeg4. {}", this.encoderName, stderr);
			this.encoderName = "mpeg4";
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
		command.add(this.encoderName);
		if ("libx264".equals(this.encoderName)) {
			command.add("-preset");
			command.add("veryfast");
			command.add("-crf");
			command.add("18");
		} else if ("mpeg4".equals(this.encoderName)) {
			command.add("-q:v");
			command.add("3");
		}
		command.add("-pix_fmt");
		command.add("yuv420p");
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
