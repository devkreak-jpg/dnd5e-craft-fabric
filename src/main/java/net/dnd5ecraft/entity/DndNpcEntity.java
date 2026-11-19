package net.dnd5ecraft.entity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.dnd5ecraft.dialogue.DialogueNbt;
import net.dnd5ecraft.dialogue.DialogueTree;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.server.DialogueSessions;
import net.dnd5ecraft.server.TacticalCombat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class DndNpcEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> PRESET =
            SynchedEntityData.defineId(DndNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> ATTITUDE =
            SynchedEntityData.defineId(DndNpcEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<String> SKIN =
            SynchedEntityData.defineId(DndNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> SCALE =
            SynchedEntityData.defineId(DndNpcEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> TACTICAL_HP =
            SynchedEntityData.defineId(DndNpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TEMP_HP =
            SynchedEntityData.defineId(DndNpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<UUID>> POSSESSOR =
            SynchedEntityData.defineId(DndNpcEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    private final Map<UUID, NpcAttitude> attitudes = new HashMap<>();
    private BestiaryTurnState bestiary = new BestiaryTurnState(BestiaryPreset.GOBLIN);
    private DialogueTree dialogue = DialogueTree.defaultTree();
    private HitPoints tacticalHealth;
    private Boolean previousAi;
    private DeathSaveTracker death = DeathSaveTracker.CONSCIOUS;

    public DndNpcEntity(EntityType<? extends DndNpcEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createNpcAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, BestiaryPreset.GOBLIN.maxHp())
                .add(Attributes.MOVEMENT_SPEED, .25).add(Attributes.ATTACK_DAMAGE, 5)
                .add(Attributes.ARMOR, 5).add(Attributes.FOLLOW_RANGE, 24);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(PRESET, BestiaryPreset.GOBLIN.id());
        entityData.define(ATTITUDE, NpcAttitude.INDIFFERENT.ordinal());
        entityData.define(SKIN, "");
        entityData.define(SCALE, 0f);
        entityData.define(TACTICAL_HP, -1);
        entityData.define(TEMP_HP, 0);
        entityData.define(POSSESSOR, Optional.empty());
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, true));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, .7));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                player -> !preset().civilian() && !player.isSpectator() && attitudeFor(player.getUUID()) == NpcAttitude.HOSTILE));
    }

    @Override
    public void setTarget(LivingEntity target) {
        if (target != null && (preset().civilian() || target instanceof Player player && attitudeFor(player.getUUID()) != NpcAttitude.HOSTILE)) return;
        super.setTarget(target);
    }

    public BestiaryPreset preset() { return BestiaryPreset.fromId(entityData.get(PRESET)); }
    public BestiaryTurnState bestiary() { return bestiary; }
    public float physicalScale() { return entityData.get(SCALE) == 0 ? preset().scale() : entityData.get(SCALE); }
    public Optional<net.minecraft.resources.ResourceLocation> skin() {
        return Optional.ofNullable(net.minecraft.resources.ResourceLocation.tryParse(entityData.get(SKIN)))
                .filter(id -> !entityData.get(SKIN).isEmpty());
    }
    public Optional<UUID> possessingDmUuid() { return entityData.get(POSSESSOR); }

    public void setPossessingDm(UUID player) { entityData.set(POSSESSOR, Optional.ofNullable(player)); }

    public void setAppearance(String skinId, float scale) {
        var id = net.minecraft.resources.ResourceLocation.tryParse(skinId);
        if (!skinId.isEmpty() && (id == null || !java.util.Set.of("minecraft", "dnd-5e-craft").contains(id.getNamespace())
                || !id.getPath().startsWith("textures/entity/") || !id.getPath().endsWith(".png")
                || id.getPath().contains(".."))) {
            throw new IllegalArgumentException("Choose a local entity texture");
        }
        if (!Float.isFinite(scale) || scale < .3f || scale > 4f) throw new IllegalArgumentException("Scale must be 0.3–4.0");
        entityData.set(SKIN, skinId.isEmpty() ? "" : id.toString());
        entityData.set(SCALE, scale);
    }
    public DialogueTree dialogue() { return dialogue; }

    public void setDialogue(DialogueTree tree) {
        dialogue = java.util.Objects.requireNonNull(tree);
    }

    public NpcAttitude attitude() { return NpcAttitude.fromIndex(entityData.get(ATTITUDE)); }

    public NpcAttitude attitudeFor(UUID player) { return attitudes.getOrDefault(player, attitude()); }

    public void setAttitude(NpcAttitude attitude) {
        entityData.set(ATTITUDE, attitude.ordinal());
        if (getTarget() instanceof Player player && attitudeFor(player.getUUID()) != NpcAttitude.HOSTILE) setTarget(null);
    }

    public void setAttitudeFor(UUID player, NpcAttitude attitude) {
        if (attitude == attitude()) attitudes.remove(player);
        else attitudes.put(player, attitude);
        if (getTarget() != null && getTarget().getUUID().equals(player) && attitude != NpcAttitude.HOSTILE) setTarget(null);
    }

    public void initializePreset(BestiaryPreset preset, NpcAttitude attitude) {
        if (level().isClientSide || TacticalCombat.isLocked(this)) throw new IllegalStateException("Cannot change an active NPC");
        applyPreset(preset);
        setDialogue(BestiaryRegistry5e.starterDialogue(preset));
        bestiary = new BestiaryTurnState(preset);
        death = DeathSaveTracker.CONSCIOUS;
        syncCombatHealth(new HitPoints(preset.maxHp(), preset.maxHp(), 0, 0), DeathSaveTracker.CONSCIOUS);
        setCustomName(Component.translatable("entity.dnd-5e-craft.npc." + preset.id()));
        setAttitude(attitude);
    }

    private void applyPreset(BestiaryPreset preset) {
        entityData.set(PRESET, preset.id());
        java.util.Objects.requireNonNull(getAttribute(Attributes.MAX_HEALTH)).setBaseValue(preset.maxHp());
        java.util.Objects.requireNonNull(getAttribute(Attributes.ARMOR)).setBaseValue(Math.max(0, preset.armor() - 10));
        java.util.Objects.requireNonNull(getAttribute(Attributes.MOVEMENT_SPEED)).setBaseValue(preset.speedFeet() / 120.0);
        double damage = preset.baseAttackDamage();
        java.util.Objects.requireNonNull(getAttribute(Attributes.ATTACK_DAMAGE)).setBaseValue(damage);
        if (preset.civilian()) setTarget(null);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (PRESET.equals(key) || SCALE.equals(key)) refreshDimensions();
    }

    @Override
    public MobType getMobType() {
        return switch (preset().kind()) {
            case UNDEAD -> MobType.UNDEAD;
            case ARTHROPOD -> MobType.ARTHROPOD;
            default -> MobType.UNDEFINED;
        };
    }

    public HitPoints tacticalHealth() {
        if (level().isClientSide) {
            int hp = entityData.get(TACTICAL_HP);
            return new HitPoints(hp < 0 ? Math.min(preset().maxHp(), (int) Math.ceil(getHealth())) : Math.min(preset().maxHp(), hp),
                    preset().maxHp(), entityData.get(TEMP_HP), 0);
        }
        if (tacticalHealth == null) return new HitPoints(Math.min(preset().maxHp(), (int) Math.ceil(getHealth())), preset().maxHp(), 0, 0);
        if (death.state() != DeathSaveTracker.State.CONSCIOUS) return tacticalHealth;
        return new HitPoints(Math.min(tacticalHealth.maximum(), (int) Math.ceil(getHealth())),
                tacticalHealth.maximum(), tacticalHealth.temporary(), tacticalHealth.arcaneWard());
    }

    public DeathSaveTracker deathSaves() { return death; }

    public void suspendAiForCombat() {
        if (previousAi != null) return;
        previousAi = isNoAi();
        getNavigation().stop();
        setNoAi(true);
    }

    public void restoreAiAfterCombat() {
        if (previousAi == null) return;
        setNoAi(previousAi || death.state() != DeathSaveTracker.State.CONSCIOUS);
        previousAi = null;
    }

    public void syncCombatHealth(HitPoints health, DeathSaveTracker saves) {
        tacticalHealth = health;
        death = saves;
        entityData.set(TACTICAL_HP, health.current());
        entityData.set(TEMP_HP, health.temporary());
        setHealth(Math.max(1, health.current()));
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown()) return super.mobInteract(player, hand);
        if (player instanceof ServerPlayer serverPlayer && !TacticalCombat.isLocked(this)) {
            if (DialogueSessions.open(serverPlayer, this)) return InteractionResult.CONSUME;
            return super.mobInteract(player, hand);
        }
        return level().isClientSide ? InteractionResult.SUCCESS : super.mobInteract(player, hand);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (previousAi != null) tag.putBoolean("NoAI", previousAi || death.state() != DeathSaveTracker.State.CONSCIOUS);
        tag.putString("DndPreset", preset().id());
        if (entityData.get(SCALE) != 0) tag.putFloat("DndScale", entityData.get(SCALE));
        skin().ifPresent(id -> tag.putString("DndSkin", id.toString()));
        tag.putString("DndAttitude", attitude().name());
        var individual = new CompoundTag();
        attitudes.forEach((player, value) -> individual.putString(player.toString(), value.name()));
        tag.put("DndAttitudes", individual);
        tag.put("DndDialogue", DialogueNbt.write(dialogue));
        var state = bestiary.snapshot();
        tag.putBoolean("DndBreathReady", state.breathReady());
        tag.putBoolean("DndRegenBlocked", state.regenerationSuppressed());
        tag.putInt("DndLegendaryResistances", state.legendaryResistances());
        tag.putInt("DndLegendaryActions", state.legendaryActions());
        tag.putBoolean("DndLair", state.lairEnabled());

        HitPoints health = tacticalHealth();
        tag.putInt("DndHp", health.current());
        tag.putInt("DndTemporaryHp", health.temporary());
        tag.putInt("DndWard", health.arcaneWard());
        tag.putString("DndDeathState", death.state().name());
        tag.putInt("DndDeathSuccesses", death.successes());
        tag.putInt("DndDeathFailures", death.failures());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        BestiaryPreset preset = BestiaryPreset.GOBLIN;
        try {
            if (tag.contains("DndPreset", Tag.TAG_STRING)) preset = BestiaryPreset.fromId(tag.getString("DndPreset"));
        } catch (IllegalArgumentException invalidPreset) {
            reportInvalid("preset", invalidPreset);
        }
        applyPreset(preset);
        dialogue = BestiaryRegistry5e.starterDialogue(preset);
        if (tag.contains("DndScale", Tag.TAG_FLOAT)) {
            try {
                setAppearance(tag.getString("DndSkin"), tag.getFloat("DndScale"));
            } catch (IllegalArgumentException invalidAppearance) {
                reportInvalid("appearance", invalidAppearance);
            }
        }
        bestiary = new BestiaryTurnState(preset);
        if (tag.contains("DndBreathReady")) {
            try {
                bestiary = new BestiaryTurnState(preset, new BestiaryTurnState.Snapshot(tag.getBoolean("DndBreathReady"),
                        tag.getBoolean("DndRegenBlocked"), tag.getInt("DndLegendaryResistances"),
                        tag.getInt("DndLegendaryActions"), tag.getBoolean("DndLair")));
            } catch (IllegalArgumentException invalidState) {
                reportInvalid("bestiary resources", invalidState);
            }
        }
        try {
            if (tag.contains("DndAttitude", Tag.TAG_STRING)) setAttitude(NpcAttitude.valueOf(tag.getString("DndAttitude")));
        } catch (IllegalArgumentException invalidAttitude) {
            reportInvalid("attitude", invalidAttitude);
        }
        attitudes.clear();
        var overrides = tag.getCompound("DndAttitudes");
        for (String key : overrides.getAllKeys()) {
            try {
                attitudes.put(UUID.fromString(key), NpcAttitude.valueOf(overrides.getString(key)));
            } catch (IllegalArgumentException invalidOverride) {
                reportInvalid("attitude override", invalidOverride);
            }
        }
        if (tag.contains("DndDialogue", Tag.TAG_COMPOUND)) {
            try {
                setDialogue(DialogueNbt.readStored(tag.getCompound("DndDialogue")));
            } catch (IllegalArgumentException invalidDialogue) {
                reportInvalid("dialogue", invalidDialogue);
            }
        }
        if (tag.contains("DndDeathState", Tag.TAG_STRING)) {
            try {
                var saves = new DeathSaveTracker(DeathSaveTracker.State.valueOf(tag.getString("DndDeathState")),
                        tag.getInt("DndDeathSuccesses"), tag.getInt("DndDeathFailures"));
                var health = new HitPoints(tag.getInt("DndHp"), preset.maxHp(),
                        tag.getInt("DndTemporaryHp"), tag.getInt("DndWard"));
                if ((health.current() > 0) != (saves.state() == DeathSaveTracker.State.CONSCIOUS)) {
                    throw new IllegalArgumentException("HP does not match death save state");
                }
                syncCombatHealth(health, saves);
            } catch (IllegalArgumentException invalidHealth) {
                reportInvalid("combat health", invalidHealth);
            }
        }
    }

    private void reportInvalid(String field, IllegalArgumentException failure) {
        org.slf4j.LoggerFactory.getLogger(DndNpcEntity.class).warn("Ignored invalid NPC {} for {}", field, getUUID(), failure);
    }
}
