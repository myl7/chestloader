package org.myl7.chestloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * The on-disk shape of {@code config/chestloader.json}. The activation condition is a list of
 * {@link PatternConfig patterns}; a container activates when it matches any one of them. Items are
 * stored as identifier strings and resolved against the item registry in {@link LoaderRules}, where
 * an unknown entry is reported in the log and skipped rather than failing the whole file.
 */
public class ChestLoaderConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "chestloader.json";

	/** One activation layout. A container matches the config when it matches any pattern. */
	public static final class PatternConfig {
		/** Only used in log messages, so a broken pattern can be pointed at. */
		public @Nullable String name;

		/**
		 * The layout drawn row by row over the container's slot grid. A {@code .} or a space is a slot
		 * that must stay empty; any other character is a key that must appear in {@link #keys}. Rows
		 * must be equal length, at most {@link LoaderRules#ROWS} of them, each at most
		 * {@link LoaderRules#COLUMNS} wide.
		 */
		public List<String> shape = new ArrayList<>();

		/** Maps each character used in {@link #shape} to the items that satisfy that slot. */
		public Map<String, KeyConfig> keys = new LinkedHashMap<>();

		/** Whether the shape may sit at any offset it fits at, rather than only the top-left corner. */
		public boolean slide = true;

		/** Whether the left-to-right mirror of the shape is accepted as well. */
		public boolean mirror = true;
	}

	/** What a single slot of a pattern accepts: one of a set of items, in a count range. */
	public static final class KeyConfig {
		/** The items that satisfy the slot; the slot matches when it holds any one of them. */
		public List<String> items = new ArrayList<>();

		/** Smallest stack count the slot may hold. */
		public int min = 1;

		/** Largest stack count the slot may hold. Omitted means a full stack, 64. */
		public @Nullable Integer max;
	}

	public List<PatternConfig> patterns = defaultPatterns();

	/**
	 * Absolute chunk ticket level of the centre chunk. 30 reproduces the nether portal loader: a 3x3
	 * of entity ticking chunks, a ring of block ticking chunks around it and a border ring outside
	 * that, 7x7 loaded in total. Anything above 30 shrinks the range, and 32 or above leaves the
	 * centre chunk without entity ticking.
	 */
	public int ticketLevel = 30;

	public int scanIntervalTicks = 200;
	public int maxLoadersPerDimension = 32;
	public int maxLoadersTotal = 128;
	public boolean notifyOnActivate = true;
	public boolean particleOnActive = true;

	/**
	 * The built-in default: a 4x3 obsidian ring with a powered rail and a minecart in the two
	 * enclosed slots. With {@code slide} the ring may start at any of the six columns, and with
	 * {@code mirror} the rail and the minecart may swap places.
	 */
	private static List<PatternConfig> defaultPatterns() {
		PatternConfig frame = new PatternConfig();
		frame.name = "obsidian-frame";
		frame.shape = new ArrayList<>(List.of(
				"OOOO",
				"ORMO",
				"OOOO"));
		frame.keys = new LinkedHashMap<>();
		frame.keys.put("O", key(List.of("minecraft:obsidian"), 1, null));
		frame.keys.put("R", key(List.of("minecraft:powered_rail"), 4, null));
		frame.keys.put("M", key(List.of(
				"minecraft:minecart",
				"minecraft:chest_minecart",
				"minecraft:hopper_minecart",
				"minecraft:furnace_minecart"), 1, 1));
		frame.slide = true;
		frame.mirror = true;
		return new ArrayList<>(List.of(frame));
	}

	private static KeyConfig key(List<String> items, int min, @Nullable Integer max) {
		KeyConfig config = new KeyConfig();
		config.items = new ArrayList<>(items);
		config.min = min;
		config.max = max;
		return config;
	}

	public static ChestLoaderConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		if (!Files.exists(path)) {
			ChestLoaderConfig config = new ChestLoaderConfig();
			config.write(path);
			return config;
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			ChestLoaderConfig config = GSON.fromJson(reader, ChestLoaderConfig.class);
			if (config == null) {
				ChestLoader.LOGGER.warn("{} is empty, using defaults", path);
				return new ChestLoaderConfig();
			}
			return config;
		} catch (Exception e) {
			ChestLoader.LOGGER.error("Failed to read {}, using defaults", path, e);
			return new ChestLoaderConfig();
		}
	}

	private void write(Path path) {
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			ChestLoader.LOGGER.error("Failed to write default config to {}", path, e);
		}
	}
}
