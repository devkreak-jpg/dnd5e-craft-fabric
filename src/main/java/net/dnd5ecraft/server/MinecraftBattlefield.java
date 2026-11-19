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
