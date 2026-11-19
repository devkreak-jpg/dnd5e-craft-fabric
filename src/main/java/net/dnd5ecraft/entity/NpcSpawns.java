package net.dnd5ecraft.entity;

import java.util.Optional;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class NpcSpawns {
    private NpcSpawns() {}

    public static Optional<DndNpcEntity> spawn(ServerLevel level, BlockPos position, BestiaryPreset preset, NpcAttitude attitude) {
        if (!level.isLoaded(position) || !level.getWorldBorder().isWithinBounds(position)
                || level.isOutsideBuildHeight(position)) return Optional.empty();
        DndNpcEntity npc = ModEntities.DND_NPC.create(level);
        if (npc == null) return Optional.empty();
        npc.initializePreset(preset, preset.civilian() ? NpcAttitude.FRIENDLY : attitude);
        npc.moveTo(position.getX() + .5, position.getY(), position.getZ() + .5, 0, 0);
        if (!level.noCollision(npc)) return Optional.empty();
        return level.addFreshEntity(npc) ? Optional.of(npc) : Optional.empty();
    }
}
