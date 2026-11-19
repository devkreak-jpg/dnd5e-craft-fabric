package net.dnd5ecraft.rules.character;

import java.util.List;
import java.util.Set;
import net.dnd5ecraft.rules.ability.Ability;

import static net.dnd5ecraft.rules.ability.Ability.*;

public enum ClassType {
    BARBARIAN(12, STRENGTH, CONSTITUTION, Set.of(STRENGTH)),
    BARD(8, DEXTERITY, CHARISMA, Set.of(CHARISMA)),
    CLERIC(8, WISDOM, CHARISMA, Set.of(WISDOM)),
    DRUID(8, INTELLIGENCE, WISDOM, Set.of(WISDOM)),
    FIGHTER(10, STRENGTH, CONSTITUTION, Set.of(STRENGTH), Set.of(DEXTERITY)),
    MONK(8, STRENGTH, DEXTERITY, Set.of(DEXTERITY, WISDOM)),
    PALADIN(10, WISDOM, CHARISMA, Set.of(STRENGTH, CHARISMA)),
    RANGER(10, STRENGTH, DEXTERITY, Set.of(DEXTERITY, WISDOM)),
    ROGUE(8, DEXTERITY, INTELLIGENCE, Set.of(DEXTERITY)),
    SORCERER(6, CONSTITUTION, CHARISMA, Set.of(CHARISMA)),
    WARLOCK(8, WISDOM, CHARISMA, Set.of(CHARISMA)),
    WIZARD(6, INTELLIGENCE, WISDOM, Set.of(INTELLIGENCE)),
    ARTIFICER(8, CONSTITUTION, INTELLIGENCE, Set.of(INTELLIGENCE)),
    HARLEQUIN(8, DEXTERITY, CHARISMA, Set.of(DEXTERITY), Set.of(CHARISMA));

    private final int hitDie;
    private final Set<Ability> savingThrows;
    private final List<Set<Ability>> multiclassAlternatives;

    ClassType(int hitDie, Ability firstSave, Ability secondSave, Set<Ability> prerequisite) {
        this(hitDie, firstSave, secondSave, List.of(prerequisite));
    }

    ClassType(int hitDie, Ability firstSave, Ability secondSave, Set<Ability> prerequisite,
            Set<Ability> alternative) {
        this(hitDie, firstSave, secondSave, List.of(prerequisite, alternative));
    }

    ClassType(int hitDie, Ability firstSave, Ability secondSave, List<Set<Ability>> alternatives) {
        this.hitDie = hitDie;
        this.savingThrows = Set.of(firstSave, secondSave);
        this.multiclassAlternatives = alternatives;
    }

    public enum CasterTier { FULL, HALF, PACT, NONE }

    public CasterTier casterTier() {
        return switch (this) {
            case BARD, CLERIC, DRUID, SORCERER, WIZARD -> CasterTier.FULL;
            case PALADIN, RANGER, ARTIFICER -> CasterTier.HALF;
            case WARLOCK -> CasterTier.PACT;
            default -> CasterTier.NONE;
        };
    }

    public java.util.Optional<Ability> castingAbility() {
        return switch (this) {
            case BARD, PALADIN, SORCERER, WARLOCK -> java.util.Optional.of(CHARISMA);
            case CLERIC, DRUID, RANGER -> java.util.Optional.of(WISDOM);
            case WIZARD, ARTIFICER -> java.util.Optional.of(INTELLIGENCE);
            default -> java.util.Optional.empty();
        };
    }

    public int attacks(int level) {
        return switch (this) {
            case FIGHTER -> level >= 20 ? 4 : level >= 11 ? 3 : level >= 5 ? 2 : 1;
            case BARBARIAN, MONK, PALADIN, RANGER -> level >= 5 ? 2 : 1;
            default -> 1;
        };
    }

    public int hitDie() { return hitDie; }
    public Set<Ability> savingThrows() { return savingThrows; }
    public List<Set<Ability>> multiclassAlternatives() { return multiclassAlternatives; }
}
