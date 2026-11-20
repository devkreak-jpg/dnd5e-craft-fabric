package net.dnd5ecraft.rules.spells;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.spells.AoEGeometry.Template;
import net.dnd5ecraft.rules.spells.Spell.CastingTime;
import net.dnd5ecraft.rules.spells.Spell.Effect;
import net.dnd5ecraft.rules.spells.Spell.EffectKind;
import net.dnd5ecraft.rules.spells.Spell.Resolution;
import net.dnd5ecraft.rules.spells.Spell.Rider;
import net.dnd5ecraft.rules.spells.Spell.Targeting;

import static net.dnd5ecraft.rules.ability.Ability.*;
import static net.dnd5ecraft.rules.character.ClassType.*;
import static net.dnd5ecraft.rules.equipment.DamageType5e.*;
import static net.dnd5ecraft.rules.spells.AoEGeometry.*;
import static net.dnd5ecraft.rules.spells.Spell.CastingTime.*;
import static net.dnd5ecraft.rules.spells.Spell.EffectKind.*;
import static net.dnd5ecraft.rules.spells.Spell.Resolution.*;
import static net.dnd5ecraft.rules.spells.Spell.Rider.*;
import static net.dnd5ecraft.rules.spells.Spell.Targeting.*;
import static net.dnd5ecraft.rules.spells.SpellSchool.*;

public final class SpellRegistry5e {
    private SpellRegistry5e() {}

    private static final Map<String, Spell> SPELLS = create();

    public static Map<String, Spell> all() { return SPELLS; }

    public static Optional<Spell> find(String id) { return Optional.ofNullable(SPELLS.get(id)); }

    public static Spell require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown spell: " + id));
    }

    private static Map<String, Spell> create() {
        Map<String, Spell> spells = new LinkedHashMap<>();
        add(spells, "acid_splash", "Acid Splash", 0, CONJURATION, ACTION, 60, 0, false, false, CREATURES, 2, null,
                SAVE_NEGATES, DEXTERITY, cantrip(ACID, 6, NONE), SORCERER, WIZARD);
        add(spells, "chill_touch", "Chill Touch", 0, NECROMANCY, ACTION, 120, 1, false, false, CREATURES, 1, null,
                ATTACK, null, cantrip(NECROTIC, 8, NO_HEALING), SORCERER, WARLOCK, WIZARD);
        add(spells, "eldritch_blast", "Eldritch Blast", 0, EVOCATION, ACTION, 120, 0, false, false, CREATURES, 1, null,
                ATTACK, null, new Effect(DAMAGE, Optional.of(FORCE), 1, 10, 0, false, 0, 0, false, true, NONE), WARLOCK);
        add(spells, "fire_bolt", "Fire Bolt", 0, EVOCATION, ACTION, 120, 0, false, false, CREATURES, 1, null,
                ATTACK, null, cantrip(FIRE, 10, NONE), SORCERER, WIZARD);
        add(spells, "poison_spray", "Poison Spray", 0, CONJURATION, ACTION, 10, 0, false, false, CREATURES, 1, null,
                SAVE_NEGATES, CONSTITUTION, cantrip(POISON, 12, NONE), DRUID, SORCERER, WARLOCK, WIZARD);
        add(spells, "ray_of_frost", "Ray of Frost", 0, EVOCATION, ACTION, 60, 1, false, false, CREATURES, 1, null,
                ATTACK, null, cantrip(COLD, 8, SLOW_TEN_FEET), SORCERER, WIZARD);
        add(spells, "sacred_flame", "Sacred Flame", 0, EVOCATION, ACTION, 60, 0, false, false, CREATURES, 1, null,
                SAVE_NEGATES, DEXTERITY, cantrip(RADIANT, 8, NO_COVER_BONUS), CLERIC);
        add(spells, "shocking_grasp", "Shocking Grasp", 0, EVOCATION, ACTION, 5, 1, false, false, CREATURES, 1, null,
                ATTACK, null, cantrip(LIGHTNING, 8, NO_REACTIONS), SORCERER, WIZARD);
        add(spells, "spare_the_dying", "Spare the Dying", 0, NECROMANCY, ACTION, 5, 0, false, false, CREATURES, 1, null,
                AUTOMATIC, null, effect(STABILIZE), CLERIC);

        add(spells, "burning_hands", "Burning Hands", 1, EVOCATION, ACTION, 0, 0, false, false, AREA, 1,
                new Template(CONE, 15), SAVE_HALF, DEXTERITY, damage(FIRE, 3, 6, 1, 0, NONE), SORCERER, WIZARD);
        add(spells, "cure_wounds", "Cure Wounds", 1, EVOCATION, ACTION, 5, 0, false, false, CREATURES, 1, null,
                AUTOMATIC, null, heal(1, 8, 1), BARD, CLERIC, DRUID, PALADIN, RANGER);
        add(spells, "healing_word", "Healing Word", 1, EVOCATION, BONUS_ACTION, 60, 0, false, false, CREATURES, 1, null,
                AUTOMATIC, null, heal(1, 4, 1), BARD, CLERIC, DRUID);
        add(spells, "magic_missile", "Magic Missile", 1, EVOCATION, ACTION, 120, 0, false, false, CREATURES, 3, null,
                AUTOMATIC, null, new Effect(DAMAGE, Optional.of(FORCE), 1, 4, 1, false, 0, 1, false, false, NONE),
                SORCERER, WIZARD);
        add(spells, "shield", "Shield", 1, ABJURATION, REACTION, 0, 1, false, false, SELF, 1, null,
                AUTOMATIC, null, effect(ARMOR_BONUS), SORCERER, WIZARD);
        add(spells, "bless", "Bless", 1, ENCHANTMENT, ACTION, 30, 10, true, false, CREATURES, 3, null,
                AUTOMATIC, null, extraTarget(BLESS), CLERIC, PALADIN);
        add(spells, "bane", "Bane", 1, ENCHANTMENT, ACTION, 30, 10, true, false, CREATURES, 3, null,
                SAVE_NEGATES, CHARISMA, extraTarget(BANE), BARD, CLERIC);
        add(spells, "thunderwave", "Thunderwave", 1, EVOCATION, ACTION, 0, 0, false, false, AREA, 1,
                new Template(CUBE, 15), SAVE_HALF, CONSTITUTION, damage(THUNDER, 2, 8, 1, 0, PUSH_TEN_FEET),
                BARD, DRUID, SORCERER, WIZARD);
        add(spells, "inflict_wounds", "Inflict Wounds", 1, NECROMANCY, ACTION, 5, 0, false, false, CREATURES, 1, null,
                ATTACK, null, damage(NECROTIC, 3, 10, 1, 0, NONE), CLERIC);
        add(spells, "guiding_bolt", "Guiding Bolt", 1, EVOCATION, ACTION, 120, 1, false, false, CREATURES, 1, null,
                ATTACK, null, damage(RADIANT, 4, 6, 1, 0, GUIDING_LIGHT), CLERIC);
        add(spells, "mage_armor", "Mage Armor", 1, ABJURATION, ACTION, 5, 4800, false, false, CREATURES, 1, null,
                AUTOMATIC, null, effect(AC_FORMULA), SORCERER, WIZARD);
        add(spells, "false_life", "False Life", 1, NECROMANCY, ACTION, 0, 600, false, false, SELF, 1, null,
                AUTOMATIC, null, new Effect(TEMP_HP, Optional.empty(), 1, 4, 4, false, 0, 0, false, false, NONE),
                SORCERER, WIZARD);
        add(spells, "detect_magic", "Detect Magic", 1, DIVINATION, ACTION, 0, 100, true, true, SELF, 1, null,
                AUTOMATIC, null, effect(DETECT_MAGIC), BARD, CLERIC, DRUID, PALADIN, RANGER, SORCERER, WIZARD);

        add(spells, "scorching_ray", "Scorching Ray", 2, EVOCATION, ACTION, 120, 0, false, false, CREATURES, 3, null,
                ATTACK, null, damage(FIRE, 2, 6, 0, 1, NONE), SORCERER, WIZARD);
        add(spells, "shatter", "Shatter", 2, EVOCATION, ACTION, 60, 0, false, false, AREA, 1,
                new Template(SPHERE, 10), SAVE_HALF, CONSTITUTION, damage(THUNDER, 3, 8, 1, 0, NONE),
                BARD, SORCERER, WARLOCK, WIZARD);
        add(spells, "hold_person", "Hold Person", 2, ENCHANTMENT, ACTION, 60, 10, true, false, CREATURES, 1, null,
                SAVE_NEGATES, WISDOM, extraTarget(PARALYSIS), BARD, CLERIC, DRUID, SORCERER, WARLOCK, WIZARD);
        add(spells, "blindness_deafness", "Blindness/Deafness", 2, NECROMANCY, ACTION, 30, 10, false, false, CREATURES, 1, null,
                SAVE_NEGATES, CONSTITUTION, extraTarget(BLINDNESS), BARD, CLERIC, SORCERER, WIZARD);
        add(spells, "lesser_restoration", "Lesser Restoration", 2, ABJURATION, ACTION, 5, 0, false, false, CREATURES, 1, null,
                AUTOMATIC, null, effect(RESTORE), BARD, CLERIC, DRUID, PALADIN, RANGER);
        add(spells, "prayer_of_healing", "Prayer of Healing", 2, EVOCATION, TEN_MINUTES, 30, 0, false, false, CREATURES, 6, null,
                AUTOMATIC, null, heal(2, 8, 1), CLERIC);

        add(spells, "fireball", "Fireball", 3, EVOCATION, ACTION, 150, 0, false, false, AREA, 1,
                new Template(SPHERE, 20), SAVE_HALF, DEXTERITY, damage(FIRE, 8, 6, 1, 0, NONE), SORCERER, WIZARD);
        add(spells, "lightning_bolt", "Lightning Bolt", 3, EVOCATION, ACTION, 0, 0, false, false, AREA, 1,
                new Template(LINE, 100, 5), SAVE_HALF, DEXTERITY, damage(LIGHTNING, 8, 6, 1, 0, NONE), SORCERER, WIZARD);
        add(spells, "counterspell", "Counterspell", 3, ABJURATION, REACTION, 60, 0, false, false, CREATURES, 1, null,
                AUTOMATIC, null, effect(COUNTERSPELL), SORCERER, WARLOCK, WIZARD);
        add(spells, "mass_healing_word", "Mass Healing Word", 3, EVOCATION, BONUS_ACTION, 60, 0, false, false, CREATURES, 6, null,
                AUTOMATIC, null, heal(1, 4, 1), CLERIC);
        add(spells, "protection_from_energy", "Protection from Energy", 3, ABJURATION, ACTION, 5, 600, true, false, CREATURES, 1, null,
                AUTOMATIC, null, effect(RESISTANCE), CLERIC, DRUID, RANGER, SORCERER, WIZARD);

        add(spells, "mass_cure_wounds", "Mass Cure Wounds", 5, EVOCATION, ACTION, 60, 0, false, false, AREA, 6,
                new Template(SPHERE, 30), AUTOMATIC, null, heal(3, 8, 1), BARD, CLERIC, DRUID);
        add(spells, "cone_of_cold", "Cone of Cold", 5, EVOCATION, ACTION, 0, 0, false, false, AREA, 1,
                new Template(CONE, 60), SAVE_HALF, CONSTITUTION, damage(COLD, 8, 8, 1, 0, NONE), SORCERER, WIZARD);
        add(spells, "guidance", "Guidance", 0, DIVINATION, ACTION, 5, 10, true, false, CREATURES, 1, null,
                AUTOMATIC, null, effect(GUIDANCE), CLERIC, DRUID);
        add(spells, "vicious_mockery", "Vicious Mockery", 0, ENCHANTMENT, ACTION, 60, 1, false, false, CREATURES, 1, null,
                SAVE_NEGATES, WISDOM, cantrip(PSYCHIC, 4, MOCKERY), BARD);
        add(spells, "toll_the_dead", "Toll the Dead", 0, NECROMANCY, ACTION, 60, 0, false, false, CREATURES, 1, null,
                SAVE_NEGATES, WISDOM, cantrip(NECROTIC, 8, NONE), CLERIC, WARLOCK, WIZARD);
        add(spells, "faerie_fire", "Faerie Fire", 1, EVOCATION, ACTION, 60, 10, true, false, AREA, 1,
                new Template(CUBE, 20), SAVE_NEGATES, DEXTERITY, effect(REVEAL), BARD, DRUID);
        add(spells, "hunters_mark", "Hunter's Mark", 1, DIVINATION, BONUS_ACTION, 90, 600, true, false, CREATURES, 1, null,
                AUTOMATIC, null, effect(MARK), RANGER);
        add(spells, "misty_step", "Misty Step", 2, CONJURATION, BONUS_ACTION, 30, 0, false, false, AREA, 1,
                new Template(SINGLE, 5), AUTOMATIC, null, effect(TELEPORT), SORCERER, WARLOCK, WIZARD);
        add(spells, "spiritual_weapon", "Spiritual Weapon", 2, EVOCATION, BONUS_ACTION, 60, 10, false, false, CREATURES, 1, null,
                ATTACK, null, ongoing(SPIRITUAL_WEAPON, FORCE, 1, 8, true, 0), CLERIC);
        add(spells, "web", "Web", 2, CONJURATION, ACTION, 60, 600, true, false, AREA, 1,
                new Template(CUBE, 20), AUTOMATIC, null, effect(ZONE), SORCERER, WIZARD);
        add(spells, "moonbeam", "Moonbeam", 2, EVOCATION, ACTION, 120, 10, true, false, AREA, 1,
                new Template(CYLINDER, 5, 40), AUTOMATIC, null, ongoing(ZONE, RADIANT, 2, 10, false, 1), DRUID);
        add(spells, "haste", "Haste", 3, TRANSMUTATION, ACTION, 30, 10, true, false, CREATURES, 1, null,
                AUTOMATIC, null, effect(HASTE), SORCERER, WIZARD);
        add(spells, "spirit_guardians", "Spirit Guardians", 3, CONJURATION, ACTION, 0, 100, true, false, SELF, 1, null,
                AUTOMATIC, null, ongoing(ZONE, RADIANT, 3, 8, false, 1), CLERIC);
        add(spells, "revivify", "Revivify", 3, NECROMANCY, ACTION, 5, 0, false, false, CREATURES, 1, null,
                AUTOMATIC, null, effect(REVIVE), CLERIC, PALADIN);
        add(spells, "mending", "Mending", 0, TRANSMUTATION, ACTION, 5, 0, false, false, SELF, 1, null,
                AUTOMATIC, null, effect(UTILITY), ARTIFICER, WIZARD);
        add(spells, "message", "Message", 0, TRANSMUTATION, ACTION, 120, 0, false, false, CREATURES, 1, null,
                AUTOMATIC, null, effect(UTILITY), ARTIFICER, WIZARD);
        add(spells, "prestidigitation", "Prestidigitation", 0, TRANSMUTATION, ACTION, 10, 0, false, false, SELF, 1, null,
                AUTOMATIC, null, effect(UTILITY), ARTIFICER, WIZARD);
        add(spells, "lightning_lure", "Lightning Lure", 0, EVOCATION, ACTION, 15, 0, false, false, CREATURES, 1, null,
                SAVE_NEGATES, Ability.STRENGTH, cantrip(LIGHTNING, 8, PULL_TEN_FEET), ARTIFICER, WIZARD);
        add(spells, "booming_blade", "Booming Blade", 0, EVOCATION, ACTION, 5, 1, false, false, CREATURES, 1, null,
                ATTACK, null, damage(THUNDER, 0, 0, 0, 0, BOOM_ON_MOVE), ARTIFICER, WIZARD);
        add(spells, "absorb_elements", "Absorb Elements", 1, ABJURATION, REACTION, 0, 1, false, false, SELF, 1, null,
                AUTOMATIC, null, effect(ABSORB), ARTIFICER, WIZARD);
        add(spells, "grease", "Grease", 1, CONJURATION, ACTION, 60, 10, false, false, AREA, 1,
                new Template(CUBE, 10), AUTOMATIC, null, effect(ZONE), ARTIFICER, WIZARD);
        add(spells, "fog_cloud", "Fog Cloud", 1, CONJURATION, ACTION, 120, 100, true, false, AREA, 1,
                new Template(SPHERE, 20, 0), AUTOMATIC, null, effect(ZONE), ARTIFICER, WIZARD, DRUID, RANGER);
        add(spells, "disguise_self", "Disguise Self", 1, ILLUSION, ACTION, 0, 600, false, false, SELF, 1, null,
                AUTOMATIC, null, effect(UTILITY), ARTIFICER, WIZARD);
        return Collections.unmodifiableMap(spells);
    }

    private static void add(Map<String, Spell> spells, String id, String name, int level, SpellSchool school,
            CastingTime time, int range, int rounds, boolean concentration, boolean ritual, Targeting targeting,
            int targets, Template area, Resolution resolution, Ability save, Effect effect, ClassType... classes) {
        Spell spell = new Spell(id, name, level, school, time, range, rounds, concentration, ritual, targeting,
                targets, Optional.ofNullable(area), resolution, Optional.ofNullable(save), effect, Set.of(classes));
        if (spells.putIfAbsent(id, spell) != null) throw new IllegalStateException("Duplicate spell: " + id);
    }

    private static Effect ongoing(EffectKind kind, DamageType5e type, int dice, int sides, boolean modifier, int upcast) {
        return new Effect(kind, Optional.of(type), dice, sides, 0, modifier, upcast, 0, false, false, NONE);
    }

    private static Effect effect(EffectKind kind) {
        return new Effect(kind, Optional.empty(), 0, 0, 0, false, 0, 0, false, false, NONE);
    }

    private static Effect extraTarget(EffectKind kind) {
        return new Effect(kind, Optional.empty(), 0, 0, 0, false, 0, 1, false, false, NONE);
    }

    private static Effect heal(int dice, int sides, int upcast) {
        return new Effect(HEAL, Optional.empty(), dice, sides, 0, true, upcast, 0, false, false, NONE);
    }

    private static Effect damage(DamageType5e type, int dice, int sides, int upcast, int targets, Rider rider) {
        return new Effect(DAMAGE, Optional.of(type), dice, sides, 0, false, upcast, targets, false, false, rider);
    }

    private static Effect cantrip(DamageType5e type, int sides, Rider rider) {
        return new Effect(DAMAGE, Optional.of(type), 1, sides, 0, false, 0, 0, true, false, rider);
    }
}
