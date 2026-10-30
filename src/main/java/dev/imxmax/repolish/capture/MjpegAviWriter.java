package dev.imxmax.repolish.capture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Motion-JPEG AVI writer. The file plays without an external encoder.
 */
public final class MjpegAviWriter implements AutoCloseable {
	private final FileChannel channel;
	private final int width;
	private final int height;
	private final int fps;
	private final List<Chunk> chunks = new ArrayList<>();
	private final long avihData;
	private final long strhData;
	private final long moviListSizePosition;
	private final long moviFourccPosition;
	private int frames;
	private int maxChunk;

	public MjpegAviWriter(Path path, int width, int height, int fps) throws IOException {
		this.width = width;
		this.height = height;
		this.fps = Math.max(1, fps);
		this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		writeAscii("RIFF");
		writeInt(0);
		writeAscii("AVI ");

		writeAscii("LIST");
		long hdrlSizePosition = this.channel.position();
		writeInt(0);
		long hdrlStart = this.channel.position();
		writeAscii("hdrl");

		writeAscii("avih");
		writeInt(56);
		this.avihData = this.channel.position();
		writeInt(1_000_000 / this.fps);
		writeInt(0);
		writeInt(0);
		writeInt(0x110);
		writeInt(0);
		writeInt(0);
		writeInt(1);
		writeInt(0);
		writeInt(this.width);
		writeInt(this.height);
		writeInt(0);
		writeInt(0);
		writeInt(0);
		writeInt(0);

		writeAscii("LIST");
		long strlSizePosition = this.channel.position();
		writeInt(0);
		long strlStart = this.channel.position();
		writeAscii("strl");
		writeAscii("strh");
		writeInt(56);
		this.strhData = this.channel.position();
		writeAscii("vids");
		writeAscii("MJPG");
		writeInt(0);
		writeShort(0);
		writeShort(0);
		writeInt(0);
		writeInt(1);
		writeInt(this.fps);
		writeInt(0);
		writeInt(0);
		writeInt(0);
		writeInt(-1);
		writeInt(0);
		writeShort(0);
		writeShort(0);
		writeShort(this.width);
		writeShort(this.height);

		writeAscii("strf");
		writeInt(40);
		writeInt(40);
		writeInt(this.width);
		writeInt(this.height);
		writeShort(1);
		writeShort(24);
		writeAscii("MJPG");
		writeInt(this.width * this.height * 3);
		writeInt(0);
		writeInt(0);
		writeInt(0);
		writeInt(0);
		patchInt(strlSizePosition, (int) (this.channel.position() - strlStart));
		patchInt(hdrlSizePosition, (int) (this.channel.position() - hdrlStart));

		writeAscii("LIST");
		this.moviListSizePosition = this.channel.position();
		writeInt(0);
		this.moviFourccPosition = this.channel.position();
		writeAscii("movi");
	}

	public void writeJpeg(byte[] jpeg) throws IOException {
		long offset = this.channel.position() - this.moviFourccPosition;
		writeAscii("00dc");
		writeInt(jpeg.length);
		this.channel.write(ByteBuffer.wrap(jpeg));
		if ((jpeg.length & 1) == 1) {
			this.channel.write(ByteBuffer.wrap(new byte[] {0}));
		}
		this.chunks.add(new Chunk((int) offset, jpeg.length));
		this.maxChunk = Math.max(this.maxChunk, jpeg.length);
		this.frames++;
	}

	@Override
	public void close() throws IOException {
		int moviContent = (int) (this.channel.position() - this.moviListSizePosition - 4);
		patchInt(this.moviListSizePosition, moviContent);
		writeAscii("idx1");
		writeInt(this.chunks.size() * 16);
		for (Chunk chunk : this.chunks) {
			writeAscii("00dc");
			writeInt(0x10);
			writeInt(chunk.offset());
			writeInt(chunk.size());
		}
		patchInt(4, (int) (this.channel.position() - 8));
		patchInt(this.avihData + 4, Math.max(this.maxChunk, 1) * this.fps);
		patchInt(this.avihData + 16, this.frames);
		patchInt(this.avihData + 28, this.maxChunk);
		patchInt(this.strhData + 32, this.frames);
		patchInt(this.strhData + 36, this.maxChunk);
		this.channel.close();
	}

	private void writeAscii(String text) throws IOException {
		this.channel.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.US_ASCII)));
	}

	private void writeInt(int value) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(value);
		buffer.flip();
		this.channel.write(buffer);
	}

	private void writeShort(int value) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putShort((short) value);
		buffer.flip();
		this.channel.write(buffer);
	}

	private void patchInt(long position, int value) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(value);
		buffer.flip();
		this.channel.write(buffer, position);
	}

	private record Chunk(int offset, int size) {
	}
}
