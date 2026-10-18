package dev.imxmax.repolish.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LookSmootherTest {
	@Test
	void holdsASteadyLook() {
		LookSmoother smoother = new LookSmoother();
		LookSmoother.Aim aim = smoother.smooth(40.0F, -12.0F, 0.0, 0.5F, 0.5F);
		for (int i = 1; i <= 20; i++) {
			aim = smoother.smooth(40.0F, -12.0F, i * 0.02, 0.5F, 0.5F);
		}
		assertEquals(40.0F, aim.yaw(), 0.2F);
		assertEquals(-12.0F, aim.pitch(), 0.2F);
	}

	@Test
	void sharpTurnBecomesASmoothArcAcrossSubframes() {
		LookSmoother smoother = new LookSmoother();
		smoother.smooth(0.0F, 0.0F, 0.0, 0.45F, 0.7F);
		LookSmoother.Aim firstFrame = smoother.smooth(90.0F, 0.0F, 1.0 / 60.0, 0.45F, 0.7F);
		assertTrue(firstFrame.yaw() > 4.0F && firstFrame.yaw() < 65.0F,
			"a 90° tick step should start sweeping instead of snapping, was " + firstFrame.yaw());

		LookSmoother.Aim settled = firstFrame;
		for (int frame = 2; frame <= 18; frame++) {
			LookSmoother.Aim next = smoother.smooth(90.0F, 0.0F, frame / 60.0, 0.45F, 0.7F);
			assertTrue(next.yaw() >= settled.yaw() - 0.5F, "sweep should keep moving forward");
			settled = next;
		}
		assertTrue(settled.yaw() > 82.0F, "turn should finish within a fraction of a second, was " + settled.yaw());
	}

	@Test
	void zeroPhaseTrackRoundsATickCornerWithoutLag() {
		float[] raw = new float[21];
		for (int i = 10; i < raw.length; i++) {
			raw[i] = 90.0F;
		}
		float[] smoothed = LookSmoother.smoothTrack(raw, true, 0.5F, 0.7F);
		assertTrue(smoothed[8] > 2.0F, "turn begins slightly before the tick step");
		assertTrue(smoothed[10] > 35.0F && smoothed[10] < 65.0F, "midpoint stays centered on the step, was " + smoothed[10]);
		assertTrue(smoothed[13] > 80.0F, "turn finishes right after the step");
	}

	@Test
	void smallJitterShrinks() {
		LookSmoother smoother = new LookSmoother();
		double inputEnergy = 0.0;
		double outputEnergy = 0.0;
		for (int i = 0; i < 80; i++) {
			float noise = (i % 2 == 0) ? 1.4F : -1.4F;
			LookSmoother.Aim aim = smoother.smooth(noise, 0.0F, i * 0.02, 0.85F, 0.25F);
			if (i < 6) {
				continue;
			}
			inputEnergy += noise * noise;
			outputEnergy += aim.yaw() * aim.yaw();
		}
		assertTrue(outputEnergy < inputEnergy * 0.35, "output " + outputEnergy + " input " + inputEnergy);
	}

	@Test
	void yawWrapDoesNotSpinTheLongWay() {
		LookSmoother smoother = new LookSmoother();
		smoother.smooth(170.0F, 0.0F, 0.0, 0.4F, 0.8F);
		LookSmoother.Aim aim = smoother.smooth(-170.0F, 0.0F, 0.02, 0.4F, 0.8F);
		float delta = Math.abs(LookSmoother.wrapDegrees(aim.yaw() - 170.0F));
		assertTrue(delta < 25.0F, "wrapped step should stay short, delta " + delta + " yaw " + aim.yaw());
	}
}
