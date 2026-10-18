package dev.imxmax.repolish.client;

import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.capture.FrameScaler;
import dev.imxmax.repolish.capture.PlaybackClock;
import dev.imxmax.repolish.client.capture.FfmpegEncoder;
import dev.imxmax.repolish.client.capture.FrameEncoder;
import dev.imxmax.repolish.client.capture.MjpegEncoder;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

public final class CaptureController {
	private static final CaptureController INSTANCE = new CaptureController();

	private final ArrayBlockingQueue<Frame> frames = new ArrayBlockingQueue<>(3);
	private final List<String> markers = new ArrayList<>();

	private volatile boolean recording;
	private volatile boolean finish;
	private volatile Thread worker;
	private int generation;
	private boolean capturePending;
	private long nextFrameNanos;
	private long startedNanos;
	private boolean previousHideGui;
	private FrameEncoder encoder;
	private int recordFps = 60;
	private boolean wantAudio;
	private String clipName = "clip";
	private ExportSettings.Resolution resolution = ExportSettings.Resolution.WINDOW;
	private PlaybackClock clock;

	private CaptureController() {
	}

	public static boolean isRecording() {
		return INSTANCE.recording;
	}

	public static String elapsed() {
		long seconds = Math.max(0L, (System.nanoTime() - INSTANCE.startedNanos) / 1_000_000_000L);
		return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
	}

	public static void toggle(Minecraft minecraft) {
		if (INSTANCE.recording) {
			INSTANCE.stop(minecraft);
		} else {
			INSTANCE.start(minecraft);
		}
	}

	public static void startRecording(Minecraft minecraft) {
		INSTANCE.start(minecraft);
	}

	public static void shutdown(Minecraft minecraft) {
		if (!INSTANCE.recording) {
			return;
		}
		INSTANCE.stop(minecraft);
		Thread thread = INSTANCE.worker;
		if (thread != null) {
			try {
				thread.join(8_000L);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public static void toggleHud(Minecraft minecraft) {
		ExportSettings settings = ExportSettings.get();
		settings.includeHud = !settings.includeHud;
		settings.save();
		if (INSTANCE.recording) {
			minecraft.options.hideGui = !settings.includeHud;
		}
		chat(minecraft, settings.includeHud ? "HUD will be in the recording." : "HUD is hidden for the recording.");
	}

	public static void mark() {
		if (!INSTANCE.recording) {
			return;
		}
		double seconds = (System.nanoTime() - INSTANCE.startedNanos) / 1_000_000_000.0;
		synchronized (INSTANCE.markers) {
			INSTANCE.markers.add(String.format(Locale.ROOT, "%.3f", seconds));
		}
	}

	public static int markerCount() {
		synchronized (INSTANCE.markers) {
			return INSTANCE.markers.size();
		}
	}

	public static void onFrame(Minecraft minecraft) {
		INSTANCE.captureFrame(minecraft);
	}

	public void start(Minecraft minecraft) {
		if (this.recording) {
			return;
		}
		Thread existing = this.worker;
		if (existing != null && existing.isAlive()) {
			chat(minecraft, "Still saving the last recording.");
			return;
		}
		if (minecraft.level == null || minecraft.player == null) {
			chat(minecraft, "Join a world, then record.");
			return;
		}
		ExportSettings settings = ExportSettings.get();
		settings.save();
		ShaderPacks.apply(settings.shader);

		this.previousHideGui = minecraft.options.hideGui;
		if (!settings.includeHud) {
			minecraft.options.hideGui = true;
		}
		this.recordFps = settings.fps;
		this.wantAudio = settings.includeAudio;
		this.clipName = settings.clipName;
		this.resolution = settings.resolution == null ? ExportSettings.Resolution.WINDOW : settings.resolution;
		this.frames.clear();
		synchronized (this.markers) {
			this.markers.clear();
		}
		this.generation++;
		this.capturePending = false;
		this.finish = false;
		this.encoder = null;
		this.clock = null;
		this.startedNanos = System.nanoTime();
		this.nextFrameNanos = this.startedNanos;
		this.recording = true;
		Thread thread = new Thread(() -> this.encodeLoop(minecraft), "repolish-encode");
		thread.setDaemon(true);
		this.worker = thread;
		thread.start();

		String key = RepolishKeybinds.record.getTranslatedKeyMessage().getString();
		chat(minecraft, "Recording. Press " + key + " to stop, or press Esc and click Stop recording.");
		if (FfmpegInstaller.isWindows()) {
			FfmpegInstaller.ensureAsync();
		}
		if (this.wantAudio && FfmpegEncoder.binary() == null) {
			if (FfmpegInstaller.isWindows()) {
				chat(minecraft, "Game audio needs ffmpeg. It is installing in the background, so this take is video only.");
			} else {
				chat(minecraft, "Game audio needs ffmpeg installed. This take is video only.");
			}
		}
	}

	public void stop(Minecraft minecraft) {
		if (!this.recording) {
			return;
		}
		this.recording = false;
		this.finish = true;
		this.generation++;
		this.capturePending = false;
		minecraft.options.hideGui = this.previousHideGui;
	}

	private void captureFrame(Minecraft minecraft) {
		if (!this.recording || minecraft.level == null || this.frames.remainingCapacity() == 0) {
			return;
		}
		long now = System.nanoTime();
		long interval = 1_000_000_000L / this.recordFps;
		if (now < this.nextFrameNanos || this.capturePending) {
			return;
		}
		this.nextFrameNanos = now + interval;
		int request = this.generation;
		long capturedAt = now;
		this.capturePending = true;
		try {
			Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> acceptFrame(request, capturedAt, image));
		} catch (RuntimeException exception) {
			this.capturePending = false;
			Repolish.LOGGER.error("Could not read the framebuffer", exception);
			this.stop(minecraft);
			minecraft.execute(() -> chat(minecraft, "Could not read the framebuffer."));
		}
	}

	private void acceptFrame(int request, long capturedAt, NativeImage image) {
		try {
			if (request != this.generation || !this.recording) {
				return;
			}
			int width = image.getWidth();
			int height = image.getHeight();
			int[] copy = java.util.Arrays.copyOf(image.getPixelsABGR(), width * height);
			this.frames.offer(new Frame(copy, width, height, capturedAt));
		} finally {
			image.close();
			if (request == this.generation) {
				this.capturePending = false;
			}
		}
	}

	private void encodeLoop(Minecraft minecraft) {
		String error = null;
		Path output = null;
		boolean audio = false;
		List<String> markerSnapshot = List.of();
		try {
			while (true) {
				Frame frame = this.frames.poll(200, TimeUnit.MILLISECONDS);
				if (frame != null) {
					this.writeFrame(minecraft, frame);
					continue;
				}
				if (this.finish) {
					break;
				}
			}
			Frame leftover;
			while ((leftover = this.frames.poll()) != null) {
				this.writeFrame(minecraft, leftover);
			}
			synchronized (this.markers) {
				markerSnapshot = List.copyOf(this.markers);
			}
			if (this.encoder != null) {
				output = this.encoder.output();
				this.encoder.close();
				audio = this.encoder.capturedAudio();
				writeMarkers(output, markerSnapshot);
			}
		} catch (Exception exception) {
			error = exception.getMessage();
			Repolish.LOGGER.error("Frame capture failed", exception);
			if (this.encoder != null) {
				output = this.encoder.output();
				try {
					this.encoder.close();
				} catch (IOException closeError) {
					Repolish.LOGGER.warn("Could not close the partial recording", closeError);
				}
			}
		} finally {
			this.encoder = null;
			this.finish = false;
			if (this.worker == Thread.currentThread()) {
				this.worker = null;
			}
			Path saved = output;
			String failure = error;
			boolean heard = audio;
			minecraft.execute(() -> report(minecraft, saved, failure, heard));
		}
	}

	private void writeFrame(Minecraft minecraft, Frame frame) throws IOException {
		FrameScaler.Size size = FrameScaler.targetSize(frame.width, frame.height, this.resolution.width(), this.resolution.height());
		int[] pixels = FrameScaler.fit(frame.pixels, frame.width, frame.height, this.resolution.width(), this.resolution.height());
		if (this.clock == null) {
			this.clock = new PlaybackClock(frame.capturedAtNanos, this.recordFps);
		}
		int hold = this.clock.previousRepeats(frame.capturedAtNanos);
		if (hold < 0) {
			return;
		}
		if (this.encoder == null) {
			this.encoder = openEncoder(minecraft, size.width(), size.height());
			hold = 0;
		}
		if (hold > 0) {
			this.encoder.repeatLast(hold);
		}
		this.encoder.write(pixels, size.width(), size.height());
	}

	private FrameEncoder openEncoder(Minecraft minecraft, int width, int height) throws IOException {
		Path directory = minecraft.gameDirectory.toPath().resolve("repolish");
		String binary = FfmpegEncoder.binary();
		String extension = binary != null ? ".mp4" : ".avi";
		Path file = uniqueFile(directory, sanitize(this.clipName), extension);
		if (binary != null) {
			return new FfmpegEncoder(file, this.recordFps, this.wantAudio, binary);
		}
		return new MjpegEncoder(file, width, height, this.recordFps);
	}

	private static void report(Minecraft minecraft, Path output, String error, boolean audio) {
		if (error != null) {
			chat(minecraft, "Export failed. " + error);
		} else if (output != null) {
			String audioNote = audio
				? "Game audio is included."
				: "Video only. Install ffmpeg to capture game audio on a later take.";
			chat(minecraft, "Saved " + output + ". " + audioNote + " Open Recordings on the main menu.");
		} else {
			chat(minecraft, "Recording stopped before a frame was captured.");
		}
	}

	private static void writeMarkers(Path output, List<String> markerSnapshot) {
		if (markerSnapshot.isEmpty() || output == null) {
			return;
		}
		Path markerPath = Path.of(output.toString() + ".markers.txt");
		try {
			Files.writeString(markerPath, String.join(System.lineSeparator(), markerSnapshot) + System.lineSeparator());
		} catch (IOException exception) {
			Repolish.LOGGER.warn("Could not write markers", exception);
		}
	}

	public static Path recordingsDirectory(Minecraft minecraft) {
		return minecraft.gameDirectory.toPath().resolve("repolish");
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

	private static String sanitize(String name) {
		String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
		return cleaned.isBlank() ? "clip" : cleaned;
	}

	private static void chat(Minecraft minecraft, String message) {
		if (minecraft.gui != null) {
			minecraft.gui.getChat().addMessage(Component.literal("[Repolish] " + message));
		}
		Repolish.LOGGER.info(message);
	}

	private record Frame(int[] pixels, int width, int height, long capturedAtNanos) {
	}
}
