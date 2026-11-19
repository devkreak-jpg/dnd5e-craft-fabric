package net.dnd5ecraft.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

public final class EchoAvatarEntity extends CampaignCompanion {
    public EchoAvatarEntity(EntityType<? extends EchoAvatarEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 1).add(Attributes.ARMOR, armorAttribute(2));
    }

    public static int armorAttribute(int proficiencyBonus) {
        return net.dnd5ecraft.rules.campaign.CampaignRules.echoArmor(proficiencyBonus) - 10;
    }

    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return source.getEntity() != null && super.hurt(source, amount);
    }

    public void dissolve() {
        if (level() instanceof net.minecraft.server.level.ServerLevel world) {
            world.sendParticles(ParticleTypes.ASH, getX(), getY() + .75, getZ(), 24, .3, .55, .3, .03);
        }
        discard();
    }

    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }

    @Override public boolean canBeAffected(MobEffectInstance effect) { return false; }

    @Override public void tick() {
        if (!level().isClientSide && ownerId().isPresent()) {
            var player = owner();
            if (player == null || !player.isAlive() || player.level() != level()) {
                dissolve();
                return;
            }
            int level = net.dnd5ecraft.server.PlayerSheets.get(player).map(sheet -> sheet.level()).orElse(1);
            var attribute = getAttribute(Attributes.ARMOR);
            int armor = armorAttribute(net.dnd5ecraft.rules.ability.ProficiencyLevel.bonusAtLevel(level));
            if (attribute.getBaseValue() != armor) attribute.setBaseValue(armor);
        }
        super.tick();
        clearFire();
        setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if (level().isClientSide && tickCount % 6 == 0) {
            level().addParticle(ParticleTypes.ASH, getX(), getY() + .9, getZ(), 0, .05, 0);
        }
    }
}
