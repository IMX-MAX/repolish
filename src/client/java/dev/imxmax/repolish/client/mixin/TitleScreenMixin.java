package dev.imxmax.repolish.client.mixin;

import dev.imxmax.repolish.client.screen.RecordingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void repolish$addRecordings(CallbackInfo ci) {
		this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.recordings"), button -> {
			if (this.minecraft != null) {
				this.minecraft.setScreen(new RecordingsScreen(this));
			}
		}).bounds(this.width / 2 - 100, this.height - 54, 200, 20).build());
	}
}
