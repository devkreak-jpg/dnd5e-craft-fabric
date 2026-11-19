package net.dnd5ecraft.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.dnd5ecraft.network.ModPackets;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

public final class CinematicFreeze {
    private record Scene(long until, Set<UUID> party, Map<BlockPos, BlockState> doors) {}
    private static final Map<ServerLevel, Scene> SCENES = new HashMap<>();
    private static final Set<ServerLevel> MANUAL = new java.util.HashSet<>();

    private CinematicFreeze() {}

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(level -> {
            Scene scene = SCENES.get(level);
            if (scene != null && level.getGameTime() >= scene.until()) end(level);
            if (active(level)) level.players().stream().filter(CinematicFreeze::frozen)
                    .forEach(player -> player.setDeltaMovement(Vec3.ZERO));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SCENES.keySet().stream().filter(level -> level.getServer() == server).toList().forEach(CinematicFreeze::end);
            MANUAL.removeIf(level -> level.getServer() == server);
        });
    }

    public static boolean active(ServerLevel level) {
        return SCENES.containsKey(level) || MANUAL.contains(level);
    }

    public static boolean frozen(ServerPlayer player) {
        Scene scene = SCENES.get(player.serverLevel());
        return !DmMode.active(player) && !player.isSpectator() && (MANUAL.contains(player.serverLevel())
                || scene != null && scene.party().contains(player.getUUID()));
    }

    public static boolean toggle(ServerPlayer operator) {
        if (!operator.hasPermissions(2)) throw new IllegalStateException("Operator permissions are required");
        ServerLevel level = operator.serverLevel();
        if (MANUAL.remove(level)) return false;
        MANUAL.add(level);
        return true;
    }

    public static void begin(ServerLevel level, java.util.List<ServerPlayer> party, BlockPos min, BlockPos max,
            Component title) {
        end(level);
        Map<BlockPos, BlockState> doors = new HashMap<>();
        for (BlockPos position : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(position)) continue;
            BlockState state = level.getBlockState(position);
            if (state.getBlock() instanceof DoorBlock door && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    && state.getValue(DoorBlock.OPEN)) {
                doors.put(position.immutable(), state);
                door.setOpen(null, level, state, position, false);
            }
        }
        SCENES.put(level, new Scene(level.getGameTime() + 60,
                party.stream().map(ServerPlayer::getUUID).collect(Collectors.toUnmodifiableSet()), Map.copyOf(doors)));
        party.forEach(player -> ModPackets.banner(player, title));
    }

    private static void end(ServerLevel level) {
        Scene scene = SCENES.remove(level);
        if (scene == null) return;
        scene.doors().forEach((position, original) -> {
            BlockState current = level.getBlockState(position);
            if (current.equals(original.setValue(DoorBlock.OPEN, false))) {
                ((DoorBlock) current.getBlock()).setOpen(null, level, current, position, true);
            }
        });
    }
}
