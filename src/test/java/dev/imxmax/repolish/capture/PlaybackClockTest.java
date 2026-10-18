package dev.imxmax.repolish.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaybackClockTest {
	@Test
	void steadyCaptureWritesOneFramePerSlot() {
		PlaybackClock clock = new PlaybackClock(0L, 60);
		assertEquals(0, clock.previousRepeats(0L));
		assertEquals(0, clock.previousRepeats(16_666_667L));
		assertEquals(0, clock.previousRepeats(33_333_333L));
	}

	@Test
	void slowCaptureHoldsThePreviousPicture() {
		PlaybackClock clock = new PlaybackClock(0L, 60);
		assertEquals(0, clock.previousRepeats(0L));
		assertEquals(5, clock.previousRepeats(100_000_000L));
	}

	@Test
	void oneSecondAtTwentyCapturesStillLastsAboutOneSecond() {
		PlaybackClock clock = new PlaybackClock(0L, 60);
		long frames = 0L;
		for (int millis = 0; millis <= 1000; millis += 50) {
			int hold = clock.previousRepeats(millis * 1_000_000L);
			if (hold >= 0) {
				frames += hold + 1L;
			}
		}
		assertTrue(frames >= 58 && frames <= 63, "frames=" + frames);
	}

	@Test
	void duplicateSlotIsDropped() {
		PlaybackClock clock = new PlaybackClock(0L, 60);
		assertEquals(0, clock.previousRepeats(0L));
		assertEquals(-1, clock.previousRepeats(1_000_000L));
	}
}
