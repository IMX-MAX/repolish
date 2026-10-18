package dev.imxmax.repolish.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PixelPackTest {
	@Test
	void packsAbgrIntAsRgbaBytes() {
		int abgr = (0xFF << 24) | (0x10 << 16) | (0x20 << 8) | 0x30;
		byte[] rgba = PixelPack.abgrToRgba(new int[] {abgr}, 1);
		assertEquals(0x30, rgba[0] & 0xFF);
		assertEquals(0x20, rgba[1] & 0xFF);
		assertEquals(0x10, rgba[2] & 0xFF);
		assertEquals(0xFF, rgba[3] & 0xFF);
	}
}
