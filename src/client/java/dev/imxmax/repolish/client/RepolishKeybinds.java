package dev.imxmax.repolish.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.imxmax.repolish.Repolish;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class RepolishKeybinds {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Repolish.id("controls"));
	public static KeyMapping record;
	public static KeyMapping export;
	public static KeyMapping preview;
	public static KeyMapping marker;
	public static KeyMapping hud;

	private RepolishKeybinds() {
	}

	public static void register() {
		record = register("record", GLFW.GLFW_KEY_F8);
		export = register("export", GLFW.GLFW_KEY_F7);
		preview = register("preview", GLFW.GLFW_KEY_F6);
		marker = register("marker", GLFW.GLFW_KEY_F9);
		hud = register("hud", GLFW.GLFW_KEY_F10);
	}

	private static KeyMapping register(String path, int key) {
		return KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.repolish." + path,
			InputConstants.Type.KEYSYM,
			key,
			CATEGORY
		));
	}
}
