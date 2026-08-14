package org.myl7.chestloader;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.myl7.chestloader.LoaderManager.LoaderEntry;
import org.myl7.chestloader.LoaderManager.LoaderStatus;
import org.myl7.chestloader.LoaderManager.ToggleResult;

public final class ChestLoaderCommand {
	private ChestLoaderCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		// list, enable and disable only ask for the moderator level, so trusted non-admin players
		// can manage the loading, and list is where the clickable enable and disable buttons live.
		// check drives the whole activation path, that one stays at the gamemaster level.
		dispatcher.register(Commands.literal("chestloader")
				.requires(Commands.hasPermission(Commands.LEVEL_MODERATORS))
				.then(Commands.literal("list")
						.executes(ChestLoaderCommand::list))
				.then(Commands.literal("check")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.executes(ChestLoaderCommand::check)))
				.then(toggleBranch("disable", false))
				.then(toggleBranch("enable", true)));
	}

	/**
	 * {@code /chestloader <disable|enable> <pos> [dimension]}. The dimension argument is there for
	 * the buttons in the list output: a click event runs its command as the clicking player, in that
	 * player's own dimension, and the moderator level has no {@code /execute in} to reach another
	 * one. The buttons therefore always spell the dimension out. Typed by hand without a dimension,
	 * the command acts in the sender's dimension.
	 *
	 * <p>The position is taken as it is, with no loaded-chunk requirement: the whole point of enable
	 * is that the target chunk is usually not loaded. Whether anything is tracked there is for the
	 * manager to answer.
	 */
	private static LiteralArgumentBuilder<CommandSourceStack> toggleBranch(String name, boolean enable) {
		return Commands.literal(name)
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
						.executes(context -> toggle(context, enable, context.getSource().getLevel()))
						.then(Commands.argument("dimension", DimensionArgument.dimension())
								.executes(context -> toggle(context, enable,
										DimensionArgument.getDimension(context, "dimension")))));
	}

	private static int toggle(CommandContext<CommandSourceStack> context, boolean enable, ServerLevel level)
			throws CommandSyntaxException {
		LoaderManager manager = ChestLoader.manager();
		if (manager == null) {
			context.getSource().sendFailure(Component.literal("Chest Loader is not running"));
			return 0;
		}

		BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
		ToggleResult result = enable ? manager.enable(level, pos) : manager.disable(level, pos);
		String where = pos.getX() + " " + pos.getY() + " " + pos.getZ() + " in " + level.dimension().identifier();

		String success = switch (result) {
			case DISABLED -> "Disabled the chunk loader at " + where + ", its chunk is no longer force-loaded";
			case DISABLED_CHUNK_STILL_HELD -> "Disabled the chunk loader at " + where
					+ ", but another loader still keeps that chunk loaded";
			case ENABLED -> "Enabled the chunk loader at " + where + ", its chunk is force-loaded again";
			case ENABLED_AWAITING_CHECK -> "Enabled the chunk loader at " + where
					+ ", it will be re-checked once its chunk is readable";
			default -> null;
		};
		if (success != null) {
			context.getSource().sendSuccess(() -> Component.literal(success), true);
			return 1;
		}

		String failure = switch (result) {
			case ALREADY_DISABLED -> "The chunk loader at " + where + " is already disabled";
			case ALREADY_ENABLED -> "The chunk loader at " + where + " is already enabled";
			case NOT_A_LOADER -> "No chunk loader at " + where;
			case OUT_OF_BOUNDS -> "The position " + where + " is outside the world";
			case DISMANTLED -> "The container at " + where
					+ " no longer holds the pattern, its disabled record is removed";
			case LIMIT_TOTAL -> "Chunk loader limit reached for the server ("
					+ manager.rules().maxLoadersTotal() + "), not enabled";
			case LIMIT_DIMENSION -> "Chunk loader limit reached for " + level.dimension().identifier()
					+ " (" + manager.rules().maxLoadersPerDimension() + "), not enabled";
			default -> throw new IllegalStateException("Unhandled toggle result " + result);
		};
		context.getSource().sendFailure(Component.literal(failure));
		return 0;
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		LoaderManager manager = ChestLoader.manager();
		if (manager == null) {
			context.getSource().sendFailure(Component.literal("Chest Loader is not running"));
			return 0;
		}

		Map<ResourceKey<Level>, List<LoaderEntry>> loaders = manager.listLoaders();
		if (loaders.isEmpty()) {
			context.getSource().sendSuccess(() -> Component.literal("No chunk loaders"), false);
			return 0;
		}

		int enabled = 0;
		int disabled = 0;
		for (List<LoaderEntry> entries : loaders.values()) {
			for (LoaderEntry entry : entries) {
				if (entry.status() == LoaderStatus.DISABLED) {
					disabled++;
				} else {
					enabled++;
				}
			}
		}

		LoaderRules rules = manager.rules();
		int radius = rules.ticketRadius();
		int side = radius * 2 + 1;
		MutableComponent message = Component.literal("Chunk loaders (ticket level " + rules.ticketLevel()
				+ ", radius " + radius + ", " + side + "x" + side + " chunks loaded when enabled), "
				+ enabled + " enabled, " + disabled + " disabled:");

		for (Map.Entry<ResourceKey<Level>, List<LoaderEntry>> entry : loaders.entrySet()) {
			String dimension = entry.getKey().identifier().toString();
			message.append(Component.literal("\n" + dimension + ":").withStyle(ChatFormatting.AQUA));
			for (LoaderEntry loader : entry.getValue()) {
				BlockPos pos = loader.pos();
				ChunkPos chunkPos = ChunkPos.containing(pos);
				message.append(Component.literal("\n  " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
						+ "  (chunk " + chunkPos.x() + " " + chunkPos.z() + ")").withStyle(ChatFormatting.GRAY));
				switch (loader.status()) {
					case AWAITING_CHECK -> message.append(Component.literal("  restored, not re-checked yet")
							.withStyle(ChatFormatting.YELLOW));
					case DISABLED -> message.append(Component.literal("  disabled")
							.withStyle(ChatFormatting.RED));
					case ENABLED -> {
					}
				}
				boolean isDisabled = loader.status() == LoaderStatus.DISABLED;
				message.append(button(
						isDisabled ? "enable" : "disable",
						(isDisabled ? "chestloader enable " : "chestloader disable ")
								+ pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + dimension,
						isDisabled ? ChatFormatting.GREEN : ChatFormatting.RED,
						isDisabled ? "Give this loader its ticket back" : "Stop the loading, keep the loader"));
			}
		}

		context.getSource().sendSuccess(() -> message, false);
		return enabled + disabled;
	}

	/** A clickable suffix like {@code [disable]}. The console shows it as plain text. */
	private static Component button(String label, String command, ChatFormatting color, String hover) {
		return Component.literal(" [" + label + "]")
				.withStyle(style -> style.withColor(color)
						.withClickEvent(new ClickEvent.RunCommand(command))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/**
	 * Runs the same check the open and close hooks run. Useful for looking at one container without
	 * touching it, and it is the only way to drive the whole activation path from the console.
	 */
	private static int check(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		LoaderManager manager = ChestLoader.manager();
		if (manager == null) {
			context.getSource().sendFailure(Component.literal("Chest Loader is not running"));
			return 0;
		}

		ServerLevel level = context.getSource().getLevel();
		// Said outright here, unlike the player-facing path, which stays silent on purpose. An admin
		// asking about a position deserves the real reason over a misleading "does not match".
		if (!manager.rules().enabledIn(level.dimension().identifier())) {
			context.getSource().sendSuccess(() -> Component.literal("No configured pattern applies in "
					+ level.dimension().identifier() + ", chunk loaders are disabled in this dimension"), true);
			return 0;
		}
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
		boolean before = manager.isActive(level, pos);
		boolean disabledBefore = manager.isDisabled(level, pos);
		manager.evaluate(level, pos, context.getSource().getPlayer());
		boolean after = manager.isActive(level, pos);

		String where = pos.getX() + " " + pos.getY() + " " + pos.getZ();
		String verdict;
		if (manager.isDisabled(level, pos)) {
			verdict = "The chunk loader at " + where + " is disabled, enable it with /chestloader enable";
		} else if (disabledBefore) {
			verdict = "The disabled chunk loader at " + where + " was dismantled, its record is removed";
		} else if (after && !before) {
			verdict = "Activated the chunk loader at " + where;
		} else if (before && !after) {
			verdict = "The pattern no longer matches, revoked the chunk loader at " + where;
		} else if (after) {
			verdict = "Still a valid chunk loader at " + where;
		} else {
			verdict = "No chunk loader at " + where + ", the container does not match the pattern";
		}
		context.getSource().sendSuccess(() -> Component.literal(verdict), true);
		return after ? 1 : 0;
	}
}
