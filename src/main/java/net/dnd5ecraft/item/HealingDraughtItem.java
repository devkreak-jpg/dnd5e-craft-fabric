package net.dnd5ecraft.item;

import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.server.TacticalCombat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

public final class HealingDraughtItem extends Item {
    public HealingDraughtItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.DRINK; }
    @Override public int getUseDuration(ItemStack stack) { return 32; }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity user) {
        if (level.isClientSide) return stack;
        int amount = 2 + AbilityCheckEngine.rollDie(4, sides -> 1 + user.getRandom().nextInt(sides))
                + AbilityCheckEngine.rollDie(4, sides -> 1 + user.getRandom().nextInt(sides));
        if (!TacticalCombat.drinkHealingDraught(user, amount)) return stack;
        if (user instanceof Player player && player.getAbilities().instabuild) return stack;
        stack.shrink(1);
        ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
        if (stack.isEmpty()) return bottle;
        if (user instanceof Player player) {
            if (!player.getInventory().add(bottle)) player.drop(bottle, false);
        } else {
            user.spawnAtLocation(bottle);
        }
        return stack;
    }
}
