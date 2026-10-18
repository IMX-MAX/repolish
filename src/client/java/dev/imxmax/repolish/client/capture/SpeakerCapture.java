package dev.imxmax.repolish.client.capture;

import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.client.FfmpegInstaller;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Records the speakers beside a finished video, then copies that video unchanged
 * into a new file with the audio attached.
 */
public final class SpeakerCapture {
	private final String binary;
	private final Path wav;
	private Process process;

	public SpeakerCapture(String binary, Path wav) {
		this.binary = binary;
		this.wav = wav;
	}

	public void start() throws IOException {
		ProcessBuilder builder = FfmpegInstaller.isWindows()
			? new ProcessBuilder(this.binary, "-y", "-loglevel", "error", "-f", "wasapi", "-loopback", "1", "-i", "default", "-c:a", "pcm_s16le", this.wav.toString())
			: new ProcessBuilder(this.binary, "-y", "-loglevel", "error", "-f", "pulse", "-i", monitorName(), "-c:a", "pcm_s16le", this.wav.toString());
		this.process = builder.start();
		Process started = this.process;
		Thread drain = new Thread(() -> drain(started), "repolish-audio");
		drain.setDaemon(true);
		drain.start();
	}

	public boolean finish() {
		Process running = this.process;
		if (running == null) {
			return false;
		}
		try {
			running.getOutputStream().write('q');
			running.getOutputStream().flush();
			running.getOutputStream().close();
		} catch (IOException exception) {
			running.destroy();
		}
		try {
			if (!running.waitFor(8, TimeUnit.SECONDS)) {
				running.destroyForcibly();
				running.waitFor(2, TimeUnit.SECONDS);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			running.destroyForcibly();
			return false;
		}
		return Files.exists(this.wav);
	}

	public Path wav() {
		return this.wav;
	}

	public boolean finishAndMix(Path video) {
		Process running = this.process;
		if (running == null) {
			return false;
		}
		try {
			running.getOutputStream().write('q');
			running.getOutputStream().flush();
			running.getOutputStream().close();
		} catch (IOException exception) {
			running.destroy();
		}
		try {
			if (!running.waitFor(8, TimeUnit.SECONDS)) {
				running.destroyForcibly();
				running.waitFor(2, TimeUnit.SECONDS);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			running.destroyForcibly();
			return false;
		}
		if (video == null || !Files.exists(this.wav)) {
			try {
				Files.deleteIfExists(this.wav);
			} catch (IOException ignored) {
			}
			return false;
		}
		Path mixed = video.resolveSibling(video.getFileName().toString() + ".mix.avi");
		try {
			ProcessBuilder muxBuilder = new ProcessBuilder(this.binary, "-y", "-loglevel", "error", "-i", video.toString(), "-i", this.wav.toString(), "-c:v", "copy", "-c:a", "pcm_s16le", "-shortest", mixed.toString());
			muxBuilder.redirectErrorStream(true);
			Process mux = muxBuilder.start();
			mux.getInputStream().readAllBytes();
			if (!mux.waitFor(60, TimeUnit.SECONDS) || mux.exitValue() != 0 || !Files.exists(mixed)) {
				Files.deleteIfExists(mixed);
				return false;
			}
			Files.move(mixed, video, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException | InterruptedException exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			Repolish.LOGGER.warn("Could not add game audio. The picture was kept.", exception);
			try {
				Files.deleteIfExists(mixed);
			} catch (IOException ignored) {
			}
			return false;
		} finally {
			try {
				Files.deleteIfExists(this.wav);
			} catch (IOException ignored) {
			}
		}
	}

	private static void drain(Process process) {
		try {
			process.getErrorStream().readAllBytes();
		} catch (IOException exception) {
			Repolish.LOGGER.debug("Could not read audio capture output", exception);
		}
	}

	public static String binary() {
		return FfmpegEncoder.binary();
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
}
