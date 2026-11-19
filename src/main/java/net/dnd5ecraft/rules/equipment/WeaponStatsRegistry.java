package net.dnd5ecraft.rules.equipment;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Dice;

import static net.dnd5ecraft.rules.equipment.DamageType5e.*;
import static net.dnd5ecraft.rules.equipment.WeaponProperty.*;

public final class WeaponStatsRegistry {
    private WeaponStatsRegistry() {}

    public enum Weapon {
        CLUB(1, 4, BLUDGEONING, LIGHT),
        DAGGER(1, 4, PIERCING, 20, 60, FINESSE, LIGHT, THROWN),
        GREATCLUB(1, 8, BLUDGEONING, TWO_HANDED),
        HANDAXE(1, 6, SLASHING, 20, 60, LIGHT, THROWN),
        JAVELIN(1, 6, PIERCING, 30, 120, THROWN),
        LIGHT_HAMMER(1, 4, BLUDGEONING, 20, 60, LIGHT, THROWN),
        MACE(1, 6, BLUDGEONING),
        QUARTERSTAFF(1, 6, BLUDGEONING, 8),
        SICKLE(1, 4, SLASHING, LIGHT),
        SPEAR(1, 6, PIERCING, 8, 20, 60, VERSATILE, THROWN),
        LIGHT_CROSSBOW(1, 8, PIERCING, 80, 320, AMMUNITION, LOADING, RANGED, TWO_HANDED),
        DART(1, 4, PIERCING, 20, 60, FINESSE, RANGED, THROWN),
        SHORTBOW(1, 6, PIERCING, 80, 320, AMMUNITION, RANGED, TWO_HANDED),
        SLING(1, 4, BLUDGEONING, 30, 120, AMMUNITION, RANGED),
        BATTLEAXE(1, 8, SLASHING, 10),
        FLAIL(1, 8, BLUDGEONING),
        GLAIVE(1, 10, SLASHING, HEAVY, REACH, TWO_HANDED),
        GREATAXE(1, 12, SLASHING, HEAVY, TWO_HANDED),
        GREATSWORD(2, 6, SLASHING, HEAVY, TWO_HANDED),
        HALBERD(1, 10, SLASHING, HEAVY, REACH, TWO_HANDED),
        LANCE(1, 12, PIERCING, REACH, SPECIAL),
        LONGSWORD(1, 8, SLASHING, 10),
        MAUL(2, 6, BLUDGEONING, HEAVY, TWO_HANDED),
        MORNINGSTAR(1, 8, PIERCING),
        PIKE(1, 10, PIERCING, HEAVY, REACH, TWO_HANDED),
        RAPIER(1, 8, PIERCING, FINESSE),
        SCIMITAR(1, 6, SLASHING, FINESSE, LIGHT),
        SHORTSWORD(1, 6, PIERCING, FINESSE, LIGHT),
        TRIDENT(1, 6, PIERCING, 8, 20, 60, VERSATILE, THROWN),
        WAR_PICK(1, 8, PIERCING),
        WARHAMMER(1, 8, BLUDGEONING, 10),
        WHIP(1, 4, SLASHING, FINESSE, REACH),
        BLOWGUN(0, 0, 1, PIERCING, 0, 25, 100, AMMUNITION, LOADING, RANGED),
        HAND_CROSSBOW(1, 6, PIERCING, 30, 120, AMMUNITION, LIGHT, LOADING, RANGED),
        HEAVY_CROSSBOW(1, 10, PIERCING, 100, 400, AMMUNITION, HEAVY, LOADING, RANGED, TWO_HANDED),
        LONGBOW(1, 8, PIERCING, 150, 600, AMMUNITION, HEAVY, RANGED, TWO_HANDED),
        NET(0, 0, null, 5, 15, RANGED, SPECIAL, THROWN);

        private final Stats stats;

        Weapon(int count, int sides, DamageType5e type, WeaponProperty... properties) {
            this(count, sides, type, 0, 0, properties);
        }

        Weapon(int count, int sides, DamageType5e type, int versatileSides) {
            this(count, sides, type, versatileSides, 0, 0, VERSATILE);
        }

        Weapon(int count, int sides, DamageType5e type, int normalRange, int longRange,
                WeaponProperty... properties) {
            this(count, sides, type, 0, normalRange, longRange, properties);
        }

        Weapon(int count, int sides, DamageType5e type, int versatileSides, int normalRange,
                int longRange, WeaponProperty... properties) {
            this(count, sides, 0, type, versatileSides, normalRange, longRange, properties);
        }

        Weapon(int count, int sides, int flatDamage, DamageType5e type, int versatileSides,
                int normalRange, int longRange, WeaponProperty... properties) {
            Optional<Dice> versatile = versatileSides == 0 ? Optional.empty() : Optional.of(new Dice(1, versatileSides));
            this.stats = new Stats(new Dice(count, sides), versatile, flatDamage,
                    Optional.ofNullable(type), normalRange, longRange, Set.of(properties));
        }

        public Stats stats() {
            return stats;
        }
    }

    public record Stats(Dice damage, Optional<Dice> twoHandedDamage, int flatDamage,
                        Optional<DamageType5e> damageType, int normalRangeFeet, int longRangeFeet,
                        Set<WeaponProperty> properties) {
        public Stats {
            properties = Set.copyOf(properties);
            if (damageType.isPresent() != (damage.count() > 0 || flatDamage > 0)) {
                throw new IllegalArgumentException("Only weapons that deal damage have a damage type");
            }
        }

        public boolean has(WeaponProperty property) {
            return properties.contains(property);
        }

        public int meleeReachFeet() {
            return has(REACH) ? 10 : 5;
        }

        public boolean allowsAttackAbility(Ability ability) {
            if (has(FINESSE)) {
                return ability == Ability.STRENGTH || ability == Ability.DEXTERITY;
            }
            return ability == (has(RANGED) ? Ability.DEXTERITY : Ability.STRENGTH);
        }

        public Ability preferredAttackAbility(int strengthScore, int dexterityScore) {
            if (has(FINESSE)) {
                return Ability.modifier(dexterityScore) > Ability.modifier(strengthScore)
                        ? Ability.DEXTERITY : Ability.STRENGTH;
            }
            return has(RANGED) ? Ability.DEXTERITY : Ability.STRENGTH;
        }

        public Dice damageDice(boolean twoHandedMelee) {
            return twoHandedMelee ? twoHandedDamage.orElse(damage) : damage;
        }

        public boolean dealsDamage() {
            return damage.count() > 0 || flatDamage > 0;
        }
    }

    public static Optional<Stats> findItem(String namespace, String path) {
        if (namespace.equals("dnd-5e-craft")) return find(path);
        if (!namespace.equals("minecraft")) return Optional.empty();
        return switch (path) {
            case "wooden_sword", "golden_sword" -> find("shortsword");
            case "netherite_sword" -> find("greatsword");
            case "wooden_axe", "stone_axe", "golden_axe" -> find("handaxe");
            case "netherite_axe" -> find("greataxe");
            case "bow" -> find("longbow");
            case "crossbow" -> find("light_crossbow");
            case "trident" -> find("trident");
            case "stick" -> find("club");
            default -> {
                if (path.endsWith("_sword")) yield find("longsword");
                if (path.endsWith("_axe")) yield find("battleaxe");
                if (path.endsWith("_hoe")) yield find("sickle");
                yield Optional.empty();
            }
        };
    }

    public static Optional<Stats> find(String name) {
        String key = name.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return Optional.of(Weapon.valueOf(key).stats());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
