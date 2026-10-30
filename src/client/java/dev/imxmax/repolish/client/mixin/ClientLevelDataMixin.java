package dev.imxmax.repolish.client.mixin;

import dev.imxmax.repolish.client.ReplayController;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.ClientLevelData.class)
public abstract class ClientLevelDataMixin {
	/**
	 * Flashback-style world-clock recreation: while a take is open, the client
	 * world's day time is read from the recorded clock at the current playhead,
	 * so sky, lighting, and time-of-day match the original footage.
	 */
	@Inject(method = "getDayTime", at = @At("HEAD"), cancellable = true)
	private void repolish$replayDayTime(CallbackInfoReturnable<Long> cir) {
		Long replayTime = ReplayController.replayDayTime();
		if (replayTime != null) {
			cir.setReturnValue(replayTime);
		}
	}
}
