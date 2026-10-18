package dev.imxmax.repolish.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MjpegAviWriterTest {
	@Test
	void writesAPlayableContainer() throws Exception {
		Path file = Files.createTempFile("repolish", ".avi");
		int[] red = new int[16];
		java.util.Arrays.fill(red, 0xFF0000FF);
		int[] blue = new int[16];
		java.util.Arrays.fill(blue, 0xFFFF0000);
		try (MjpegAviWriter writer = new MjpegAviWriter(file, 4, 4, 10)) {
			writer.writeJpeg(JpegFrames.fromAbgr(red, 4, 4, 0.8F));
			writer.writeJpeg(JpegFrames.fromAbgr(blue, 4, 4, 0.8F));
		}
		byte[] bytes = Files.readAllBytes(file);
		assertEquals("RIFF", new String(bytes, 0, 4));
		assertEquals("AVI ", new String(bytes, 8, 4));
		int jpeg = indexOf(bytes, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
		assertTrue(jpeg > 0);
		assertTrue(new String(bytes, bytes.length - 8, 4).equals("00dc") || containsAscii(bytes, "idx1"));
		Files.deleteIfExists(file);
	}

	private static boolean containsAscii(byte[] bytes, String text) {
		return indexOf(bytes, text.getBytes()) >= 0;
	}

	private static int indexOf(byte[] bytes, byte[] needle) {
		for (int i = 0; i <= bytes.length - needle.length; i++) {
			boolean match = true;
			for (int j = 0; j < needle.length; j++) {
				if (bytes[i + j] != needle[j]) {
					match = false;
					break;
				}
			}
			if (match) {
				return i;
			}
		}
		return -1;
	}
}
