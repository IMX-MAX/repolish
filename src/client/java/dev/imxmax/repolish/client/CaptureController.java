package dev.imxmax.repolish.client;

import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.client.capture.SpeakerCapture;
import dev.imxmax.repolish.take.TakeData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;

public final class CaptureController {
	private static final CaptureController INSTANCE = new CaptureController();
	private static final long MIN_SAMPLE_INTERVAL_NANOS = 4_000_000L; // ~250 Hz max

	private volatile boolean recording;
	private long startedNanos;
	private long lastSampleNanos;
	private TakeData currentTake;
	private Path currentAudioPath;
	private SpeakerCapture speakers;

	private CaptureController() {
	}

	public static boolean isRecording() {
		return INSTANCE.recording;
	}

	public static boolean isRendering() {
		return ReplayController.isExporting() || ReplayController.isEncoding();
	}

	public static int renderPercent() {
		return ReplayController.progressPercent();
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
		if (INSTANCE.recording) {
			INSTANCE.stopAndSaveOnly(minecraft);
		}
		if (ReplayController.isActive()) {
			ReplayController.get().closeReplay(minecraft);
		}
	}

	public static void toggleHud(Minecraft minecraft) {
		ExportSettings settings = ExportSettings.get();
		settings.includeHud = !settings.includeHud;
		settings.save();
		if (ReplayController.isActive()) {
			minecraft.options.hideGui = !settings.includeHud;
		}
		chat(minecraft, settings.includeHud ? "HUD will be shown in the recreated footage." : "HUD is hidden for the recreated footage.");
	}

	public static void mark() {
		if (INSTANCE.recording && INSTANCE.currentTake != null) {
			double seconds = Math.max(0.0, (System.nanoTime() - INSTANCE.startedNanos) / 1_000_000_000.0);
			synchronized (INSTANCE) {
				INSTANCE.currentTake.addMarker(seconds);
			}
		} else if (ReplayController.isActive()) {
			ReplayController.get().addMarkerAtPlayhead();
		}
	}

	public static int markerCount() {
		if (INSTANCE.recording && INSTANCE.currentTake != null) {
			synchronized (INSTANCE) {
				return INSTANCE.currentTake.markers().size();
			}
		}
		if (ReplayController.isActive() && ReplayController.get().take() != null) {
			return ReplayController.get().take().markers().size();
		}
		return 0;
	}

	public static void onTick(Minecraft minecraft) {
		INSTANCE.sampleIfRecording(minecraft, 1.0F, true);
	}

	public static void onBeforeRender(Minecraft minecraft, float partialTick) {
		if (INSTANCE.recording) {
			INSTANCE.sampleIfRecording(minecraft, partialTick, false);
		} else if (ReplayController.isActive()) {
			ReplayController.get().onBeforeRender(minecraft);
		}
	}

	public static void onFrame(Minecraft minecraft) {
		if (ReplayController.isActive()) {
			ReplayController.get().onAfterRender(minecraft);
		}
	}

	public void start(Minecraft minecraft) {
		if (this.recording) {
			return;
		}
		if (isRendering()) {
			chat(minecraft, "Still exporting the current take (" + renderPercent() + "%).");
			return;
		}
		if (minecraft.level == null || minecraft.player == null) {
			chat(minecraft, "Join a world, then start recording.");
			return;
		}
		if (ReplayController.isActive()) {
			ReplayController.get().closeReplay(minecraft);
		}

		ExportSettings settings = ExportSettings.get();
		settings.save();
		String baseName = sanitize(settings.clipName);
		int currentFov = minecraft.options.fov().get();

		Path directory = recordingsDirectory(minecraft);
		this.speakers = null;
		this.currentAudioPath = null;
		String audioFileName = null;
		if (settings.includeAudio) {
			String binary = SpeakerCapture.binary();
			if (binary != null) {
				try {
					Files.createDirectories(directory);
					Path wav = uniqueFile(directory, "." + baseName + "-audio", ".wav");
					SpeakerCapture capture = new SpeakerCapture(binary, wav);
					capture.start();
					this.speakers = capture;
					this.currentAudioPath = wav;
					audioFileName = wav.getFileName().toString();
				} catch (IOException exception) {
					Repolish.LOGGER.warn("Could not start speaker capture for take", exception);
				}
			}
		}

		TakeData take = new TakeData(baseName, System.currentTimeMillis(), currentFov);
		if (minecraft.level != null) {
			take.setRecordedDayTime(minecraft.level.getDayTime());
			take.setRecordedGameTime(minecraft.level.getGameTime());
		}
		take.setAudioFileName(audioFileName);
		this.currentTake = take;
		this.startedNanos = System.nanoTime();
		this.lastSampleNanos = 0L;
		this.recording = true;
		this.sampleIfRecording(minecraft, 1.0F, true);

		String key = RepolishKeybinds.record.getTranslatedKeyMessage().getString();
		chat(minecraft, "Recording take. Press " + key + " to stop and open the take editor.");
		if (FfmpegInstaller.isWindows()) {
			FfmpegInstaller.ensureAsync();
		}
	}

	public void stop(Minecraft minecraft) {
		SavedTake saved = this.stopAndSaveOnly(minecraft);
		if (saved == null) {
			return;
		}
		chat(minecraft, "Saved take " + saved.file().getFileName() + ". Opening first-person editor...");
		ReplayController.get().openTake(minecraft, saved.take(), saved.file());
	}

	private SavedTake stopAndSaveOnly(Minecraft minecraft) {
		if (!this.recording) {
			return null;
		}
		this.sampleIfRecording(minecraft, 1.0F, true);
		this.recording = false;

		TakeData finishedTake;
		synchronized (this) {
			finishedTake = this.currentTake;
			this.currentTake = null;
		}
		SpeakerCapture activeSpeakers = this.speakers;
		Path wavPath = this.currentAudioPath;
		this.speakers = null;
		this.currentAudioPath = null;

		if (activeSpeakers != null) {
			boolean ok = activeSpeakers.finish();
			if (!ok || wavPath == null || !Files.exists(wavPath)) {
				if (finishedTake != null) {
					finishedTake.setAudioFileName(null);
				}
			}
		}

		if (finishedTake == null || finishedTake.samples().isEmpty()) {
			chat(minecraft, "Recording stopped before any gameplay samples were captured.");
			return null;
		}

		Path directory = recordingsDirectory(minecraft);
		try {
			Files.createDirectories(directory);
			Path takeFile = uniqueFile(directory, sanitize(finishedTake.name()), ".rtake");
			finishedTake.save(takeFile);
			return new SavedTake(finishedTake, takeFile);
		} catch (IOException exception) {
			Repolish.LOGGER.error("Could not save take", exception);
			chat(minecraft, "Could not save take: " + exception.getMessage());
			return null;
		}
	}

	private void sampleIfRecording(Minecraft minecraft, float partialTick, boolean force) {
		if (!this.recording || minecraft.player == null || minecraft.level == null) {
			return;
		}
		long now = System.nanoTime();
		if (!force && now - this.lastSampleNanos < MIN_SAMPLE_INTERVAL_NANOS) {
			return;
		}
		this.lastSampleNanos = now;

		LocalPlayer player = minecraft.player;
		double t = Math.max(0.0, (now - this.startedNanos) / 1_000_000_000.0);
		double x = Mth.lerp(partialTick, player.xo, player.getX());
		double y = Mth.lerp(partialTick, player.yo, player.getY());
		double z = Mth.lerp(partialTick, player.zo, player.getZ());
		float eyeHeight = player.getEyeHeight(player.getPose());
		float yaw = player.getViewYRot(partialTick);
		float pitch = player.getViewXRot(partialTick);
		float walkSpeed = player.walkAnimation.speed(partialTick);
		float walkPos = player.walkAnimation.position(partialTick);
		float xBob = Mth.lerp(partialTick, player.xBobO, player.xBob);
		float yBob = Mth.lerp(partialTick, player.yBobO, player.yBob);
		float attackAnim = player.getAttackAnim(partialTick);
		int hotbar = player.getInventory().getSelectedSlot();
		int poseOrdinal = player.getPose().ordinal();

		int flags = 0;
		if (player.isShiftKeyDown()) {
			flags |= TakeData.FLAG_SNEAKING;
		}
		if (player.isSprinting()) {
			flags |= TakeData.FLAG_SPRINTING;
		}
		if (player.onGround()) {
			flags |= TakeData.FLAG_ON_GROUND;
		}
		if (player.swinging) {
			flags |= TakeData.FLAG_SWINGING;
		}
		if (player.swingingArm == InteractionHand.OFF_HAND) {
			flags |= TakeData.FLAG_OFFHAND_SWING;
		}

		TakeData.Sample sample = new TakeData.Sample(
			t,
			x,
			y,
			z,
			eyeHeight,
			yaw,
			pitch,
			walkSpeed,
			walkPos,
			xBob,
			yBob,
			attackAnim,
			hotbar,
			poseOrdinal,
			flags
		);
		synchronized (this) {
			if (this.currentTake != null) {
				this.currentTake.addSample(sample);
			}
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
		String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
		return cleaned.isBlank() ? "clip" : cleaned;
	}

	private static void chat(Minecraft minecraft, String message) {
		if (minecraft.gui != null) {
			minecraft.gui.getChat().addMessage(Component.literal("[Repolish] " + message));
		}
		Repolish.LOGGER.info(message);
	}

	private record SavedTake(TakeData take, Path file) {
	}
}
