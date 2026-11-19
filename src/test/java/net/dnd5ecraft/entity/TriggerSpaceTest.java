package net.dnd5ecraft.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TriggerSpaceTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void blocksAmbushesAtEitherOccupiedHeight() {
        BlockPos spot = new BlockPos(0, 5, 0);
        assertTrue(TriggerZoneBlockEntity.clearAmbushSpace(space(spot.below()), spot));
        assertFalse(TriggerZoneBlockEntity.clearAmbushSpace(space(spot), spot));
        assertFalse(TriggerZoneBlockEntity.clearAmbushSpace(space(spot.above()), spot));
    }

    private static BlockGetter space(BlockPos blocked) {
        return new BlockGetter() {
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) {
                return (pos.equals(blocked) ? Blocks.STONE : Blocks.AIR).defaultBlockState();
            }
            @Override public FluidState getFluidState(BlockPos pos) { return Fluids.EMPTY.defaultFluidState(); }
            @Override public int getHeight() { return 64; }
            @Override public int getMinBuildHeight() { return 0; }
        };
    }
}
