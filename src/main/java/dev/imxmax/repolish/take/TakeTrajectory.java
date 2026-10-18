package dev.imxmax.repolish.take;

import dev.imxmax.repolish.camera.LookSmoother;
import java.util.List;

/**
 * Reconstructs continuous first-person camera and player state from a recorded
 * take.
 *
 * Unlike Minecraft's spectator camera (which trails behind the player with a
 * one-sided damper), this evaluator uses the full recorded take to build a
 * zero-phase human mouse trajectory:
 * - 20 TPS staircase steps are converted into continuous mouse arcs centered on
 *   the exact tick where the turn happened.
 * - High-velocity flicks tighten their kernel width automatically based on the
 *   responsiveness setting so fast turns stay crisp and land right on target.
 * - First-person position is interpolated across sub-tick intervals so high-FPS
 *   exports have no tick stutter while remaining locked to the player's head.
 */
public final class TakeTrajectory {
	private static final int GRID_HZ = 240;

	private final TakeData take;
	private float lastSmoothing = -1.0F;
	private float lastResponsiveness = -1.0F;
	private float[] gridYaw = new float[0];
	private float[] gridPitch = new float[0];
	private double duration;

	public TakeTrajectory(TakeData take) {
		this.take = take;
		this.rebuild(0.35F, 0.70F);
	}

	public TakeData take() {
		return this.take;
	}

	public void rebuild(float smoothing, float responsiveness) {
		float clampedSmoothing = clamp01(smoothing);
		float clampedResponsiveness = clamp01(responsiveness);
		if (Math.abs(clampedSmoothing - this.lastSmoothing) < 1.0E-4F
			&& Math.abs(clampedResponsiveness - this.lastResponsiveness) < 1.0E-4F
			&& this.gridYaw.length > 0) {
			return;
		}
		this.lastSmoothing = clampedSmoothing;
		this.lastResponsiveness = clampedResponsiveness;

		List<TakeData.Sample> samples = this.take.samples();
		if (samples.isEmpty()) {
			this.duration = 0.0;
			this.gridYaw = new float[0];
			this.gridPitch = new float[0];
			return;
		}
		this.duration = samples.get(samples.size() - 1).time();
		int gridCount = Math.max(2, (int) Math.ceil(this.duration * GRID_HZ) + 1);

		float[] unwrappedSampleYaw = new float[samples.size()];
		unwrappedSampleYaw[0] = samples.get(0).yaw();
		for (int i = 1; i < samples.size(); i++) {
			float prev = unwrappedSampleYaw[i - 1];
			unwrappedSampleYaw[i] = prev + LookSmoother.wrapDegrees(samples.get(i).yaw() - prev);
		}

		float[] rawYawGrid = new float[gridCount];
		float[] rawPitchGrid = new float[gridCount];
		int cursor = 0;
		for (int g = 0; g < gridCount; g++) {
			double t = Math.min(this.duration, g / (double) GRID_HZ);
			while (cursor + 1 < samples.size() && samples.get(cursor + 1).time() < t) {
				cursor++;
			}
			TakeData.Sample a = samples.get(cursor);
			TakeData.Sample b = samples.get(Math.min(samples.size() - 1, cursor + 1));
			float alpha = segmentFraction(t, a.time(), b.time());
			rawYawGrid[g] = lerp(unwrappedSampleYaw[cursor], unwrappedSampleYaw[Math.min(samples.size() - 1, cursor + 1)], alpha);
			rawPitchGrid[g] = lerp(a.pitch(), b.pitch(), alpha);
		}

		this.gridYaw = smoothMouseCurve(rawYawGrid, clampedSmoothing, clampedResponsiveness, false);
		this.gridPitch = smoothMouseCurve(rawPitchGrid, clampedSmoothing, clampedResponsiveness, true);
	}

	public TakeData.Sample sampleAt(double timeSeconds, float smoothing, float responsiveness) {
		this.rebuild(smoothing, responsiveness);
		List<TakeData.Sample> samples = this.take.samples();
		if (samples.isEmpty()) {
			return new TakeData.Sample(0.0, 0.0, 0.0, 0.0, 1.62F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0, 0, TakeData.FLAG_ON_GROUND);
		}
		if (samples.size() == 1 || this.duration <= 1.0E-6) {
			return samples.get(0);
		}

		double clampedTime = Math.max(0.0, Math.min(this.duration, timeSeconds));
		int low = 0;
		int high = samples.size() - 1;
		while (low + 1 < high) {
			int mid = (low + high) >>> 1;
			if (samples.get(mid).time() <= clampedTime) {
				low = mid;
			} else {
				high = mid;
			}
		}

		TakeData.Sample s0 = samples.get(Math.max(0, low - 1));
		TakeData.Sample s1 = samples.get(low);
		TakeData.Sample s2 = samples.get(high);
		TakeData.Sample s3 = samples.get(Math.min(samples.size() - 1, high + 1));
		float alpha = segmentFraction(clampedTime, s1.time(), s2.time());

		// Smooth position interpolation that stays anchored to the recorded path
		double x = catmullRom(s0.x(), s1.x(), s2.x(), s3.x(), alpha);
		double y = catmullRom(s0.y(), s1.y(), s2.y(), s3.y(), alpha);
		double z = catmullRom(s0.z(), s1.z(), s2.z(), s3.z(), alpha);
		float eyeHeight = lerp(s1.eyeHeight(), s2.eyeHeight(), alpha);

		float yaw = LookSmoother.wrapDegrees(sampleGrid(this.gridYaw, clampedTime));
		float pitch = clamp(sampleGrid(this.gridPitch, clampedTime), -90.0F, 90.0F);

		float walkSpeed = lerp(s1.walkSpeed(), s2.walkSpeed(), alpha);
		float walkPos = lerp(s1.walkPos(), s2.walkPos(), alpha);
		float xBob = lerp(s1.xBob(), s2.xBob(), alpha);
		float yBob = lerp(s1.yBob(), s2.yBob(), alpha);
		float attackAnim = interpolateAttack(s1.attackAnim(), s2.attackAnim(), alpha);
		TakeData.Sample nearest = alpha < 0.5F ? s1 : s2;

		return new TakeData.Sample(
			clampedTime,
			x,
			y,
			z,
			eyeHeight,
			yaw,
			pitch,
			walkSpeed,
			walkPos,
			xBob,
			yBob,
			attackAnim,
			nearest.hotbarSlot(),
			nearest.poseOrdinal(),
			nearest.flags()
		);
	}

	/**
	 * Applies a velocity-adaptive zero-phase Gaussian pass across the uniform
	 * 240 Hz angle track.
	 *
	 * Because the kernel is symmetric in time [-radius, +radius], turns never
	 * lag behind gameplay actions (unlike spectator camera damping). Fast flicks
	 * shrink the effective radius according to responsiveness so they remain
	 * sharp and intentional.
	 */
	static float[] smoothMouseCurve(float[] rawGrid, float smoothing, float responsiveness, boolean clampPitch) {
		int n = rawGrid.length;
		if (n < 3 || smoothing <= 0.01F) {
			return rawGrid.clone();
		}

		// Base window half-width in 240 Hz samples:
		// 1 tick = 12 samples (50 ms). A window of 6..28 samples bridges 20 TPS steps into a smooth curve.
		float baseSigma = lerp(1.5F, 13.5F, smoothing) * lerp(1.25F, 0.55F, responsiveness);
		int maxRadius = Math.min(n - 1, Math.max(2, (int) Math.ceil(baseSigma * 2.6F)));

		float[] current = rawGrid.clone();
		int passes = smoothing > 0.55F ? 2 : 1;
		for (int pass = 0; pass < passes; pass++) {
			float[] next = new float[n];
			for (int i = 0; i < n; i++) {
				// Measure local angular speed over +/- 1 tick (12 samples = 50 ms)
				int back = Math.max(0, i - 6);
				int ahead = Math.min(n - 1, i + 6);
				float spanSeconds = Math.max(1.0F / GRID_HZ, (ahead - back) / (float) GRID_HZ);
				float degPerSec = Math.abs(current[ahead] - current[back]) / spanSeconds;

				// High angular speed + high responsiveness tightens the filter so flicks don't float
				float flickFactor = clamp01((degPerSec - 90.0F) / 540.0F) * responsiveness;
				float sigma = Math.max(0.85F, baseSigma * (1.0F - 0.68F * flickFactor));
				int radius = Math.min(maxRadius, Math.max(1, (int) Math.ceil(sigma * 2.5F)));
				float invTwoSigmaSq = 1.0F / (2.0F * sigma * sigma);

				double weightedSum = 0.0;
				double weightTotal = 0.0;
				int start = Math.max(0, i - radius);
				int end = Math.min(n - 1, i + radius);
				for (int j = start; j <= end; j++) {
					int d = j - i;
					double w = Math.exp(-(d * d) * invTwoSigmaSq);
					weightedSum += current[j] * w;
					weightTotal += w;
				}
				float value = (float) (weightedSum / weightTotal);
				next[i] = clampPitch ? clamp(value, -90.0F, 90.0F) : value;
			}
			current = next;
		}
		return current;
	}

	private float sampleGrid(float[] grid, double timeSeconds) {
		if (grid.length == 0) {
			return 0.0F;
		}
		double pos = timeSeconds * GRID_HZ;
		int idx = (int) Math.floor(pos);
		if (idx < 0) {
			return grid[0];
		}
		if (idx + 1 >= grid.length) {
			return grid[grid.length - 1];
		}
		float frac = (float) (pos - idx);
		return lerp(grid[idx], grid[idx + 1], frac);
	}

	private static float interpolateAttack(float a, float b, float alpha) {
		if (b < a && a - b > 0.4F) {
			return a < 0.01F ? 0.0F : b;
		}
		return lerp(a, b, alpha);
	}

	private static double catmullRom(double p0, double p1, double p2, double p3, float t) {
		if (Math.abs(p2 - p1) > 8.0) {
			// Teleport or sudden cut: linear/step rather than spline ringing
			return p1 + (p2 - p1) * t;
		}
		double t2 = t * t;
		double t3 = t2 * t;
		return 0.5 * (
			(2.0 * p1)
				+ (-p0 + p2) * t
				+ (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2
				+ (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
		);
	}

	private static float segmentFraction(double t, double start, double end) {
		double span = end - start;
		if (span <= 1.0E-6) {
			return 0.0F;
		}
		return clamp01((float) ((t - start) / span));
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static float clamp01(float value) {
		return clamp(value, 0.0F, 1.0F);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}
