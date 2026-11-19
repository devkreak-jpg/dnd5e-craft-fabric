package net.dnd5ecraft.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

public final class DungeonBrushItem extends Item {
    private static final int MAX_BLOCKS = 4096;

    public DungeonBrushItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player) || !WandSelections.allowed(player)) return InteractionResult.FAIL;

        ItemStack brush = context.getItemInHand();
        BlockPos clicked = context.getClickedPos();
        if (player.isShiftKeyDown()) {
            var state = level.getBlockState(clicked);
            if (!state.isSolidRender(level, clicked) || state.hasBlockEntity() || state.getDestroySpeed(level, clicked) < 0) {
                WandSelections.message(player, "dnd-5e-craft.brush.invalid_palette");
                return InteractionResult.FAIL;
            }
            var block = state.getBlock();
            brush.getOrCreateTag().putString("Palette", BuiltInRegistries.BLOCK.getKey(block).toString());
            WandSelections.clearAnchor(brush);
            WandSelections.message(player, "dnd-5e-craft.brush.palette", block.getName());
            return InteractionResult.CONSUME;
        }
        BlockPos corner = clicked.relative(context.getClickedFace());
        var first = WandSelections.anchor(brush, level);
        if (first.isEmpty()) {
            WandSelections.setAnchor(brush, level, corner);
            WandSelections.message(player, "dnd-5e-craft.first_corner", corner.toShortString());
            return InteractionResult.CONSUME;
        }
        if (!bounded(first.get(), corner)) {
            WandSelections.message(player, "dnd-5e-craft.brush.too_large");
            return InteractionResult.FAIL;
        }
        var positions = BlockPos.betweenClosed(first.get(), corner);
        for (BlockPos pos : positions) {
            if (!WandSelections.inspectable(level, pos) || level.getBlockEntity(pos) != null
                    || level.getBlockState(pos).getDestroySpeed(level, pos) < 0) {
                WandSelections.message(player, "dnd-5e-craft.brush.invalid_volume");
                return InteractionResult.FAIL;
            }
        }
        Block block = palette(brush);
        int changed = 0;
        for (BlockPos pos : positions) {
            if (level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL)) changed++;
        }
        WandSelections.clearAnchor(brush);
        WandSelections.message(player, "dnd-5e-craft.brush.painted", changed);
        return InteractionResult.CONSUME;
    }

    private static boolean bounded(BlockPos one, BlockPos two) {
        long dx = Math.abs((long) one.getX() - two.getX()) + 1;
        long dy = Math.abs((long) one.getY() - two.getY()) + 1;
        long dz = Math.abs((long) one.getZ() - two.getZ()) + 1;
        return dx <= MAX_BLOCKS && dy <= MAX_BLOCKS && dz <= MAX_BLOCKS && dx * dy * dz <= MAX_BLOCKS;
    }

    private static Block palette(ItemStack stack) {
        ResourceLocation key = ResourceLocation.tryParse(stack.getOrCreateTag().getString("Palette"));
        if (key == null) return Blocks.STONE_BRICKS;
        Block block = BuiltInRegistries.BLOCK.get(key);
        var state = block.defaultBlockState();
        return state.isAir() || state.hasBlockEntity() ? Blocks.STONE_BRICKS : block;
    }
}
