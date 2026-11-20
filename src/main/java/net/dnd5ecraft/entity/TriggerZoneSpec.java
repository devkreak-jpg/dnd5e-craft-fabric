package net.dnd5ecraft.entity;

import java.util.Objects;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;

public record TriggerZoneSpec(Cell min, Cell max, Mode mode, int cooldownTicks, boolean oneShot,
                              int damage, BestiaryPreset ambusher, int ambushers, int perceptionDc,
                              int investigationDc, int disarmDc, int saveDc, String title) {
    public enum Mode { TRAP, AMBUSH, AMBUSH_CUTSCENE }

    public TriggerZoneSpec(Cell min, Cell max, Mode mode, int cooldownTicks, boolean oneShot,
            int damage, BestiaryPreset ambusher, int ambushers) {
        this(min, max, mode, cooldownTicks, oneShot, damage, ambusher, ambushers, 14, 12, 15, 13, "");
    }

    public TriggerZoneSpec {
        Objects.requireNonNull(min);
        Objects.requireNonNull(max);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(ambusher);
        if (!axisValid(min.x(), max.x()) || !axisValid(min.y(), max.y()) || !axisValid(min.z(), max.z())
                || cooldownTicks < 5 || cooldownTicks > 72_000 || damage < 1 || damage > 100
                || ambushers < 1 || ambushers > 8 || mode != Mode.TRAP && ambusher.civilian() || !validDc(perceptionDc) || !validDc(investigationDc)
                || !validDc(disarmDc) || !validDc(saveDc) || Objects.requireNonNull(title).length() > 100) {
            throw new IllegalArgumentException("Invalid trigger zone dimensions or settings");
        }
    }

    private static boolean validDc(int dc) { return dc >= 1 && dc <= 40; }

    private static boolean axisValid(int min, int max) { return min <= max && (long) max - min < 32; }

    public static TriggerZoneSpec between(Cell a, Cell b, Mode mode) {
        Cell min = new Cell(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()));
        Cell max = new Cell(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
        return new TriggerZoneSpec(min, max, mode, 100, mode != Mode.TRAP, 6, BestiaryPreset.GOBLIN, 2);
    }

    public boolean contains(Cell point) {
        return point.x() >= min.x() && point.x() <= max.x() && point.y() >= min.y() && point.y() <= max.y()
                && point.z() >= min.z() && point.z() <= max.z();
    }
}
