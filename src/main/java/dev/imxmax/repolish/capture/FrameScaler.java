package dev.imxmax.repolish.capture;

public final class FrameScaler {
	private FrameScaler() {
	}

	public static Size targetSize(int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
		if (maxWidth <= 0 || maxHeight <= 0 || (sourceWidth <= maxWidth && sourceHeight <= maxHeight)) {
			return new Size(sourceWidth, sourceHeight);
		}
		double scale = Math.min(maxWidth / (double) sourceWidth, maxHeight / (double) sourceHeight);
		int width = Math.max(2, (int) Math.round(sourceWidth * scale));
		int height = Math.max(2, (int) Math.round(sourceHeight * scale));
		width -= width & 1;
		height -= height & 1;
		if (width < 2) {
			width = 2;
		}
		if (height < 2) {
			height = 2;
		}
		return new Size(width, height);
	}

	public static int[] fit(int[] source, int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
		Size target = targetSize(sourceWidth, sourceHeight, maxWidth, maxHeight);
		if (target.width == sourceWidth && target.height == sourceHeight) {
			return source;
		}
		int[] scaled = new int[target.width * target.height];
		for (int y = 0; y < target.height; y++) {
			int sourceY = y * sourceHeight / target.height;
			for (int x = 0; x < target.width; x++) {
				int sourceX = x * sourceWidth / target.width;
				scaled[y * target.width + x] = source[sourceY * sourceWidth + sourceX];
			}
		}
		return scaled;
	}

	public record Size(int width, int height) {
	}
}
