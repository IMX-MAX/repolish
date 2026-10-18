package dev.imxmax.repolish.client.capture;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PngSequenceEncoder implements FrameEncoder {
	private final Path directory;
	private int frame;
	private int[] lastPixels;
	private int lastWidth;
	private int lastHeight;

	public PngSequenceEncoder(Path directory) throws IOException {
		this.directory = directory;
		Files.createDirectories(directory);
	}

	@Override
	public void repeatLast(int times) throws IOException {
		if (this.lastPixels == null || times <= 0) {
			return;
		}
		for (int i = 0; i < times; i++) {
			this.write(this.lastPixels, this.lastWidth, this.lastHeight);
		}
	}

	@Override
	public void write(int[] abgr, int width, int height) throws IOException {
		this.lastPixels = abgr;
		this.lastWidth = width;
		this.lastHeight = height;
		this.frame++;
		try (NativeImage image = new NativeImage(width, height, false)) {
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					image.setPixelABGR(x, y, abgr[y * width + x]);
				}
			}
			image.writeToFile(this.directory.resolve(String.format("frame-%06d.png", this.frame)));
		}
	}

	@Override
	public void close() {
	}

	@Override
	public Path output() {
		return this.directory;
	}

	@Override
	public boolean capturedAudio() {
		return false;
	}
}
