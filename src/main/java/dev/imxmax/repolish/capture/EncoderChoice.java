package dev.imxmax.repolish.capture;

/**
 * Picks a video encoder from {@code ffmpeg -encoders} output.
 * LGPL ffmpeg builds do not include libx264.
 */
public final class EncoderChoice {
	private EncoderChoice() {
	}

	public static String choose(String listing) {
		boolean mediaFoundation = false;
		boolean mpeg4 = false;
		if (listing == null) {
			return null;
		}
		for (String line : listing.split("\n")) {
			String[] parts = line.trim().split("\\s+");
			if (parts.length < 2) {
				continue;
			}
			String name = parts[1];
			if ("libx264".equals(name)) {
				return "libx264";
			}
			if ("h264_mf".equals(name)) {
				mediaFoundation = true;
			} else if ("mpeg4".equals(name)) {
				mpeg4 = true;
			}
		}
		if (mediaFoundation) {
			return "h264_mf";
		}
		if (mpeg4) {
			return "mpeg4";
		}
		return null;
	}
}
