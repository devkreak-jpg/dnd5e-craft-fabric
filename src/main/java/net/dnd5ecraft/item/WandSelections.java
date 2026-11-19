package net.dnd5ecraft.item;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

final class WandSelections {
    private WandSelections() {}

    static boolean allowed(ServerPlayer player) {
        if (player.hasPermissions(2)) return true;
        message(player, "dnd-5e-craft.operator_required");
        return false;
    }

    static void message(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }

    static Optional<BlockPos> anchor(ItemStack stack, ServerLevel level) {
        var tag = stack.getOrCreateTag();
        if (tag.contains("Anchor", Tag.TAG_LONG) && tag.getString("Dimension").equals(level.dimension().location().toString())) {
            return Optional.of(BlockPos.of(tag.getLong("Anchor")));
        }
        return Optional.empty();
    }

    static void setAnchor(ItemStack stack, ServerLevel level, BlockPos pos) {
        var tag = stack.getOrCreateTag();
        tag.putLong("Anchor", pos.asLong());
        tag.putString("Dimension", level.dimension().location().toString());
    }

    static void clearAnchor(ItemStack stack) {
        var tag = stack.getOrCreateTag();
        tag.remove("Anchor");
        tag.remove("Dimension");
    }

    static boolean inspectable(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.isLoaded(pos) && level.getWorldBorder().isWithinBounds(pos);
    }
}
