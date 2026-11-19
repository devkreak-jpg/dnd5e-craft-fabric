package net.dnd5ecraft.rules.character;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.LoggerFactory;

public final class PlayerSheetStorage extends SavedData {
    private static final String SAVE_NAME = "dnd-5e-craft-characters";
    private final Map<UUID, CharacterSheetData> sheets = new HashMap<>();

    private static final String REST_TIMES = "RestTimes";
    private final Map<UUID, Long> shortRests = new HashMap<>();
    private final Map<UUID, Long> longRests = new HashMap<>();

    public boolean canRest(UUID player, long now, boolean longRest) {
        Map<UUID, Long> times = longRest ? longRests : shortRests;
        return now - times.getOrDefault(player, -24_000L) >= (longRest ? 24_000L : 1000L);
    }

    public void recordRest(UUID player, long now, boolean longRest) {
        shortRests.put(player, now);
        if (longRest) longRests.put(player, now);
        setDirty();
    }

    public static PlayerSheetStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PlayerSheetStorage::load, PlayerSheetStorage::new, SAVE_NAME);
    }

    public Optional<CharacterSheetData> find(UUID player) { return Optional.ofNullable(sheets.get(player)); }

    public void put(UUID player, CharacterSheetData sheet) {
        if (!sheet.equals(sheets.put(player, sheet))) setDirty();
    }

    public static PlayerSheetStorage load(CompoundTag data) {
        var storage = new PlayerSheetStorage();
        for (String key : data.getAllKeys()) {
            if (key.equals(REST_TIMES)) continue;
            try {
                if (data.contains(key, Tag.TAG_COMPOUND)) {
                    storage.sheets.put(UUID.fromString(key), CharacterSheetData.fromNbt(data.getCompound(key)));
                }
            } catch (IllegalArgumentException invalid) {
                LoggerFactory.getLogger(PlayerSheetStorage.class).warn("Ignoring invalid D&D character record");
            }
        }
        var rests = data.getCompound(REST_TIMES);
        for (UUID player : storage.sheets.keySet()) {
            var times = rests.getCompound(player.toString());
            if (times.contains("Short", Tag.TAG_LONG)) storage.shortRests.put(player, times.getLong("Short"));
            if (times.contains("Long", Tag.TAG_LONG)) storage.longRests.put(player, times.getLong("Long"));
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag data) {
        sheets.forEach((player, sheet) -> data.put(player.toString(), sheet.toNbt()));
        var rests = new CompoundTag();
        shortRests.forEach((player, time) -> {
            var times = new CompoundTag();
            times.putLong("Short", time);
            if (longRests.containsKey(player)) times.putLong("Long", longRests.get(player));
            rests.put(player.toString(), times);
        });
        data.put(REST_TIMES, rests);
        return data;
    }
}
