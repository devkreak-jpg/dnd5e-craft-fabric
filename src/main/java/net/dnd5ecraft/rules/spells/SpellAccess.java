package net.dnd5ecraft.rules.spells;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassType;

public final class SpellAccess {
    private SpellAccess() {}

    public static Map<String, Ability> available(CharacterSheetData sheet) {
        Map<String, Ability> result = new TreeMap<>();
        var finneas = net.dnd5ecraft.rules.campaign.CampaignHero.FINNEAS;
        if (finneas.matches(sheet)) {
            finneas.spells().forEach(id -> result.put(id, Ability.INTELLIGENCE));
            return Map.copyOf(result);
        }
        for (Spell spell : SpellRegistry5e.all().values()) {
            for (ClassType type : ClassType.values()) {
                int level = sheet.classLevels().getOrDefault(type, 0);
                Ability casting = type.castingAbility().orElse(null);
                if (casting == null || level == 0 || !spell.classes().contains(type) || spell.level() > highestSlot(type, level)) continue;
                if (spell.level() == 0 && (type == ClassType.PALADIN || type == ClassType.RANGER)) continue;
                result.merge(spell.id(), casting, (first, next) -> sheet.score(next) > sheet.score(first) ? next : first);
            }
        }
        if (sheet.race() == net.dnd5ecraft.rules.character.RaceType.TRITON) result.put("fog_cloud", Ability.CHARISMA);
        return Map.copyOf(result);
    }

    public static Set<String> rituals(CharacterSheetData sheet, Map<String, Ability> available) {
        Set<String> result = new TreeSet<>();
        available.keySet().forEach(id -> {
            Spell spell = SpellRegistry5e.require(id);
            if (spell.ritual() && sheet.classLevels().keySet().stream().anyMatch(type ->
                    spell.classes().contains(type) && switch (type) {
                        case BARD, CLERIC, DRUID, WIZARD -> true;
                        default -> false;
                    })) result.add(id);
        });
        return Set.copyOf(result);
    }

    private static int highestSlot(ClassType type, int level) {
        if (type == ClassType.WARLOCK) return SpellSlotTable.pactSlots(level).level();
        int[] slots = SpellSlotTable.regularSlots(SpellSlotTable.effectiveLevel(Map.of(type, level)));
        for (int slot = slots.length; slot > 0; slot--) if (slots[slot - 1] != 0) return slot;
        return 0;
    }

}
