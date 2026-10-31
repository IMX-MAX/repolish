package com.vibe.flashbackheadless.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ReplayUI.class)
public class MixinReplayUI {

    @Inject(method = "init", at = @At("HEAD"), cancellable = true, remap = false)
    private static void flashbackHeadless$disableInit(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "drawOverlay", at = @At("HEAD"), cancellable = true, remap = false)
    private static void flashbackHeadless$disableDrawOverlay(CallbackInfo ci) {
        ci.cancel();
    }

}
