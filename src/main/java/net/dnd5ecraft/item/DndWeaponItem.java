package net.dnd5ecraft.item;

import java.util.List;
import java.util.Locale;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public final class DndWeaponItem extends Item {
    private final WeaponStatsRegistry.Stats stats;
    private final int enhancement;

    public DndWeaponItem(WeaponStatsRegistry.Stats stats, int enhancement, Properties properties) {
        super(properties);
        this.stats = stats;
        this.enhancement = enhancement;
    }

    public WeaponStatsRegistry.Stats stats() { return stats; }
    public int enhancement() { return enhancement; }
    public static boolean broken(ItemStack stack) { return stack.hasTag() && stack.getTag().getBoolean("DndBroken"); }

    @Override public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        String amount = stats.damage().count() == 0 ? Integer.toString(stats.flatDamage())
                : stats.damage().count() + "d" + stats.damage().sides();
        if (enhancement != 0) amount += "+" + enhancement;
        var display = amount;
        tooltip.add(stats.damageType().<Component>map(type -> Component.translatable("dnd-5e-craft.weapon.damage", display,
                Component.translatable("dnd-5e-craft.ui.damage." + type.name().toLowerCase(Locale.ROOT))))
                .orElseGet(() -> Component.translatable("dnd-5e-craft.weapon.net")));
        stats.properties().stream().sorted().forEach(property -> tooltip.add(Component.translatable(
                "dnd-5e-craft.weapon.property." + property.name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.GRAY)));
        if (broken(stack)) tooltip.add(Component.translatable("dnd-5e-craft.weapon.broken").withStyle(ChatFormatting.RED));
    }
}
