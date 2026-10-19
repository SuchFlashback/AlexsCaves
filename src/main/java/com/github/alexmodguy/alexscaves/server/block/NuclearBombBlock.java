package com.github.alexmodguy.alexscaves.server.block;

import com.github.alexmodguy.alexscaves.server.misc.war.NukeWarText;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

public class NuclearBombBlock extends Block {
    public NuclearBombBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(8, 1001).sound(ACSoundTypes.NUCLEAR_BOMB));
    }

    // Community patch: a nuclear bomb is only detonated when dropped from an Immersive Aircraft warship
    // (see NukeAircraft). Fire, redstone, burning projectiles and remote detonators do nothing.
    public void onCaughtFire(BlockState state, Level level, BlockPos blockPos, @Nullable net.minecraft.core.Direction face, @Nullable LivingEntity igniter) {
        if (!level.isClientSide && igniter instanceof Player player) {
            player.displayClientMessage(NukeWarText.onlyFromWarship(), true);
        }
    }

    public boolean dropFromExplosion(Explosion explosion) {
        return false;
    }

    public ItemInteractionResult useItemOn(ItemStack itemstack, BlockState state, Level level, BlockPos blockPos, Player player, InteractionHand hand, BlockHitResult result) {
        if (!itemstack.is(Items.FLINT_AND_STEEL) && !itemstack.is(Items.FIRE_CHARGE)) {
            return super.useItemOn(itemstack, state, level, blockPos, player, hand, result);
        } else {
            if (!level.isClientSide) {
                player.displayClientMessage(NukeWarText.onlyFromWarship(), true);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.DESTROY;
    }

}
