package dev.imxmax.repolish.client;

import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.client.screen.ExportSettingsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class RepolishClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ExportSettings.load();
		RepolishKeybinds.register();
		ClientTickEvents.END_CLIENT_TICK.register(RepolishClient::onTick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(CaptureController::shutdown);
		HudElementRegistry.addLast(Repolish.id("status"), (graphics, tickCounter) -> {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player == null) {
				return;
			}
			if (CaptureController.isRecording() || !ExportSettings.get().preview) {
				return;
			}
			graphics.drawString(minecraft.font, Component.translatable("repolish.hud.preview"), 6, 6, 0xFFE7C36A);
		});
		Repolish.LOGGER.info("Repolish client loaded");
	}

	private static void onTick(Minecraft minecraft) {
		if (minecraft.player == null) {
			return;
		}
		while (RepolishKeybinds.record.consumeClick()) {
			CaptureController.toggle(minecraft);
		}
		while (RepolishKeybinds.export.consumeClick()) {
			if (!(minecraft.screen instanceof ExportSettingsScreen)) {
				minecraft.setScreen(new ExportSettingsScreen(minecraft.screen));
			}
		}
		while (RepolishKeybinds.preview.consumeClick()) {
			ExportSettings settings = ExportSettings.get();
			settings.preview = !settings.preview;
			settings.save();
			minecraft.gui.getChat().addMessage(Component.translatable(settings.preview ? "repolish.chat.preview_on" : "repolish.chat.preview_off"));
		}
		while (RepolishKeybinds.marker.consumeClick()) {
			if (CaptureController.isRecording()) {
				CaptureController.mark();
				minecraft.gui.getChat().addMessage(Component.translatable("repolish.chat.marker", CaptureController.markerCount()));
			} else {
				minecraft.gui.getChat().addMessage(Component.translatable("repolish.chat.marker_idle"));
			}
		}
		while (RepolishKeybinds.hud.consumeClick()) {
			CaptureController.toggleHud(minecraft);
		}
	}
}
