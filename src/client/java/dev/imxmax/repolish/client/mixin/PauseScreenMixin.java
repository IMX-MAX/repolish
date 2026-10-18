package dev.imxmax.repolish.client.mixin;

import dev.imxmax.repolish.client.CaptureController;
import dev.imxmax.repolish.client.screen.ExportSettingsScreen;
import dev.imxmax.repolish.client.screen.RecordingsScreen;
import net.minecraft.ChatFormatting;
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
	private void repolish$addButtons(CallbackInfo ci) {
		int x = this.width / 2 - 102;
		if (CaptureController.isRecording()) {
			this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.stop").withStyle(ChatFormatting.RED), button -> {
				if (this.minecraft != null) {
					CaptureController.toggle(this.minecraft);
					this.minecraft.setScreen(new PauseScreen(true));
				}
			}).bounds(x, 6, 204, 20).build());
		}
		int y = CaptureController.isRecording() ? 30 : 6;
		this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.recordings"), button -> {
			if (this.minecraft != null) {
				this.minecraft.setScreen(new RecordingsScreen(this));
			}
		}).bounds(x, y, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("repolish.button.pause"), button -> {
			if (this.minecraft != null) {
				this.minecraft.setScreen(new ExportSettingsScreen(this));
			}
		}).bounds(x + 104, y, 100, 20).build());
	}
}
