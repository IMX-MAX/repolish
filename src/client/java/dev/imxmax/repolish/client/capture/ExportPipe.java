package dev.imxmax.repolish.client.capture;

import dev.imxmax.repolish.Repolish;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Pipes raw RGBA frames straight into ffmpeg while the export renders, so the
 * take never round-trips through a giant uncompressed spool file and encoding
 * finishes moments after the last frame is rendered.
 *
 * The output is scaled to the exact resolution chosen in the export settings
 * (lanczos), so the video always matches the configured settings rather than
 * whatever size the window happened to be.
 */
public final class ExportPipe {
	private final Process process;
	private final OutputStream stdin;
	private final Thread drain;
	private final StringBuilder errors = new StringBuilder();
	private final Path output;
	private final boolean audio;
	private int frameWidth;
	private int frameHeight;

	private ExportPipe(Process process, OutputStream stdin, Thread drain, Path output, boolean audio) {
		this.process = process;
		this.stdin = stdin;
		this.drain = drain;
		this.output = output;
		this.audio = audio;
	}

	public static ExportPipe open(
		String binary,
		String encoder,
		int fps,
		Path directory,
		String baseName,
		int targetWidth,
		int targetHeight,
		Path audioWav,
		double audioOffsetSeconds,
		int sourceWidth,
		int sourceHeight
	) throws IOException {
		boolean includeAudio = audioWav != null && Files.exists(audioWav) && Files.size(audioWav) > 44L;
		Path output = uniqueFile(directory, baseName, ".mp4");
		List<String> command = new ArrayList<>();
		command.add(binary);
		command.add("-y");
		command.add("-loglevel");
		command.add("error");
		command.add("-f");
		command.add("rawvideo");
		command.add("-pix_fmt");
		command.add("rgba");
		command.add("-s");
		command.add(sourceWidth + "x" + sourceHeight);
		command.add("-framerate");
		command.add(Integer.toString(Math.max(1, fps)));
		command.add("-i");
		command.add("pipe:0");
		if (includeAudio) {
			if (audioOffsetSeconds > 0.005) {
				command.add("-ss");
				command.add(String.format(Locale.ROOT, "%.3f", audioOffsetSeconds));
			}
			command.add("-i");
			command.add(audioWav.toString());
		}
		command.add("-vf");
		if (targetWidth > 0 && targetHeight > 0) {
			command.add("scale=" + targetWidth + ":" + targetHeight + ":flags=lanczos");
		} else {
			command.add("scale=trunc(iw/2)*2:trunc(ih/2)*2");
		}
		if (includeAudio) {
			command.add("-map");
			command.add("0:v:0");
			command.add("-map");
			command.add("1:a:0");
			command.add("-c:a");
			command.add("aac");
			command.add("-b:a");
			command.add("192k");
			command.add("-shortest");
		}
		command.add("-c:v");
		command.add(encoder);
		if ("libx264".equals(encoder)) {
			command.add("-preset");
			command.add("fast");
			command.add("-crf");
			command.add("14");
		} else if ("h264_mf".equals(encoder)) {
			command.add("-b:v");
			command.add("45M");
		} else if ("mpeg4".equals(encoder)) {
			command.add("-q:v");
			command.add("1");
		}
		command.add("-pix_fmt");
		command.add("yuv420p");
		command.add("-movflags");
		command.add("+faststart");
		command.add(output.toString());

		Process process = new ProcessBuilder(command).start();
		OutputStream stdin = new BufferedOutputStream(process.getOutputStream(), 1 << 20);
		StringBuilder errors = new StringBuilder();
		Thread drain = new Thread(() -> drainStderr(process, errors), "repolish-export-pipe");
		drain.setDaemon(true);
		drain.start();
		return new ExportPipe(process, stdin, drain, output, includeAudio);
	}

	public void write(byte[] rgba, int width, int height) throws IOException {
		if (this.frameWidth == 0) {
			this.frameWidth = width;
			this.frameHeight = height;
		} else if (width != this.frameWidth || height != this.frameHeight) {
			throw new IOException("Export frame size changed from " + this.frameWidth + "x" + this.frameHeight + " to " + width + "x" + height);
		}
		try {
			this.stdin.write(rgba);
		} catch (IOException exception) {
			throw new IOException("ffmpeg exited during export. " + errorText(), exception);
		}
	}

	/**
	 * Closes the frame stream and waits for ffmpeg to finish the file.
	 * Must be called after the last frame was written.
	 */
	public boolean finish() {
		try {
			this.stdin.close();
		} catch (IOException exception) {
			Repolish.LOGGER.warn("Could not close ffmpeg frame stream", exception);
		}
		try {
			if (!this.process.waitFor(10, TimeUnit.MINUTES)) {
				this.process.destroyForcibly();
				return false;
			}
			joinQuietly(this.drain);
			boolean ok = this.process.exitValue() == 0;
			if (!ok) {
				Repolish.LOGGER.warn("ffmpeg export encode failed: {}", errorText());
			}
			return ok;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			this.process.destroyForcibly();
			return false;
		}
	}

	public void abort() {
		try {
			this.stdin.close();
		} catch (IOException ignored) {
		}
		this.process.destroyForcibly();
	}

	public Path output() {
		return this.output;
	}

	public boolean audio() {
		return this.audio;
	}

	private String errorText() {
		joinQuietly(this.drain);
		synchronized (this.errors) {
			return this.errors.toString().trim();
		}
	}

	private static void drainStderr(Process process, StringBuilder target) {
		try {
			byte[] bytes = process.getErrorStream().readAllBytes();
			synchronized (target) {
				target.append(new String(bytes, StandardCharsets.UTF_8));
			}
		} catch (IOException ignored) {
		}
	}

	private static void joinQuietly(Thread thread) {
		try {
			thread.join(1_000L);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private static Path uniqueFile(Path directory, String name, String extension) throws IOException {
		Files.createDirectories(directory);
		Path candidate = directory.resolve(name + extension);
		int suffix = 2;
		while (Files.exists(candidate)) {
			candidate = directory.resolve(name + "-" + suffix + extension);
			suffix++;
		}
		return candidate;
	}
}
