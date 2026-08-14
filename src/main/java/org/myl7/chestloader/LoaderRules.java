package org.myl7.chestloader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.myl7.chestloader.ChestLoaderConfig.KeyConfig;
import org.myl7.chestloader.ChestLoaderConfig.PatternConfig;

/**
 * The activation condition with every pattern compiled against the item registry, plus the derived
 * numbers the rest of the mod needs. Immutable, rebuilt once per server start.
 *
 * <p>Each pattern is a small grid of item predicates laid over the 9x3 slot grid of a chest or a
 * barrel. A slot the grid does not cover, and a cell the pattern leaves blank, must be empty. A
 * pattern with {@code slide} on is tried at every offset it fits at; with {@code mirror} on its
 * left-to-right reflection is tried too. A container matches when any pattern, at any of its
 * placements, matches. The built-in default is a 4x3 obsidian ring with a powered rail and a
 * minecart inside it:
 *
 * <pre>
 *   O O O O
 *   O R M O
 *   O O O O
 * </pre>
 *
 * <p>Each pattern also lists the dimensions it applies in, and matching is asked per dimension. A
 * dimension no pattern lists is disabled outright: nothing activates there, and positions restored
 * from an older save are dropped. The default pattern lists the Overworld and the Nether, so the
 * End stays off until the config adds it.
 */
public final class LoaderRules {
	public static final int CONTAINER_SIZE = 27;
	public static final int COLUMNS = 9;
	public static final int ROWS = 3;

	/** Absolute level of a chunk that is loaded but runs nothing. Ticket levels count down from it. */
	private static final int FULL_CHUNK_LEVEL = ChunkLevel.byStatus(FullChunkStatus.FULL);

	private static final int MAX_TICKET_RADIUS = 8;
	private static final int MAX_SLOT_COUNT = 64;

	/** The patterns and derived item union of one dimension, keyed by the dimension identifier. */
	private final Map<Identifier, DimensionGroup> dimensions;
	private final int ticketRadius;
	private final int scanIntervalTicks;
	private final int maxLoadersPerDimension;
	private final int maxLoadersTotal;
	private final boolean notifyOnActivate;
	private final boolean particleOnActive;

	private LoaderRules(Map<Identifier, DimensionGroup> dimensions, int ticketRadius,
			int scanIntervalTicks, int maxLoadersPerDimension, int maxLoadersTotal,
			boolean notifyOnActivate, boolean particleOnActive) {
		this.dimensions = dimensions;
		this.ticketRadius = ticketRadius;
		this.scanIntervalTicks = scanIntervalTicks;
		this.maxLoadersPerDimension = maxLoadersPerDimension;
		this.maxLoadersTotal = maxLoadersTotal;
		this.notifyOnActivate = notifyOnActivate;
		this.particleOnActive = particleOnActive;
	}

	public static LoaderRules from(ChestLoaderConfig config) {
		ChestLoaderConfig fallback = new ChestLoaderConfig();
		List<PatternConfig> configured = config.patterns != null ? config.patterns : fallback.patterns;

		Map<Identifier, DimensionGroup> dimensions = new LinkedHashMap<>();
		int index = 0;
		for (PatternConfig patternConfig : configured) {
			Pattern compiled = compile(patternConfig, index++);
			if (compiled == null) {
				continue;
			}
			for (Identifier dimension : compileDimensions(patternConfig, compiled.name)) {
				DimensionGroup group = dimensions.computeIfAbsent(dimension, key -> new DimensionGroup());
				group.patterns.add(compiled);
				compiled.collectItems(group.allowedItems);
			}
		}
		if (dimensions.isEmpty()) {
			ChestLoader.LOGGER.warn("No usable pattern applies in any dimension, no container will ever activate");
		}

		int ticketLevel = clamp(config.ticketLevel, FULL_CHUNK_LEVEL - MAX_TICKET_RADIUS, FULL_CHUNK_LEVEL, "ticketLevel");
		if (ticketLevel > ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)) {
			ChestLoader.LOGGER.warn("ticketLevel {} leaves the centre chunk without entity ticking", ticketLevel);
		}

		return new LoaderRules(
				dimensions,
				FULL_CHUNK_LEVEL - ticketLevel,
				clamp(config.scanIntervalTicks, 1, 72000, "scanIntervalTicks"),
				clamp(config.maxLoadersPerDimension, 0, Integer.MAX_VALUE, "maxLoadersPerDimension"),
				clamp(config.maxLoadersTotal, 0, Integer.MAX_VALUE, "maxLoadersTotal"),
				config.notifyOnActivate,
				config.particleOnActive);
	}

	// Compilation ----------------------------------------------------------------------------

	private static Set<Identifier> compileDimensions(PatternConfig config, String name) {
		List<String> ids = config.dimensions != null ? config.dimensions : PatternConfig.defaultDimensions();
		Set<Identifier> result = new LinkedHashSet<>();
		for (String id : ids) {
			Identifier parsed = id != null ? Identifier.tryParse(id) : null;
			if (parsed == null) {
				ChestLoader.LOGGER.warn("Pattern '{}' lists an invalid dimension id '{}', ignoring it", name, id);
			} else {
				result.add(parsed);
			}
		}
		if (result.isEmpty()) {
			ChestLoader.LOGGER.warn("Pattern '{}' lists no valid dimension, it will never apply", name);
		}
		return result;
	}

	private static @Nullable Pattern compile(PatternConfig config, int index) {
		String name = config.name != null && !config.name.isBlank() ? config.name : "pattern-" + index;

		List<String> shape = config.shape;
		if (shape == null || shape.isEmpty()) {
			ChestLoader.LOGGER.warn("Pattern '{}' has no shape, skipping it", name);
			return null;
		}
		int height = shape.size();
		int width = shape.get(0).length();
		if (height > ROWS || width < 1 || width > COLUMNS) {
			ChestLoader.LOGGER.warn("Pattern '{}' is {}x{}, outside the {}x{} a container holds, skipping it",
					name, width, height, COLUMNS, ROWS);
			return null;
		}

		Map<String, KeyConfig> keys = config.keys != null ? config.keys : Map.of();
		Cell[][] grid = new Cell[height][width];
		for (int row = 0; row < height; row++) {
			String line = shape.get(row);
			if (line.length() != width) {
				ChestLoader.LOGGER.warn("Pattern '{}' row {} is {} wide, the first row is {}, skipping it",
						name, row, line.length(), width);
				return null;
			}
			for (int column = 0; column < width; column++) {
				char symbol = line.charAt(column);
				if (symbol == '.' || symbol == ' ') {
					continue;
				}
				KeyConfig keyConfig = keys.get(String.valueOf(symbol));
				if (keyConfig == null) {
					ChestLoader.LOGGER.warn("Pattern '{}' uses '{}' with no matching key, skipping it", name, symbol);
					return null;
				}
				Cell cell = compileCell(keyConfig, name, symbol);
				if (cell == null) {
					return null;
				}
				grid[row][column] = cell;
			}
		}
		return new Pattern(name, width, height, grid, config.slide, config.mirror);
	}

	private static @Nullable Cell compileCell(KeyConfig config, String patternName, char symbol) {
		Set<Item> items = new LinkedHashSet<>();
		List<String> ids = config.items != null ? config.items : List.of();
		for (String id : ids) {
			Item item = lookupItem(id);
			if (item == null) {
				ChestLoader.LOGGER.warn("Pattern '{}' key '{}' lists unknown item '{}', ignoring it",
						patternName, symbol, id);
			} else {
				items.add(item);
			}
		}
		if (items.isEmpty()) {
			ChestLoader.LOGGER.warn("Pattern '{}' key '{}' resolved to no known item, skipping the pattern",
					patternName, symbol);
			return null;
		}

		int min = clamp(config.min, 1, MAX_SLOT_COUNT, "pattern '" + patternName + "' key '" + symbol + "' min");
		int max = config.max != null ? config.max : MAX_SLOT_COUNT;
		max = clamp(max, min, MAX_SLOT_COUNT, "pattern '" + patternName + "' key '" + symbol + "' max");
		return new Cell(items, min, max);
	}

	private static @Nullable Item lookupItem(@Nullable String id) {
		if (id == null) {
			return null;
		}
		Identifier key = Identifier.tryParse(id);
		// getValue would hand back air for an unknown key, so ask the registry first.
		if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
			return null;
		}
		return BuiltInRegistries.ITEM.getValue(key);
	}

	private static int clamp(int value, int min, int max, String field) {
		if (value < min || value > max) {
			int clamped = Math.min(max, Math.max(min, value));
			ChestLoader.LOGGER.warn("{} is {}, clamped to {}", field, value, clamped);
			return clamped;
		}
		return value;
	}

	// Matching -------------------------------------------------------------------------------

	/**
	 * The patterns that apply in one dimension, plus the union of every item they accept, which
	 * backs the cheap reject before any placement is tried.
	 */
	private static final class DimensionGroup {
		private final List<Pattern> patterns = new ArrayList<>();
		private final Set<Item> allowedItems = new LinkedHashSet<>();
	}

	/** One cell of a compiled pattern: the items it accepts and the count range it allows. */
	private record Cell(Set<Item> items, int min, int max) {
		boolean accepts(@Nullable Item item, int count) {
			return item != null && items.contains(item) && count >= min && count <= max;
		}
	}

	/**
	 * A compiled pattern. {@code grid[row][column]} is the cell at that spot within the sub-grid, or
	 * null for a blank cell that must map to an empty slot.
	 */
	private static final class Pattern {
		private final String name;
		private final int width;
		private final int height;
		private final @Nullable Cell[][] grid;
		private final boolean slide;
		private final boolean mirror;

		private Pattern(String name, int width, int height, @Nullable Cell[][] grid, boolean slide,
				boolean mirror) {
			this.name = name;
			this.width = width;
			this.height = height;
			this.grid = grid;
			this.slide = slide;
			this.mirror = mirror;
		}

		private void collectItems(Set<Item> into) {
			for (@Nullable Cell[] row : grid) {
				for (Cell cell : row) {
					if (cell != null) {
						into.addAll(cell.items());
					}
				}
			}
		}

		private boolean matches(SlotView slots) {
			return matchesOriented(slots, false) || (mirror && matchesOriented(slots, true));
		}

		private boolean matchesOriented(SlotView slots, boolean mirrored) {
			if (!slide) {
				return matchesAt(slots, 0, 0, mirrored);
			}
			for (int rowOffset = 0; rowOffset <= ROWS - height; rowOffset++) {
				for (int columnOffset = 0; columnOffset <= COLUMNS - width; columnOffset++) {
					if (matchesAt(slots, rowOffset, columnOffset, mirrored)) {
						return true;
					}
				}
			}
			return false;
		}

		private boolean matchesAt(SlotView slots, int rowOffset, int columnOffset, boolean mirrored) {
			for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
				Cell cell = cellAt(slot / COLUMNS - rowOffset, slot % COLUMNS - columnOffset, mirrored);
				Item item = slots.item(slot);
				if (cell == null) {
					if (item != null) {
						return false;
					}
				} else if (!cell.accepts(item, slots.count(slot))) {
					return false;
				}
			}
			return true;
		}

		/** The cell covering a sub-grid coordinate, or null when the coordinate falls outside it. */
		private @Nullable Cell cellAt(int row, int column, boolean mirrored) {
			if (row < 0 || row >= height || column < 0 || column >= width) {
				return null;
			}
			return grid[row][mirrored ? width - 1 - column : column];
		}
	}

	/** Whether any pattern applies in the dimension at all. A dimension no pattern lists is off. */
	public boolean enabledIn(Identifier dimension) {
		return dimensions.containsKey(dimension);
	}

	public boolean matches(Identifier dimension, Container container) {
		return matches(dimension, new ContainerSlotView(container));
	}

	public boolean matches(Identifier dimension, SlotView slots) {
		DimensionGroup group = dimensions.get(dimension);
		if (group == null || slots.size() != CONTAINER_SIZE) {
			return false;
		}
		// Cheap reject first: an ordinary storage chest almost always holds something no pattern lists,
		// and that costs one pass instead of walking every placement of every pattern.
		if (!onlyHoldsAllowedItems(group, slots)) {
			return false;
		}
		for (Pattern pattern : group.patterns) {
			if (pattern.matches(slots)) {
				return true;
			}
		}
		return false;
	}

	private static boolean onlyHoldsAllowedItems(DimensionGroup group, SlotView slots) {
		for (int slot = 0; slot < CONTAINER_SIZE; slot++) {
			Item item = slots.item(slot);
			if (item != null && !group.allowedItems.contains(item)) {
				return false;
			}
		}
		return true;
	}

	// Container access -----------------------------------------------------------------------

	/**
	 * The part of a container the check reads. Going through this instead of {@link ItemStack}
	 * directly keeps the check usable without a bound data component map, which only exists once a
	 * server has loaded its data packs.
	 */
	public interface SlotView {
		int size();

		/** The item in the slot, or null when the slot is empty. */
		@Nullable Item item(int slot);

		int count(int slot);
	}

	private record ContainerSlotView(Container container) implements SlotView {
		@Override
		public int size() {
			return container.getContainerSize();
		}

		@Override
		public @Nullable Item item(int slot) {
			ItemStack stack = container.getItem(slot);
			return stack.isEmpty() ? null : stack.getItem();
		}

		@Override
		public int count(int slot) {
			return container.getItem(slot).getCount();
		}
	}

	// Derived numbers ------------------------------------------------------------------------

	public int ticketRadius() {
		return ticketRadius;
	}

	public int ticketLevel() {
		return FULL_CHUNK_LEVEL - ticketRadius;
	}

	public int scanIntervalTicks() {
		return scanIntervalTicks;
	}

	public int maxLoadersPerDimension() {
		return maxLoadersPerDimension;
	}

	public int maxLoadersTotal() {
		return maxLoadersTotal;
	}

	public boolean notifyOnActivate() {
		return notifyOnActivate;
	}

	public boolean particleOnActive() {
		return particleOnActive;
	}
}
