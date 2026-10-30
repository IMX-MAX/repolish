package dev.imxmax.repolish.capture;

/**
 * Turns uneven capture times into a constant frame rate.
 * A late frame repeats the previous picture so playback stays in real time.
 */
public final class PlaybackClock {
	private final long originNanos;
	private final int fps;
	private long nextIndex;

	public PlaybackClock(long originNanos, int fps) {
		this.originNanos = originNanos;
		this.fps = Math.max(1, fps);
	}

	/**
	 * @return how many times to repeat the previous picture before showing this one,
	 * or -1 when this capture falls on a slot that is already filled
	 */
	public int previousRepeats(long capturedAtNanos) {
		long elapsed = Math.max(0L, capturedAtNanos - this.originNanos);
		long index = (elapsed * this.fps + 500_000_000L) / 1_000_000_000L;
		if (index < this.nextIndex) {
			return -1;
		}
		long gap = index - this.nextIndex;
		long cap = this.fps * 3L;
		if (gap > cap) {
			gap = cap;
		}
		this.nextIndex = index + 1;
		return (int) gap;
	}
}
