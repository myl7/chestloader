package org.myl7.chestloader;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChestLoader implements ModInitializer {
	public static final String MOD_ID = "chestloader";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * Loads and simulates the chunk, and keeps the dimension active so the machines inside keep
	 * ticking once every player has left the dimension. Without the keep-dimension-active flag the
	 * dimension idles out after 300 ticks and stops entity and block entity ticking, which would
	 * leave the chunk loaded but dead.
	 *
	 * <p>No timeout: the mod adds and removes the ticket explicitly. The vanilla persist flag stays
	 * off on purpose. Vanilla saves tickets per chunk, which cannot be traced back to a container,
	 * so the mod stores the container positions itself in {@link LoaderSavedData} and puts the
	 * tickets back from there. See the persistence section of the README.
	 */
	public static final TicketType TICKET = new TicketType(
			TicketType.NO_TIMEOUT,
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);

	// Written on the server thread, also read from the client thread in single player, where the
	// level check below always rejects the call.
	private static volatile @Nullable LoaderManager manager;

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.TICKET_TYPE, id("chunk_loader"), TICKET);

		ChestLoaderConfig config = ChestLoaderConfig.load();

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			// Resolved here rather than in onInitialize because the item registry is only guaranteed
			// to be populated once the server is coming up.
			manager = new LoaderManager(LoaderRules.from(config));
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (manager != null) {
				manager.shutdown();
				manager = null;
			}
		});

		// Fired from createLevels, which runs after SERVER_STARTING, so the manager already exists.
		// Restoring per level rather than walking every level once covers dimensions that appear
		// later, and a dimension that a data pack removed simply never fires.
		ServerLevelEvents.LOAD.register((server, level) -> {
			if (manager != null) {
				manager.onLevelLoaded(level);
			}
		});

		ServerLevelEvents.UNLOAD.register((server, level) -> {
			if (manager != null) {
				manager.onLevelUnloaded(level);
			}
		});

		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			if (manager != null) {
				manager.tickLevel(level);
			}
		});

		ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
			if (manager != null) {
				manager.onBlockEntityRemoved(level, blockEntity.getBlockPos());
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				ChestLoaderCommand.register(dispatcher));

		LOGGER.info("Chest Loader ready");
	}

	/**
	 * Called from the {@code ContainerOpenersCounter} mixin whenever a container is opened or
	 * closed. Closing matters as much as opening because the player may have just taken a block out
	 * of the pattern.
	 */
	public static void onContainerToggled(Level level, BlockPos pos, @Nullable LivingEntity entity) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		LoaderManager current = manager;
		if (current == null) {
			return;
		}
		ServerPlayer player = entity instanceof ServerPlayer serverPlayer ? serverPlayer : null;
		current.evaluate(serverLevel, pos, player);
	}

	public static @Nullable LoaderManager manager() {
		return manager;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
