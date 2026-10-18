package dev.imxmax.repolish.client.mixin;

import dev.imxmax.repolish.client.screen.ExportSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
	protected PauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void repolish$addButton(CallbackInfo ci) {
		this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.pause"), button -> {
			if (this.minecraft != null) {
				this.minecraft.setScreen(new ExportSettingsScreen(this));
			}
		}).bounds(this.width - 108, 8, 100, 20).build());
	}
}
