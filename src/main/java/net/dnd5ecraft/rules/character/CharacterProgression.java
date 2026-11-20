package net.dnd5ecraft.rules.character;

public final class CharacterProgression {
    private static final int[] XP = {0, 300, 900, 2700, 6500, 14000, 23000, 34000, 48000, 64000,
            85000, 100000, 120000, 140000, 165000, 195000, 225000, 265000, 305000, 355000};

    private CharacterProgression() {}

    public static int threshold(int level) {
        if (level < 1 || level > 20) throw new IllegalArgumentException("Invalid character level");
        return XP[level - 1];
    }
}
