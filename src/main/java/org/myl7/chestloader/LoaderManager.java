package org.myl7.chestloader;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Tracks which containers are currently holding a chunk ticket, one instance per running server.
 *
 * <p>Two containers can share a chunk, and the vanilla ticket storage collapses tickets of the same
 * type and level within a chunk into one. Removing a ticket for one container would therefore also
 * drop it for the other, so the manager keeps a reference count per chunk and only touches the
 * ticket when that count leaves or reaches zero.
 *
 * <p>The position set of each dimension lives in a {@link LoaderSavedData} so it survives a restart.
 * The reference counts and the pending checks are rebuilt from it on load.
 */
public final class LoaderManager {
	/** How often the particle marker is emitted above an active container. */
	private static final int PARTICLE_INTERVAL_TICKS = 20;

	/** Deactivation messages go to players within this many blocks of the container. */
	private static final double NOTIFY_RADIUS = 32.0;

	/**
	 * How many scan rounds a position may stay unreadable before its ticket is dropped. At the
	 * default scan interval this is a little over a minute and a half, far longer than restoring a
	 * chunk should ever take.
	 */
	private static final int MAX_UNREADABLE_SCANS = 10;

	private final LoaderRules rules;
	private final Map<ResourceKey<Level>, DimensionState> dimensions = new LinkedHashMap<>();

	public LoaderManager(LoaderRules rules) {
		this.rules = rules;
	}

	public LoaderRules rules() {
		return rules;
	}

	/** One active container, and whether it still has to pass its first check after a restore. */
	public record ActiveLoader(BlockPos pos, boolean awaitingCheck) {
	}

	private enum DeactivationReason {
		/** The container no longer holds the pattern. */
		PATTERN(true),
		/** The block entity was broken or replaced. */
		REMOVED(true),
		/** The chunk never came back, so there is nobody around to tell either. */
		UNREADABLE(false);

		private final boolean notifiesPlayers;

		DeactivationReason(boolean notifiesPlayers) {
			this.notifiesPlayers = notifiesPlayers;
		}
	}

	private static final class DimensionState {
		private final ServerLevel level;
		private final LoaderSavedData saved;
		private final Long2IntOpenHashMap chunkRefCounts = new Long2IntOpenHashMap();
		private final ValidationTracker validation = new ValidationTracker(MAX_UNREADABLE_SCANS);
		private int tickCounter;

		private DimensionState(ServerLevel level, LoaderSavedData saved) {
			this.level = level;
			this.saved = saved;
			this.chunkRefCounts.defaultReturnValue(0);
		}

		private LongSet positions() {
			return saved.positions();
		}
	}

	// Level bookkeeping ----------------------------------------------------------------------

	public void onLevelLoaded(ServerLevel level) {
		state(level);
	}

	public void onLevelUnloaded(ServerLevel level) {
		// The level is going away, so its tickets go with it. The saved data is left alone.
		dimensions.remove(level.dimension());
	}

	private DimensionState state(ServerLevel level) {
		DimensionState existing = dimensions.get(level.dimension());
		return existing != null ? existing : load(level);
	}

	private DimensionState load(ServerLevel level) {
		LoaderSavedData saved = level.getDataStorage().computeIfAbsent(LoaderSavedData.TYPE);
		DimensionState state = new DimensionState(level, saved);
		dimensions.put(level.dimension(), state);
		restore(state);
		return state;
	}

	/**
	 * Puts the tickets of a dimension back without looking at the containers first. Reading a
	 * container needs its chunk loaded, and loading the chunk is what the ticket is for, so the check
	 * has to come later. Every restored position is handed to the validation tracker, and the
	 * periodic scan runs the real check as soon as the chunk is readable.
	 */
	private void restore(DimensionState state) {
		long[] stored = state.positions().toLongArray();
		if (stored.length == 0) {
			return;
		}

		ServerLevel level = state.level;
		if (!rules.enabledIn(level.dimension().identifier())) {
			// The config stopped covering this dimension between two starts, e.g. the End was turned
			// back off. No pattern can ever match here, so the ticket must not come back either.
			state.positions().clear();
			state.saved.setDirty();
			ChestLoader.LOGGER.warn("Dropped {} chunk loader(s) in {}, no configured pattern applies in this "
					+ "dimension", stored.length, level.dimension().identifier());
			return;
		}
		// Rebuilt entry by entry so the limit checks below see a growing count.
		state.positions().clear();

		int restored = 0;
		int outOfBounds = 0;
		int overLimit = 0;
		for (long packed : stored) {
			BlockPos pos = BlockPos.of(packed);
			if (!level.isInWorldBounds(pos)) {
				outOfBounds++;
				continue;
			}
			if (countTotal() >= rules.maxLoadersTotal()
					|| state.positions().size() >= rules.maxLoadersPerDimension()) {
				overLimit++;
				continue;
			}
			addActive(state, pos);
			state.validation.await(packed);
			restored++;
		}
		state.saved.setDirty();

		String dimension = level.dimension().identifier().toString();
		if (restored > 0) {
			ChestLoader.LOGGER.info("Restored {} chunk loader(s) in {}, each pending a re-check", restored, dimension);
		}
		if (outOfBounds > 0) {
			ChestLoader.LOGGER.warn("Dropped {} chunk loader(s) in {} whose position is outside the world",
					outOfBounds, dimension);
		}
		if (overLimit > 0) {
			ChestLoader.LOGGER.warn("Dropped {} chunk loader(s) in {}, the configured limits are lower than what "
					+ "the save file holds", overLimit, dimension);
		}
	}

	public void shutdown() {
		for (DimensionState state : dimensions.values()) {
			for (long packedChunk : state.chunkRefCounts.keySet().toLongArray()) {
				state.level.getChunkSource().removeTicketWithRadius(
						ChestLoader.TICKET, ChunkPos.unpack(packedChunk), rules.ticketRadius());
			}
			state.chunkRefCounts.clear();
			// The positions stay put. They belong to the saved data and have to survive the restart.
		}
		dimensions.clear();
	}

	// Checking -------------------------------------------------------------------------------

	/**
	 * Re-checks one container and brings its ticket in line with what the container currently holds.
	 *
	 * @param player the player who triggered the check, to send feedback to; may be null
	 */
	public void evaluate(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player) {
		DimensionState state = state(level);
		boolean loaded = state.positions().contains(pos.asLong());
		boolean shouldLoad = matchesPattern(level, pos);
		if (loaded) {
			// The container was read just now, so whatever it holds, the position is settled.
			state.validation.readable(pos.asLong());
		}
		if (shouldLoad == loaded) {
			return;
		}
		if (shouldLoad) {
			activate(level, pos, player);
		} else {
			deactivate(level, pos, DeactivationReason.PATTERN);
		}
	}

	/** Drops the ticket when the container is broken or replaced. */
	public void onBlockEntityRemoved(ServerLevel level, BlockPos pos) {
		if (isActive(level, pos)) {
			deactivate(level, pos, DeactivationReason.REMOVED);
		}
	}

	public void tickLevel(ServerLevel level) {
		DimensionState state = dimensions.get(level.dimension());
		if (state == null || state.positions().isEmpty()) {
			return;
		}
		state.tickCounter++;
		if (rules.particleOnActive() && state.tickCounter % PARTICLE_INTERVAL_TICKS == 0) {
			emitParticles(state);
		}
		if (state.tickCounter % rules.scanIntervalTicks() == 0) {
			rescan(state);
		}
	}

	/**
	 * A hopper moving items in or out never reaches the open and close hooks, and an active
	 * container keeps its own chunk loaded so it never gets re-checked on chunk load either. This
	 * periodic pass is the only thing that catches those changes, and it is also where restored
	 * positions get their first real check.
	 */
	private void rescan(DimensionState state) {
		ServerLevel level = state.level;
		for (long packed : state.positions().toLongArray()) {
			BlockPos pos = BlockPos.of(packed);
			if (!level.isLoaded(pos)) {
				// Not readable yet. Counting rounds rather than waiting a fixed time means slow chunk
				// loading cannot cause a wrong revocation.
				if (state.validation.unreadable(packed)) {
					ChestLoader.LOGGER.warn("Dropping the chunk loader at {} in {}, its chunk stayed unreadable "
							+ "for {} scans", pos, level.dimension().identifier(), state.validation.maxRounds());
					deactivate(level, pos, DeactivationReason.UNREADABLE);
				}
				continue;
			}
			state.validation.readable(packed);
			if (!matchesPattern(level, pos)) {
				deactivate(level, pos, DeactivationReason.PATTERN);
			}
		}
	}

	private void emitParticles(DimensionState state) {
		for (long packed : state.positions().toLongArray()) {
			if (state.validation.isAwaiting(packed)) {
				continue;
			}
			BlockPos pos = BlockPos.of(packed);
			state.level.sendParticles(ParticleTypes.PORTAL,
					pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
					3, 0.2, 0.1, 0.2, 0.0);
		}
	}

	// Queries --------------------------------------------------------------------------------

	public boolean isActive(ServerLevel level, BlockPos pos) {
		DimensionState state = dimensions.get(level.dimension());
		return state != null && state.positions().contains(pos.asLong());
	}

	public int countIn(ServerLevel level) {
		DimensionState state = dimensions.get(level.dimension());
		return state == null ? 0 : state.positions().size();
	}

	public int countTotal() {
		int total = 0;
		for (DimensionState state : dimensions.values()) {
			total += state.positions().size();
		}
		return total;
	}

	/** Every active container, grouped by dimension, for {@code /chestloader list}. */
	public Map<ResourceKey<Level>, List<ActiveLoader>> listActive() {
		Map<ResourceKey<Level>, List<ActiveLoader>> result = new LinkedHashMap<>();
		for (Map.Entry<ResourceKey<Level>, DimensionState> entry : dimensions.entrySet()) {
			DimensionState state = entry.getValue();
			List<ActiveLoader> loaders = new ArrayList<>();
			for (long packed : state.positions().toLongArray()) {
				loaders.add(new ActiveLoader(BlockPos.of(packed), state.validation.isAwaiting(packed)));
			}
			loaders.sort(Comparator.comparingInt((ActiveLoader l) -> l.pos().getX())
					.thenComparingInt(l -> l.pos().getZ())
					.thenComparingInt(l -> l.pos().getY()));
			if (!loaders.isEmpty()) {
				result.put(entry.getKey(), loaders);
			}
		}
		return result;
	}

	// Mutation -------------------------------------------------------------------------------

	private void activate(ServerLevel level, BlockPos pos, @Nullable ServerPlayer player) {
		if (countTotal() >= rules.maxLoadersTotal()) {
			notifyLimit(player, "the server", rules.maxLoadersTotal());
			return;
		}
		if (countIn(level) >= rules.maxLoadersPerDimension()) {
			notifyLimit(player, level.dimension().identifier().toString(), rules.maxLoadersPerDimension());
			return;
		}
		if (!addActive(state(level), pos)) {
			return;
		}
		ChestLoader.LOGGER.debug("Activated chunk loader at {} in {}", pos, level.dimension().identifier());

		if (rules.notifyOnActivate() && player != null) {
			player.sendSystemMessage(prefixed(Component.literal(
					"This chunk is now force-loaded. Keep this container as it is: it can no longer be used for "
							+ "storage, and a hopper feeding into it will break the pattern and stop the loading.")
					.withStyle(ChatFormatting.GREEN)));
		}
	}

	private void deactivate(ServerLevel level, BlockPos pos, DeactivationReason reason) {
		DimensionState state = dimensions.get(level.dimension());
		if (state == null || !removeActive(state, pos)) {
			return;
		}
		ChestLoader.LOGGER.debug("Deactivated chunk loader at {} in {} ({})",
				pos, level.dimension().identifier(), reason);

		if (reason.notifiesPlayers && rules.notifyOnActivate()) {
			notifyNearby(level, pos, prefixed(Component.literal(
					"The pattern is broken, this chunk is no longer force-loaded.")
					.withStyle(ChatFormatting.YELLOW)));
		}
	}

	private boolean addActive(DimensionState state, BlockPos pos) {
		if (!state.positions().add(pos.asLong())) {
			return false;
		}
		state.saved.setDirty();
		ChunkPos chunkPos = ChunkPos.containing(pos);
		if (state.chunkRefCounts.addTo(chunkPos.pack(), 1) == 0) {
			state.level.getChunkSource().addTicketWithRadius(ChestLoader.TICKET, chunkPos, rules.ticketRadius());
		}
		return true;
	}

	private boolean removeActive(DimensionState state, BlockPos pos) {
		if (!state.positions().remove(pos.asLong())) {
			return false;
		}
		state.saved.setDirty();
		state.validation.forget(pos.asLong());
		ChunkPos chunkPos = ChunkPos.containing(pos);
		long packedChunk = chunkPos.pack();
		if (state.chunkRefCounts.addTo(packedChunk, -1) == 1) {
			state.chunkRefCounts.remove(packedChunk);
			state.level.getChunkSource().removeTicketWithRadius(ChestLoader.TICKET, chunkPos, rules.ticketRadius());
		}
		return true;
	}

	// Feedback -------------------------------------------------------------------------------

	private void notifyLimit(@Nullable ServerPlayer player, String scope, int limit) {
		if (player == null || !rules.notifyOnActivate()) {
			return;
		}
		player.sendSystemMessage(prefixed(Component.literal(
				"Chunk loader limit reached for " + scope + " (" + limit + "), this container was not activated.")
				.withStyle(ChatFormatting.RED)));
	}

	private static void notifyNearby(ServerLevel level, BlockPos pos, Component message) {
		double x = pos.getX() + 0.5;
		double y = pos.getY() + 0.5;
		double z = pos.getZ() + 0.5;
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(x, y, z) <= NOTIFY_RADIUS * NOTIFY_RADIUS) {
				player.sendSystemMessage(message);
			}
		}
	}

	private static Component prefixed(Component body) {
		return Component.literal("[Chest Loader] ").withStyle(ChatFormatting.GRAY).append(body);
	}

	// Container access -----------------------------------------------------------------------

	private boolean matchesPattern(ServerLevel level, BlockPos pos) {
		Container container = containerAt(level, pos);
		return container != null && rules.matches(level.dimension().identifier(), container);
	}

	/**
	 * Only a chest, a trapped chest or a barrel counts, and only the 27 slots of that one block
	 * entity. Each half of a double chest is judged on its own, which keeps the question of which
	 * chunk a double chest belongs to from ever coming up.
	 *
	 * <p>{@code Level#isLoaded} checks the world bounds and then asks the chunk source whether the
	 * chunk is already visible, so it never pulls a chunk in by itself.
	 */
	private static @Nullable Container containerAt(ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			return null;
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		// TrappedChestBlockEntity extends ChestBlockEntity, so trapped chests are covered too.
		if (blockEntity instanceof ChestBlockEntity || blockEntity instanceof BarrelBlockEntity) {
			return (Container) blockEntity;
		}
		return null;
	}
}
