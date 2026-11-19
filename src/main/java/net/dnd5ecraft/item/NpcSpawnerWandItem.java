package net.dnd5ecraft.item;

import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.entity.BestiaryRegistry5e;
import net.dnd5ecraft.entity.NpcSpawns;
import net.minecraft.network.chat.Component;
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

public final class NpcSpawnerWandItem extends Item {
    public NpcSpawnerWandItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            if (!WandSelections.allowed(serverPlayer)) return InteractionResultHolder.fail(stack);
            BestiaryPreset preset = selected(stack);
            if (player.isShiftKeyDown()) {
                var presets = BestiaryRegistry5e.spawnable();
                preset = presets.get((presets.indexOf(preset) + 1) % presets.size());
                stack.getOrCreateTag().putString("Preset", preset.id());
            }
            WandSelections.message(serverPlayer, "dnd-5e-craft.npc.selected",
                    Component.translatable("entity.dnd-5e-craft.npc." + preset.id()));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player) || !WandSelections.allowed(player)) return InteractionResult.FAIL;
        var pos = context.getClickedPos().relative(context.getClickedFace());
        var preset = selected(context.getItemInHand());
        NpcAttitude attitude = player.isShiftKeyDown() ? NpcAttitude.HOSTILE : NpcAttitude.INDIFFERENT;
        var npc = NpcSpawns.spawn(level, pos, preset, attitude);
        if (npc.isEmpty()) {
            WandSelections.message(player, "dnd-5e-craft.npc.cannot_spawn");
            return InteractionResult.FAIL;
        }
        WandSelections.message(player, "dnd-5e-craft.npc.spawned", npc.get().getDisplayName());
        return InteractionResult.CONSUME;
    }

    private static BestiaryPreset selected(ItemStack stack) {
        try {
            return BestiaryPreset.fromId(stack.getOrCreateTag().getString("Preset"));
        } catch (IllegalArgumentException invalid) {
            return BestiaryPreset.GOBLIN;
        }
    }
}
