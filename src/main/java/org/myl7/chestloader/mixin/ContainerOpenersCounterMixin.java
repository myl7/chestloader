package org.myl7.chestloader.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.state.BlockState;
import org.myl7.chestloader.ChestLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chests, trapped chests and barrels all run their open and close bookkeeping through this class, so
 * one pair of hooks covers every container the mod cares about.
 */
@Mixin(ContainerOpenersCounter.class)
public class ContainerOpenersCounterMixin {
	@Inject(method = "incrementOpeners", at = @At("TAIL"))
	private void chestloader$onOpened(LivingEntity entity, Level level, BlockPos pos, BlockState blockState,
			double maxInteractionRange, CallbackInfo ci) {
		ChestLoader.onContainerToggled(level, pos, entity);
	}

	@Inject(method = "decrementOpeners", at = @At("TAIL"))
	private void chestloader$onClosed(LivingEntity entity, Level level, BlockPos pos, BlockState blockState,
			CallbackInfo ci) {
		ChestLoader.onContainerToggled(level, pos, entity);
	}
}
