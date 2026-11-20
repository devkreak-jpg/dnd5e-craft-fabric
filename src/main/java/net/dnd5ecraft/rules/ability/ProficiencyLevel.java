package net.dnd5ecraft.rules.ability;

public enum ProficiencyLevel {
    NONE, HALF, PROFICIENT, EXPERTISE;

    public int contribution(int characterLevel) {
        int bonus = bonusAtLevel(characterLevel);
        return switch (this) {
            case NONE -> 0;
            case HALF -> bonus / 2;
            case PROFICIENT -> bonus;
            case EXPERTISE -> 2 * bonus;
        };
    }

    public static int bonusAtLevel(int characterLevel) {
        if (characterLevel < 1 || characterLevel > 20) {
            throw new IllegalArgumentException("Character level must be between 1 and 20");
        }
        return 2 + (characterLevel - 1) / 4;
    }
}
