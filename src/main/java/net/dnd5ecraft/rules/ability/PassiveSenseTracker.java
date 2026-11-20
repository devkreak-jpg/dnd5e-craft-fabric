package net.dnd5ecraft.rules.ability;

import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;

public final class PassiveSenseTracker {
    private PassiveSenseTracker() {}

    public static int score(int checkModifier, RollMode mode) {
        return 10 + checkModifier + switch (mode) {
            case NORMAL -> 0;
            case ADVANTAGE -> 5;
            case DISADVANTAGE -> -5;
        };
    }

    public static boolean notices(int stealthTotal, int perceptionModifier, RollMode mode) {
        return score(perceptionModifier, mode) >= stealthTotal;
    }
}
