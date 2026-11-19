package net.dnd5ecraft.item;

import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.server.DmMode;
import net.dnd5ecraft.server.TacticalCombat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.dnd5ecraft.server.DmDirector;
import net.minecraft.world.level.Level;

public final class DmMasterWandItem extends Item {
    public DmMasterWandItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            if (!WandSelections.allowed(serverPlayer)) return InteractionResultHolder.fail(stack);
            try {
                boolean enabled = DmMode.toggle(serverPlayer);
                if (!enabled) WandSelections.message(serverPlayer, "dnd-5e-craft.dm.disabled");
            } catch (IllegalStateException rejected) {
                serverPlayer.displayClientMessage(net.minecraft.network.chat.Component.literal(rejected.getMessage()), true);
                return InteractionResultHolder.fail(stack);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof DndNpcEntity npc)) return InteractionResult.PASS;
        if (player instanceof ServerPlayer serverPlayer) {
            if (!WandSelections.allowed(serverPlayer)) return InteractionResult.FAIL;
            if (!player.isShiftKeyDown()) {
                DmDirector.select(serverPlayer, npc, false);
                return InteractionResult.CONSUME;
            }
            if (TacticalCombat.isLocked(npc)) return InteractionResult.FAIL;
            if (ModPackets.supports(serverPlayer)) {
                ModPackets.inspectNpc(serverPlayer, npc);
                return InteractionResult.CONSUME;
            }
            NpcAttitude[] attitudes = NpcAttitude.values();
            npc.setAttitude(attitudes[(npc.attitude().ordinal() + 1) % attitudes.length]);
            WandSelections.message(serverPlayer, "dnd-5e-craft.npc.attitude", net.minecraft.network.chat.Component.translatable(
                    "dnd-5e-craft.ui.attitude." + npc.attitude().name().toLowerCase(java.util.Locale.ROOT)));
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        if (!WandSelections.allowed(player)) return InteractionResult.FAIL;
        if (DmDirector.selected(player) == null) return InteractionResult.PASS;
        try {
            DmDirector.direct(player, context.getClickedPos().relative(context.getClickedFace()), player.isShiftKeyDown());
            return InteractionResult.CONSUME;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(invalid.getMessage()), true);
            return InteractionResult.FAIL;
        }
    }
}
