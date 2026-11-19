package net.dnd5ecraft.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.dnd5ecraft.combat.CombatManager;
import net.dnd5ecraft.combat.CombatantEntry;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.LightingVisionEvaluator.Light;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;

final class MinecraftBattlefield implements CombatManager.Battlefield {
    private final ServerLevel level;
    private final Map<UUID, LivingEntity> entities;

    MinecraftBattlefield(ServerLevel level, Map<UUID, LivingEntity> entities) {
        this.level = level;
        this.entities = entities;
    }

    @Override
    public boolean manualDeathSaves(CombatantEntry target) {
        return entities.get(target.id()) instanceof net.minecraft.server.level.ServerPlayer player
                && net.dnd5ecraft.network.TabletopPackets.supports(player);
    }

    @Override public void spellVisual(String id, CombatantEntry caster, CombatantEntry target) {
        var entity = entities.get(target.id());
        if (entity == null) return;
        var pos = entity.position().add(0, entity.getBbHeight() * .53, 0);
        switch (id) {
            case "shield" -> {
                for (int ring = 0; ring < 3; ring++) for (int step = 0; step < 6; step++) {
                    double angle = step * Math.PI / 3 + ring * .15;
                    level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, pos.x + Math.cos(angle) * .72,
                            pos.y + (ring - 1) * .57, pos.z + Math.sin(angle) * .72, 1, 0, 0, 0, 0);
                }
                level.playSound(null, entity.blockPosition(), net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                        net.minecraft.sounds.SoundSource.PLAYERS, .8f, .75f);
            }
            case "absorb_elements" -> CampaignMagic.visual(level, pos, net.minecraft.core.particles.ParticleTypes.ENCHANT,
                    net.minecraft.sounds.SoundEvents.RESPAWN_ANCHOR_CHARGE, 24);
            case "fire_bolt" -> CampaignMagic.visual(level, pos, net.minecraft.core.particles.ParticleTypes.FLAME, net.minecraft.sounds.SoundEvents.BLAZE_SHOOT, 18);
            case "lightning_lure" -> CampaignMagic.visual(level, pos, net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_IMPACT, 24);
            case "booming_blade", "thunderwave" -> CampaignMagic.visual(level, pos, net.minecraft.core.particles.ParticleTypes.SONIC_BOOM, net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_THUNDER, 1);
            case "cure_wounds" -> CampaignMagic.visual(level, pos, net.minecraft.core.particles.ParticleTypes.HEART, net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME, 8);
            case "faerie_fire" -> {
                CampaignMagic.visual(level, pos, net.minecraft.core.particles.ParticleTypes.WITCH, net.minecraft.sounds.SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM, 16);
                entity.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.GLOWING, 120, 0, false, false));
            }
            default -> { }
        }
    }

    @Override public void zoneVisual(String id, net.dnd5ecraft.rules.spells.AoEGeometry.Area area) {
        var origin = area.origin();
        var p = new net.minecraft.world.phys.Vec3(origin.x(), origin.y(), origin.z());
        if (id.equals("fog_cloud")) CampaignMagic.fogVisual(level, p);
        if (id.equals("grease")) CampaignMagic.visual(level, p, net.minecraft.core.particles.ParticleTypes.FALLING_HONEY, net.minecraft.sounds.SoundEvents.HONEY_BLOCK_SLIDE, 30);
    }

    @Override public boolean canUseWeapon(CombatantEntry actor, CombatantEntry.Attack attack) {
        var entity = entities.get(actor.id());
        if (entity == null || !attack.weaponKey().contains(":")) return true;
        return java.util.stream.Stream.of(entity.getMainHandItem(), entity.getOffhandItem()).anyMatch(stack ->
                !net.dnd5ecraft.item.DndWeaponItem.broken(stack)
                        && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(attack.weaponKey()));
    }

    @Override public void weaponHit(CombatantEntry actor, CombatantEntry target, CombatantEntry.Attack attack) {
        if (!(entities.get(actor.id()) instanceof net.minecraft.server.level.ServerPlayer player)) return;
        if (attack.weaponKey().equals("fist")) {
            var struck = entities.get(target.id());
            if (struck == null) return;
            level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                    level.getBlockState(struck.blockPosition().below())), struck.getX(), struck.getY() + .25, struck.getZ(), 18, .25, .25, .25, .06);
            level.playSound(null, struck.blockPosition(), net.minecraft.sounds.SoundEvents.ANVIL_LAND,
                    net.minecraft.sounds.SoundSource.PLAYERS, .43f, .72f);
            return;
        }
        if (!attack.melee() || PlayerSheets.get(player).filter(net.dnd5ecraft.rules.campaign.CampaignHero.ALEXANDER::matches).isEmpty()) return;
        var stack = java.util.stream.Stream.of(player.getMainHandItem(), player.getOffhandItem())
                .filter(held -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(attack.weaponKey()))
                .filter(held -> !held.isEnchanted() && net.dnd5ecraft.combat.PlayerWeaponProfiles.stats(held)
                        .filter(stats -> stats.has(net.dnd5ecraft.rules.equipment.WeaponProperty.MAGICAL)).isEmpty())
                .findFirst().orElse(null);
        if (stack == null) return;
        stack.getOrCreateTag().putBoolean("DndBroken", true);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, player.getX(), player.getY() + 1,
                player.getZ(), 15, .3, .3, .3, .12);
        level.playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.ITEM_BREAK,
                net.minecraft.sounds.SoundSource.PLAYERS, .8f, .68f);
    }

    @Override public Cell echoCell(CombatantEntry actor) {
        if (!(entities.get(actor.id()) instanceof net.minecraft.server.level.ServerPlayer player)) return null;
        var echo = CampaignActions.companion(player, net.dnd5ecraft.entity.EchoAvatarEntity.class);
        return echo == null ? null : TacticalCombat.cell(echo.blockPosition());
    }

    @Override public void echoSwing(CombatantEntry actor) {
        if (!(entities.get(actor.id()) instanceof net.minecraft.server.level.ServerPlayer player)) return;
        var echo = CampaignActions.companion(player, net.dnd5ecraft.entity.EchoAvatarEntity.class);
        if (echo == null) return;
        echo.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        var at = echo.position();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.ASH, at.x, at.y + .8, at.z, 14, .25, .35, .25, .02);
    }

    @Override public boolean nettable(CombatantEntry target) {
        var entity = entities.get(target.id());
        return entity != null && entity.getBbWidth() <= 2.1f;
    }

    @Override
    public boolean canOccupy(Cell cell) {
        BlockPos position = position(cell);
        if (!loaded(position) || level.isOutsideBuildHeight(position.above())) return false;
        BlockPos floor = position.below();
        return level.getBlockState(position).getCollisionShape(level, position).isEmpty()
                && level.getBlockState(position.above()).getCollisionShape(level, position.above()).isEmpty()
                && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP);
    }

    @Override
    public boolean canOccupy(CombatantEntry mover, Cell cell) {
        if (!canOccupy(cell)) return false;
        LivingEntity entity = entities.get(mover.id());
        var moved = entity.getBoundingBox().move(cell.x() + .5 - entity.getX(), cell.y() - entity.getY(), cell.z() + .5 - entity.getZ());
        return level.noCollision(entity, moved);
    }

    @Override public boolean canSwap(CombatantEntry mover, CombatantEntry other) {
        Cell destination = other.cell();
        if (!canOccupy(destination)) return false;
        var entity = entities.get(mover.id());
        var counterpart = entities.get(other.id());
        if (entity == null || counterpart == null) return false;
        var bounds = entity.getBoundingBox().move(destination.x() + .5 - entity.getX(), destination.y() - entity.getY(),
                destination.z() + .5 - entity.getZ());
        return !level.getBlockCollisions(entity, bounds).iterator().hasNext()
                && level.getEntities(entity, bounds, candidate -> candidate != counterpart && candidate.canBeCollidedWith()).isEmpty();
    }

    @Override
    public List<Cell> neighbors(Cell from) {
        List<Cell> result = new ArrayList<>(24);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy : new int[]{0, 1, -1}) {
                    Cell to = new Cell(from.x() + dx, from.y() + dy, from.z() + dz);
                    if (canOccupy(to)) result.add(to);
                }
            }
        }
        return result;
    }

    @Override
    public boolean difficult(Cell cell) {
        BlockPos pos = position(cell);
        if (!loaded(pos)) return true;
        var state = level.getBlockState(pos);
        return !state.getFluidState().isEmpty() || state.is(BlockTags.SNOW) || level.getBlockState(pos.below()).is(BlockTags.SAND);
    }

    @Override
    public boolean blocksCover(Cell cell) {
        BlockPos pos = position(cell);
        return !loaded(pos) || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    @Override
    public boolean blocksSight(Cell cell) {
        if (CampaignMagic.obscures(level, position(cell))) return true;
        BlockPos pos = position(cell);
        return !loaded(pos) || level.getBlockState(pos).isSolidRender(level, pos);
    }

    @Override
    public Light lightAt(Cell cell) {
        BlockPos pos = position(cell);
        if (!loaded(pos)) return Light.DARK;
        if (entities.values().stream().anyMatch(entity -> entity.blockPosition().equals(pos)
                && entity.getTags().contains(TacticalCombat.MAGIC_DARKNESS_TAG))) return Light.MAGICAL_DARKNESS;
        int brightness = level.getMaxLocalRawBrightness(pos);
        return brightness >= 9 ? Light.BRIGHT : brightness >= 4 ? Light.DIM : Light.DARK;
    }

    @Override
    public boolean suppressesRegeneration(CombatantEntry target) {
        if (target.bestiary().filter(state -> state.preset() == BestiaryPreset.VAMPIRE || state.preset() == BestiaryPreset.VAMPIRE_SPAWN).isEmpty()) return false;
        LivingEntity entity = entities.get(target.id());
        return (level.isDay() && level.canSeeSky(entity.blockPosition()))
                || (entity.isInWater() && !level.getFluidState(entity.blockPosition()).isSource());
    }

    private boolean loaded(BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.isLoaded(pos) && level.getWorldBorder().isWithinBounds(pos);
    }

    private static BlockPos position(Cell cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }
}
