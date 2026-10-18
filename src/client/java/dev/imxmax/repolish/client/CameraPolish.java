package dev.imxmax.repolish.client;

import dev.imxmax.repolish.camera.LookSmoother;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public final class CameraPolish {
	private static final CameraPolish INSTANCE = new CameraPolish();
	private static final long REPEAT_WINDOW_NANOS = 5_000_000L;

	private final LookSmoother smoother = new LookSmoother();
	private boolean wasActive;
	private long lastNanos;
	private LookSmoother.Aim cached;

	private CameraPolish() {
	}

	public static CameraPolish get() {
		return INSTANCE;
	}

	public static boolean wantsPolish() {
		return ExportSettings.get().preview || CaptureController.isRecording();
	}

	public LookSmoother.Aim polish(Entity entity, float partialTick) {
		boolean active = wantsPolish();
		if (!active || entity == null || (entity instanceof Player player && player.isSpectator())) {
			if (this.wasActive) {
				this.smoother.reset();
				this.cached = null;
				this.wasActive = false;
			}
			return null;
		}
		if (!this.wasActive) {
			this.smoother.reset();
			this.wasActive = true;
			this.lastNanos = 0L;
		}

		long now = System.nanoTime();
		if (this.cached != null && now - this.lastNanos < REPEAT_WINDOW_NANOS) {
			return this.cached;
		}
		this.lastNanos = now;
		ExportSettings settings = ExportSettings.get();
		this.cached = this.smoother.smooth(
			entity.getViewYRot(partialTick),
			entity.getViewXRot(partialTick),
			now / 1_000_000_000.0,
			settings.smoothingAmount(),
			settings.responsivenessAmount()
		);
		return this.cached;
	}
}
