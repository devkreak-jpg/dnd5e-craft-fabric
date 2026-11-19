package net.dnd5ecraft.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.TriggerZoneSpec.Mode;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.CheckResult;
import net.dnd5ecraft.rules.ability.ProficiencyLevel;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.server.CinematicFreeze;
import net.dnd5ecraft.server.PlayerRules;
import net.dnd5ecraft.server.PlayerSheets;
import net.dnd5ecraft.server.TacticalCombat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class TriggerZoneBlockEntity extends BlockEntity {
    private final Set<UUID> detected = new HashSet<>();
    private final Map<UUID, Long> checked = new HashMap<>();
    private TriggerZoneSpec spec;
    private TriggerOccupancy occupancy = new TriggerOccupancy(false, 0);

    public TriggerZoneBlockEntity(BlockPos pos, BlockState state) {
        super(ModEntities.TRIGGER_ZONE_BLOCK_ENTITY, pos, state);
    }

    public void configure(TriggerZoneSpec spec) {
        if (level != null && level.isClientSide) throw new IllegalStateException("Zones are server controlled");
        if (!spec.contains(cell(worldPosition))) throw new IllegalArgumentException("The trigger marker must be inside the zone");
        this.spec = spec;
        detected.clear();
        checked.clear();
        occupancy = new TriggerOccupancy(true, 0);
        setChanged();
    }

    public boolean armed() { return occupancy.armed(); }

    public boolean toggleArmed() {
        if (spec == null) return false;
        occupancy.setArmed(!occupancy.armed());
        setChanged();
        return occupancy.armed();
    }

    public TriggerZoneSpec specification() { return spec; }

    static void tick(Level level, BlockPos pos, BlockState block, TriggerZoneBlockEntity zone) {
        if (!(level instanceof ServerLevel server) || zone.spec == null) return;
        zone.scan(server);
    }

    private void scan(ServerLevel level) {
        var min = spec.min();
        var max = spec.max();
        AABB bounds = new AABB(min.x(), min.y(), min.z(), max.x() + 1.0, max.y() + 1.0, max.z() + 1.0);
        List<ServerPlayer> present = level.getEntitiesOfClass(ServerPlayer.class, bounds,
                player -> player.isAlive() && !player.isSpectator() && !player.isCreative()
                        && spec.contains(cell(player.blockPosition())));
        long now = level.getGameTime();
        if (spec.mode() == Mode.TRAP && armed() && now % 20 == 0) {
            var nearby = level.getEntitiesOfClass(ServerPlayer.class, bounds.inflate(8),
                    player -> player.isAlive() && !player.isSpectator() && !player.isCreative());
            for (ServerPlayer viewer : nearby) {
                if (PlayerSheets.get(viewer).isPresent()
                        && PlayerRules.passive(viewer, Skill.PERCEPTION) >= spec.perceptionDc()
                        && detected.add(viewer.getUUID())) detect(viewer);
            }
        }
        var entrantIds = occupancy.entrants(present.stream().map(ServerPlayer::getUUID).toList(), now);
        if (entrantIds.isEmpty()) return;
        var entrants = present.stream().filter(player -> entrantIds.contains(player.getUUID())).toList();
        if (spec.mode() == Mode.TRAP) {
            for (ServerPlayer entrant : entrants) {
                var save = PlayerRules.save(entrant, Ability.DEXTERITY, spec.saveDc());
                int damage = save.succeeds() ? spec.damage() / 2 : spec.damage();
                if (damage > 0) {
                    TacticalCombat.environmentDamage(entrant, DamageType5e.PIERCING, damage);
                }
            }
        } else if (!ambush(level, entrants)) {
            return;
        }
        occupancy.activated(spec, now);
        setChanged();
    }

    private boolean ambush(ServerLevel level, List<ServerPlayer> entrants) {
        List<DndNpcEntity> spawned = new ArrayList<>();
        BlockPos center = entrants.get(0).blockPosition();
        // Spawn on the same floor near the entrant. A loaded, supported cell is a prerequisite.
        for (int radius = 2; radius <= 4 && spawned.size() < spec.ambushers(); radius++) {
            for (int dx = -radius; dx <= radius && spawned.size() < spec.ambushers(); dx++) {
                for (int dz = -radius; dz <= radius && spawned.size() < spec.ambushers(); dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    BlockPos spot = center.offset(dx, 0, dz);
                    if (!level.isLoaded(spot) || !level.getBlockState(spot.below()).isSolidRender(level, spot.below())) continue;
                    NpcSpawns.spawn(level, spot, spec.ambusher(), NpcAttitude.HOSTILE).ifPresent(spawned::add);
                }
            }
        }
        if (spawned.isEmpty()) return false;
        if (spec.mode() == Mode.AMBUSH_CUTSCENE) {
            var party = level.getEntitiesOfClass(ServerPlayer.class, new AABB(center).inflate(8),
                    player -> player.isAlive() && !player.isCreative() && !player.isSpectator());
            var title = spec.title().isBlank() ? Component.translatable("dnd-5e-craft.zone.ambush")
                    : Component.literal(spec.title());
            CinematicFreeze.begin(level, party, block(spec.min()), block(spec.max()), title);
            TacticalCombat.tryAmbush(level, party, spawned);
        } else TacticalCombat.tryAmbush(level, entrants, spawned);
        return true;
    }

    public void investigate(ServerPlayer player, boolean disarm) {
        PlayerRules.requireDowntime(player);
        if (spec == null || spec.mode() != Mode.TRAP || !armed() || player.level() != level
                || player.distanceToSqr(Vec3.atCenterOf(worldPosition)) > (disarm ? 9 : 64)) {
            throw new IllegalStateException("No reachable armed trap");
        }
        long tick = level.getGameTime();
        if (tick - checked.getOrDefault(player.getUUID(), -40L) < 40) throw new IllegalStateException("Wait before trying again");
        var skill = disarm ? Skill.SLEIGHT_OF_HAND : Skill.INVESTIGATION;
        var sheet = PlayerSheets.getOrCreate(player);
        var result = PlayerRules.check(player, skill, disarm ? spec.disarmDc() : spec.investigationDc());
        if (disarm) {
            if (!detected.contains(player.getUUID()) || (!player.isHolding(Items.TRIPWIRE_HOOK) && !player.isHolding(net.dnd5ecraft.item.ModItems.THIEVES_TOOLS))) {
                throw new IllegalStateException("Locate the trap and hold a tripwire hook as thieves’ tools");
            }
            if (sheet.background().tools().contains("thieves_tools")) {
                result = new CheckResult(result.naturalRoll(),
                        Ability.modifier(sheet.score(Ability.DEXTERITY))
                                + ProficiencyLevel.bonusAtLevel(sheet.level()), result.difficulty());
            } else result = new CheckResult(result.naturalRoll(),
                    Ability.modifier(sheet.score(Ability.DEXTERITY)), result.difficulty());
        }
        checked.put(player.getUUID(), tick);
        PlayerRules.show(player, PlayerRules.label("skill", skill.name()), result);
        if (!result.succeeds()) return;
        if (disarm) {
            occupancy.setArmed(false);
            setChanged();
        } else if (detected.add(player.getUUID())) detect(player);
    }

    private void detect(ServerPlayer viewer) {
        ModPackets.glint(viewer, worldPosition,
                Component.translatable("dnd-5e-craft.zone.detected"));
    }

    private static int dc(CompoundTag tag, String key, int fallback) {
        return tag.contains(key, Tag.TAG_INT) ? tag.getInt(key) : fallback;
    }

    private static BlockPos block(Cell cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }

    private static Cell cell(BlockPos pos) { return new Cell(pos.getX(), pos.getY(), pos.getZ()); }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (spec == null) return;
        tag.putLong("Min", new BlockPos(spec.min().x(), spec.min().y(), spec.min().z()).asLong());
        tag.putLong("Max", new BlockPos(spec.max().x(), spec.max().y(), spec.max().z()).asLong());
        tag.putString("Mode", spec.mode().name());
        tag.putInt("Cooldown", spec.cooldownTicks());
        tag.putBoolean("OneShot", spec.oneShot());
        tag.putInt("Damage", spec.damage());
        tag.putString("Ambusher", spec.ambusher().id());
        tag.putInt("Ambushers", spec.ambushers());
        tag.putInt("PerceptionDC", spec.perceptionDc());
        tag.putInt("InvestigationDC", spec.investigationDc());
        tag.putInt("DisarmDC", spec.disarmDc());
        tag.putInt("SaveDC", spec.saveDc());
        tag.putString("Title", spec.title());
        tag.putBoolean("Armed", occupancy.armed());
        tag.putLong("Next", occupancy.nextActivation());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        spec = null;
        occupancy = new TriggerOccupancy(false, 0);
        if (!tag.contains("Min", Tag.TAG_LONG) || !tag.contains("Max", Tag.TAG_LONG)) return;
        try {
            TriggerZoneSpec loaded = new TriggerZoneSpec(cell(BlockPos.of(tag.getLong("Min"))),
                    cell(BlockPos.of(tag.getLong("Max"))), Mode.valueOf(tag.getString("Mode")), tag.getInt("Cooldown"),
                    tag.getBoolean("OneShot"), tag.getInt("Damage"), BestiaryPreset.fromId(tag.getString("Ambusher")),
                    tag.getInt("Ambushers"), dc(tag, "PerceptionDC", 14), dc(tag, "InvestigationDC", 12),
                    dc(tag, "DisarmDC", 15), dc(tag, "SaveDC", 13), tag.getString("Title"));
            if (!loaded.contains(cell(worldPosition))) throw new IllegalArgumentException("Zone marker outside bounds");
            var savedOccupancy = new TriggerOccupancy(tag.getBoolean("Armed"), tag.getLong("Next"));
            spec = loaded;
            occupancy = savedOccupancy;
        } catch (IllegalArgumentException invalid) {
            org.slf4j.LoggerFactory.getLogger(TriggerZoneBlockEntity.class).warn("Ignored invalid trigger zone at {}", worldPosition, invalid);
        }
    }
}
