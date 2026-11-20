package net.dnd5ecraft.rules.campaign;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PartyReadyCheck {
    public enum Status { WAITING, READY, NEED_TIME }

    private final UUID id = UUID.randomUUID();
    private final String label;
    private final Map<UUID, Status> members = new LinkedHashMap<>();

    public PartyReadyCheck(String label, Collection<UUID> players) {
        this.label = label;
        players.forEach(this::join);
    }

    public UUID id() { return id; }
    public String label() { return label; }
    public Map<UUID, Status> members() { return Map.copyOf(members); }
    public void join(UUID player) { members.putIfAbsent(player, Status.WAITING); }
    public void leave(UUID player) { members.remove(player); }

    public boolean respond(UUID check, UUID player, boolean ready) {
        if (!id.equals(check) || !members.containsKey(player)) return false;
        members.put(player, ready ? Status.READY : Status.NEED_TIME);
        return true;
    }

    public boolean allReady() {
        return !members.isEmpty() && members.values().stream().allMatch(status -> status == Status.READY);
    }
}
