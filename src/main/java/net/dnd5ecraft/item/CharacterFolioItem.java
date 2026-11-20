package net.dnd5ecraft.item;

import net.dnd5ecraft.server.PlayerSheets;
import net.dnd5ecraft.network.ModPackets;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class CharacterFolioItem extends Item {
    public CharacterFolioItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer && ModPackets.supports(serverPlayer)) {
            ModPackets.sendSheet(serverPlayer, true);
        } else if (player instanceof ServerPlayer serverPlayer) {
            var sheet = PlayerSheets.getOrCreate(serverPlayer);
            serverPlayer.sendSystemMessage(Component.translatable("dnd-5e-craft.folio.summary",
                    sheet.name(), sheet.level(),
                    Component.translatable("dnd-5e-craft.ui.race." + sheet.race().name().toLowerCase(java.util.Locale.ROOT)),
                    Component.translatable("dnd-5e-craft.ui.class." + sheet.startingClass().name().toLowerCase(java.util.Locale.ROOT)),
                    sheet.hitPoints().current(), sheet.hitPoints().maximum()));
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
}
