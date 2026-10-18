package dev.imxmax.repolish.client.mixin;

import dev.imxmax.repolish.camera.LookSmoother;
import dev.imxmax.repolish.client.CameraPolish;
import dev.imxmax.repolish.client.ReplayController;
import dev.imxmax.repolish.take.TakeData;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "setup", at = @At("RETURN"))
	private void repolish$steadyLook(Level level, Entity entity, boolean detached, boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
		if (ReplayController.isActive()) {
			TakeData.Sample sample = ReplayController.currentSample();
			if (sample != null) {
				this.setPosition(sample.x(), sample.y() + sample.eyeHeight(), sample.z());
				this.setRotation(sample.yaw(), sample.pitch());
				return;
			}
		}
		LookSmoother.Aim aim = CameraPolish.get().polish(entity, partialTick);
		if (aim != null) {
			this.setRotation(aim.yaw(), aim.pitch());
		}
	}
}
