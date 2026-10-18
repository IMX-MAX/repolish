package dev.imxmax.repolish.client.capture;

import java.io.IOException;
import java.nio.file.Path;

public interface FrameEncoder extends AutoCloseable {
	void write(int[] abgr, int width, int height) throws IOException;

	@Override
	void close() throws IOException;

	Path output();

	boolean capturedAudio();
}
