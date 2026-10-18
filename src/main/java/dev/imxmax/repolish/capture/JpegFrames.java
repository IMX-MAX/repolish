package dev.imxmax.repolish.capture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

public final class JpegFrames {
	private JpegFrames() {
	}

	public static byte[] fromAbgr(int[] abgr, int width, int height, float quality) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] rgb = new int[width * height];
		for (int i = 0; i < rgb.length; i++) {
			int pixel = abgr[i];
			int red = pixel & 0xFF;
			int green = (pixel >> 8) & 0xFF;
			int blue = (pixel >> 16) & 0xFF;
			rgb[i] = (red << 16) | (green << 8) | blue;
		}
		image.setRGB(0, 0, width, height, rgb, 0, width);
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(quality);
		ByteArrayOutputStream output = new ByteArrayOutputStream(width * height / 4);
		try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
			writer.setOutput(stream);
			writer.write(null, new IIOImage(image, null, null), param);
			stream.flush();
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		} finally {
			writer.dispose();
		}
		return output.toByteArray();
	}
}
