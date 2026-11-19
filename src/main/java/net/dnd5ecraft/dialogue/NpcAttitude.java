package net.dnd5ecraft.dialogue;

public enum NpcAttitude {
    FRIENDLY(-5), INDIFFERENT(0), HOSTILE(5);

    private final int difficultyAdjustment;

    NpcAttitude(int difficultyAdjustment) {
        this.difficultyAdjustment = difficultyAdjustment;
    }

    public static NpcAttitude fromIndex(int index) {
        NpcAttitude[] attitudes = values();
        return index >= 0 && index < attitudes.length ? attitudes[index] : INDIFFERENT;
    }

    public int adjustDifficulty(int base) {
        if (base < 0 || base > 30) throw new IllegalArgumentException("Dialogue DC must be between 0 and 30");
        return Math.max(0, Math.min(30, base + difficultyAdjustment));
    }
}
