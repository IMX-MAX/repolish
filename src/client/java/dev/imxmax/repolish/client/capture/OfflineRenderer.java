package dev.imxmax.repolish.client.capture;

import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.capture.EncoderChoice;
import dev.imxmax.repolish.capture.FrameScaler;
import dev.imxmax.repolish.capture.JpegFrames;
import dev.imxmax.repolish.capture.MjpegAviWriter;
import dev.imxmax.repolish.capture.PixelPack;
import dev.imxmax.repolish.capture.PlaybackClock;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Streams uncompressed frames to a temporary spool while the take is running,
 * then renders the final video after the take stops.
 */
public final class OfflineRenderer {
	private OfflineRenderer() {
	}

	public static final class Spool implements AutoCloseable {
		private final Path file;
		private final DataOutputStream output;
		private int frames;

		public Spool(Path file) throws IOException {
			this.file = file;
			this.output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 20));
		}

		public void write(long capturedAtNanos, int width, int height, int[] abgr) throws IOException {
			byte[] rgba = PixelPack.abgrToRgba(abgr, width * height);
			this.output.writeLong(capturedAtNanos);
			this.output.writeInt(width);
			this.output.writeInt(height);
			this.output.write(rgba);
			this.frames++;
		}

		public int frames() {
			return this.frames;
		}

		public Path file() {
			return this.file;
		}

		@Override
		public void close() throws IOException {
			this.output.close();
		}
	}

	public static Result render(
		Path spoolFile,
		int capturedFrames,
		Path directory,
		String baseName,
		int fps,
		int maxWidth,
		int maxHeight,
		Path audioWav,
		String ffmpegBinary,
		IntConsumer progressPercent
	) throws IOException {
		return render(spoolFile, capturedFrames, directory, baseName, fps, maxWidth, maxHeight, audioWav, 0.0, true, ffmpegBinary, progressPercent);
	}

	public static Result render(
		Path spoolFile,
		int capturedFrames,
		Path directory,
		String baseName,
		int fps,
		int maxWidth,
		int maxHeight,
		Path audioWav,
		double audioOffsetSeconds,
		boolean deleteAudioWav,
		String ffmpegBinary,
		IntConsumer progressPercent
	) throws IOException {
		if (capturedFrames <= 0 || !Files.exists(spoolFile)) {
			deleteQuietly(spoolFile);
			if (deleteAudioWav) {
				deleteQuietly(audioWav);
			}
			return new Result(null, false);
		}
		String encoder = ffmpegBinary != null ? videoEncoder(ffmpegBinary) : null;
		try {
			if (ffmpegBinary != null && encoder != null) {
				Path mp4 = uniqueFile(directory, baseName, ".mp4");
				try {
					boolean audio = renderMp4(spoolFile, capturedFrames, mp4, fps, maxWidth, maxHeight, audioWav, audioOffsetSeconds, ffmpegBinary, encoder, progressPercent);
					return new Result(mp4, audio);
				} catch (IOException exception) {
					Repolish.LOGGER.warn("ffmpeg render failed, falling back to high-quality AVI", exception);
					deleteQuietly(mp4);
				}
			}
			Path avi = uniqueFile(directory, baseName, ".avi");
			renderAvi(spoolFile, capturedFrames, avi, fps, maxWidth, maxHeight, progressPercent);
			boolean audio = false;
			if (ffmpegBinary != null && audioWav != null && Files.exists(audioWav)) {
				audio = muxAudioIntoAvi(ffmpegBinary, avi, audioWav, audioOffsetSeconds);
			}
			return new Result(avi, audio);
		} finally {
			deleteQuietly(spoolFile);
			if (deleteAudioWav) {
				deleteQuietly(audioWav);
			}
		}
	}

	private static boolean renderMp4(
		Path spoolFile,
		int capturedFrames,
		Path mp4,
		int fps,
		int maxWidth,
		int maxHeight,
		Path audioWav,
		double audioOffsetSeconds,
		String binary,
		String encoder,
		IntConsumer progressPercent
	) throws IOException {
		FirstFrame first = peekFirstFrame(spoolFile, maxWidth, maxHeight);
		if (first == null) {
			throw new IOException("No frames in spool");
		}
		boolean includeAudio = audioWav != null && Files.exists(audioWav) && Files.size(audioWav) > 44L;
		Process process = startFfmpeg(binary, encoder, mp4, first.size.width(), first.size.height(), fps, includeAudio ? audioWav : null, audioOffsetSeconds);
		StringBuilder stderr = new StringBuilder();
		Thread drain = new Thread(() -> drainStderr(process, stderr), "repolish-render-log");
		drain.setDaemon(true);
		drain.start();

		try (DataInputStream input = new DataInputStream(Files.newInputStream(spoolFile));
			OutputStream stdin = new BufferedOutputStream(process.getOutputStream(), 1 << 20)) {
			PlaybackClock clock = null;
			byte[] lastRgba = null;
			for (int index = 0; index < capturedFrames; index++) {
				RawFrame raw = readFrame(input, maxWidth, maxHeight);
				if (raw == null) {
					break;
				}
				if (raw.width != first.size.width() || raw.height != first.size.height()) {
					continue;
				}
				if (clock == null) {
					clock = new PlaybackClock(raw.capturedAtNanos, fps);
				}
				int hold = clock.previousRepeats(raw.capturedAtNanos);
				if (hold < 0) {
					continue;
				}
				if (lastRgba != null) {
					for (int repeat = 0; repeat < hold; repeat++) {
						stdin.write(lastRgba);
					}
				}
				stdin.write(raw.rgba);
				lastRgba = raw.rgba;
				reportProgress(progressPercent, index + 1, capturedFrames);
			}
		} catch (IOException exception) {
			joinQuietly(drain);
			throw new IOException("ffmpeg render interrupted. " + stderr.toString().trim(), exception);
		}

		try {
			if (!process.waitFor(10, TimeUnit.MINUTES)) {
				process.destroyForcibly();
				throw new IOException("ffmpeg render timed out");
			}
			joinQuietly(drain);
			if (process.exitValue() != 0) {
				throw new IOException("ffmpeg render exited with " + process.exitValue() + ": " + stderr.toString().trim());
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Interrupted while rendering video", exception);
		}
		return includeAudio;
	}

	private static void renderAvi(
		Path spoolFile,
		int capturedFrames,
		Path avi,
		int fps,
		int maxWidth,
		int maxHeight,
		IntConsumer progressPercent
	) throws IOException {
		FirstFrame first = peekFirstFrame(spoolFile, maxWidth, maxHeight);
		if (first == null) {
			throw new IOException("No frames in spool");
		}
		try (DataInputStream input = new DataInputStream(Files.newInputStream(spoolFile));
			MjpegAviWriter writer = new MjpegAviWriter(avi, first.size.width(), first.size.height(), fps)) {
			PlaybackClock clock = null;
			byte[] lastJpeg = null;
			for (int index = 0; index < capturedFrames; index++) {
				RawFrame raw = readFrame(input, maxWidth, maxHeight);
				if (raw == null) {
					break;
				}
				if (raw.width != first.size.width() || raw.height != first.size.height()) {
					continue;
				}
				if (clock == null) {
					clock = new PlaybackClock(raw.capturedAtNanos, fps);
				}
				int hold = clock.previousRepeats(raw.capturedAtNanos);
				if (hold < 0) {
					continue;
				}
				if (lastJpeg != null) {
					for (int repeat = 0; repeat < hold; repeat++) {
						writer.writeJpeg(lastJpeg);
					}
				}
				byte[] jpeg = JpegFrames.fromAbgr(raw.abgr, raw.width, raw.height, 0.98F);
				writer.writeJpeg(jpeg);
				lastJpeg = jpeg;
				reportProgress(progressPercent, index + 1, capturedFrames);
			}
		}
	}

	private static Process startFfmpeg(String binary, String encoder, Path output, int width, int height, int fps, Path audioWav, double audioOffsetSeconds) throws IOException {
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
		command.add(width + "x" + height);
		command.add("-r");
		command.add(Integer.toString(fps));
		command.add("-i");
		command.add("pipe:0");
		if (audioWav != null) {
			if (audioOffsetSeconds > 0.005) {
				command.add("-ss");
				command.add(String.format(java.util.Locale.ROOT, "%.3f", audioOffsetSeconds));
			}
			command.add("-i");
			command.add(audioWav.toString());
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
		return new ProcessBuilder(command).start();
	}

	private static boolean muxAudioIntoAvi(String binary, Path avi, Path wav, double audioOffsetSeconds) {
		Path mixed = avi.resolveSibling(avi.getFileName().toString() + ".mix.avi");
		try {
			List<String> cmd = new ArrayList<>();
			cmd.add(binary);
			cmd.add("-y");
			cmd.add("-loglevel");
			cmd.add("error");
			cmd.add("-i");
			cmd.add(avi.toString());
			if (audioOffsetSeconds > 0.005) {
				cmd.add("-ss");
				cmd.add(String.format(java.util.Locale.ROOT, "%.3f", audioOffsetSeconds));
			}
			cmd.add("-i");
			cmd.add(wav.toString());
			cmd.add("-c:v");
			cmd.add("copy");
			cmd.add("-c:a");
			cmd.add("pcm_s16le");
			cmd.add("-shortest");
			cmd.add(mixed.toString());
			ProcessBuilder builder = new ProcessBuilder(cmd);
			builder.redirectErrorStream(true);
			Process mux = builder.start();
			mux.getInputStream().readAllBytes();
			if (!mux.waitFor(60, TimeUnit.SECONDS) || mux.exitValue() != 0 || !Files.exists(mixed)) {
				deleteQuietly(mixed);
				return false;
			}
			Files.move(mixed, avi, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			deleteQuietly(mixed);
			return false;
		}
	}

	private static FirstFrame peekFirstFrame(Path spoolFile, int maxWidth, int maxHeight) throws IOException {
		try (DataInputStream input = new DataInputStream(Files.newInputStream(spoolFile))) {
			try {
				input.readLong();
				int width = input.readInt();
				int height = input.readInt();
				return new FirstFrame(FrameScaler.targetSize(width, height, maxWidth, maxHeight));
			} catch (EOFException exception) {
				return null;
			}
		}
	}

	private static RawFrame readFrame(DataInputStream input, int maxWidth, int maxHeight) throws IOException {
		long capturedAt;
		int width;
		int height;
		try {
			capturedAt = input.readLong();
			width = input.readInt();
			height = input.readInt();
		} catch (EOFException exception) {
			return null;
		}
		byte[] rgba = input.readNBytes(width * height * 4);
		if (rgba.length < width * height * 4) {
			return null;
		}
		int[] abgr = unpackRgbaToAbgr(rgba, width * height);
		FrameScaler.Size size = FrameScaler.targetSize(width, height, maxWidth, maxHeight);
		if (size.width() != width || size.height() != height) {
			abgr = FrameScaler.fit(abgr, width, height, maxWidth, maxHeight);
			width = size.width();
			height = size.height();
			rgba = PixelPack.abgrToRgba(abgr, width * height);
		}
		return new RawFrame(capturedAt, width, height, rgba, abgr);
	}

	private static int[] unpackRgbaToAbgr(byte[] rgba, int pixelCount) {
		int[] abgr = new int[pixelCount];
		int cursor = 0;
		for (int i = 0; i < pixelCount; i++) {
			int r = rgba[cursor] & 0xFF;
			int g = rgba[cursor + 1] & 0xFF;
			int b = rgba[cursor + 2] & 0xFF;
			int a = rgba[cursor + 3] & 0xFF;
			abgr[i] = (a << 24) | (b << 16) | (g << 8) | r;
			cursor += 4;
		}
		return abgr;
	}

	private static void reportProgress(IntConsumer consumer, int done, int total) {
		if (consumer == null || total <= 0) {
			return;
		}
		consumer.accept(Math.min(100, done * 100 / total));
	}

	public static String videoEncoder(String binary) {
		try {
			Process process = new ProcessBuilder(binary, "-hide_banner", "-encoders").redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
				return null;
			}
			return EncoderChoice.choose(output);
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return null;
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

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}

	public record Result(Path output, boolean audio) {
	}

	private record FirstFrame(FrameScaler.Size size) {
	}

	private record RawFrame(long capturedAtNanos, int width, int height, byte[] rgba, int[] abgr) {
	}
}
