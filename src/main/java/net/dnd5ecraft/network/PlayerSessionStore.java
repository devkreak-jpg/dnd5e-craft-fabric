package net.dnd5ecraft.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Main-thread session state belongs to a player UUID across entity replacements. */
public final class PlayerSessionStore<T> {
    private final Map<UUID, T> sessions = new HashMap<>();

    public T getOrCreate(UUID player, Supplier<T> create) {
        return sessions.computeIfAbsent(player, id -> create.get());
    }

    public void remove(UUID player) { sessions.remove(player); }
    void clear() { sessions.clear(); }
}
