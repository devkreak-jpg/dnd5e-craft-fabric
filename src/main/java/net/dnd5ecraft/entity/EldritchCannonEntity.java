package net.dnd5ecraft.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

public final class EldritchCannonEntity extends CampaignCompanion {
    public enum Mode { FLAMETHROWER, FORCE_BALLISTA, PROTECTOR }

    private static final EntityDataAccessor<Integer> MODE = SynchedEntityData.defineId(EldritchCannonEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> PERCHED = SynchedEntityData.defineId(EldritchCannonEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> RECOIL = SynchedEntityData.defineId(EldritchCannonEntity.class, EntityDataSerializers.INT);

    public EldritchCannonEntity(EntityType<? extends EldritchCannonEntity> type, Level level) { super(type, level); }

    public static AttributeSupplier.Builder attributes() {
        return createMobAttributes().add(Attributes.MAX_HEALTH, 15).add(Attributes.ARMOR, 8)
                .add(Attributes.MOVEMENT_SPEED, .24).add(Attributes.FOLLOW_RANGE, 24);
    }

    @Override protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(MODE, Mode.FORCE_BALLISTA.ordinal());
        entityData.define(PERCHED, false);
        entityData.define(RECOIL, 0);
    }

    public Mode mode() { return Mode.values()[Math.floorMod(entityData.get(MODE), Mode.values().length)]; }
    public void mode(Mode mode) { if (mode != null) entityData.set(MODE, mode.ordinal()); }
    public boolean perched() { return entityData.get(PERCHED); }
    public void perched(boolean value) {
        entityData.set(PERCHED, value);
        setNoGravity(value);
        noPhysics = value;
        if (value) fallDistance = 0;
    }

    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PERCHED.equals(key)) noPhysics = perched();
    }
    public int recoil() { return entityData.get(RECOIL); }
    public void fire() { entityData.set(RECOIL, 8); }

    @Override public void aiStep() {
        super.aiStep();
        if (level().isClientSide) return;
        if (recoil() > 0) entityData.set(RECOIL, recoil() - 1);
        var owner = owner();
        if (owner == null) return;
        if (!owner.isAlive() || owner.level() != level()) {
            discard();
            return;
        }
        if (perched()) {
            noPhysics = true;
            fallDistance = 0;
            getNavigation().stop();
            double yaw = Math.toRadians(owner.getYRot());
            setPos(owner.getX() + Math.cos(yaw) * .48, owner.getEyeY() - .26, owner.getZ() + Math.sin(yaw) * .48);
            setYRot(owner.getYRot());
            setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        } else if (tickCount % 10 == 0 && distanceToSqr(owner) > 9) {
            if (distanceToSqr(owner) > 256 && !net.dnd5ecraft.server.TacticalCombat.isLocked(owner)) {
                var spot = net.dnd5ecraft.server.CampaignActions.companionGround(owner, this, 2);
                if (spot != null) {
                    getNavigation().stop();
                    moveTo(spot.getX() + .5, spot.getY(), spot.getZ() + .5, owner.getYRot(), 0);
                    ((net.minecraft.server.level.ServerLevel) level()).sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,
                            getX(), getY() + .3, getZ(), 24, .25, .25, .25, .1);
                }
            } else getNavigation().moveTo(owner, 1.1);
        }
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Mode", mode().ordinal());
        tag.putBoolean("Perched", perched());
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Mode")) mode(Mode.values()[Math.floorMod(tag.getInt("Mode"), Mode.values().length)]);
        perched(tag.getBoolean("Perched"));
    }
}
