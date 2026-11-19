package net.dnd5ecraft.entity;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

public abstract class CampaignCompanion extends PathfinderMob {
    private static final EntityDataAccessor<Optional<UUID>> OWNER =
            SynchedEntityData.defineId(CampaignCompanion.class, EntityDataSerializers.OPTIONAL_UUID);

    protected CampaignCompanion(EntityType<? extends CampaignCompanion> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    @Override protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(OWNER, Optional.empty());
    }

    public final Optional<UUID> ownerId() { return entityData.get(OWNER); }
    public final void bind(ServerPlayer owner) { entityData.set(OWNER, Optional.of(owner.getUUID())); }

    public final ServerPlayer owner() {
        if (!(level() instanceof ServerLevel level)) return null;
        return ownerId().map(level::getEntity).filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast)
                .filter(ServerPlayer::isAlive).orElse(null);
    }

    @Override public boolean hurt(DamageSource source, float amount) {
        return (source.getEntity() == null || !ownerId().filter(source.getEntity().getUUID()::equals).isPresent())
                && super.hurt(source, amount);
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ownerId().ifPresent(id -> tag.putUUID("DndOwner", id));
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("DndOwner")) entityData.set(OWNER, Optional.of(tag.getUUID("DndOwner")));
    }
}
