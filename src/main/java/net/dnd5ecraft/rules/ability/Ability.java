package net.dnd5ecraft.rules.ability;

public enum Ability {
    STRENGTH, DEXTERITY, CONSTITUTION, INTELLIGENCE, WISDOM, CHARISMA;

    public static final Ability STR = STRENGTH, DEX = DEXTERITY, CON = CONSTITUTION,
            INT = INTELLIGENCE, WIS = WISDOM, CHA = CHARISMA;

    public static Ability fromId(String name) {
        String key = name.toUpperCase(java.util.Locale.ROOT);
        return switch (key) {
            case "STR" -> STRENGTH;
            case "DEX" -> DEXTERITY;
            case "CON" -> CONSTITUTION;
            case "INT" -> INTELLIGENCE;
            case "WIS" -> WISDOM;
            case "CHA" -> CHARISMA;
            default -> valueOf(key);
        };
    }

    public static int modifier(int score) {
        if (score < 1 || score > 30) {
            throw new IllegalArgumentException("An ability score must be between 1 and 30");
        }
        return Math.floorDiv(score - 10, 2);
    }
}
