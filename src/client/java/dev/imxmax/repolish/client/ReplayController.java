package dev.imxmax.repolish.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.capture.PixelPack;
import dev.imxmax.repolish.client.capture.ExportPipe;
import dev.imxmax.repolish.client.capture.OfflineRenderer;
import dev.imxmax.repolish.client.capture.SpeakerCapture;
import dev.imxmax.repolish.client.screen.TakeEditorScreen;
import dev.imxmax.repolish.take.TakeData;
import dev.imxmax.repolish.take.TakeTrajectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;

/**
 * Recreates a recorded first-person take live on screen for the editor and
 * drives deterministic frame-by-frame video rendering during export.
 */
public final class ReplayController {
	private static final ReplayController INSTANCE = new ReplayController();
	private static final Pose[] POSES = Pose.values();
	private static final String[] ENCODER_CACHE = new String[2];
	private static boolean encoderProbed;

	private TakeData take;
	private Path takeFile;
	private TakeTrajectory trajectory;
	private TakeData.Sample currentSample;

	private boolean active;
	private boolean playing;
	private boolean compareRaw;
	private double playhead;
	private double inTime;
	private double outTime;
	private float playbackSpeed = 1.0F;
	private long lastWallNanos;
	private int lastAppliedSlot = -1;

	// Saved player state before entering the replay editor
	private SavedPlayerState savedPlayerState;

	// Deterministic frame-by-frame export state
	private volatile boolean exporting;
	private volatile boolean encoding;
	private volatile int progressPercent;
	private int exportGeneration;
	private boolean frameCapturePending;
	private int exportFrameIndex;
	private int exportTotalFrames;
	private int exportFps = 60;
	private double exportStartSeconds;
	private ExportSettings.Resolution exportResolution = ExportSettings.Resolution.WINDOW;
	private String exportName = "clip";
	private Path exportSpoolPath;
	private OfflineRenderer.Spool exportSpool;
	private boolean preExportHideGui;

	// Settings frozen at the moment the export started, so the exported video
	// always matches what was configured even if the live settings change later.
	private float exportSmoothing;
	private float exportResponsiveness;
	private boolean exportMatchFov;
	private int exportFov;
	private boolean exportIncludeHud;
	private boolean exportIncludeAudio;
	private String preExportShader;
	private String exportFfmpeg;
	private String exportEncoder;
	private int exportTargetWidth;
	private int exportTargetHeight;
	private ExportPipe exportPipe;

	private ReplayController() {
	}

	public static ReplayController get() {
		return INSTANCE;
	}

	public static boolean isActive() {
		return INSTANCE.active && INSTANCE.take != null;
	}

	public static boolean isExporting() {
		return INSTANCE.exporting;
	}

	public static boolean isEncoding() {
		return INSTANCE.encoding;
	}

	public static int progressPercent() {
		return INSTANCE.progressPercent;
	}

	public static TakeData.Sample currentSample() {
		return INSTANCE.currentSample;
	}

	public void openTake(Minecraft minecraft, TakeData take, Path takeFile) {
		if (minecraft.player == null || minecraft.level == null || take == null || take.samples().isEmpty()) {
			return;
		}
		if (!this.active) {
			this.savedPlayerState = SavedPlayerState.capture(minecraft);
		}
		this.take = take;
		this.takeFile = takeFile;
		this.trajectory = new TakeTrajectory(take);
		this.active = true;
		this.playing = true;
		this.compareRaw = false;
		this.inTime = 0.0;
		this.outTime = Math.max(0.05, take.duration());
		this.playhead = 0.0;
		this.playbackSpeed = 1.0F;
		this.lastWallNanos = System.nanoTime();
		this.lastAppliedSlot = -1;

		ExportSettings settings = ExportSettings.get();
		settings.clipName = take.name();
		ShaderPacks.apply(settings.shader);
		minecraft.options.setCameraType(CameraType.FIRST_PERSON);
		minecraft.options.hideGui = !settings.includeHud;

		this.evaluateSample();
		this.applyToPlayer(minecraft);
		minecraft.setScreen(new TakeEditorScreen(null));
	}

	public void openTakeFile(Minecraft minecraft, Path file) {
		if (minecraft.player == null || minecraft.level == null) {
			chat(minecraft, "Join a world to open and edit a take in 3D.");
			return;
		}
		try {
			TakeData loaded = TakeData.load(file);
			this.openTake(minecraft, loaded, file);
		} catch (IOException exception) {
			Repolish.LOGGER.warn("Could not load take {}", file, exception);
			chat(minecraft, "Could not load take: " + exception.getMessage());
		}
	}

	public void closeReplay(Minecraft minecraft) {
		if (this.exporting) {
			this.cancelExport(minecraft);
		}
		if (!this.active) {
			return;
		}
		this.active = false;
		this.playing = false;
		this.currentSample = null;
		this.trajectory = null;
		if (this.savedPlayerState != null) {
			this.savedPlayerState.restore(minecraft);
			this.savedPlayerState = null;
		}
	}

	public TakeData take() {
		return this.take;
	}

	public Path takeFile() {
		return this.takeFile;
	}

	public boolean isPlaying() {
		return this.playing && !this.exporting;
	}

	public void setPlaying(boolean playing) {
		this.playing = playing;
		this.lastWallNanos = System.nanoTime();
		if (playing && this.playhead >= this.outTime - 1.0E-3) {
			this.playhead = this.inTime;
		}
	}

	public void togglePlaying() {
		this.setPlaying(!this.playing);
	}

	public boolean isCompareRaw() {
		return this.compareRaw;
	}

	public void setCompareRaw(boolean compareRaw) {
		this.compareRaw = compareRaw;
		this.evaluateSample();
	}

	public double playhead() {
		return this.playhead;
	}

	public void setPlayhead(double seconds) {
		double duration = this.duration();
		this.playhead = Math.max(0.0, Math.min(duration, seconds));
		this.lastWallNanos = System.nanoTime();
		this.evaluateSample();
	}

	public void stepFrames(int frames) {
		this.playing = false;
		int fps = Math.max(30, ExportSettings.get().fps);
		this.setPlayhead(this.playhead + frames / (double) fps);
	}

	public double inTime() {
		return this.inTime;
	}

	public void setInTime(double seconds) {
		this.inTime = Math.max(0.0, Math.min(seconds, Math.max(0.0, this.outTime - 0.05)));
		if (this.playhead < this.inTime) {
			this.setPlayhead(this.inTime);
		}
	}

	public double outTime() {
		return this.outTime;
	}

	public void setOutTime(double seconds) {
		this.outTime = Math.max(this.inTime + 0.05, Math.min(this.duration(), seconds));
		if (this.playhead > this.outTime) {
			this.setPlayhead(this.outTime);
		}
	}

	public float playbackSpeed() {
		return this.playbackSpeed;
	}

	public void setPlaybackSpeed(float playbackSpeed) {
		this.playbackSpeed = playbackSpeed;
		this.lastWallNanos = System.nanoTime();
	}

	public double duration() {
		return this.take == null ? 0.0 : this.take.duration();
	}

	public void addMarkerAtPlayhead() {
		if (this.take == null) {
			return;
		}
		this.take.addMarker(this.playhead);
		if (this.takeFile != null) {
			try {
				this.take.save(this.takeFile);
			} catch (IOException exception) {
				Repolish.LOGGER.warn("Could not save marker to take", exception);
			}
		}
	}

	public void jumpToMarker(boolean forward) {
		if (this.take == null || this.take.markers().isEmpty()) {
			return;
		}
		List<Double> sorted = new ArrayList<>(this.take.markers());
		sorted.sort(Double::compareTo);
		if (forward) {
			for (double marker : sorted) {
				if (marker > this.playhead + 0.02) {
					this.setPlayhead(marker);
					return;
				}
			}
			this.setPlayhead(sorted.get(0));
		} else {
			for (int i = sorted.size() - 1; i >= 0; i--) {
				if (sorted.get(i) < this.playhead - 0.02) {
					this.setPlayhead(sorted.get(i));
					return;
				}
			}
			this.setPlayhead(sorted.get(sorted.size() - 1));
		}
	}

	public void startExport(Minecraft minecraft) {
		if (!this.active || this.take == null || this.exporting || this.encoding) {
			return;
		}
		ExportSettings settings = ExportSettings.get();
		settings.save();
		ShaderPacks.apply(settings.shader);

		double span = Math.max(0.05, this.outTime - this.inTime);
		this.exportFps = settings.fps;
		this.exportStartSeconds = this.inTime;
		this.exportTotalFrames = Math.max(1, (int) Math.round(span * this.exportFps));
		this.exportFrameIndex = 0;
		this.exportResolution = settings.resolution == null ? ExportSettings.Resolution.WINDOW : settings.resolution;
		this.exportName = sanitize(settings.clipName);
		this.exportSmoothing = settings.smoothingAmount();
		this.exportResponsiveness = settings.responsivenessAmount();
		this.exportMatchFov = settings.matchFov;
		this.exportFov = settings.fov;
		this.exportIncludeHud = settings.includeHud;
		this.exportIncludeAudio = settings.includeAudio;
		this.preExportShader = settings.shader;
		this.exportGeneration++;
		this.frameCapturePending = false;
		this.playing = false;
		this.compareRaw = false;
		this.progressPercent = 0;
		this.preExportHideGui = minecraft.options.hideGui;
		minecraft.options.setCameraType(CameraType.FIRST_PERSON);
		minecraft.options.hideGui = !settings.includeHud;

		String[] encoderPair = resolveExportEncoder();
		this.exportFfmpeg = encoderPair[0];
		this.exportEncoder = encoderPair[1];
		this.exportTargetWidth = 0;
		this.exportTargetHeight = 0;
		this.exportPipe = null;

		Path directory = CaptureController.recordingsDirectory(minecraft);
		try {
			Files.createDirectories(directory);
			// When ffmpeg is available, frames stream straight into it and no
			// uncompressed spool is needed. Without ffmpeg, fall back to the
			// spool so the export still produces a high-quality AVI.
			this.exportSpoolPath = null;
			this.exportSpool = null;
			if (this.exportEncoder == null) {
				this.exportSpoolPath = directory.resolve(".repolish-export-" + this.exportGeneration + ".raw");
				this.exportSpool = new OfflineRenderer.Spool(this.exportSpoolPath);
			}
			this.exporting = true;
			this.playhead = this.exportStartSeconds;
			this.evaluateSample();
			this.applyToPlayer(minecraft);
		} catch (IOException exception) {
			Repolish.LOGGER.error("Could not start take export", exception);
			chat(minecraft, "Could not start export: " + exception.getMessage());
		}
	}

	public void cancelExport(Minecraft minecraft) {
		if (!this.exporting) {
			return;
		}
		this.exporting = false;
		this.frameCapturePending = false;
		this.exportGeneration++;
		minecraft.options.hideGui = this.preExportHideGui;
		ShaderPacks.apply(this.preExportShader);
		ExportPipe pipe = this.exportPipe;
		this.exportPipe = null;
		if (pipe != null) {
			pipe.abort();
			deleteQuietly(pipe.output());
		}
		closeSpoolQuietly();
		if (this.exportSpoolPath != null) {
			try {
				Files.deleteIfExists(this.exportSpoolPath);
			} catch (IOException ignored) {
			}
		}
		chat(minecraft, "Export canceled.");
	}

	/**
	 * FOV used by the live recreation and export. When "match FOV" is on this is
	 * the FOV the take was actually recorded with, so the recreated footage looks
	 * like the original gameplay instead of whatever FOV the options menu has now.
	 */
	public float activeFov() {
		if (this.take != null && this.take.recordedFov() > 0) {
			if (this.exporting) {
				return this.exportMatchFov ? this.take.recordedFov() : this.exportFov;
			}
			ExportSettings settings = ExportSettings.get();
			return settings.matchFov ? this.take.recordedFov() : settings.fov;
		}
		ExportSettings settings = ExportSettings.get();
		return settings.matchFov ? 70 : settings.fov;
	}

	public void onBeforeRender(Minecraft minecraft) {
		if (!this.active || this.take == null || minecraft.player == null || minecraft.level == null) {
			return;
		}
		minecraft.options.setCameraType(CameraType.FIRST_PERSON);
		if (this.exporting) {
			minecraft.options.hideGui = !this.exportIncludeHud;
		} else {
			minecraft.options.hideGui = !ExportSettings.get().includeHud;
		}

		if (this.exporting) {
			double targetTime = Math.min(this.outTime, this.exportStartSeconds + this.exportFrameIndex / (double) this.exportFps);
			this.playhead = targetTime;
		} else if (this.playing) {
			long now = System.nanoTime();
			double dt = (now - this.lastWallNanos) / 1_000_000_000.0;
			this.lastWallNanos = now;
			if (dt > 0.0 && dt < 0.25) {
				this.playhead += dt * this.playbackSpeed;
				if (this.playhead >= this.outTime) {
					this.playhead = this.inTime;
				}
			}
		} else {
			this.lastWallNanos = System.nanoTime();
		}

		this.evaluateSample();
		this.applyToPlayer(minecraft);
	}

	public void onAfterRender(Minecraft minecraft) {
		if (!this.active || !this.exporting || this.frameCapturePending) {
			return;
		}
		// Frames go either to the streaming ffmpeg pipe (opened lazily once the
		// first frame reveals its size) or to the fallback spool when no ffmpeg
		// encoder is available.
		if (this.exportSpool == null && this.exportEncoder == null) {
			return;
		}
		int gen = this.exportGeneration;
		int frameIdx = this.exportFrameIndex;
		long syntheticTimestampNanos = frameIdx * (1_000_000_000L / this.exportFps);
		this.frameCapturePending = true;
		try {
			Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> acceptExportFrame(minecraft, gen, frameIdx, syntheticTimestampNanos, image));
		} catch (RuntimeException exception) {
			this.frameCapturePending = false;
			Repolish.LOGGER.error("Failed to read framebuffer during take export", exception);
			this.cancelExport(minecraft);
		}
	}

	private void acceptExportFrame(Minecraft minecraft, int gen, int frameIdx, long timestampNanos, NativeImage image) {
		try {
			if (gen != this.exportGeneration || !this.exporting || (this.exportSpool == null && this.exportPipe == null)) {
				return;
			}
			int width = image.getWidth();
			int height = image.getHeight();
			int[] copy = Arrays.copyOf(image.getPixelsABGR(), width * height);
			if (this.exportPipe == null && this.exportEncoder != null) {
				// First frame: open the streaming ffmpeg pipe with the real frame size.
				Path directory = CaptureController.recordingsDirectory(minecraft);
				Path audioWav = resolveExportAudio(directory);
				int targetW = this.exportResolution.width();
				int targetH = this.exportResolution.height();
				if (targetW > 0 && targetH > 0 && (targetW != width || targetH != height)) {
					this.exportTargetWidth = targetW & ~1;
					this.exportTargetHeight = targetH & ~1;
				} else {
					this.exportTargetWidth = 0;
					this.exportTargetHeight = 0;
				}
				try {
					this.exportPipe = ExportPipe.open(
						this.exportFfmpeg,
						this.exportEncoder,
						this.exportFps,
						directory,
						this.exportName,
						this.exportTargetWidth,
						this.exportTargetHeight,
						audioWav,
						this.exportStartSeconds,
						width,
						height
					);
					this.exportPipe.write(PixelPack.abgrToRgba(copy, width * height), width, height);
				} catch (IOException pipeFailure) {
					// Streaming encode is unavailable: keep exporting into the
					// spool and let the offline AVI renderer finish the video.
					Repolish.LOGGER.warn("ffmpeg pipe failed, falling back to spool export", pipeFailure);
					this.exportEncoder = null;
					this.exportSpoolPath = directory.resolve(".repolish-export-" + this.exportGeneration + ".raw");
					this.exportSpool = new OfflineRenderer.Spool(this.exportSpoolPath);
					this.exportSpool.write(timestampNanos, width, height, copy);
				}
			} else if (this.exportPipe != null) {
				this.exportPipe.write(PixelPack.abgrToRgba(copy, width * height), width, height);
			} else {
				this.exportSpool.write(timestampNanos, width, height, copy);
			}
			this.exportFrameIndex = frameIdx + 1;
			this.progressPercent = Math.min(99, this.exportFrameIndex * 100 / Math.max(1, this.exportTotalFrames));
			if (this.exportFrameIndex >= this.exportTotalFrames) {
				this.finishCaptureAndEncode(minecraft);
			}
		} catch (IOException exception) {
			Repolish.LOGGER.error("Failed writing exported frame", exception);
			this.cancelExport(minecraft);
		} finally {
			image.close();
			if (gen == this.exportGeneration) {
				this.frameCapturePending = false;
			}
		}
	}

	private void finishCaptureAndEncode(Minecraft minecraft) {
		this.exporting = false;
		minecraft.options.hideGui = this.preExportHideGui;
		ShaderPacks.apply(this.preExportShader);

		ExportPipe pipe = this.exportPipe;
		this.exportPipe = null;
		OfflineRenderer.Spool spool = this.exportSpool;
		Path spoolPath = this.exportSpoolPath;
		this.exportSpool = null;
		this.exportSpoolPath = null;

		Path directory = CaptureController.recordingsDirectory(minecraft);
		List<String> trimmedMarkers = collectTrimmedMarkers();

		if (pipe != null) {
			// Frames were streamed into ffmpeg while rendering; finishing the file
			// only takes the time ffmpeg needs to write the container tail.
			this.encoding = true;
			this.progressPercent = 100;
			Thread finisher = new Thread(() -> {
				String error = null;
				Path output = pipe.output();
				boolean hasAudio = pipe.audio();
				try {
					if (!pipe.finish()) {
						error = "ffmpeg could not finish the video";
						deleteQuietly(output);
						output = null;
					}
					if (output != null && !trimmedMarkers.isEmpty()) {
						Path markerPath = Path.of(output.toString() + ".markers.txt");
						Files.writeString(markerPath, String.join(System.lineSeparator(), trimmedMarkers) + System.lineSeparator());
					}
				} catch (Exception exception) {
					error = exception.getMessage();
					Repolish.LOGGER.error("Take video encode failed", exception);
					deleteQuietly(output);
					output = null;
				} finally {
					this.encoding = false;
					this.progressPercent = 0;
					Path saved = output;
					String failure = error;
					boolean audioIncluded = hasAudio;
					minecraft.execute(() -> {
						if (failure != null) {
							chat(minecraft, "Export failed: " + failure);
						} else if (saved != null) {
							String audioNote = audioIncluded ? "Game audio included." : "Video only.";
							chat(minecraft, "Exported recreated video to " + saved.getFileName() + " (" + audioNote + ")");
						}
					});
				}
			}, "repolish-take-encode");
			finisher.setDaemon(true);
			finisher.start();
			return;
		}

		if (spool == null || spoolPath == null) {
			return;
		}
		int captured = spool.frames();
		try {
			spool.close();
		} catch (IOException exception) {
			Repolish.LOGGER.warn("Could not close export spool", exception);
		}

		String baseName = this.exportName;
		int fps = this.exportFps;
		int maxW = this.exportResolution.width();
		int maxH = this.exportResolution.height();
		double audioOffset = this.exportStartSeconds;
		Path finalAudioWav = resolveExportAudio(directory);

		this.encoding = true;
		this.progressPercent = 0;
		Thread encoderThread = new Thread(() -> {
				String error = null;
				Path output = null;
				boolean hasAudio = false;
				try {
					OfflineRenderer.Result result = OfflineRenderer.render(
					spoolPath,
						captured,
						directory,
						baseName,
						fps,
						maxW,
						maxH,
						finalAudioWav,
						audioOffset,
						false,
						this.exportFfmpeg,
						pct -> this.progressPercent = pct
					);
					output = result.output();
					hasAudio = result.audio();
					if (output != null && !trimmedMarkers.isEmpty()) {
						Path markerPath = Path.of(output.toString() + ".markers.txt");
						Files.writeString(markerPath, String.join(System.lineSeparator(), trimmedMarkers) + System.lineSeparator());
					}
				} catch (Exception exception) {
					error = exception.getMessage();
					Repolish.LOGGER.error("Take video encode failed", exception);
				} finally {
					this.encoding = false;
					this.progressPercent = 0;
					Path saved = output;
					String failure = error;
					boolean audioIncluded = hasAudio;
					minecraft.execute(() -> {
						if (failure != null) {
							chat(minecraft, "Export failed: " + failure);
						} else if (saved != null) {
							String audioNote = audioIncluded ? "Game audio included." : "Video only.";
							chat(minecraft, "Exported recreated video to " + saved.getFileName() + " (" + audioNote + ")");
						}
					});
				}
			}, "repolish-take-encode");
			encoderThread.setDaemon(true);
			encoderThread.start();
	}

	/**
	 * ffmpeg is probed at most once per game session; the result is cached so
	 * starting an export never stalls the render thread on process spawns.
	 */
	private static String[] resolveExportEncoder() {
		synchronized (ENCODER_CACHE) {
			if (!encoderProbed) {
				String binary = SpeakerCapture.binary();
				ENCODER_CACHE[0] = binary;
				ENCODER_CACHE[1] = binary == null ? null : OfflineRenderer.videoEncoder(binary);
				encoderProbed = true;
			}
			return ENCODER_CACHE;
		}
	}

	private Path resolveExportAudio(Path directory) {
		if (!this.exportIncludeAudio || this.take == null || this.take.audioFileName() == null || this.take.audioFileName().isBlank()) {
			return null;
		}
		Path candidate = directory.resolve(this.take.audioFileName());
		return Files.exists(candidate) ? candidate : null;
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

	private List<String> collectTrimmedMarkers() {
		if (this.take == null || this.take.markers().isEmpty()) {
			return List.of();
		}
		List<String> list = new ArrayList<>();
		for (double m : this.take.markers()) {
			if (m >= this.inTime && m <= this.outTime) {
				list.add(String.format(Locale.ROOT, "%.3f", m - this.inTime));
			}
		}
		return list;
	}

	public void evaluateSample() {
		if (this.trajectory == null) {
			this.currentSample = null;
			return;
		}
		float smoothing;
		float responsiveness;
		if (this.exporting) {
			// Frozen at export start so live edits never leak into the exported
			// video, and the export always uses the polished look (never raw).
			smoothing = this.exportSmoothing;
			responsiveness = this.exportResponsiveness;
		} else {
			ExportSettings settings = ExportSettings.get();
			smoothing = this.compareRaw ? 0.0F : settings.smoothingAmount();
			responsiveness = settings.responsivenessAmount();
		}
		this.currentSample = this.trajectory.sampleAt(this.playhead, smoothing, responsiveness);
	}

	private void applyToPlayer(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		TakeData.Sample sample = this.currentSample;
		if (player == null || sample == null) {
			return;
		}

		player.setPos(sample.x(), sample.y(), sample.z());
		player.xo = sample.x();
		player.yo = sample.y();
		player.zo = sample.z();
		// Kill gameplay momentum so physics never fights the recreated path; a
		// drifting client prediction is what made old exports wobble and snap back.
		player.setDeltaMovement(0.0, 0.0, 0.0);
		player.fallDistance = 0.0F;
		// Keep the integrated-server player locked to the same path so server-side
		// corrections never rubber-band the recreation.
		syncIntegratedServerPlayer(minecraft, player.getUUID(), sample);

		player.setYRot(sample.yaw());
		player.setXRot(sample.pitch());
		player.yRotO = sample.yaw();
		player.xRotO = sample.pitch();
		player.yHeadRot = sample.yaw();
		player.yHeadRotO = sample.yaw();
		player.yBodyRot = sample.yaw();
		player.yBodyRotO = sample.yaw();

		player.xBob = sample.xBob();
		player.xBobO = sample.xBob();
		player.yBob = sample.yBob();
		player.yBobO = sample.yBob();

		player.attackAnim = sample.attackAnim();
		player.oAttackAnim = sample.attackAnim();
		player.swinging = sample.swinging();
		player.swingingArm = sample.offhandSwing() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		player.walkAnimation.setSpeed(sample.walkSpeed());

		int poseIdx = sample.poseOrdinal();
		if (poseIdx >= 0 && poseIdx < POSES.length) {
			player.setPose(POSES[poseIdx]);
		}
		player.setShiftKeyDown(sample.sneaking());
		player.setSprinting(sample.sprinting());
		player.setOnGround(sample.onGround());

		int slot = sample.hotbarSlot();
		if (slot >= 0 && slot < 9) {
			player.getInventory().setSelectedSlot(slot);
			if (slot != this.lastAppliedSlot) {
				this.lastAppliedSlot = slot;
				minecraft.gameRenderer.itemInHandRenderer.tick();
			}
		}

	}

	private static void syncIntegratedServerPlayer(Minecraft minecraft, UUID uuid, TakeData.Sample sample) {
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		double x = sample.x();
		double y = sample.y();
		double z = sample.z();
		float yaw = sample.yaw();
		float pitch = sample.pitch();
		server.execute(() -> {
			ServerPlayer serverPlayer = server.getPlayerList().getPlayer(uuid);
			if (serverPlayer != null) {
				serverPlayer.setPos(x, y, z);
				serverPlayer.setYRot(yaw);
				serverPlayer.setXRot(pitch);
			}
		});
	}

	private void closeSpoolQuietly() {
		OfflineRenderer.Spool spool = this.exportSpool;
		this.exportSpool = null;
		if (spool != null) {
			try {
				spool.close();
			} catch (IOException ignored) {
			}
		}
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

	private record SavedPlayerState(
		double x,
		double y,
		double z,
		float yaw,
		float pitch,
		Pose pose,
		int hotbarSlot,
		CameraType cameraType,
		boolean hideGui
	) {
		static SavedPlayerState capture(Minecraft minecraft) {
			LocalPlayer p = minecraft.player;
			return new SavedPlayerState(
				p.getX(),
				p.getY(),
				p.getZ(),
				p.getYRot(),
				p.getXRot(),
				p.getPose(),
				p.getInventory().getSelectedSlot(),
				minecraft.options.getCameraType(),
				minecraft.options.hideGui
			);
		}

		void restore(Minecraft minecraft) {
			minecraft.options.setCameraType(this.cameraType);
			minecraft.options.hideGui = this.hideGui;
			LocalPlayer p = minecraft.player;
			if (p != null) {
				p.setPos(this.x, this.y, this.z);
				p.xo = this.x;
				p.yo = this.y;
				p.zo = this.z;
				p.setYRot(this.yaw);
				p.setXRot(this.pitch);
				p.yRotO = this.yaw;
				p.xRotO = this.pitch;
				p.yHeadRot = this.yaw;
				p.yHeadRotO = this.yaw;
				p.setPose(this.pose);
				if (this.hotbarSlot >= 0 && this.hotbarSlot < 9) {
					p.getInventory().setSelectedSlot(this.hotbarSlot);
				}
				MinecraftServer server = minecraft.getSingleplayerServer();
				if (server != null) {
					UUID uuid = p.getUUID();
					server.execute(() -> {
						ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
						if (sp != null) {
							sp.setPos(this.x, this.y, this.z);
							sp.setYRot(this.yaw);
							sp.setXRot(this.pitch);
						}
					});
				}
			}
		}
	}
}
