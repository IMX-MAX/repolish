package dev.imxmax.repolish.client;

import dev.imxmax.repolish.Repolish;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class ShaderPacks {
	public static final String CURRENT = "current";
	public static final String OFF = "off";

	private ShaderPacks() {
	}

	public static List<String> choices(Minecraft minecraft) {
		List<String> choices = new ArrayList<>();
		choices.add(CURRENT);
		choices.add(OFF);
		File directory = new File(minecraft.gameDirectory, "shaderpacks");
		File[] files = directory.listFiles();
		if (files != null) {
			List<String> packs = new ArrayList<>();
			for (File file : files) {
				String name = file.getName();
				if (name.endsWith(".zip") || name.endsWith(".jar") || file.isDirectory()) {
					packs.add(name);
				}
			}
			packs.sort(Comparator.naturalOrder());
			choices.addAll(packs);
		}
		return choices;
	}

	public static String label(String choice) {
		if (CURRENT.equals(choice)) {
			return "Current";
		}
		if (OFF.equals(choice)) {
			return "Off";
		}
		return choice;
	}

	public static void apply(String choice) {
		if (choice == null || CURRENT.equals(choice)) {
			return;
		}
		try {
			Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
			Object config = iris.getMethod("getIrisConfig").invoke(null);
			String packName = OFF.equals(choice) ? "OFF" : choice;
			Method setter = config.getClass().getMethod("setShaderPackName", String.class);
			setter.invoke(config, packName);
			iris.getMethod("reload").invoke(null);
			Repolish.LOGGER.info("Switched shader pack to {}", packName);
		} catch (ReflectiveOperationException exception) {
			Repolish.LOGGER.warn("Shader pack was not changed. Enable it in your shader mod and Repolish will record it.");
		}
	}
}
