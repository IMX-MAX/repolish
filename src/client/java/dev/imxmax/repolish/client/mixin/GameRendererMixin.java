package dev.imxmax.repolish.client.mixin;

import dev.imxmax.repolish.client.CameraPolish;
import dev.imxmax.repolish.client.CaptureController;
import dev.imxmax.repolish.client.ExportSettings;
import dev.imxmax.repolish.client.ReplayController;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void repolish$beforeRender(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		CaptureController.onBeforeRender(
			((GameRenderer) (Object) this).getMinecraft(),
			deltaTracker.getGameTimeDeltaPartialTick(true)
		);
	}

	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void repolish$overrideFov(Camera camera, float partialTick, boolean applyEffects, CallbackInfoReturnable<Float> cir) {
		ExportSettings settings = ExportSettings.get();
		if (ReplayController.isActive()) {
			// The recreation renders with the FOV the take was recorded with (or the
			// user's custom FOV), so the recreated scene matches the original footage.
			cir.setReturnValue(ReplayController.get().activeFov());
		} else if (!settings.matchFov && CameraPolish.wantsPolish()) {
			cir.setReturnValue((float) settings.fov);
		}
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void repolish$captureFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		CaptureController.onFrame(((GameRenderer) (Object) this).getMinecraft());
	}
}
