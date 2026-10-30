package dev.imxmax.repolish.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EncoderChoiceTest {
	@Test
	void prefersLibx264WhenPresent() {
		String listing = """
			Encoders:
			 V..... mpeg4               MPEG-4 part 2
			 V..... libx264             libx264 H.264 / AVC
			 V..... h264_mf             H264 via MediaFoundation
			""";
		assertEquals("libx264", EncoderChoice.choose(listing));
	}

	@Test
	void usesMediaFoundationWhenLibx264IsMissing() {
		String listing = """
			 V..... mpeg4               MPEG-4 part 2
			 V..... h264_mf             H264 via MediaFoundation
			""";
		assertEquals("h264_mf", EncoderChoice.choose(listing));
	}

	@Test
	void usesMpeg4AsLastResort() {
		assertEquals("mpeg4", EncoderChoice.choose(" V.S... mpeg4               MPEG-4 part 2\n"));
	}

	@Test
	void ignoresNamesThatOnlyShareAPrefix() {
		assertEquals("mpeg4", EncoderChoice.choose(" V..... libx264rgb          libx264 RGB\n V..... mpeg4 mpeg\n"));
	}
}
