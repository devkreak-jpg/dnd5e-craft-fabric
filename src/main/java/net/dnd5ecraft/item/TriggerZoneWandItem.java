package net.dnd5ecraft.item;

import net.dnd5ecraft.entity.ModEntities;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.entity.TriggerZoneBlockEntity;
import net.dnd5ecraft.entity.TriggerZoneSpec;
import net.dnd5ecraft.entity.TriggerZoneSpec.Mode;
import net.dnd5ecraft.server.TacticalCombat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

public final class TriggerZoneWandItem extends Item {
    public TriggerZoneWandItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            if (!WandSelections.allowed(serverPlayer)) return InteractionResultHolder.fail(stack);
            if (player.isShiftKeyDown()) {
                stack.getOrCreateTag().putBoolean("Ambush", mode(stack) != Mode.AMBUSH);
                WandSelections.clearAnchor(stack);
            }
            WandSelections.message(serverPlayer, "dnd-5e-craft.zone.mode", net.minecraft.network.chat.Component.translatable(
                    "dnd-5e-craft.ui.zone.mode." + mode(stack).name().toLowerCase(java.util.Locale.ROOT)));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player) || !WandSelections.allowed(player)) return InteractionResult.FAIL;
        if (level.getBlockEntity(context.getClickedPos()) instanceof TriggerZoneBlockEntity existing) {
            if (player.isShiftKeyDown() && ModPackets.supports(player) && existing.specification() != null) {
                ModPackets.inspectZone(player, context.getClickedPos());
                return InteractionResult.CONSUME;
            }
            boolean armed = existing.toggleArmed();
            WandSelections.message(player, armed ? "dnd-5e-craft.zone.armed" : "dnd-5e-craft.zone.disarmed");
            return InteractionResult.CONSUME;
        }
        ItemStack stack = context.getItemInHand();
        BlockPos corner = context.getClickedPos().relative(context.getClickedFace());
        if (!WandSelections.inspectable(level, corner)) return InteractionResult.FAIL;
        var first = WandSelections.anchor(stack, level);
        if (first.isEmpty()) {
            if (!level.isEmptyBlock(corner)) {
                WandSelections.message(player, "dnd-5e-craft.zone.blocked");
                return InteractionResult.FAIL;
            }
            WandSelections.setAnchor(stack, level, corner);
            WandSelections.message(player, "dnd-5e-craft.first_corner", corner.toShortString());
            return InteractionResult.CONSUME;
        }
        BlockPos anchor = first.get();
        if (!WandSelections.inspectable(level, anchor) || !level.isEmptyBlock(anchor)) {
            WandSelections.clearAnchor(stack);
            WandSelections.message(player, "dnd-5e-craft.zone.blocked");
            return InteractionResult.FAIL;
        }
        TriggerZoneSpec spec;
        try {
            spec = TriggerZoneSpec.between(TacticalCombat.cell(anchor), TacticalCombat.cell(corner), mode(stack));
        } catch (IllegalArgumentException invalid) {
            WandSelections.message(player, "dnd-5e-craft.zone.too_large");
            return InteractionResult.FAIL;
        }
        if (!level.setBlock(anchor, ModEntities.TRIGGER_ZONE.defaultBlockState(), Block.UPDATE_ALL)) return InteractionResult.FAIL;
        if (!(level.getBlockEntity(anchor) instanceof TriggerZoneBlockEntity zone)) {
            level.removeBlock(anchor, false);
            return InteractionResult.FAIL;
        }
        zone.configure(spec);
        WandSelections.clearAnchor(stack);
        WandSelections.message(player, "dnd-5e-craft.zone.armed");
        return InteractionResult.CONSUME;
    }

    private static Mode mode(ItemStack stack) {
        return stack.getOrCreateTag().getBoolean("Ambush") ? Mode.AMBUSH : Mode.TRAP;
    }
}
