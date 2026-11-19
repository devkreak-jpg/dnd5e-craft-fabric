package net.dnd5ecraft.rules.campaign;

import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;

public final class TacticalFeedback {
    public static final int PING_RANGE = 48;
    public static final int AUDIENCE = 96;
    public static final long PING_COOLDOWN = 1_000_000_000L;

    private TacticalFeedback() {}

    public static double hitChance(int armor, int bonus, RollMode mode) {
        int needed = (int)Math.max(2, Math.min(20, (long)armor - bonus));
        double p = (21 - needed) / 20.0;
        return switch (mode) {
            case ADVANTAGE -> 1 - (1 - p) * (1 - p);
            case DISADVANTAGE -> p * p;
            case NORMAL -> p;
        };
    }

    public static int rulerBlocks(double distance) { return (int)Math.round(Math.max(0, distance)); }
    public static int rulerFeet(int blocks) { return blocks * 5; }

    public static final class PingLimiter {
        private boolean used;
        private long last;

        public boolean take(long now) {
            if (used && now - last < PING_COOLDOWN) return false;
            used = true;
            last = now;
            return true;
        }
    }
}
