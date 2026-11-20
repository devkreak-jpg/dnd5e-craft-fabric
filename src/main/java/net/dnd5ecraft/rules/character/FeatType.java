package net.dnd5ecraft.rules.character;

import net.dnd5ecraft.rules.ability.Ability;

public enum FeatType {
    ALERT, ATHLETE, GRAPPLER, LUCKY, MOBILE, RESILIENT, SAVAGE_ATTACKER,
    SENTINEL, SHARPSHOOTER, TOUGH, GREAT_WEAPON_MASTER, WAR_CASTER, MEDIUM_ARMOR_MASTER;

    public boolean meetsPrerequisites(CharacterSheetData sheet) {
        return switch (this) {
            case GRAPPLER -> sheet.score(Ability.STRENGTH) >= 13;
            case WAR_CASTER -> !net.dnd5ecraft.rules.spells.SpellAccess.available(sheet).isEmpty();
            default -> true;
        };
    }
}
