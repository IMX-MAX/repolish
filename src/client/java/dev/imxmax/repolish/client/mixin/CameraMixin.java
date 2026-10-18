package dev.imxmax.repolish.client.mixin;

import dev.imxmax.repolish.camera.LookSmoother;
import dev.imxmax.repolish.client.CameraPolish;
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

	@Inject(method = "setup", at = @At("RETURN"))
	private void repolish$steadyLook(Level level, Entity entity, boolean detached, boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
		LookSmoother.Aim aim = CameraPolish.get().polish(entity, partialTick);
		if (aim != null) {
			this.setRotation(aim.yaw(), aim.pitch());
		}
	}
}
