package dev.imxmax.repolish.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class PixelPack {
	private PixelPack() {
	}

	/**
	 * Packs Minecraft ABGR ints (A in the top byte, R in the low byte) into tightly packed RGBA bytes.
	 */
	public static byte[] abgrToRgba(int[] abgr, int pixelCount) {
		byte[] rgba = new byte[pixelCount * 4];
		ByteBuffer.wrap(rgba).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(abgr, 0, pixelCount);
		return rgba;
	}
}
