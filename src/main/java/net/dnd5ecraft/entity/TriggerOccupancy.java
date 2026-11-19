package net.dnd5ecraft.entity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TriggerOccupancy {
    private Set<UUID> inside = Set.of();
    private boolean armed;
    private long nextActivation;

    public TriggerOccupancy(boolean armed, long nextActivation) {
        if (nextActivation < 0) throw new IllegalArgumentException("Invalid trigger time");
        this.armed = armed;
        this.nextActivation = nextActivation;
    }

    public List<UUID> entrants(List<UUID> present, long gameTime) {
        Set<UUID> previous = inside;
        inside = Set.copyOf(present);
        if (!armed || gameTime < nextActivation) return List.of();
        return present.stream().distinct().filter(id -> !previous.contains(id)).toList();
    }

    public void activated(TriggerZoneSpec spec, long gameTime) {
        nextActivation = Math.addExact(gameTime, spec.cooldownTicks());
        if (spec.oneShot()) armed = false;
    }

    public void setArmed(boolean enabled) {
        armed = enabled;
    }

    public boolean armed() { return armed; }
    public long nextActivation() { return nextActivation; }
}
