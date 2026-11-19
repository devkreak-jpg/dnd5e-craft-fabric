package net.dnd5ecraft.item;

import net.dnd5ecraft.entity.TriggerZoneBlockEntity;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.server.PlayerRules;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;

public final class ThievesToolsItem extends Item {
    public ThievesToolsItem(Properties properties) { super(properties); }

    @Override public InteractionResult useOn(UseOnContext context) {
        var data = context.getLevel().getBlockEntity(context.getClickedPos());
        if (!(data instanceof TriggerZoneBlockEntity) && !(data instanceof BaseContainerBlockEntity)) return InteractionResult.PASS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        if (!player.serverLevel().mayInteract(player, context.getClickedPos())) return InteractionResult.FAIL;
        try {
            PlayerRules.requireDowntime(player);
            if (data instanceof TriggerZoneBlockEntity trigger) {
                trigger.investigate(player, true);
            } else if (data instanceof BaseContainerBlockEntity locked) {
                var saved = locked.saveWithoutMetadata();
                if (saved.getString("Lock").isEmpty()) return InteractionResult.PASS;
                var check = PlayerRules.check(player, Skill.SLEIGHT_OF_HAND, 15);
                PlayerRules.show(player, PlayerRules.label("skill", "sleight_of_hand"), check);
                if (check.succeeds()) {
                    saved.remove("Lock");
                    locked.load(saved);
                    locked.setChanged();
                }
            }
            var pos = context.getClickedPos().getCenter();
            player.serverLevel().sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y, pos.z, 12, .22, .22, .22, .07);
            player.serverLevel().playSound(null, context.getClickedPos(), SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, .45f, 1.5f);
            return InteractionResult.CONSUME;
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            player.displayClientMessage(Component.literal(rejected.getMessage()), true);
            return InteractionResult.FAIL;
        }
    }
}
