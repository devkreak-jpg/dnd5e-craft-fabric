package net.dnd5ecraft.entity;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.dnd5ecraft.combat.CombatantEntry;
import net.dnd5ecraft.combat.CombatantEntry.Trait;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.equipment.DamagePipeline.DamageRoll;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Dice;
import net.dnd5ecraft.rules.equipment.DamageType5e;

import static net.dnd5ecraft.rules.equipment.DamageType5e.*;
import static net.dnd5ecraft.entity.BestiaryPreset.Feature.*;

public enum BestiaryPreset {
    KOBOLD("1/8", 12, 5, 30, 60, .75f, Kind.HUMANOID, scores(7, 15, 9, 8, 7, 8),
            attack("Dagger", 4, 5, 1, 4, 2, PIERCING), PACK_TACTICS),
    GOBLIN("1/4", 15, 7, 30, 60, .8f, Kind.HUMANOID, scores(8, 14, 10, 10, 8, 8),
            attack("Scimitar", 4, 5, 1, 6, 2, SLASHING)),
    ORC("1/2", 13, 15, 30, 60, 1.12f, Kind.HUMANOID, scores(16, 12, 16, 7, 11, 10),
            attack("Greataxe", 5, 5, 1, 12, 3, SLASHING), AGGRESSIVE),
    WOLF("1/4", 13, 11, 40, 0, .85f, Kind.BEAST, scores(12, 15, 12, 3, 12, 6),
            attack("Bite", 4, 5, 2, 4, 2, PIERCING), PACK_TACTICS),
    DIRE_WOLF("1", 14, 37, 50, 0, 1.3f, Kind.BEAST, scores(17, 15, 15, 3, 12, 7),
            attack("Bite", 5, 5, 2, 6, 3, PIERCING), PACK_TACTICS),
    SKELETON("1/4", 13, 13, 30, 60, 1f, Kind.UNDEAD, scores(10, 14, 15, 6, 8, 5),
            attack("Shortsword", 4, 5, 1, 6, 2, PIERCING)),
    ZOMBIE("1/4", 8, 22, 20, 60, 1f, Kind.UNDEAD, scores(13, 6, 16, 3, 6, 5),
            attack("Slam", 3, 5, 1, 6, 1, BLUDGEONING)),
    GIANT_SPIDER("1", 14, 26, 30, 60, 1.3f, Kind.ARTHROPOD, scores(14, 16, 12, 2, 11, 4),
            attack("Bite", 5, 5, 1, 8, 3, PIERCING)),
    OGRE("2", 11, 59, 40, 60, 1.45f, Kind.GIANT, scores(19, 8, 16, 5, 7, 7),
            attack("Greatclub", 6, 5, 2, 8, 4, BLUDGEONING)),
    OWLBEAR("3", 13, 59, 40, 60, 1.35f, Kind.MONSTROSITY, scores(20, 12, 17, 3, 12, 7),
            attack("Claws", 7, 5, 2, 8, 5, SLASHING), MULTIATTACK),
    TROLL("5", 15, 84, 30, 60, 1.55f, Kind.GIANT, scores(18, 13, 20, 7, 9, 7),
            attack("Claws", 7, 5, 2, 6, 4, SLASHING), REGENERATION, MULTIATTACK),
    HELL_HOUND("3", 15, 45, 50, 60, 1f, Kind.FIEND, scores(17, 12, 14, 6, 13, 6),
            new CombatantEntry.Attack("Searing bite", 5, 5, 0, 0, List.of(
                    new DamageRoll(PIERCING, new Dice(1, 8), 3), new DamageRoll(FIRE, new Dice(2, 6), 0))),
            PACK_TACTICS, BREATH_RECHARGE),
    IMP("1", 13, 10, 20, 120, .55f, Kind.FIEND, scores(6, 17, 13, 11, 12, 14),
            attack("Sting", 5, 5, 1, 4, 3, PIERCING), MAGIC_RESISTANCE),
    VAMPIRE("13", 16, 144, 30, 120, 1f, Kind.UNDEAD, scores(18, 18, 18, 17, 15, 18),
            attack("Unarmed strike", 9, 5, 1, 8, 4, BLUDGEONING), REGENERATION, LEGENDARY, MULTIATTACK),
    ADULT_RED_DRAGON("17", 19, 256, 40, 120, 2.4f, Kind.DRAGON, scores(27, 10, 25, 16, 13, 21),
            new CombatantEntry.Attack("Bite", 14, 10, 0, 0, List.of(
                    new DamageRoll(PIERCING, new Dice(2, 10), 8), new DamageRoll(FIRE, new Dice(2, 6), 0))),
            BREATH_RECHARGE, LEGENDARY, LAIR_ACTION, MULTIATTACK),
    BANDIT_CAPTAIN("2", 15, 65, 30, 0, 1f, Kind.HUMANOID, scores(15, 16, 14, 14, 11, 14),
            attack("Scimitar", 5, 5, 1, 6, 3, SLASHING), MULTIATTACK),
    BUGBEAR("1", 16, 27, 30, 60, 1.22f, Kind.HUMANOID, scores(15, 14, 13, 8, 11, 9),
            attack("Morningstar", 4, 5, 2, 8, 2, PIERCING)),
    GHOUL("1", 12, 22, 30, 60, .98f, Kind.UNDEAD, scores(13, 15, 10, 7, 10, 6),
            attack("Claws", 4, 5, 2, 4, 2, SLASHING)),
    VAMPIRE_SPAWN("5", 15, 82, 30, 60, 1f, Kind.UNDEAD, scores(16, 16, 16, 11, 10, 12),
            attack("Claws", 6, 5, 2, 4, 3, SLASHING), REGENERATION, MULTIATTACK),
    YOUNG_RED_DRAGON("10", 18, 178, 40, 120, 2.1f, Kind.DRAGON, scores(23, 10, 21, 14, 11, 19),
            new CombatantEntry.Attack("Bite", 10, 10, 0, 0, List.of(
                    new DamageRoll(PIERCING, new Dice(2, 10), 6), new DamageRoll(FIRE, new Dice(1, 6), 0))),
            BREATH_RECHARGE, MULTIATTACK),
    TAVERN_KEEPER("0", 10, 4, 30, 0, 1f, Kind.HUMANOID, scores(10, 10, 10, 10, 12, 12), List.of()),
    MERCHANT("0", 10, 4, 30, 0, 1f, Kind.HUMANOID, scores(10, 10, 10, 12, 10, 12), List.of()),
    TOWN_GUARD("0", 16, 11, 30, 0, 1f, Kind.HUMANOID, scores(13, 12, 12, 10, 11, 10), List.of()),
    GOBLIN_ARCHER("1/4", 13, 9, 30, 60, .8f, Kind.HUMANOID, scores(8, 15, 10, 10, 8, 8),
            List.of(ranged("Shortbow", 4, 80, 320, 1, 6, 2, PIERCING),
                    attack("Dagger", 4, 5, 1, 4, 2, PIERCING))),
    BANDIT_THUG("1/2", 12, 18, 30, 0, 1f, Kind.HUMANOID, scores(14, 11, 13, 9, 10, 9),
            attack("Mace", 4, 5, 1, 6, 2, BLUDGEONING), PACK_TACTICS),
    CULTIST_ACOLYTE("1", 12, 19, 30, 0, 1f, Kind.HUMANOID, scores(9, 12, 12, 14, 14, 11),
            attack("Sickle", 3, 5, 1, 4, 1, SLASHING)),
    CAPTAIN_MORGAN("5", 16, 85, 30, 0, 1.12f, Kind.HUMANOID, scores(17, 16, 16, 13, 12, 15),
            List.of(attack("Scimitar", 6, 5, 1, 6, 3, SLASHING),
                    ranged("Hand Crossbow", 6, 30, 120, 1, 6, 3, PIERCING)),
            MULTIATTACK, LEGENDARY, BREATH_RECHARGE),
    BANDIT("1/8", 12, 11, 30, 0, 1f, Kind.HUMANOID, scores(11, 12, 12, 10, 10, 10),
            attack("Scimitar", 3, 5, 1, 6, 1, SLASHING)),
    BANDIT_CROSSBOWMAN("1/8", 12, 11, 30, 0, 1f, Kind.HUMANOID, scores(11, 12, 12, 10, 10, 10),
            ranged("Light Crossbow", 3, 80, 320, 1, 8, 1, PIERCING)),
    CULTIST("1/8", 12, 9, 30, 0, 1f, Kind.HUMANOID, scores(11, 12, 10, 10, 11, 10),
            attack("Scimitar", 3, 5, 1, 6, 1, SLASHING)),
    KOBOLD_SHAMAN("1/2", 12, 13, 30, 60, 0.76f, Kind.HUMANOID, scores(7, 15, 10, 10, 12, 10),
            attack("Dagger", 4, 5, 1, 4, 2, PIERCING), PACK_TACTICS),
    SKELETON_ARCHER("1/4", 13, 13, 30, 60, 1f, Kind.UNDEAD, scores(10, 14, 15, 6, 8, 5),
            ranged("Shortbow", 4, 80, 320, 1, 6, 2, PIERCING)),
    DROW_WARRIOR("1/4", 15, 13, 30, 120, 1f, Kind.HUMANOID, scores(10, 14, 10, 11, 11, 12),
            attack("Shortsword", 4, 5, 1, 6, 2, PIERCING)),
    HOBGOBLIN_LEGIONARY("1/2", 18, 11, 30, 60, 1.05f, Kind.HUMANOID, scores(13, 12, 12, 10, 10, 9),
            attack("Longsword", 3, 5, 1, 8, 1, SLASHING), MARTIAL_ADVANTAGE),
    GNOLL_HUNTER("1/2", 15, 22, 30, 60, 1.15f, Kind.HUMANOID, scores(14, 12, 11, 6, 10, 7),
            ranged("Longbow", 3, 150, 600, 1, 8, 1, PIERCING)),
    LIZARDFOLK("1/2", 15, 22, 30, 0, 1.06f, Kind.HUMANOID, scores(15, 10, 13, 7, 12, 7),
            attack("Club", 4, 5, 1, 6, 2, BLUDGEONING), MULTIATTACK),
    SAHUAGIN("1/2", 12, 22, 30, 120, 1.06f, Kind.HUMANOID, scores(13, 11, 12, 12, 13, 9),
            attack("Spear", 3, 5, 1, 6, 1, PIERCING), MULTIATTACK),
    GOBLIN_BOSS("1", 17, 21, 30, 60, 0.86f, Kind.HUMANOID, scores(10, 14, 10, 10, 8, 10),
            attack("Scimitar", 4, 5, 1, 6, 2, SLASHING), MULTIATTACK),
    SPECTER("1", 12, 22, 50, 60, 1.07f, Kind.UNDEAD, scores(1, 14, 11, 10, 10, 11),
            attack("Life Drain", 4, 5, 3, 6, 0, NECROTIC), LIFE_DRAIN),
    ANIMATED_ARMOR("1", 18, 33, 25, 60, 1.09f, Kind.CONSTRUCT, scores(14, 11, 13, 1, 3, 1),
            attack("Slam", 4, 5, 1, 6, 2, BLUDGEONING), MULTIATTACK),
    BANDIT_CUTTHROAT("1", 12, 27, 30, 0, 1f, Kind.HUMANOID, scores(10, 15, 10, 12, 14, 16),
            attack("Shortsword", 4, 5, 1, 6, 2, PIERCING), MULTIATTACK, SNEAK_STRIKE),
    CULT_FANATIC("2", 13, 33, 30, 0, 1f, Kind.HUMANOID, scores(11, 14, 12, 10, 13, 14),
            attack("Dagger", 4, 5, 1, 4, 2, PIERCING), MULTIATTACK),
    GARGOYLE("2", 15, 52, 30, 60, 1.2f, Kind.ELEMENTAL, scores(15, 11, 16, 6, 11, 7),
            attack("Claws", 4, 5, 1, 6, 2, SLASHING), MULTIATTACK),
    MINOTAUR_SKELETON("2", 12, 67, 40, 60, 1.25f, Kind.UNDEAD, scores(18, 11, 15, 6, 8, 5),
            attack("Greataxe", 6, 5, 2, 12, 4, SLASHING)),
    MUMMY("3", 11, 58, 20, 60, 1.05f, Kind.UNDEAD, scores(16, 8, 15, 6, 10, 12),
            attack("Rotting Fist", 5, 5, 2, 6, 3, BLUDGEONING), MUMMY_ROT),
    WIGHT("3", 14, 45, 30, 60, 1.07f, Kind.UNDEAD, scores(15, 14, 16, 10, 13, 15),
            List.of(attack("Longsword", 4, 5, 1, 8, 2, SLASHING), attack("Life Drain", 4, 5, 1, 6, 2, NECROTIC)), MULTIATTACK, LIFE_DRAIN),
    BEARDED_DEVIL("3", 13, 52, 30, 120, 1.19f, Kind.FIEND, scores(16, 15, 15, 9, 11, 11),
            List.of(attack("Glaive", 5, 10, 1, 10, 3, SLASHING), attack("Beard", 5, 5, 1, 8, 2, PIERCING)), MULTIATTACK, MAGIC_RESISTANCE),
    DRAGONBORN_KNIGHT("4", 18, 65, 30, 60, 1.17f, Kind.HUMANOID, scores(16, 11, 16, 10, 12, 14),
            attack("Greatsword", 5, 5, 2, 6, 3, SLASHING), MULTIATTACK),
    HILL_GIANT("5", 13, 105, 40, 0, 1.85f, Kind.GIANT, scores(21, 8, 19, 5, 9, 6),
            List.of(attack("Greatclub", 8, 10, 3, 8, 5, BLUDGEONING), ranged("Rock", 8, 60, 240, 3, 10, 5, BLUDGEONING)), MULTIATTACK),
    ORC_WARLORD("5", 16, 93, 30, 60, 1.22f, Kind.HUMANOID, scores(18, 12, 18, 11, 11, 16),
            attack("Greataxe", 7, 5, 1, 12, 4, SLASHING), AGGRESSIVE, MULTIATTACK),
    ASSASSIN("8", 15, 78, 30, 0, 1f, Kind.HUMANOID, scores(11, 16, 14, 13, 11, 10),
            attack("Shortsword", 6, 5, 1, 6, 3, PIERCING), MULTIATTACK, SNEAK_STRIKE, ASSASSIN_POISON),
    STONE_GOLEM("10", 17, 178, 30, 120, 1.55f, Kind.CONSTRUCT, scores(22, 9, 20, 3, 11, 1),
            attack("Slam", 10, 5, 3, 8, 6, BLUDGEONING), MAGIC_RESISTANCE, MULTIATTACK),
    DEATH_KNIGHT("12", 20, 180, 30, 120, 1.26f, Kind.UNDEAD, scores(20, 11, 20, 12, 16, 18),
            attack("Longsword", 9, 5, 1, 8, 5, SLASHING), LEGENDARY, MULTIATTACK, MAGIC_RESISTANCE, BREATH_RECHARGE),
    LICH_ARCHMAGE("12", 17, 135, 30, 120, 1.03f, Kind.UNDEAD, scores(11, 16, 16, 20, 14, 16),
            attack("Paralyzing Touch", 9, 5, 3, 6, 0, COLD), LEGENDARY, LICH_TOUCH);

    public enum Kind { HUMANOID, BEAST, UNDEAD, ARTHROPOD, GIANT, MONSTROSITY, FIEND, DRAGON, CONSTRUCT, ELEMENTAL }
    public enum Feature { PACK_TACTICS, MAGIC_RESISTANCE, REGENERATION, BREATH_RECHARGE, LEGENDARY, LAIR_ACTION, MULTIATTACK, AGGRESSIVE, MARTIAL_ADVANTAGE, SNEAK_STRIKE, LIFE_DRAIN, MUMMY_ROT, LICH_TOUCH, ASSASSIN_POISON }
    public record Breath(int lengthFeet, int saveDc, Dice dice) {}

    private final String challenge;
    private final int armor;
    private final int hp;
    private final int speed;
    private final int darkvision;
    private final float scale;
    private final Kind kind;
    private final Map<Ability, Integer> scores;
    private final List<CombatantEntry.Attack> baseAttacks;
    private final Set<Feature> features;

    BestiaryPreset(String challenge, int armor, int hp, int speed, int darkvision, float scale, Kind kind,
            Map<Ability, Integer> scores, CombatantEntry.Attack attack, Feature... features) {
        this(challenge, armor, hp, speed, darkvision, scale, kind, scores, List.of(attack), features);
    }

    BestiaryPreset(String challenge, int armor, int hp, int speed, int darkvision, float scale, Kind kind,
            Map<Ability, Integer> scores, List<CombatantEntry.Attack> attacks, Feature... features) {
        this.challenge = challenge;
        this.armor = armor;
        this.hp = hp;
        this.speed = speed;
        this.darkvision = darkvision;
        this.scale = scale;
        this.kind = kind;
        this.scores = scores;
        this.baseAttacks = List.copyOf(attacks);
        this.features = Set.of(features);
    }

    public String id() { return name().toLowerCase(Locale.ROOT); }
    public String challenge() { return challenge; }
    public int armor() { return armor; }
    public int maxHp() { return hp; }
    public int speedFeet() { return speed; }
    public int darkvisionFeet() { return darkvision; }
    public float scale() { return scale; }
    public Kind kind() { return kind; }
    public Map<Ability, Integer> scores() { return scores; }
    public Set<Feature> features() { return features; }

    public static BestiaryPreset fromId(String id) {
        return valueOf(id.toUpperCase(Locale.ROOT));
    }

    public NpcCombatRole combatRole() {
        return switch (this) {
            case GOBLIN_ARCHER, BANDIT_CROSSBOWMAN, SKELETON_ARCHER, GNOLL_HUNTER -> NpcCombatRole.RANGED_SKIRMISHER;
            case CULTIST_ACOLYTE, KOBOLD_SHAMAN, CULT_FANATIC, LICH_ARCHMAGE -> NpcCombatRole.CASTER_SUPPORT;
            case CAPTAIN_MORGAN, ADULT_RED_DRAGON, YOUNG_RED_DRAGON, VAMPIRE, DEATH_KNIGHT, DRAGONBORN_KNIGHT, STONE_GOLEM -> NpcCombatRole.MINI_BOSS;
            default -> NpcCombatRole.MELEE_BRUTE;
        };
    }

    public int legendaryLimit() { return this == CAPTAIN_MORGAN ? 2 : features.contains(LEGENDARY) ? 3 : 0; }

    private Map<String, Ability> npcSpells() {
        if (this == LICH_ARCHMAGE) return Map.of("fireball", Ability.INTELLIGENCE, "magic_missile", Ability.INTELLIGENCE,
                "ray_of_frost", Ability.INTELLIGENCE, "shield", Ability.INTELLIGENCE, "counterspell", Ability.INTELLIGENCE,
                "hold_person", Ability.INTELLIGENCE);
        return casterLevel() > 0
                ? Map.of("fire_bolt", Ability.WISDOM, "healing_word", Ability.WISDOM, "magic_missile", Ability.WISDOM,
                        "bane", Ability.WISDOM, "hold_person", Ability.WISDOM)
                : Map.of();
    }

    private int casterLevel() {
        return switch (this) {
            case LICH_ARCHMAGE -> 12;
            case CULT_FANATIC -> 4;
            case CULTIST_ACOLYTE -> 3;
            case KOBOLD_SHAMAN -> 2;
            default -> 0;
        };
    }

    public boolean boss() {
        return combatRole() == NpcCombatRole.MINI_BOSS || this == LICH_ARCHMAGE;
    }

    public Breath breath() {
        return switch (this) {
            case DEATH_KNIGHT -> new Breath(120, 18, new Dice(10, 6));
            case CAPTAIN_MORGAN -> new Breath(20, 14, new Dice(4, 6));
            case HELL_HOUND -> new Breath(15, 12, new Dice(6, 6));
            case YOUNG_RED_DRAGON -> new Breath(30, 17, new Dice(16, 6));
            case ADULT_RED_DRAGON -> new Breath(60, 21, new Dice(18, 6));
            default -> throw new IllegalStateException("Preset has no breath attack");
        };
    }

    public Set<DamageType5e> suppressesRegeneration() {
        return switch (this) {
            case TROLL -> Set.of(ACID, FIRE);
            case VAMPIRE, VAMPIRE_SPAWN -> Set.of(RADIANT);
            default -> Set.of();
        };
    }

    public int regeneration() { return this == TROLL || this == VAMPIRE_SPAWN ? 10 : this == VAMPIRE ? 20 : 0; }
    public int attacksPerAction() {
        return switch (this) {
            case TAVERN_KEEPER, MERCHANT, TOWN_GUARD -> 0;
            case DEATH_KNIGHT -> 3;
            case TROLL, ADULT_RED_DRAGON, YOUNG_RED_DRAGON, BANDIT_CAPTAIN -> 3;
            default -> features.contains(MULTIATTACK) ? 2 : 1;
        };
    }

    public java.util.Optional<net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon> equipment() {
        for (var profile : attacks()) {
            String id = profile.name().toUpperCase(Locale.ROOT).replace(' ', '_');
            for (var weapon : net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon.values())
                if (weapon.name().equals(id)) return java.util.Optional.of(weapon);
        }
        return java.util.Optional.empty();
    }

    public boolean civilian() { return baseAttacks.isEmpty(); }

    public double baseAttackDamage() {
        var profiles = attacks();
        if (profiles.isEmpty()) return 0;
        return Math.max(1, profiles.get(0).damage().stream().mapToDouble(part ->
                part.dice().count() * (part.dice().sides() + 1) / 2.0 + part.modifier()).sum());
    }

    public List<CombatantEntry.Attack> attacks() {
        if (baseAttacks.isEmpty()) return List.of();
        var attack = baseAttacks.get(0);
        return switch (this) {
            case GOBLIN, SKELETON -> List.of(attack, ranged("Shortbow", 4, 80, 320, 1, 6, 2, PIERCING));
            case ORC, OGRE -> List.of(attack, ranged("Javelin", this == ORC ? 5 : 6, 30, 120,
                    this == ORC ? 1 : 2, 6, this == ORC ? 3 : 4, PIERCING));
            case BANDIT_CAPTAIN -> List.of(attack, ranged("Dagger", 5, 20, 60, 1, 4, 3, PIERCING));
            case BUGBEAR -> List.of(attack, ranged("Javelin", 4, 30, 120, 2, 6, 2, PIERCING));
            case GHOUL -> List.of(attack, attack("Bite", 2, 5, 2, 6, 2, PIERCING));
            case VAMPIRE_SPAWN -> List.of(attack, new CombatantEntry.Attack("Bite", 6, 5, 0, 0,
                    List.of(new DamageRoll(PIERCING, new Dice(1, 6), 3), new DamageRoll(NECROTIC, new Dice(2, 6), 0))));
            case YOUNG_RED_DRAGON -> List.of(attack, attack("Claw", 10, 5, 2, 6, 6, SLASHING));
            case OWLBEAR -> List.of(attack, attack("Beak", 7, 5, 1, 10, 5, PIERCING));
            case TROLL -> List.of(attack, attack("Bite", 7, 5, 1, 6, 4, PIERCING));
            case ADULT_RED_DRAGON -> List.of(attack, attack("Claw", 14, 5, 2, 6, 8, SLASHING),
                    attack("Tail", 14, 15, 2, 8, 8, BLUDGEONING));
            default -> baseAttacks;
        };
    }

    public Defenses defenses() {
        return switch (this) {
            case SKELETON, SKELETON_ARCHER, MINOTAUR_SKELETON -> new Defenses(Set.of(POISON), Set.of(), Set.of(BLUDGEONING));
            case ZOMBIE, GHOUL, WIGHT, LICH_ARCHMAGE, ANIMATED_ARMOR, STONE_GOLEM -> new Defenses(Set.of(POISON), Set.of(), Set.of());
            case MUMMY -> new Defenses(Set.of(POISON), Set.of(), Set.of(FIRE));
            case SPECTER -> new Defenses(Set.of(POISON), Set.of(ACID, COLD, FIRE, LIGHTNING, THUNDER), Set.of());
            case DEATH_KNIGHT -> new Defenses(Set.of(POISON, NECROTIC), Set.of(), Set.of());
            case HELL_HOUND, ADULT_RED_DRAGON, YOUNG_RED_DRAGON -> new Defenses(Set.of(FIRE), Set.of(), Set.of());
            case IMP, BEARDED_DEVIL -> new Defenses(Set.of(FIRE, POISON), Set.of(COLD), Set.of());
            case VAMPIRE, VAMPIRE_SPAWN -> new Defenses(Set.of(), Set.of(NECROTIC), Set.of());
            default -> Defenses.NONE;
        };
    }

    public CombatantEntry.Stats combatStats() {
        var traits = java.util.EnumSet.noneOf(Trait.class);
        if (kind == Kind.HUMANOID) traits.add(Trait.HUMANOID);
        if (kind == Kind.UNDEAD) traits.add(Trait.UNDEAD);
        if (kind == Kind.CONSTRUCT) traits.add(Trait.CONSTRUCT);
        if (kind == Kind.FIEND) traits.add(Trait.FIEND);
        if (this == IMP) traits.add(Trait.DEVILS_SIGHT);
        if (this == VAMPIRE || this == VAMPIRE_SPAWN) traits.add(Trait.SHAPECHANGER);
        if (features.contains(MAGIC_RESISTANCE)) traits.add(Trait.MAGICAL);
        var saves = new EnumMap<Ability, Integer>(Ability.class);
        if (this == ZOMBIE) saves.put(Ability.WISDOM, 0);
        if (this == VAMPIRE) {
            saves.put(Ability.DEXTERITY, 9);
            saves.put(Ability.WISDOM, 7);
            saves.put(Ability.CHARISMA, 9);
        }
        if (this == BANDIT_CAPTAIN) {
            saves.put(Ability.STRENGTH, 4);
            saves.put(Ability.DEXTERITY, 5);
            saves.put(Ability.WISDOM, 2);
        }
        if (this == VAMPIRE_SPAWN) {
            saves.put(Ability.DEXTERITY, 6);
            saves.put(Ability.WISDOM, 3);
        }
        if (this == YOUNG_RED_DRAGON) {
            saves.put(Ability.DEXTERITY, 4);
            saves.put(Ability.CONSTITUTION, 9);
            saves.put(Ability.WISDOM, 4);
            saves.put(Ability.CHARISMA, 8);
        }
        if (this == ADULT_RED_DRAGON) {
            saves.put(Ability.DEXTERITY, 6);
            saves.put(Ability.CONSTITUTION, 13);
            saves.put(Ability.WISDOM, 7);
            saves.put(Ability.CHARISMA, 11);
        }
        return new CombatantEntry.Stats(speed, armor, this == ORC || this == GOBLIN || this == SKELETON || this == CAPTAIN_MORGAN,
                darkvision, Math.max(1, casterLevel()), scores, saves,
                casterLevel() > 0 ? Map.of(this == LICH_ARCHMAGE ? net.dnd5ecraft.rules.character.ClassType.WIZARD
                        : net.dnd5ecraft.rules.character.ClassType.CLERIC, casterLevel()) : Map.of(),
                npcSpells(), Set.of(), traits, attacks());
    }

    private static Map<Ability, Integer> scores(int strength, int dexterity, int constitution, int intelligence,
            int wisdom, int charisma) {
        return Map.of(Ability.STRENGTH, strength, Ability.DEXTERITY, dexterity, Ability.CONSTITUTION, constitution,
                Ability.INTELLIGENCE, intelligence, Ability.WISDOM, wisdom, Ability.CHARISMA, charisma);
    }

    private static CombatantEntry.Attack attack(String name, int accuracy, int reach, int dice, int sides,
            int modifier, DamageType5e type) {
        return new CombatantEntry.Attack(name, accuracy, reach, 0, 0,
                List.of(new DamageRoll(type, new Dice(dice, sides), modifier)));
    }

    private static CombatantEntry.Attack ranged(String name, int accuracy, int range, int longRange, int dice,
            int sides, int modifier, DamageType5e type) {
        return new CombatantEntry.Attack(name, accuracy, 0, range, longRange,
                List.of(new DamageRoll(type, new Dice(dice, sides), modifier)));
    }
}
