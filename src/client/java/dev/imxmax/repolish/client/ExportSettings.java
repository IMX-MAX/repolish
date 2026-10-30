package dev.imxmax.repolish.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.imxmax.repolish.Repolish;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class ExportSettings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ExportSettings instance = new ExportSettings();

	public Resolution resolution = Resolution.WINDOW;
	public int fps = 60;
	public boolean matchFov = true;
	public int fov = 70;
	public int smoothing = 35;
	public int responsiveness = 70;
	public boolean includeHud = true;
	public boolean includeAudio = true;
	public boolean preview = false;
	public boolean recordRaw = false;
	public String shader = "current";
	public String clipName = "clip";

	public static ExportSettings get() {
		return instance;
	}

	public static void load() {
		Path path = configPath();
		if (!Files.exists(path)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			ExportSettings loaded = GSON.fromJson(reader, ExportSettings.class);
			if (loaded != null) {
				loaded.sanitize();
				instance = loaded;
			}
		} catch (IOException | RuntimeException exception) {
			Repolish.LOGGER.warn("Could not read Repolish settings", exception);
		}
	}

	public void save() {
		sanitize();
		try {
			Files.createDirectories(configPath().getParent());
			try (Writer writer = Files.newBufferedWriter(configPath())) {
				GSON.toJson(this, writer);
			}
		} catch (IOException exception) {
			Repolish.LOGGER.warn("Could not save Repolish settings", exception);
		}
	}

	public float smoothingAmount() {
		return this.smoothing / 100.0F;
	}

	public float responsivenessAmount() {
		return this.responsiveness / 100.0F;
	}

	private void sanitize() {
		if (this.resolution == null) {
			this.resolution = Resolution.WINDOW;
		}
		if (this.fps != 30 && this.fps != 60 && this.fps != 120) {
			this.fps = 60;
		}
		this.fov = Math.max(30, Math.min(140, this.fov));
		this.smoothing = Math.max(0, Math.min(100, this.smoothing));
		this.responsiveness = Math.max(0, Math.min(100, this.responsiveness));
		if (this.shader == null || this.shader.isBlank()) {
			this.shader = "current";
		}
		if (this.clipName == null || this.clipName.isBlank()) {
			this.clipName = "clip";
		}
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("repolish.json");
	}

	public enum Resolution {
		WINDOW("Window", 0, 0),
		P720("1280×720", 1280, 720),
		P1080("1920×1080", 1920, 1080),
		P1440("2560×1440", 2560, 1440),
		P2160("3840×2160", 3840, 2160);

		private final String label;
		private final int width;
		private final int height;

		Resolution(String label, int width, int height) {
			this.label = label;
			this.width = width;
			this.height = height;
		}

		public String label() {
			return this.label;
		}

		public int width() {
			return this.width;
		}

		public int height() {
			return this.height;
		}
	}
}
