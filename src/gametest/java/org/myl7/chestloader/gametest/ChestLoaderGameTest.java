package org.myl7.chestloader.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.myl7.chestloader.ChestLoader;
import org.myl7.chestloader.LoaderManager;

/**
 * The three things the plain unit tests cannot reach.
 *
 * <p>The shape rules themselves are covered by {@code LoaderRulesTest}, which runs without a server
 * and is far quicker, so nothing here repeats them. What is left needs a live world: the mixin on
 * the container open and close path, the two halves of a double chest, and item stacks that carry
 * real data components, which cannot even be constructed until a server has bound them.
 */
public final class ChestLoaderGameTest {
	/** Ring slots of the pattern at column offset 1, the layout the README draws. */
	private static final int[] RING_SLOTS = {1, 2, 3, 4, 10, 13, 19, 20, 21, 22};
	private static final int RAIL_SLOT = 11;
	private static final int MINECART_SLOT = 12;

	/**
	 * The mock player is a plain {@link Player} rather than a server one on purpose. A mock server
	 * player has no connection, so the chat feedback the mod sends on activation would fail on it.
	 * The open and close path is what matters here, and it does not care which kind of player it is.
	 */
	@GameTest
	public void openingAPatternedBarrelActivatesIt(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		BarrelBlockEntity barrel = placeBarrel(helper, pos);
		fillPattern(barrel);
		helper.assertFalse(isActive(helper, pos), "filling a barrel on its own must not activate anything");

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		barrel.startOpen(player);
		helper.assertTrue(isActive(helper, pos), "opening a barrel that holds the pattern should activate it");

		barrel.stopOpen(player);
		helper.assertTrue(isActive(helper, pos), "closing an unchanged barrel should leave it active");
		helper.succeed();
	}

	@GameTest
	public void takingObsidianOutAndClosingRevokesIt(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		BarrelBlockEntity barrel = placeBarrel(helper, pos);
		fillPattern(barrel);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		barrel.startOpen(player);
		helper.assertTrue(isActive(helper, pos), "the barrel should be active while the pattern is intact");

		// This is why closing is checked as well as opening. A hopper would be caught by the
		// periodic scan instead, but a player emptying a slot is caught right here.
		barrel.setItem(RING_SLOTS[0], ItemStack.EMPTY);
		barrel.stopOpen(player);
		helper.assertFalse(isActive(helper, pos), "closing after breaking the ring should revoke it");
		helper.succeed();
	}

	@GameTest
	public void anOrdinaryStorageBarrelIsLeftAlone(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		BarrelBlockEntity barrel = placeBarrel(helper, pos);
		barrel.setItem(0, new ItemStack(Items.OBSIDIAN, 64));
		barrel.setItem(1, new ItemStack(Items.POWERED_RAIL, 64));
		barrel.setItem(2, new ItemStack(Items.MINECART));

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		barrel.startOpen(player);
		helper.assertFalse(isActive(helper, pos), "the right items in the wrong places are not a pattern");
		helper.succeed();
	}

	@GameTest
	public void componentsOnThePatternItemsAreIgnored(GameTestHelper helper) {
		BlockPos pos = new BlockPos(1, 1, 1);
		BarrelBlockEntity barrel = placeBarrel(helper, pos);
		fillPattern(barrel);

		ItemStack renamed = new ItemStack(Items.OBSIDIAN);
		renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Anvil Leftovers"));
		barrel.setItem(RING_SLOTS[0], renamed);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		barrel.startOpen(player);
		helper.assertTrue(isActive(helper, pos), "a renamed obsidian is still obsidian");
		helper.succeed();
	}

	@GameTest
	public void theHalvesOfADoubleChestAreJudgedSeparately(GameTestHelper helper) {
		BlockPos west = new BlockPos(1, 1, 1);
		BlockPos east = new BlockPos(2, 1, 1);
		// Facing north, a LEFT chest joins its east neighbour and a RIGHT chest joins its west one.
		helper.setBlock(west, Blocks.CHEST.defaultBlockState()
				.setValue(ChestBlock.FACING, Direction.NORTH)
				.setValue(ChestBlock.TYPE, ChestType.LEFT));
		helper.setBlock(east, Blocks.CHEST.defaultBlockState()
				.setValue(ChestBlock.FACING, Direction.NORTH)
				.setValue(ChestBlock.TYPE, ChestType.RIGHT));
		helper.assertValueEqual(
				ChestBlock.getConnectedBlockPos(helper.absolutePos(west), helper.getBlockState(west)),
				helper.absolutePos(east),
				"the two chests have to form one double chest for this test to mean anything");

		ChestBlockEntity westChest = helper.getBlockEntity(west, ChestBlockEntity.class);
		ChestBlockEntity eastChest = helper.getBlockEntity(east, ChestBlockEntity.class);
		fillPattern(westChest);

		// Opening a double chest runs startOpen on both halves through the compound container.
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		westChest.startOpen(player);
		eastChest.startOpen(player);

		helper.assertTrue(isActive(helper, west), "the half holding the pattern should activate");
		helper.assertFalse(isActive(helper, east), "the empty half should not, only its own 27 slots count");
		helper.succeed();
	}

	private static BarrelBlockEntity placeBarrel(GameTestHelper helper, BlockPos pos) {
		helper.setBlock(pos, Blocks.BARREL);
		return helper.getBlockEntity(pos, BarrelBlockEntity.class);
	}

	private static void fillPattern(Container container) {
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			container.setItem(slot, ItemStack.EMPTY);
		}
		for (int slot : RING_SLOTS) {
			container.setItem(slot, new ItemStack(Items.OBSIDIAN));
		}
		container.setItem(RAIL_SLOT, new ItemStack(Items.POWERED_RAIL, 4));
		container.setItem(MINECART_SLOT, new ItemStack(Items.MINECART));
	}

	private static boolean isActive(GameTestHelper helper, BlockPos relativePos) {
		LoaderManager manager = ChestLoader.manager();
		if (manager == null) {
			throw new IllegalStateException("Chest Loader is not running, the test mod is misconfigured");
		}
		return manager.isActive(helper.getLevel(), helper.absolutePos(relativePos));
	}
}
