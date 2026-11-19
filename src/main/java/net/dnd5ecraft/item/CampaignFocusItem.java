package net.dnd5ecraft.item;

import net.dnd5ecraft.server.CampaignActions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class CampaignFocusItem extends Item {
    public enum Focus { CANNON, ECHO, CHAOS }
    private final Focus focus;

    public CampaignFocusItem(Properties properties, Focus focus) { super(properties); this.focus = focus; }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer server) {
            try {
                CampaignActions.focus(server, focus, null);
            } catch (IllegalArgumentException | IllegalStateException invalid) {
                server.displayClientMessage(Component.literal(invalid.getMessage()), true);
                return InteractionResultHolder.fail(stack);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer server)) return InteractionResult.SUCCESS;
        try {
            CampaignActions.focus(server, focus, context.getClickedPos().relative(context.getClickedFace()));
            return InteractionResult.CONSUME;
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            server.displayClientMessage(Component.literal(invalid.getMessage()), true);
            return InteractionResult.FAIL;
        }
    }
}
