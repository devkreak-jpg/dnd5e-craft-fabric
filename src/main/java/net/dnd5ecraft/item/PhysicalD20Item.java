package net.dnd5ecraft.item;

import net.dnd5ecraft.entity.DiceDisplaySpawner;
import net.dnd5ecraft.network.ModPackets;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class PhysicalD20Item extends Item {
    public PhysicalD20Item(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer roller) roll(roller);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
    public static void roll(ServerPlayer roller) {
        if (roller.isSpectator() && !roller.hasPermissions(2)) return;
        if (roller.getCooldowns().isOnCooldown(ModItems.PHYSICAL_D20)) return;
        boolean lucky = net.dnd5ecraft.server.PlayerSheets.get(roller).filter(sheet ->
                sheet.race() == net.dnd5ecraft.rules.character.RaceType.HALFLING).isPresent();
        int result = net.dnd5ecraft.rules.ability.DiceRoller.naturalD20(net.dnd5ecraft.server.PlayerRules.dice(roller), lucky);
        roller.getCooldowns().addCooldown(ModItems.PHYSICAL_D20, 20);
        ModPackets.sendRoll(roller, result);
        if (!ModPackets.supports(roller)) {
            DiceDisplaySpawner.spawn(roller.serverLevel(), roller, result);
            roller.displayClientMessage(Component.translatable("dnd-5e-craft.d20.rolled", result), true);
        }
    }
}
