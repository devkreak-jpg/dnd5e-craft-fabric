package net.dnd5ecraft.rules.character;

import java.util.Set;
import net.dnd5ecraft.rules.ability.Skill;

import static net.dnd5ecraft.rules.ability.Skill.*;

public enum BackgroundType {
    ACOLYTE(INSIGHT, RELIGION),
    CHARLATAN(DECEPTION, SLEIGHT_OF_HAND),
    CRIMINAL(DECEPTION, STEALTH),
    ENTERTAINER(ACROBATICS, PERFORMANCE),
    FOLK_HERO(ANIMAL_HANDLING, SURVIVAL),
    GUILD_ARTISAN(INSIGHT, PERSUASION),
    HERMIT(MEDICINE, RELIGION),
    NOBLE(HISTORY, PERSUASION),
    OUTLANDER(ATHLETICS, SURVIVAL),
    SAGE(ARCANA, HISTORY),
    SAILOR(ATHLETICS, PERCEPTION),
    SOLDIER(ATHLETICS, INTIMIDATION),
    URCHIN(SLEIGHT_OF_HAND, STEALTH);

    private final Set<Skill> skills;

    BackgroundType(Skill first, Skill second) {
        skills = Set.of(first, second);
    }

    public int startingGold() {
        return switch (this) {
            case NOBLE -> 25;
            case HERMIT -> 5;
            case ACOLYTE, CRIMINAL, ENTERTAINER, GUILD_ARTISAN -> 15;
            default -> 10;
        };
    }

    public Set<String> tools() {
        return switch (this) {
            case CRIMINAL, URCHIN -> Set.of("thieves_tools", this == URCHIN ? "disguise_kit" : "gaming_set");
            case FOLK_HERO -> Set.of("artisan_tools", "land_vehicles");
            case SOLDIER -> Set.of("gaming_set", "land_vehicles");
            case NOBLE -> Set.of("gaming_set");
            case ENTERTAINER -> Set.of("disguise_kit", "instrument");
            case SAILOR -> Set.of("navigators_tools", "water_vehicles");
            case HERMIT -> Set.of("herbalism_kit");
            case GUILD_ARTISAN -> Set.of("artisan_tools");
            case CHARLATAN -> Set.of("disguise_kit", "forgery_kit");
            case OUTLANDER -> Set.of("instrument");
            default -> Set.of();
        };
    }

    public Set<Skill> skills() { return skills; }
}
