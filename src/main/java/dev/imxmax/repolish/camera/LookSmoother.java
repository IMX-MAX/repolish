package dev.imxmax.repolish.camera;

/**
 * Turns a TAS look into a continuous mouse sweep without spectator lag.
 *
 * A TAS changes yaw and pitch once per game tick, so fast turns arrive as big
 * steps. Those steps are rounded with a zero-phase pass (centered on the same
 * tick, not trailing behind it), then a spring with velocity matching carries
 * the view across the frames between ticks.
 */
public final class LookSmoother {
	private final SpringAxis yaw = new SpringAxis();
	private final SpringAxis pitch = new SpringAxis();
	private boolean initialized;
	private double lastTime;

	public void reset() {
		this.yaw.reset();
		this.pitch.reset();
		this.initialized = false;
	}

	public Aim smooth(float rawYaw, float rawPitch, double timeSeconds, float smoothing, float responsiveness) {
		float amount = clamp01(smoothing);
		float response = clamp01(responsiveness);
		if (!this.initialized) {
			this.initialized = true;
			this.lastTime = timeSeconds;
			this.yaw.jumpTo(rawYaw);
			this.pitch.jumpTo(rawPitch);
			return new Aim(wrapDegrees(rawYaw), clamp(rawPitch, -90.0F, 90.0F));
		}

		double dt = timeSeconds - this.lastTime;
		if (dt < 1.0E-4) {
			return new Aim(wrapDegrees(this.yaw.value()), clamp(this.pitch.value(), -90.0F, 90.0F));
		}
		if (dt > 0.25) {
			this.lastTime = timeSeconds;
			this.yaw.jumpTo(rawYaw);
			this.pitch.jumpTo(rawPitch);
			return new Aim(wrapDegrees(rawYaw), clamp(rawPitch, -90.0F, 90.0F));
		}
		this.lastTime = timeSeconds;

		if (amount <= 0.01F) {
			this.yaw.jumpTo(this.yaw.value() + wrapDegrees(rawYaw - this.yaw.value()));
			this.pitch.jumpTo(rawPitch);
			return new Aim(wrapDegrees(rawYaw), clamp(rawPitch, -90.0F, 90.0F));
		}

		float omega = lerp(26.0F, 12.5F, amount) * lerp(0.85F, 1.55F, response);
		float lead = lerp(0.75F, 1.25F, response);
		float deadband = lerp(0.0F, 0.75F, amount);

		float targetYaw = this.yaw.target() + wrapDegrees(rawYaw - this.yaw.target());
		float yawSample = this.yaw.step(targetYaw, (float) dt, omega, lead, deadband);
		float pitchSample = this.pitch.step(rawPitch, (float) dt, omega, lead, deadband * 0.7F);
		return new Aim(wrapDegrees(yawSample), clamp(pitchSample, -90.0F, 90.0F));
	}

	/**
	 * Smooths a whole series of evenly spaced samples with zero phase lag.
	 * The turn starts slightly before the tick step and finishes slightly after,
	 * centered on the original action.
	 */
	public static float[] smoothTrack(float[] rawAngles, boolean wrap, float smoothing, float responsiveness) {
		int count = rawAngles.length;
		if (count == 0) {
			return new float[0];
		}
		float[] unwrapped = new float[count];
		unwrapped[0] = rawAngles[0];
		for (int i = 1; i < count; i++) {
			unwrapped[i] = wrap ? unwrapped[i - 1] + wrapDegrees(rawAngles[i] - unwrapped[i - 1]) : rawAngles[i];
		}
		float amount = clamp01(smoothing);
		if (amount <= 0.01F || count < 3) {
			return unwrapped;
		}
		float response = clamp01(responsiveness);
		float radiusSeconds = lerp(0.01F, 0.04F, amount) * lerp(1.2F, 0.65F, response);
		float alpha = 1.0F / (1.0F + radiusSeconds * 60.0F);
		int passes = amount > 0.65F ? 2 : 1;
		float[] current = unwrapped;
		for (int pass = 0; pass < passes; pass++) {
			float[] forward = new float[count];
			forward[0] = current[0];
			for (int i = 1; i < count; i++) {
				forward[i] = forward[i - 1] + alpha * (current[i] - forward[i - 1]);
			}
			float[] backward = new float[count];
			backward[count - 1] = forward[count - 1];
			for (int i = count - 2; i >= 0; i--) {
				backward[i] = backward[i + 1] + alpha * (forward[i] - backward[i + 1]);
			}
			current = backward;
		}
		if (!wrap) {
			for (int i = 0; i < count; i++) {
				current[i] = clamp(current[i], -90.0F, 90.0F);
			}
		}
		return current;
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

	private static final class SpringAxis {
		private float value;
		private float velocity;
		private float target;
		private float targetVelocity;

		void reset() {
			this.value = 0.0F;
			this.velocity = 0.0F;
			this.target = 0.0F;
			this.targetVelocity = 0.0F;
		}

		void jumpTo(float angle) {
			this.value = angle;
			this.target = angle;
			this.velocity = 0.0F;
			this.targetVelocity = 0.0F;
		}

		float value() {
			return this.value;
		}

		float target() {
			return this.target;
		}

		float step(float rawTarget, float dt, float omega, float lead, float deadband) {
			float delta = rawTarget - this.target;
			if (Math.abs(delta) < deadband) {
				delta = 0.0F;
			}
			float observedRate = clamp(delta / Math.max(dt, 0.008F), -1080.0F, 1080.0F);
			this.targetVelocity += 0.6F * (observedRate - this.targetVelocity);
			this.target += delta;

			float aimedTarget = this.target + this.targetVelocity * dt * lead * 0.55F;
			float exp = (float) Math.exp(-omega * dt);
			float offset = this.value - aimedTarget;
			float temp = (this.velocity + omega * offset) * dt;
			this.velocity = (this.velocity - omega * temp) * exp;
			this.value = aimedTarget + (offset + temp) * exp;
			return this.value;
		}
	}
}
