package net.dnd5ecraft.rules.character;

import static net.dnd5ecraft.rules.character.ClassType.*;

public enum SubclassType {
    BERSERKER(BARBARIAN, 3),
    LORE(BARD, 3),
    LIFE_DOMAIN(CLERIC, 1),
    LAND(DRUID, 2),
    MOON(DRUID, 2),
    CHAMPION(FIGHTER, 3),
    OPEN_HAND(MONK, 3),
    DEVOTION(PALADIN, 3),
    HUNTER(RANGER, 3),
    THIEF(ROGUE, 3),
    DRACONIC_BLOODLINE(SORCERER, 1),
    FIEND(WARLOCK, 1),
    EVOCATION(WIZARD, 2),
    ABJURATION(WIZARD, 2),
    ARTILLERIST(ARTIFICER, 3),
    WORLDBREAKER(BARBARIAN, 3),
    ECHO_KNIGHT(FIGHTER, 3);

    private final ClassType parent;
    private final int selectionLevel;

    SubclassType(ClassType parent, int selectionLevel) {
        this.parent = parent;
        this.selectionLevel = selectionLevel;
    }

    public ClassType parent() { return parent; }
    public int selectionLevel() { return selectionLevel; }
}
