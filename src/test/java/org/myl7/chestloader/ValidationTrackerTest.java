package org.myl7.chestloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ValidationTrackerTest {
	private static final long POS = 1234L;
	private static final long OTHER = 5678L;

	@Test
	void awaitingStartsWithoutCountingARound() {
		ValidationTracker tracker = new ValidationTracker(3);
		tracker.await(POS);
		assertTrue(tracker.isAwaiting(POS));
		assertEquals(0, tracker.roundsWaited(POS));
	}

	@Test
	void awaitDoesNotResetAnOngoingCount() {
		ValidationTracker tracker = new ValidationTracker(3);
		tracker.await(POS);
		tracker.unreadable(POS);
		tracker.await(POS);
		assertEquals(1, tracker.roundsWaited(POS));
	}

	@Test
	void givesUpOnlyAfterTheFullRunOfRounds() {
		ValidationTracker tracker = new ValidationTracker(3);
		tracker.await(POS);
		assertFalse(tracker.unreadable(POS));
		assertFalse(tracker.unreadable(POS));
		assertTrue(tracker.unreadable(POS));
		// Giving up drops the entry, so a stale position cannot be counted twice.
		assertFalse(tracker.isAwaiting(POS));
		assertEquals(0, tracker.size());
	}

	@Test
	void aReadableRoundClearsTheCount() {
		ValidationTracker tracker = new ValidationTracker(3);
		tracker.await(POS);
		tracker.unreadable(POS);
		tracker.unreadable(POS);
		tracker.readable(POS);
		assertFalse(tracker.isAwaiting(POS));

		// The next stall starts from zero, so an intermittent chunk never accumulates.
		tracker.await(POS);
		assertFalse(tracker.unreadable(POS));
		assertFalse(tracker.unreadable(POS));
		assertTrue(tracker.unreadable(POS));
	}

	@Test
	void aPositionNobodyAwaitedStillGetsCounted() {
		ValidationTracker tracker = new ValidationTracker(2);
		assertFalse(tracker.unreadable(POS));
		assertEquals(1, tracker.roundsWaited(POS));
		assertTrue(tracker.unreadable(POS));
	}

	@Test
	void positionsAreCountedIndependently() {
		ValidationTracker tracker = new ValidationTracker(2);
		tracker.await(POS);
		tracker.await(OTHER);
		assertFalse(tracker.unreadable(POS));
		assertEquals(1, tracker.roundsWaited(POS));
		assertEquals(0, tracker.roundsWaited(OTHER));
		assertTrue(tracker.unreadable(POS));
		assertTrue(tracker.isAwaiting(OTHER));
		assertEquals(1, tracker.size());
	}

	@Test
	void forgettingDropsTheEntry() {
		ValidationTracker tracker = new ValidationTracker(3);
		tracker.await(POS);
		tracker.unreadable(POS);
		tracker.forget(POS);
		assertFalse(tracker.isAwaiting(POS));
		assertEquals(0, tracker.size());
	}

	@Test
	void oneRoundIsTheSmallestUsefulLimit() {
		ValidationTracker tracker = new ValidationTracker(1);
		tracker.await(POS);
		assertTrue(tracker.unreadable(POS));
		assertThrows(IllegalArgumentException.class, () -> new ValidationTracker(0));
	}
}
