package net.dnd5ecraft.rules.character;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.dnd5ecraft.rules.ability.Ability;

public final class MulticlassValidator {
    private MulticlassValidator() {}

    public static int spellcasterLevel(Map<ClassType, Integer> levels) {
        return net.dnd5ecraft.rules.spells.SpellSlotTable.effectiveLevel(levels);
    }

    public static boolean qualifies(ClassType type, Map<Ability, Integer> scores) {
        return type.multiclassAlternatives().stream().anyMatch(
                group -> group.stream().allMatch(ability -> scores.getOrDefault(ability, 0) >= 13));
    }

    public static Set<ClassType> unmetPrerequisites(CharacterSheetData sheet, ClassType target) {
        if (sheet.classLevels().containsKey(target)) {
            return Set.of();
        }
        EnumSet<ClassType> checked = EnumSet.copyOf(sheet.classLevels().keySet());
        checked.add(target);
        checked.removeIf(type -> qualifies(type, sheet.abilityScores()));
        return Set.copyOf(checked);
    }

    public static boolean canTakeLevel(CharacterSheetData sheet, ClassType target) {
        return sheet.level() < 20 && unmetPrerequisites(sheet, target).isEmpty();
    }
}
