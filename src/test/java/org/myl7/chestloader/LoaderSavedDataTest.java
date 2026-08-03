package org.myl7.chestloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.DataResult;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LoaderSavedDataTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static LoaderSavedData roundTrip(LoaderSavedData input) {
		DataResult<Tag> encoded = LoaderSavedData.CODEC.encodeStart(NbtOps.INSTANCE, input);
		Tag tag = encoded.getOrThrow();
		return LoaderSavedData.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
	}

	@Test
	void positionsSurviveARoundTrip() {
		LoaderSavedData data = new LoaderSavedData();
		data.positions().add(new BlockPos(0, 100, 0).asLong());
		data.positions().add(new BlockPos(-5000, -60, 12345).asLong());
		data.positions().add(new BlockPos(29999999, 319, -29999999).asLong());

		LongSet expected = new LongOpenHashSet(data.positions());
		assertEquals(expected, roundTrip(data).positions());
	}

	@Test
	void anEmptySetSurvivesARoundTrip() {
		assertTrue(roundTrip(new LoaderSavedData()).positions().isEmpty());
	}

	@Test
	void blockPositionsComeBackUnchanged() {
		BlockPos pos = new BlockPos(-1234, 71, 5678);
		LoaderSavedData data = new LoaderSavedData();
		data.positions().add(pos.asLong());

		LongSet restored = roundTrip(data).positions();
		assertEquals(1, restored.size());
		assertEquals(pos, BlockPos.of(restored.iterator().nextLong()));
	}

	@Test
	void theDecodedSetIsMutable() {
		// The optional field falls back to an immutable empty set, so the constructor has to copy it.
		// Handing that default out as the live set would break the very first activation after a load.
		LoaderSavedData decoded = roundTrip(new LoaderSavedData());
		decoded.positions().add(new BlockPos(1, 2, 3).asLong());
		assertEquals(1, decoded.positions().size());
	}

	@Test
	void aFreshInstanceIsNotDirty() {
		LoaderSavedData data = new LoaderSavedData();
		assertTrue(!data.isDirty());
		data.setDirty();
		assertTrue(data.isDirty());
	}
}
