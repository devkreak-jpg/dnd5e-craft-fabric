package net.dnd5ecraft.entity;

public enum NpcBehaviorMode {
    STATIONARY, WANDER, PATROL, DM_COMMANDED;

    public static NpcBehaviorMode saved(String value) {
        try { return valueOf(value); }
        catch (IllegalArgumentException invalid) { return WANDER; }
    }
}
