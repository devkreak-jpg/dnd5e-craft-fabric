package net.dnd5ecraft.rules.character;

import java.util.Map;
import static net.dnd5ecraft.rules.character.ClassType.*;

public enum ClassFeature {
    RAGE, BARDIC_INSPIRATION, CHANNEL_DIVINITY, WILD_SHAPE, SECOND_WIND, ACTION_SURGE,
    KI_POINTS, LAY_ON_HANDS, SORCERY_POINTS, ARCANE_RECOVERY, ARCANE_WARD, RELENTLESS_ENDURANCE, DRACONIC_BREATH, LUCKY;

    public int capacity(Map<ClassType, Integer> levels, int charismaModifier, RaceType race) {
        int barbarian = level(levels, BARBARIAN);
        int cleric = level(levels, CLERIC);
        int fighter = level(levels, FIGHTER);
        return switch (this) {
            case RAGE -> barbarian == 0 ? 0 : barbarian < 3 ? 2 : barbarian < 6 ? 3 : barbarian < 12 ? 4
                    : barbarian < 17 ? 5 : barbarian < 20 ? 6 : Integer.MAX_VALUE;
            case BARDIC_INSPIRATION -> level(levels, BARD) == 0 ? 0 : Math.max(1, charismaModifier);
            case CHANNEL_DIVINITY -> Math.max(cleric < 2 ? 0 : cleric < 6 ? 1 : cleric < 18 ? 2 : 3,
                    level(levels, PALADIN) >= 3 ? 1 : 0);
            case WILD_SHAPE -> level(levels, DRUID) == 20 ? Integer.MAX_VALUE : level(levels, DRUID) >= 2 ? 2 : 0;
            case SECOND_WIND -> fighter > 0 ? 1 : 0;
            case ACTION_SURGE -> fighter < 2 ? 0 : fighter < 17 ? 1 : 2;
            case KI_POINTS -> level(levels, MONK) >= 2 ? level(levels, MONK) : 0;
            case LAY_ON_HANDS -> level(levels, PALADIN) * 5;
            case SORCERY_POINTS -> level(levels, SORCERER) >= 2 ? level(levels, SORCERER) : 0;
            case LUCKY -> 3;
            case ARCANE_WARD -> level(levels, WIZARD) >= 2 ? 1 : 0;
            case ARCANE_RECOVERY -> level(levels, WIZARD) > 0 ? 1 : 0;
            case DRACONIC_BREATH -> race == RaceType.DRAGONBORN ? 1 : 0;
            case RELENTLESS_ENDURANCE -> race == RaceType.HALF_ORC ? 1 : 0;
        };
    }

    public boolean shortRest(Map<ClassType, Integer> levels) {
        return switch (this) {
            case BARDIC_INSPIRATION -> level(levels, BARD) >= 5;
            case CHANNEL_DIVINITY, WILD_SHAPE, SECOND_WIND, ACTION_SURGE, KI_POINTS, DRACONIC_BREATH -> true;
            default -> false;
        };
    }

    private static int level(Map<ClassType, Integer> levels, ClassType type) { return levels.getOrDefault(type, 0); }
}
