package dev.imxmax.repolish.client;

import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.client.capture.FfmpegEncoder;
import dev.imxmax.repolish.client.capture.FrameEncoder;
import dev.imxmax.repolish.client.capture.PngSequenceEncoder;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

public final class CaptureController {
	private static final CaptureController INSTANCE = new CaptureController();

	private boolean recording;
	private int generation;
	private boolean capturePending;
	private int resizeWaits;
	private long nextFrameNanos;
	private long startedNanos;
	private boolean previousHideGui;
	private boolean resizedWindow;
	private int previousWidth;
	private int previousHeight;
	private FrameEncoder encoder;
	private final List<String> markers = new ArrayList<>();

	private CaptureController() {
	}

	public static boolean isRecording() {
		return INSTANCE.recording;
	}

	public static void toggle(Minecraft minecraft) {
		if (INSTANCE.recording) {
			INSTANCE.stop(minecraft, null);
		} else {
			INSTANCE.start(minecraft);
		}
	}

	public static void startRecording(Minecraft minecraft) {
		INSTANCE.start(minecraft);
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
		INSTANCE.markers.add(String.format(Locale.ROOT, "%.3f", seconds));
	}

	public static int markerCount() {
		return INSTANCE.markers.size();
	}

	public static void onFrame(Minecraft minecraft) {
		INSTANCE.captureFrame(minecraft);
	}

	public void start(Minecraft minecraft) {
		if (this.recording) {
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
		this.resizedWindow = false;
		if (settings.resolution.width() > 0) {
			this.previousWidth = minecraft.getWindow().getScreenWidth();
			this.previousHeight = minecraft.getWindow().getScreenHeight();
			this.resizedWindow = true;
			minecraft.getWindow().setWindowed(settings.resolution.width(), settings.resolution.height());
			minecraft.resizeDisplay();
		}

		this.generation++;
		this.capturePending = false;
		this.resizeWaits = 0;
		this.encoder = null;
		this.markers.clear();
		this.startedNanos = System.nanoTime();
		this.nextFrameNanos = this.startedNanos;
		this.recording = true;
		chat(minecraft, "Recording. Press the record key again to stop.");
	}

	public void stop(Minecraft minecraft, String error) {
		if (!this.recording && this.encoder == null) {
			return;
		}
		this.recording = false;
		this.generation++;
		this.capturePending = false;
		minecraft.options.hideGui = this.previousHideGui;
		if (this.resizedWindow) {
			minecraft.getWindow().setWindowed(this.previousWidth, this.previousHeight);
			minecraft.resizeDisplay();
			this.resizedWindow = false;
		}

		FrameEncoder finished = this.encoder;
		this.encoder = null;
		Path output = null;
		boolean audio = false;
		String closeError = null;
		if (finished != null) {
			output = finished.output();
			try {
				finished.close();
				audio = finished.capturedAudio();
			} catch (IOException exception) {
				closeError = exception.getMessage();
				Repolish.LOGGER.error("Could not finish the Repolish export", exception);
			}
			writeMarkers(output);
		}
		if (error != null) {
			chat(minecraft, error);
		} else if (closeError != null) {
			chat(minecraft, "Export failed. " + closeError);
		} else if (output != null) {
			String audioNote = audio ? "Game audio is included." : "Video only. Install ffmpeg and PulseAudio or PipeWire to capture game audio.";
			chat(minecraft, "Saved " + output + ". " + audioNote);
		} else {
			chat(minecraft, "Recording stopped before a frame was captured.");
		}
	}

	private void captureFrame(Minecraft minecraft) {
		if (!this.recording || minecraft.level == null) {
			return;
		}
		long now = System.nanoTime();
		long interval = 1_000_000_000L / ExportSettings.get().fps;
		if (now < this.nextFrameNanos || this.capturePending) {
			return;
		}
		this.nextFrameNanos = now + interval;
		int request = this.generation;
		ExportSettings.Resolution resolution = ExportSettings.get().resolution;
		this.capturePending = true;
		try {
			Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> acceptFrame(minecraft, request, resolution, image));
		} catch (RuntimeException exception) {
			this.capturePending = false;
			Repolish.LOGGER.error("Could not read the framebuffer", exception);
			this.stop(minecraft, "Could not read the framebuffer.");
		}
	}

	private void acceptFrame(Minecraft minecraft, int request, ExportSettings.Resolution resolution, NativeImage image) {
		try {
			if (request != this.generation || !this.recording) {
				return;
			}
			int width = image.getWidth();
			int height = image.getHeight();
			if (resolution.width() > 0 && (width != resolution.width() || height != resolution.height()) && this.resizeWaits < 45) {
				this.resizeWaits++;
				return;
			}
			int[] pixels = image.getPixelsABGR();
			int[] copy = new int[width * height];
			System.arraycopy(pixels, 0, copy, 0, copy.length);
			if (this.encoder == null) {
				this.encoder = openEncoder(minecraft, width, height);
			}
			this.encoder.write(copy, width, height);
		} catch (IOException | RuntimeException exception) {
			Repolish.LOGGER.error("Frame capture failed", exception);
			this.stop(minecraft, "Frame capture failed. " + exception.getMessage());
		} finally {
			image.close();
			if (request == this.generation) {
				this.capturePending = false;
			}
		}
	}

	private FrameEncoder openEncoder(Minecraft minecraft, int width, int height) throws IOException {
		Path directory = minecraft.gameDirectory.toPath().resolve("repolish");
		Files.createDirectories(directory);
		String name = sanitize(ExportSettings.get().clipName);
		if (FfmpegEncoder.available()) {
			return new FfmpegEncoder(directory.resolve(name + ".mp4"), ExportSettings.get().fps, ExportSettings.get().includeAudio);
		}
		chat(minecraft, "ffmpeg is not installed. Writing a PNG sequence instead of a video.");
		return new PngSequenceEncoder(directory.resolve(name));
	}

	private void writeMarkers(Path output) {
		if (this.markers.isEmpty() || output == null) {
			return;
		}
		Path markerPath = Path.of(output.toString() + ".markers.txt");
		try {
			Files.writeString(markerPath, String.join(System.lineSeparator(), this.markers) + System.lineSeparator());
		} catch (IOException exception) {
			Repolish.LOGGER.warn("Could not write markers", exception);
		}
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
}
