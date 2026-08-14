package org.myl7.chestloader;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.myl7.chestloader.LoaderManager.ActiveLoader;

public final class ChestLoaderCommand {
	private ChestLoaderCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("chestloader")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("list")
						.executes(ChestLoaderCommand::list))
				.then(Commands.literal("check")
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.executes(ChestLoaderCommand::check))));
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		LoaderManager manager = ChestLoader.manager();
		if (manager == null) {
			context.getSource().sendFailure(Component.literal("Chest Loader is not running"));
			return 0;
		}

		Map<ResourceKey<Level>, List<ActiveLoader>> active = manager.listActive();
		if (active.isEmpty()) {
			context.getSource().sendSuccess(() -> Component.literal("No active chunk loaders"), false);
			return 0;
		}

		LoaderRules rules = manager.rules();
		int radius = rules.ticketRadius();
		int side = radius * 2 + 1;
		MutableComponent message = Component.literal("Active chunk loaders (ticket level "
				+ rules.ticketLevel() + ", radius " + radius + ", " + side + "x" + side + " chunks loaded):");

		int total = 0;
		for (Map.Entry<ResourceKey<Level>, List<ActiveLoader>> entry : active.entrySet()) {
			message.append(Component.literal("\n" + entry.getKey().identifier() + ":").withStyle(ChatFormatting.AQUA));
			for (ActiveLoader loader : entry.getValue()) {
				BlockPos pos = loader.pos();
				ChunkPos chunkPos = ChunkPos.containing(pos);
				message.append(Component.literal("\n  " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
						+ "  (chunk " + chunkPos.x() + " " + chunkPos.z() + ")").withStyle(ChatFormatting.GRAY));
				if (loader.awaitingCheck()) {
					message.append(Component.literal("  restored, not re-checked yet")
							.withStyle(ChatFormatting.YELLOW));
				}
				total++;
			}
		}

		context.getSource().sendSuccess(() -> message, false);
		return total;
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
		manager.evaluate(level, pos, context.getSource().getPlayer());
		boolean after = manager.isActive(level, pos);

		String where = pos.getX() + " " + pos.getY() + " " + pos.getZ();
		String verdict;
		if (after && !before) {
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
