package dev.imxmax.repolish.client.capture;

import dev.imxmax.repolish.capture.JpegFrames;
import dev.imxmax.repolish.capture.MjpegAviWriter;
import java.io.IOException;
import java.nio.file.Path;

public final class MjpegEncoder implements FrameEncoder {
	private final Path output;
	private final MjpegAviWriter writer;

	public MjpegEncoder(Path output, int width, int height, int fps) throws IOException {
		this.output = output;
		this.writer = new MjpegAviWriter(output, width, height, fps);
	}

	@Override
	public void write(int[] abgr, int width, int height) throws IOException {
		this.writer.writeJpeg(JpegFrames.fromAbgr(abgr, width, height, 0.82F));
	}

	@Override
	public void close() throws IOException {
		this.writer.close();
	}

	@Override
	public Path output() {
		return this.output;
	}

	@Override
	public boolean capturedAudio() {
		return false;
	}
}
