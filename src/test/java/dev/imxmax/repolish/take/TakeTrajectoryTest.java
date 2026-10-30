package dev.imxmax.repolish.take;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.imxmax.repolish.camera.LookSmoother;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TakeTrajectoryTest {
	@TempDir
	Path tempDir;

	@Test
	void roundTripsTakeDataAndMarkers() throws IOException {
		TakeData take = new TakeData("tas_run", 123456789L, 90);
		take.setRecordedDayTime(18042L);
		take.setRecordedGameTime(18050L);
		take.setAudioFileName(".tas_run-audio.wav");
		take.addMarker(0.25);
		take.addMarker(1.50);
		take.addSample(new TakeData.Sample(0.0, 10.0, 64.0, -5.0, 1.62F, 0.0F, 0.0F, 0.4F, 1.2F, 2.0F, 3.0F, 0.0F, 2, 0, TakeData.FLAG_ON_GROUND | TakeData.FLAG_SPRINTING));
		take.addSample(new TakeData.Sample(0.5, 12.5, 64.0, -3.0, 1.27F, 45.0F, -15.0F, 0.8F, 2.4F, 4.0F, 6.0F, 0.5F, 4, 5, TakeData.FLAG_SNEAKING | TakeData.FLAG_SWINGING));

		Path file = this.tempDir.resolve("tas_run.rtake");
		take.save(file);

		TakeData loaded = TakeData.load(file);
		assertEquals("tas_run", loaded.name());
		assertEquals(123456789L, loaded.createdAtMillis());
		assertEquals(90, loaded.recordedFov());
		assertTrue(loaded.hasRecordedClock());
		assertEquals(18042L, loaded.recordedDayTime());
		assertEquals(18050L, loaded.recordedGameTime());
		assertEquals(".tas_run-audio.wav", loaded.audioFileName());
		assertEquals(2, loaded.markers().size());
		assertEquals(0.25, loaded.markers().get(0), 1.0E-6);
		assertEquals(2, loaded.samples().size());

		TakeData.Sample second = loaded.samples().get(1);
		assertEquals(12.5, second.x(), 1.0E-6);
		assertEquals(45.0F, second.yaw(), 1.0E-4F);
		assertEquals(-15.0F, second.pitch(), 1.0E-4F);
		assertEquals(4, second.hotbarSlot());
		assertTrue(second.sneaking());
		assertTrue(second.swinging());
	}

	@Test
	void recreatesSteppedTasTurnAsCenteredContinuousMouseSweep() {
		TakeData take = new TakeData("turn", 0L, 70);
		// Simulate 20 TPS (50 ms per tick) where a sharp 90 degree step happens at t = 0.50s
		for (int tick = 0; tick <= 20; tick++) {
			double t = tick * 0.05;
			float yaw = tick < 10 ? 0.0F : 90.0F;
			take.addSample(new TakeData.Sample(t, tick * 0.2, 64.0, 0.0, 1.62F, yaw, 0.0F, 0.5F, tick * 0.3F, 0.0F, 0.0F, 0.0F, 0, 0, TakeData.FLAG_ON_GROUND));
		}

		TakeTrajectory trajectory = new TakeTrajectory(take);
		// Sample at 120 FPS across the turn window [0.35s .. 0.65s]
		float previousYaw = -1.0F;
		for (int frame = 42; frame <= 78; frame++) {
			double t = frame / 120.0;
			TakeData.Sample sample = trajectory.sampleAt(t, 0.55F, 0.70F);
			assertTrue(sample.yaw() >= previousYaw - 0.1F, "recreated mouse sweep should move monotonically without oscillation");
			previousYaw = sample.yaw();
		}

		// Zero-phase check: at the step midpoint (t = 0.475s between tick 9 and tick 10),
		// the recreated angle is centered around ~45 deg rather than lagging behind like spectator camera.
		TakeData.Sample center = trajectory.sampleAt(0.475, 0.55F, 0.70F);
		assertTrue(center.yaw() > 30.0F && center.yaw() < 60.0F, "turn should be centered on the action without spectator lag, was " + center.yaw());
	}

	@Test
	void unwrapsYawAcrossMinus180ToPlus180Boundary() {
		TakeData take = new TakeData("wrap", 0L, 70);
		take.addSample(new TakeData.Sample(0.0, 0.0, 64.0, 0.0, 1.62F, 172.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, TakeData.FLAG_ON_GROUND));
		take.addSample(new TakeData.Sample(0.05, 0.0, 64.0, 0.0, 1.62F, -172.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, TakeData.FLAG_ON_GROUND));
		take.addSample(new TakeData.Sample(0.10, 0.0, 64.0, 0.0, 1.62F, -165.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, TakeData.FLAG_ON_GROUND));

		TakeTrajectory trajectory = new TakeTrajectory(take);
		TakeData.Sample mid = trajectory.sampleAt(0.025, 0.5F, 0.7F);
		float diffFrom180 = Math.abs(LookSmoother.wrapDegrees(mid.yaw() - 180.0F));
		assertTrue(diffFrom180 < 15.0F, "interpolated yaw across +/-180 should pass near 180, was " + mid.yaw());
	}
}
