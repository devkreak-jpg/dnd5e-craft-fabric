package net.dnd5ecraft.server;

import net.dnd5ecraft.entity.DndNpcEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

public final class DmMode {
    private static final java.util.Map<java.util.UUID, DndNpcEntity> POSSESSED = new java.util.HashMap<>();
    private static final String TAG_PREFIX = "dnd-5e-craft.dm.";

    private DmMode() {}

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> release(handler.player));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> leave(handler.player));
        ServerPlayerEvents.COPY_FROM.register((previous, current, alive) ->
                previous.getTags().stream().filter(tag -> tag.startsWith(TAG_PREFIX)).forEach(current::addTag));
        ServerPlayerEvents.AFTER_RESPAWN.register((previous, current, alive) -> {
            var npc = POSSESSED.remove(previous.getUUID());
            if (npc == null) return;
            if (npc.isAlive() && npc.level() == current.level() && current.hasPermissions(2) && active(current)) {
                POSSESSED.put(current.getUUID(), npc);
                current.setCamera(npc);
            } else npc.setPossessingDm(null);
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, previous, destination) -> {
            if (POSSESSED.containsKey(player.getUUID())) release(player);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (active(player) && !player.hasPermissions(2)) leave(player);
                DndNpcEntity npc = POSSESSED.get(player.getUUID());
                if (npc != null && (player.getCamera() != npc || !npc.isAlive() || player.level() != npc.level())) release(player);
            }
        });
    }

    public static boolean active(ServerPlayer player) {
        return player.getTags().stream().anyMatch(tag -> tag.startsWith(TAG_PREFIX));
    }

    public static boolean toggle(ServerPlayer player) {
        if (!player.hasPermissions(2)) throw new IllegalStateException("Operator permissions are required");
        if (leave(player)) return false;
        if (TacticalCombat.isLocked(player)) throw new IllegalStateException("Leave the encounter before entering DM mode");
        GameType previous = player.gameMode.getGameModeForPlayer();
        if (!player.addTag(TAG_PREFIX + previous.getId())) throw new IllegalStateException("Unable to record game mode");
        player.setGameMode(GameType.SPECTATOR);
        player.sendSystemMessage(Component.translatable("dnd-5e-craft.dm.enabled"));
        return true;
    }

    private static boolean leave(ServerPlayer player) {
        var tags = player.getTags().stream().filter(tag -> tag.startsWith(TAG_PREFIX)).sorted().toList();
        if (tags.isEmpty()) return false;
        int previous;
        try {
            previous = Integer.parseInt(tags.get(0).substring(TAG_PREFIX.length()));
        } catch (NumberFormatException invalidTag) {
            previous = GameType.SURVIVAL.getId();
        }
        tags.forEach(player::removeTag);
        release(player);
        player.setGameMode(GameType.byId(previous));
        return true;
    }

    static boolean controls(ServerPlayer player, DndNpcEntity npc) {
        return player.hasPermissions(2) && active(player) && player.getCamera() == npc && player.level() == npc.level()
                && npc.possessingDmUuid().filter(player.getUUID()::equals).isPresent();
    }

    public static void viewThrough(ServerPlayer player, DndNpcEntity npc) {
        if (!player.hasPermissions(2) || !active(player) || npc.level() != player.level() || player.distanceToSqr(npc) > 1024
                || !npc.isAlive()) {
            throw new IllegalStateException("Enter DM mode and choose an NPC within 32 blocks");
        }
        if (npc.possessingDmUuid().filter(id -> !id.equals(player.getUUID())).isPresent()) {
            throw new IllegalStateException("This NPC is already possessed");
        }
        release(player);
        npc.setPossessingDm(player.getUUID());
        POSSESSED.put(player.getUUID(), npc);
        player.setCamera(npc);
    }
    public static void release(ServerPlayer player) {
        DndNpcEntity npc = POSSESSED.remove(player.getUUID());
        if (npc != null && npc.possessingDmUuid().filter(player.getUUID()::equals).isPresent()) npc.setPossessingDm(null);
        player.setCamera(player);
    }
}
