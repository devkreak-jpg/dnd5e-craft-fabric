package net.dnd5ecraft.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

public final class DiceDisplaySpawner {
    private static final String EXPIRY_TAG = "dnd-5e-craft.die.";
    private static final Map<ServerLevel, Map<UUID, Long>> TRACKED = new WeakHashMap<>();

    private DiceDisplaySpawner() {}

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register(DiceDisplaySpawner::track);
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            var tracked = TRACKED.get(level);
            if (tracked != null) tracked.remove(entity.getUUID());
        });
        ServerTickEvents.END_WORLD_TICK.register(DiceDisplaySpawner::tick);
    }

    public static void spawn(ServerLevel level, Entity roller, int naturalRoll) {
        if (naturalRoll < 1 || naturalRoll > 20) throw new IllegalArgumentException("A d20 result must be between 1 and 20");
        Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(level);
        if (display == null) return;
        CompoundTag data = new CompoundTag();
        data.putString("text", Component.Serializer.toJson(Component.literal("d20  " + naturalRoll)));
        data.putString("billboard", "center");
        data.putBoolean("shadow", true);
        data.putInt("background", 0x990A0B0E);
        display.load(data);
        display.setNoGravity(true);
        display.addTag(EXPIRY_TAG + (level.getGameTime() + 60L));
        display.moveTo(roller.getX(), roller.getY() + roller.getBbHeight() + .4, roller.getZ(), 0, 0);
        level.addFreshEntity(display);
    }

    private static void track(Entity entity, ServerLevel level) {
        if (!(entity instanceof Display.TextDisplay)) return;
        for (String tag : entity.getTags()) {
            if (!tag.startsWith(EXPIRY_TAG)) continue;
            try {
                long expiration = Long.parseLong(tag.substring(EXPIRY_TAG.length()));
                TRACKED.computeIfAbsent(level, ignored -> new HashMap<>()).put(entity.getUUID(), expiration);
                break;
            } catch (NumberFormatException ignored) {
                // An invalid tag is not a scheduled display.
            }
        }
    }

    private static void tick(ServerLevel level) {
        var tracked = TRACKED.get(level);
        if (tracked == null || tracked.isEmpty()) return;
        var expired = tracked.entrySet().stream().filter(e -> e.getValue() <= level.getGameTime())
                .map(Map.Entry::getKey).toList();
        for (UUID id : expired) {
            tracked.remove(id);
            Entity display = level.getEntity(id);
            if (display != null) display.discard();
        }
    }
}
