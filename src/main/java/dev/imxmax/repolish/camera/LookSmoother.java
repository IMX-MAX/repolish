package dev.imxmax.repolish.camera;

/**
 * Steadies a first-person look without the lag of Minecraft's spectator camera.
 * Large intentional flicks jump most of the way immediately, then the One Euro
 * filter cleans the leftover jitter.
 */
public final class LookSmoother {
	public static final float SNAP_YAW_DEGREES = 32.0F;
	public static final float SNAP_PITCH_DEGREES = 24.0F;
	private static final float SNAP_BLEND = 0.92F;

	private final OneEuroFilter yaw = new OneEuroFilter(1.5F, 1.0F, 1.0F);
	private final OneEuroFilter pitch = new OneEuroFilter(1.5F, 1.0F, 1.0F);

	public void reset() {
		this.yaw.reset();
		this.pitch.reset();
	}

	public Aim smooth(float rawYaw, float rawPitch, double timeSeconds, float smoothing, float responsiveness) {
		float amount = clamp01(smoothing);
		float response = clamp01(responsiveness);
		float minCutoff = lerp(9.0F, 0.4F, amount);
		float beta = lerp(0.05F, 3.4F, response);
		this.yaw.configure(minCutoff, beta, 1.0F);
		this.pitch.configure(minCutoff, beta, 1.0F);

		float yawSample = follow(this.yaw, rawYaw, SNAP_YAW_DEGREES, timeSeconds);
		float pitchSample = follow(this.pitch, rawPitch, SNAP_PITCH_DEGREES, timeSeconds);
		return new Aim(wrapDegrees(yawSample), clamp(pitchSample, -90.0F, 90.0F));
	}

	private static float follow(OneEuroFilter filter, float sample, float snapDegrees, double timeSeconds) {
		if (!filter.isInitialized()) {
			return filter.filter(sample, timeSeconds);
		}
		float unwrapped = filter.value() + wrapDegrees(sample - filter.value());
		float delta = unwrapped - filter.value();
		if (Math.abs(delta) >= snapDegrees) {
			filter.jumpTo(filter.value() + delta * SNAP_BLEND);
			unwrapped = filter.value() + wrapDegrees(sample - filter.value());
		}
		return filter.filter(unwrapped, timeSeconds);
	}

	public static float wrapDegrees(float degrees) {
		float wrapped = degrees % 360.0F;
		if (wrapped >= 180.0F) {
			wrapped -= 360.0F;
		}
		if (wrapped < -180.0F) {
			wrapped += 360.0F;
		}
		return wrapped;
	}

	private static float clamp01(float value) {
		return clamp(value, 0.0F, 1.0F);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float lerp(float from, float to, float t) {
		return from + (to - from) * t;
	}

	public record Aim(float yaw, float pitch) {
	}
}
