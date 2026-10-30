package dev.imxmax.repolish.take;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores a recorded first-person gameplay take so it can be replayed, edited
 * live in-game, and exported at any framerate, FOV, and camera smoothing.
 */
public final class TakeData {
	private static final int MAGIC = 0x5250544B; // "RPTK"
	private static final int VERSION = 1;

	public static final int FLAG_SNEAKING = 1;
	public static final int FLAG_SPRINTING = 1 << 1;
	public static final int FLAG_ON_GROUND = 1 << 2;
	public static final int FLAG_SWINGING = 1 << 3;
	public static final int FLAG_OFFHAND_SWING = 1 << 4;

	private String name;
	private long createdAtMillis;
	private int recordedFov;
	private String audioFileName;
	private final List<Sample> samples = new ArrayList<>();
	private final List<Double> markers = new ArrayList<>();

	public TakeData(String name, long createdAtMillis, int recordedFov) {
		this.name = name == null || name.isBlank() ? "take" : name;
		this.createdAtMillis = createdAtMillis;
		this.recordedFov = recordedFov;
	}

	public String name() {
		return this.name;
	}

	public void setName(String name) {
		if (name != null && !name.isBlank()) {
			this.name = name;
		}
	}

	public long createdAtMillis() {
		return this.createdAtMillis;
	}

	public int recordedFov() {
		return this.recordedFov;
	}

	public String audioFileName() {
		return this.audioFileName;
	}

	public void setAudioFileName(String audioFileName) {
		this.audioFileName = audioFileName;
	}

	public void addSample(Sample sample) {
		if (!this.samples.isEmpty()) {
			Sample last = this.samples.get(this.samples.size() - 1);
			if (sample.time() <= last.time()) {
				this.samples.set(this.samples.size() - 1, sample);
				return;
			}
		}
		this.samples.add(sample);
	}

	public void addMarker(double timeSeconds) {
		this.markers.add(Math.max(0.0, timeSeconds));
	}

	public List<Sample> samples() {
		return Collections.unmodifiableList(this.samples);
	}

	public List<Double> markers() {
		return Collections.unmodifiableList(this.markers);
	}

	public double duration() {
		if (this.samples.isEmpty()) {
			return 0.0;
		}
		return this.samples.get(this.samples.size() - 1).time();
	}

	public void save(Path file) throws IOException {
		if (file.getParent() != null) {
			Files.createDirectories(file.getParent());
		}
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 65536))) {
			out.writeInt(MAGIC);
			out.writeInt(VERSION);
			out.writeUTF(this.name);
			out.writeLong(this.createdAtMillis);
			out.writeInt(this.recordedFov);
			out.writeUTF(this.audioFileName == null ? "" : this.audioFileName);
			out.writeInt(this.markers.size());
			for (double marker : this.markers) {
				out.writeDouble(marker);
			}
			out.writeInt(this.samples.size());
			for (Sample sample : this.samples) {
				out.writeDouble(sample.time());
				out.writeDouble(sample.x());
				out.writeDouble(sample.y());
				out.writeDouble(sample.z());
				out.writeFloat(sample.eyeHeight());
				out.writeFloat(sample.yaw());
				out.writeFloat(sample.pitch());
				out.writeFloat(sample.walkSpeed());
				out.writeFloat(sample.walkPos());
				out.writeFloat(sample.xBob());
				out.writeFloat(sample.yBob());
				out.writeFloat(sample.attackAnim());
				out.writeByte(sample.hotbarSlot());
				out.writeByte(sample.poseOrdinal());
				out.writeByte(sample.flags());
			}
		}
	}

	public static TakeData load(Path file) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 65536))) {
			int magic = in.readInt();
			if (magic != MAGIC) {
				throw new IOException("Not a Repolish take file: " + file);
			}
			int version = in.readInt();
			if (version != VERSION) {
				throw new IOException("Unsupported Repolish take version: " + version);
			}
			String name = in.readUTF();
			long createdAt = in.readLong();
			int fov = in.readInt();
			String audioFile = in.readUTF();
			TakeData take = new TakeData(name, createdAt, fov);
			if (!audioFile.isBlank()) {
				take.setAudioFileName(audioFile);
			}
			int markerCount = in.readInt();
			for (int i = 0; i < markerCount; i++) {
				take.addMarker(in.readDouble());
			}
			int sampleCount = in.readInt();
			for (int i = 0; i < sampleCount; i++) {
				take.addSample(new Sample(
					in.readDouble(),
					in.readDouble(),
					in.readDouble(),
					in.readDouble(),
					in.readFloat(),
					in.readFloat(),
					in.readFloat(),
					in.readFloat(),
					in.readFloat(),
					in.readFloat(),
					in.readFloat(),
					in.readFloat(),
					in.readByte() & 0xFF,
					in.readByte() & 0xFF,
					in.readByte() & 0xFF
				));
			}
			return take;
		}
	}

	public record Sample(
		double time,
		double x,
		double y,
		double z,
		float eyeHeight,
		float yaw,
		float pitch,
		float walkSpeed,
		float walkPos,
		float xBob,
		float yBob,
		float attackAnim,
		int hotbarSlot,
		int poseOrdinal,
		int flags
	) {
		public boolean sneaking() {
			return (this.flags & FLAG_SNEAKING) != 0;
		}

		public boolean sprinting() {
			return (this.flags & FLAG_SPRINTING) != 0;
		}

		public boolean onGround() {
			return (this.flags & FLAG_ON_GROUND) != 0;
		}

		public boolean swinging() {
			return (this.flags & FLAG_SWINGING) != 0;
		}

		public boolean offhandSwing() {
			return (this.flags & FLAG_OFFHAND_SWING) != 0;
		}
	}
}
