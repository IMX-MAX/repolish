package dev.imxmax.repolish.camera;

/**
 * One Euro filter. Fast motion raises the cutoff so the signal keeps up,
 * and slow motion lowers it so small noise drops out.
 */
public final class OneEuroFilter {
	private float minCutoff;
	private float beta;
	private float derivativeCutoff;
	private boolean initialized;
	private double lastTime;
	private float value;
	private float derivative;

	public OneEuroFilter(float minCutoff, float beta, float derivativeCutoff) {
		this.minCutoff = minCutoff;
		this.beta = beta;
		this.derivativeCutoff = derivativeCutoff;
	}

	public void configure(float minCutoff, float beta, float derivativeCutoff) {
		this.minCutoff = minCutoff;
		this.beta = beta;
		this.derivativeCutoff = derivativeCutoff;
	}

	public float value() {
		return this.value;
	}

	public boolean isInitialized() {
		return this.initialized;
	}

	public void reset() {
		this.initialized = false;
		this.derivative = 0.0F;
	}

	public void jumpTo(float value) {
		this.value = value;
		this.derivative = 0.0F;
		this.initialized = true;
	}

	public float filter(float sample, double timeSeconds) {
		if (!this.initialized) {
			this.initialized = true;
			this.lastTime = timeSeconds;
			this.value = sample;
			this.derivative = 0.0F;
			return sample;
		}

		double dt = timeSeconds - this.lastTime;
		if (dt < 1.0E-4) {
			return this.value;
		}
		if (dt > 0.25) {
			this.lastTime = timeSeconds;
			this.value = sample;
			this.derivative = 0.0F;
			return sample;
		}
		this.lastTime = timeSeconds;

		float rawDerivative = (float) ((sample - this.value) / dt);
		float derivativeAlpha = alpha(dt, this.derivativeCutoff);
		this.derivative += derivativeAlpha * (rawDerivative - this.derivative);
		float cutoff = this.minCutoff + this.beta * Math.abs(this.derivative);
		float alpha = alpha(dt, cutoff);
		this.value += alpha * (sample - this.value);
		return this.value;
	}

	private static float alpha(double dt, float cutoff) {
		float tau = (float) (1.0 / (2.0 * Math.PI * Math.max(cutoff, 1.0E-3F)));
		return 1.0F / (1.0F + tau / (float) dt);
	}
}
