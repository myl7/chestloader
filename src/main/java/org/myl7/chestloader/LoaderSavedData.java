package org.myl7.chestloader;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.stream.LongStream;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The active container positions of one dimension, stored as packed {@code BlockPos} longs.
 *
 * <p>The mod stores positions itself rather than letting the vanilla persist flag save the tickets.
 * Vanilla stores tickets per chunk, which is not enough to get back to a container: the periodic
 * re-check, the loader limits and the per-chunk reference count all need the block position. Keeping
 * the ticket unsaved also means an uninstalled mod leaves nothing behind that vanilla has to parse.
 *
 * <p>{@code SavedDataStorage} resolves the type id through {@code Identifier#resolveAgainst}, which
 * is {@code root.resolve(namespace, path)}, so a namespaced id lands in the mod's own directory:
 * {@code dimensions/<namespace>/<path>/data/chestloader/loaders.dat}.
 */
public final class LoaderSavedData extends SavedData {
	private static final Codec<LongSet> POSITIONS_CODEC = Codec.LONG_STREAM.xmap(
			stream -> (LongSet) new LongOpenHashSet(stream.toArray()),
			set -> LongStream.of(set.toLongArray()));

	public static final Codec<LoaderSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			POSITIONS_CODEC.optionalFieldOf("positions", LongSets.EMPTY_SET).forGetter(LoaderSavedData::positions)
	).apply(instance, LoaderSavedData::new));

	/**
	 * Minecraft has no "no data fixer" option here, {@code SavedDataStorage} calls
	 * {@code DataFixTypes#update} unconditionally. The forced chunk type is the closest match, and
	 * the fixers registered under it look for the forced chunk shape, so they do nothing to a plain
	 * array of packed positions.
	 */
	public static final SavedDataType<LoaderSavedData> TYPE = new SavedDataType<>(
			ChestLoader.id("loaders"),
			LoaderSavedData::new,
			CODEC,
			DataFixTypes.SAVED_DATA_FORCED_CHUNKS);

	private final LongSet positions;

	public LoaderSavedData() {
		this(LongSets.EMPTY_SET);
	}

	private LoaderSavedData(LongSet initial) {
		// Copied, so the immutable default of the optional field is never handed out as the live set.
		this.positions = new LongOpenHashSet(initial);
	}

	public LongSet positions() {
		return positions;
	}
}
