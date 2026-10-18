package dev.imxmax.repolish.client;

import dev.imxmax.repolish.Repolish;
import net.fabricmc.api.ClientModInitializer;

public class RepolishClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Repolish.LOGGER.info("Repolish client loaded");
	}
}
