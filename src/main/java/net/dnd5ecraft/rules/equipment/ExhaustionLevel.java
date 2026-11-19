package net.dnd5ecraft.rules.equipment;

public enum ExhaustionLevel {
    NONE(0), ONE(1), TWO(2), THREE(3), FOUR(4), FIVE(5), SIX(6);

    private final int level;

    ExhaustionLevel(int level) {
        this.level = level;
    }

    public int level() { return level; }

    public static ExhaustionLevel of(int level) {
        if (level < 0 || level > 6) {
            throw new IllegalArgumentException("Exhaustion must be between 0 and 6");
        }
        return values()[level];
    }

    public ExhaustionLevel gainLevel() {
        return of(Math.min(6, level + 1));
    }

    public ExhaustionLevel afterLongRest(boolean consumedFoodAndDrink) {
        return consumedFoodAndDrink && !dead() ? of(Math.max(0, level - 1)) : this;
    }

    public boolean abilityCheckDisadvantage() { return level >= 1; }
    public boolean attackDisadvantage() { return level >= 3; }
    public boolean savingThrowDisadvantage() { return level >= 3; }
    public boolean dead() { return level == 6; }

    public int speedFeet(int normalSpeed) {
        if (normalSpeed < 0) {
            throw new IllegalArgumentException("Speed cannot be negative");
        }
        return level >= 5 ? 0 : level >= 2 ? normalSpeed / 2 : normalSpeed;
    }

    public int maxHitPoints(int normalMaximum) {
        if (normalMaximum < 1) {
            throw new IllegalArgumentException("Hit point maximum must be positive");
        }
        return level >= 4 ? normalMaximum / 2 : normalMaximum;
    }
}
