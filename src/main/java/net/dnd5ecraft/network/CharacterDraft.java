package net.dnd5ecraft.network;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.dnd5ecraft.network.Protocol.AbilityMethod;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.character.BackgroundType;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.PointBuyCalculator;
import net.dnd5ecraft.rules.character.RaceType;

public record CharacterDraft(String name, RaceType race, BackgroundType background, ClassType startingClass,
                             AbilityMethod method, List<Integer> scores, CharacterSheetData.Personality personality) {
    public CharacterDraft {
        scores = List.copyOf(scores);
    }

    public CharacterSheetData create(List<Integer> authorizedRolls) {
        String normalized = name.strip();
        if (normalized.isEmpty() || normalized.length() > 32 || normalized.codePoints().anyMatch(Character::isISOControl)
                || scores.size() != Ability.values().length) throw new IllegalArgumentException("Invalid character name or scores");
        for (String field : List.of(personality.ideal(), personality.bond(), personality.flaw())) {
            if (field.length() > 80 || field.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid character description");
            }
        }
        Map<Ability, Integer> ordered = new EnumMap<>(Ability.class);
        for (Ability ability : Ability.values()) ordered.put(ability, scores.get(ability.ordinal()));
        boolean valid = switch (method) {
            case POINT_BUY -> PointBuyCalculator.pointsRemaining(ordered) >= 0;
            case STANDARD_ARRAY -> PointBuyCalculator.isStandardArray(ordered);
            case ROLLED -> authorizedRolls.size() == 6 && scores.stream().sorted(Comparator.naturalOrder()).toList()
                    .equals(authorizedRolls.stream().sorted(Comparator.naturalOrder()).toList());
        };
        if (!valid) throw new IllegalArgumentException("Ability scores don't match the selected method");
        var base = CharacterSheetData.create(normalized, race, background, startingClass, ordered);
        return new CharacterSheetData(base.name(), race, background, personality, startingClass, base.classLevels(),
                base.subclasses(), base.abilityScores(), base.skillProficiencies(), base.savingThrowProficiencies(),
                base.feats(), base.experiencePoints(), base.hitPoints(), base.coins(), base.attunement(),
                base.exhaustion(), base.conditions(), base.deathSaves());
    }
}
