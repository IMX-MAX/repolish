package dev.imxmax.repolish.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class FrameScalerTest {
	@Test
	void leavesSmallerFramesAlone() {
		int[] pixels = new int[] {1, 2, 3, 4};
		assertSame(pixels, FrameScaler.fit(pixels, 2, 2, 1280, 720));
	}

	@Test
	void shrinksToTheRequestedBox() {
		int[] pixels = new int[8 * 4];
		for (int i = 0; i < pixels.length; i++) {
			pixels[i] = i;
		}
		int[] scaled = FrameScaler.fit(pixels, 8, 4, 4, 4);
		assertEquals(4, scaled.length / 2);
		assertEquals(8, scaled.length);
	}
}
