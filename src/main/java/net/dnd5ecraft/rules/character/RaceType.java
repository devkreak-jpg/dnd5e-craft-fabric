package net.dnd5ecraft.rules.character;

import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.dnd5ecraft.rules.ability.DiceRoller;
import net.dnd5ecraft.rules.equipment.DamageType5e;

public enum RaceType {
    DRAGONBORN(30, 0, 1.0, Trait.DRACONIC_BREATH),
    DWARF(25, 60, 1.0),
    ELF(30, 60, 1.0, Trait.FEY_ANCESTRY),
    GNOME(25, 60, 0.75, Trait.GNOME_CUNNING),
    HALF_ELF(30, 60, 1.0, Trait.FEY_ANCESTRY),
    HALF_ORC(30, 60, 1.12, Trait.RELENTLESS_ENDURANCE, Trait.SAVAGE_ATTACKS),
    HALFLING(25, 0, 0.75, Trait.LUCKY),
    HUMAN(30, 0, 1.0),
    TIEFLING(30, 60, 1.0);

    public enum Trait { LUCKY, RELENTLESS_ENDURANCE, SAVAGE_ATTACKS, FEY_ANCESTRY, GNOME_CUNNING, DRACONIC_BREATH }

    private final int walkingFeet;
    private final int darkvisionFeet;
    private final double scale;
    private final Set<Trait> traits;

    RaceType(int walkingFeet, int darkvisionFeet, double scale, Trait... traits) {
        this.walkingFeet = walkingFeet;
        this.darkvisionFeet = darkvisionFeet;
        this.scale = scale;
        this.traits = Set.of(traits);
    }

    public int walkingFeet() { return walkingFeet; }
    public int darkvisionFeet() { return darkvisionFeet; }
    public double scale() { return scale; }
    public Set<Trait> traits() { return traits; }

    public double walkingBlocks() { return walkingFeet / 5.0; }
    public int darkvisionBlocks() { return darkvisionFeet / 5; }
    public Set<DamageType5e> resistances() {
        return switch (this) {
            case TIEFLING, DRAGONBORN -> Set.of(DamageType5e.FIRE);
            case DWARF -> Set.of(DamageType5e.POISON);
            default -> Set.of();
        };
    }

    public int naturalD20(IntUnaryOperator roller) {
        return DiceRoller.naturalD20(roller, traits.contains(Trait.LUCKY));
    }
}
