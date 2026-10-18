package dev.imxmax.repolish.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LookSmootherTest {
	@Test
	void holdsASteadyLook() {
		LookSmoother smoother = new LookSmoother();
		LookSmoother.Aim aim = smoother.smooth(40.0F, -12.0F, 0.0, 0.5F, 0.5F);
		for (int i = 1; i <= 10; i++) {
			aim = smoother.smooth(40.0F, -12.0F, i * 0.05, 0.5F, 0.5F);
		}
		assertEquals(40.0F, aim.yaw(), 0.05F);
		assertEquals(-12.0F, aim.pitch(), 0.05F);
	}

	@Test
	void largeFlickLandsWithoutSpectatorLag() {
		LookSmoother smoother = new LookSmoother();
		smoother.smooth(0.0F, 0.0F, 0.0, 0.35F, 0.7F);
		LookSmoother.Aim aim = smoother.smooth(90.0F, 0.0F, 0.05, 0.35F, 0.7F);
		assertTrue(aim.yaw() > 75.0F, "flick should already be near the target, was " + aim.yaw());
		assertTrue(aim.yaw() <= 90.5F);
	}

	@Test
	void smallJitterShrinks() {
		LookSmoother smoother = new LookSmoother();
		double inputEnergy = 0.0;
		double outputEnergy = 0.0;
		int samples = 0;
		for (int i = 0; i < 80; i++) {
			float noise = (i % 2 == 0) ? 1.4F : -1.4F;
			LookSmoother.Aim aim = smoother.smooth(noise, 0.0F, i * 0.05, 0.85F, 0.15F);
			if (i < 4) {
				continue;
			}
			inputEnergy += noise * noise;
			outputEnergy += aim.yaw() * aim.yaw();
			samples++;
		}
		assertTrue(outputEnergy < inputEnergy * 0.35, "output " + outputEnergy + " input " + inputEnergy + " n " + samples);
	}

	@Test
	void yawWrapDoesNotSpinTheLongWay() {
		LookSmoother smoother = new LookSmoother();
		smoother.smooth(170.0F, 0.0F, 0.0, 0.4F, 0.8F);
		LookSmoother.Aim aim = smoother.smooth(-170.0F, 0.0F, 0.05, 0.4F, 0.8F);
		float delta = Math.abs(LookSmoother.wrapDegrees(aim.yaw() - 170.0F));
		assertTrue(delta < 40.0F, "wrapped step should stay short, delta " + delta + " yaw " + aim.yaw());
	}
}
