package net.dnd5ecraft.rules.ability;

import static net.dnd5ecraft.rules.ability.Ability.*;

public enum Skill {
    ACROBATICS(DEXTERITY), ANIMAL_HANDLING(WISDOM), ARCANA(INTELLIGENCE),
    ATHLETICS(STRENGTH), DECEPTION(CHARISMA), HISTORY(INTELLIGENCE),
    INSIGHT(WISDOM), INTIMIDATION(CHARISMA), INVESTIGATION(INTELLIGENCE),
    MEDICINE(WISDOM), NATURE(INTELLIGENCE), PERCEPTION(WISDOM),
    PERFORMANCE(CHARISMA), PERSUASION(CHARISMA), RELIGION(INTELLIGENCE),
    SLEIGHT_OF_HAND(DEXTERITY), STEALTH(DEXTERITY), SURVIVAL(WISDOM);

    private final Ability ability;

    Skill(Ability ability) {
        this.ability = ability;
    }

    public Ability ability() {
        return ability;
    }
}
