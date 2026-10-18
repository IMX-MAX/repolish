package dev.imxmax.repolish.client;

import dev.imxmax.repolish.Repolish;
import dev.imxmax.repolish.client.capture.FfmpegEncoder;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.fabricmc.loader.api.FabricLoader;

public final class FfmpegInstaller {
	private static final String WINDOWS_ZIP = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";
	private static volatile boolean started;

	private FfmpegInstaller() {
	}

	public static Path installedBinary() {
		Path directory = FabricLoader.getInstance().getConfigDir().resolve("repolish");
		Path windows = directory.resolve("ffmpeg.exe");
		if (Files.isExecutable(windows) || Files.exists(windows)) {
			return windows;
		}
		Path unix = directory.resolve("ffmpeg");
		if (Files.isExecutable(unix)) {
			return unix;
		}
		return null;
	}

	public static boolean ready() {
		return FfmpegEncoder.available() || installedBinary() != null;
	}

	public static void ensureAsync() {
		if (started || !isWindows() || FfmpegEncoder.binary() != null) {
			return;
		}
		started = true;
		Thread thread = new Thread(FfmpegInstaller::downloadWindows, "repolish-ffmpeg-install");
		thread.setDaemon(true);
		thread.start();
	}

	private static void downloadWindows() {
		Path directory = FabricLoader.getInstance().getConfigDir().resolve("repolish");
		Path zip = directory.resolve("ffmpeg-download.zip");
		try {
			Files.createDirectories(directory);
			Repolish.LOGGER.info("Downloading ffmpeg for game audio");
			try (InputStream input = URI.create(WINDOWS_ZIP).toURL().openStream()) {
				Files.copy(input, zip, StandardCopyOption.REPLACE_EXISTING);
			}
			try (ZipInputStream zipInput = new ZipInputStream(Files.newInputStream(zip))) {
				ZipEntry entry;
				while ((entry = zipInput.getNextEntry()) != null) {
					if (entry.isDirectory() || !entry.getName().endsWith("bin/ffmpeg.exe")) {
						continue;
					}
					Path target = directory.resolve("ffmpeg.exe");
					Files.copy(zipInput, target, StandardCopyOption.REPLACE_EXISTING);
					Repolish.LOGGER.info("ffmpeg installed at {}", target);
					break;
				}
			}
		} catch (IOException exception) {
			Repolish.LOGGER.warn("Could not download ffmpeg. Recordings still save as AVI video.", exception);
			started = false;
		} finally {
			try {
				Files.deleteIfExists(zip);
			} catch (IOException ignored) {
			}
		}
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}
}
