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
        return createMobAttributes().add(Attributes.MAX_HEALTH, 1).add(Attributes.ARMOR, 6);
    }

    @Override public boolean isPushable() { return false; }
    @Override public boolean canBeCollidedWith() { return false; }

    @Override public boolean canBeAffected(MobEffectInstance effect) { return false; }

    @Override public void tick() {
        super.tick();
        setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if (level().isClientSide && tickCount % 6 == 0) {
            level().addParticle(ParticleTypes.ASH, getX(), getY() + .9, getZ(), 0, .05, 0);
        }
    }
}
