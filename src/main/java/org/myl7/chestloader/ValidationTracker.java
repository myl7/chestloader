package org.myl7.chestloader;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Counts how many scan rounds in a row a position has been unreadable.
 *
 * <p>A restored position gets its ticket back before anything has looked at the container, because
 * reading the container needs the chunk loaded and loading the chunk is what the ticket is for. The
 * check therefore has to wait for the chunk, and waiting a fixed number of ticks would revoke the
 * ticket by mistake whenever chunk loading runs slow. Instead the periodic scan asks whether the
 * chunk is loaded yet, and only counts a round against the position when it is not.
 *
 * <p>The same counter covers a position that stops being readable during normal play, so a stale
 * entry cannot hold a ticket forever.
 */
public final class ValidationTracker {
	private final int maxRounds;
	private final Long2IntOpenHashMap rounds = new Long2IntOpenHashMap();

	public ValidationTracker(int maxRounds) {
		if (maxRounds < 1) {
			throw new IllegalArgumentException("maxRounds must be at least 1, got " + maxRounds);
		}
		this.maxRounds = maxRounds;
		this.rounds.defaultReturnValue(0);
	}

	/** Marks a position as not yet checked, without counting a round against it. */
	public void await(long pos) {
		if (!rounds.containsKey(pos)) {
			rounds.put(pos, 0);
		}
	}

	/** Whether the position still has to pass a check before it can be trusted. */
	public boolean isAwaiting(long pos) {
		return rounds.containsKey(pos);
	}

	public int roundsWaited(long pos) {
		return rounds.get(pos);
	}

	/** The position could be read, so it is settled. */
	public void readable(long pos) {
		rounds.remove(pos);
	}

	/**
	 * The position could not be read this round.
	 *
	 * @return true when it has run out of rounds and its ticket should be dropped
	 */
	public boolean unreadable(long pos) {
		int waited = rounds.addTo(pos, 1) + 1;
		if (waited >= maxRounds) {
			rounds.remove(pos);
			return true;
		}
		return false;
	}

	/** Drops the position from tracking, for when it is deactivated for an unrelated reason. */
	public void forget(long pos) {
		rounds.remove(pos);
	}

	public int size() {
		return rounds.size();
	}

	public int maxRounds() {
		return maxRounds;
	}
}
