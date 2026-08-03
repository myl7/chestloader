package org.myl7.chestloader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myl7.chestloader.ChestLoaderConfig.KeyConfig;
import org.myl7.chestloader.ChestLoaderConfig.PatternConfig;

class LoaderRulesTest {
	/** The default ring is four wide, so it starts at columns 0 through 5. */
	private static final int FRAME_WIDTH = 4;
	private static final int MAX_COLUMN_OFFSET = LoaderRules.COLUMNS - FRAME_WIDTH;

	private static LoaderRules defaultRules;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		defaultRules = LoaderRules.from(new ChestLoaderConfig());
	}

	/**
	 * A stand-in for a container. Item stacks cannot be built here because their data components are
	 * only bound once a server has loaded its data packs.
	 */
	private static final class Slots implements LoaderRules.SlotView {
		private final @Nullable Item[] items;
		private final int[] counts;

		private Slots(int size) {
			this.items = new Item[size];
			this.counts = new int[size];
		}

		private Slots put(int slot, Item item, int count) {
			items[slot] = item;
			counts[slot] = count;
			return this;
		}

		private Slots clear(int slot) {
			items[slot] = null;
			counts[slot] = 0;
			return this;
		}

		private Slots copy() {
			Slots other = new Slots(items.length);
			System.arraycopy(items, 0, other.items, 0, items.length);
			System.arraycopy(counts, 0, other.counts, 0, counts.length);
			return other;
		}

		@Override
		public int size() {
			return items.length;
		}

		@Override
		public @Nullable Item item(int slot) {
			return items[slot];
		}

		@Override
		public int count(int slot) {
			return counts[slot];
		}
	}

	/** A valid default pattern with the ring starting at the given column. */
	private static Slots pattern(int offset) {
		Slots slots = new Slots(LoaderRules.CONTAINER_SIZE);
		for (int slot = 0; slot < LoaderRules.CONTAINER_SIZE; slot++) {
			if (isFrameSlot(slot, offset)) {
				slots.put(slot, Items.OBSIDIAN, 1);
			}
		}
		slots.put(railSlot(offset), Items.POWERED_RAIL, 4);
		slots.put(railSlot(offset) + 1, Items.MINECART, 1);
		return slots;
	}

	/** The ten obsidian slots of the default ring at the given column offset. */
	private static boolean isFrameSlot(int slot, int offset) {
		int row = slot / LoaderRules.COLUMNS;
		int column = slot % LoaderRules.COLUMNS;
		if (column < offset || column > offset + FRAME_WIDTH - 1) {
			return false;
		}
		return row == 0 || row == LoaderRules.ROWS - 1 || column == offset || column == offset + FRAME_WIDTH - 1;
	}

	/** Slot of the powered rail, the left of the two enclosed slots. */
	private static int railSlot(int offset) {
		return LoaderRules.COLUMNS + offset + 1;
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2, 3, 4, 5})
	void everyHorizontalOffsetIsAccepted(int offset) {
		assertTrue(defaultRules.matches(pattern(offset)));
	}

	@Test
	void everyRingSlotIsRequired() {
		for (int offset = 0; offset <= MAX_COLUMN_OFFSET; offset++) {
			for (int slot = 0; slot < LoaderRules.CONTAINER_SIZE; slot++) {
				if (!isFrameSlot(slot, offset)) {
					continue;
				}
				assertFalse(defaultRules.matches(pattern(offset).clear(slot)),
						"offset " + offset + " should not match with slot " + slot + " emptied");
			}
		}
	}

	@Test
	void cryingObsidianIsNotObsidian() {
		assertFalse(defaultRules.matches(pattern(1).put(1, Items.CRYING_OBSIDIAN, 1)));
	}

	@Test
	void railMustBePoweredAndReachTheMinimumCount() {
		assertFalse(defaultRules.matches(pattern(1).put(railSlot(1), Items.POWERED_RAIL, 3)));
		assertFalse(defaultRules.matches(pattern(1).put(railSlot(1), Items.RAIL, 8)));
		assertFalse(defaultRules.matches(pattern(1).put(railSlot(1), Items.DETECTOR_RAIL, 8)));
		assertFalse(defaultRules.matches(pattern(1).put(railSlot(1), Items.ACTIVATOR_RAIL, 8)));
		assertTrue(defaultRules.matches(pattern(1).put(railSlot(1), Items.POWERED_RAIL, 64)));
	}

	@Test
	void obsidianCountIsNotCappedButTheMinecartCountIs() {
		// The obsidian key has no max, a full stack in a ring slot is fine.
		assertTrue(defaultRules.matches(pattern(1).put(1, Items.OBSIDIAN, 64)));
		// The minecart key is min and max one, a second minecart in the slot breaks it.
		assertFalse(defaultRules.matches(pattern(1).put(railSlot(1) + 1, Items.MINECART, 2)));
	}

	@Test
	void allDefaultMinecartTypesAreAccepted() {
		for (Item minecart : new Item[]{Items.MINECART, Items.CHEST_MINECART, Items.HOPPER_MINECART,
				Items.FURNACE_MINECART}) {
			assertTrue(defaultRules.matches(pattern(2).put(railSlot(2) + 1, minecart, 1)),
					minecart + " should be accepted");
		}
	}

	@Test
	void anExtraMinecartTypeCanBeAddedToItsKey() {
		Slots slots = pattern(2).put(railSlot(2) + 1, Items.TNT_MINECART, 1);
		assertFalse(defaultRules.matches(slots));

		ChestLoaderConfig config = new ChestLoaderConfig();
		config.patterns.get(0).keys.get("M").items.add("minecraft:tnt_minecart");
		assertTrue(LoaderRules.from(config).matches(slots));
	}

	@Test
	void mirrorAcceptsTheSwappedCoreByDefaultAndCanBeTurnedOff() {
		Slots swapped = pattern(1)
				.put(railSlot(1), Items.MINECART, 1)
				.put(railSlot(1) + 1, Items.POWERED_RAIL, 4);
		// The default pattern has mirror on, so the rail and the minecart may trade places.
		assertTrue(defaultRules.matches(swapped));

		ChestLoaderConfig noMirror = new ChestLoaderConfig();
		noMirror.patterns.get(0).mirror = false;
		assertFalse(LoaderRules.from(noMirror).matches(swapped));
	}

	@Test
	void anythingOutsideTheRingBreaksTheMatch() {
		int spare = LoaderRules.CONTAINER_SIZE - 1;
		assertFalse(defaultRules.matches(pattern(0).put(spare, Items.STONE, 1)));
		// Even a pattern item, when it sits in a slot that has to stay empty.
		assertFalse(defaultRules.matches(pattern(0).put(spare, Items.OBSIDIAN, 1)));
		assertFalse(defaultRules.matches(pattern(0).put(spare, Items.MINECART, 1)));
		assertFalse(defaultRules.matches(pattern(0).put(spare, Items.POWERED_RAIL, 4)));
	}

	@Test
	void aSecondRingIsNotAPattern() {
		// Offsets 0 and 5 do not overlap, so both rings fit at once. Neither may be accepted,
		// because each one sees the other's obsidian in slots that must be empty.
		Slots first = pattern(0);
		Slots second = pattern(5);
		Slots both = first.copy();
		for (int slot = 0; slot < LoaderRules.CONTAINER_SIZE; slot++) {
			if (second.item(slot) != null) {
				both.put(slot, second.item(slot), second.count(slot));
			}
		}
		assertFalse(defaultRules.matches(both));
	}

	@Test
	void anEmptyContainerDoesNotMatch() {
		assertFalse(defaultRules.matches(new Slots(LoaderRules.CONTAINER_SIZE)));
	}

	@Test
	void aContainerOfTheWrongSizeDoesNotMatch() {
		assertFalse(defaultRules.matches(new Slots(9)));
	}

	@Test
	void aCustomShapeSlidesInBothAxes() {
		ChestLoaderConfig config = new ChestLoaderConfig();
		config.patterns = new ArrayList<>(List.of(
				stonePair()));
		LoaderRules rules = LoaderRules.from(config);

		// Two adjacent stone at the top-left corner.
		assertTrue(rules.matches(new Slots(LoaderRules.CONTAINER_SIZE)
				.put(0, Items.STONE, 1).put(1, Items.STONE, 1)));
		// The same pair one row down and one column right, reached by sliding vertically too.
		assertTrue(rules.matches(new Slots(LoaderRules.CONTAINER_SIZE)
				.put(10, Items.STONE, 1).put(11, Items.STONE, 1)));
		// A lone stone has no partner, and a third stone is one too many.
		assertFalse(rules.matches(new Slots(LoaderRules.CONTAINER_SIZE).put(0, Items.STONE, 1)));
		assertFalse(rules.matches(new Slots(LoaderRules.CONTAINER_SIZE)
				.put(0, Items.STONE, 1).put(1, Items.STONE, 1).put(2, Items.STONE, 1)));
	}

	private static PatternConfig stonePair() {
		KeyConfig stone = new KeyConfig();
		stone.items = new ArrayList<>(List.of("minecraft:stone"));
		PatternConfig pair = new PatternConfig();
		pair.name = "stone-pair";
		pair.shape = new ArrayList<>(List.of("SS"));
		pair.keys.put("S", stone);
		pair.slide = true;
		pair.mirror = false;
		return pair;
	}

	@Test
	void unknownItemsInAKeyAreDroppedButTheRestStillMatches() {
		ChestLoaderConfig config = new ChestLoaderConfig();
		config.patterns.get(0).keys.get("O").items.add("chestloader:does_not_exist");
		// The unknown id is ignored, obsidian is still there, so the canonical pattern still matches.
		assertTrue(LoaderRules.from(config).matches(pattern(3)));
	}

	@Test
	void aKeyWithNoKnownItemDropsThePatternSoNothingMatches() {
		ChestLoaderConfig config = new ChestLoaderConfig();
		config.patterns.get(0).keys.get("O").items = new ArrayList<>(List.of("chestloader:does_not_exist"));
		assertFalse(LoaderRules.from(config).matches(pattern(3)));
	}

	@Test
	void defaultTicketLevelGivesAPortalSizedArea() {
		assertEquals(30, defaultRules.ticketLevel());
		assertEquals(3, defaultRules.ticketRadius());
	}

	@Test
	void ticketLevelIsClampedToAUsableRange() {
		ChestLoaderConfig tooLow = new ChestLoaderConfig();
		tooLow.ticketLevel = -5;
		assertEquals(25, LoaderRules.from(tooLow).ticketLevel());

		ChestLoaderConfig tooHigh = new ChestLoaderConfig();
		tooHigh.ticketLevel = 99;
		assertEquals(33, LoaderRules.from(tooHigh).ticketLevel());
		assertEquals(0, LoaderRules.from(tooHigh).ticketRadius());
	}
}
