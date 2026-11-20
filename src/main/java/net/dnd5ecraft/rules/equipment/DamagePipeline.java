package net.dnd5ecraft.rules.equipment;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;

public final class DamagePipeline {
    private DamagePipeline() {}

    public record Dice(int count, int sides) {
        public Dice {
            if (count < 0 || (count == 0 ? sides != 0 : sides < 2)) {
                throw new IllegalArgumentException("Invalid dice expression");
            }
        }

        public int roll(boolean critical, IntUnaryOperator roller) {
            int times = Math.multiplyExact(count, critical ? 2 : 1);
            int total = 0;
            for (int die = 0; die < times; die++) {
                total = Math.addExact(total, AbilityCheckEngine.rollDie(sides, roller));
            }
            return total;
        }
    }

    public record DamageRoll(DamageType5e type, Dice dice, int modifier) {
        public int roll(boolean critical, IntUnaryOperator roller) {
            return Math.addExact(dice.roll(critical, roller), modifier);
        }
    }

    public record Defenses(Set<DamageType5e> immunities, Set<DamageType5e> resistances,
                           Set<DamageType5e> vulnerabilities) {
        public static final Defenses NONE = new Defenses(Set.of(), Set.of(), Set.of());

        public Defenses {
            immunities = Set.copyOf(immunities);
            resistances = Set.copyOf(resistances);
            vulnerabilities = Set.copyOf(vulnerabilities);
        }

        public int adjust(DamageType5e type, int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Damage cannot be negative");
            }
            if (immunities.contains(type)) {
                return 0;
            }
            int resisted = resistances.contains(type) ? amount / 2 : amount;
            return vulnerabilities.contains(type) ? Math.multiplyExact(resisted, 2) : resisted;
        }
    }

    public record HitPoints(int current, int maximum, int temporary, int arcaneWard, int beast) {
        public HitPoints(int current, int maximum, int temporary, int arcaneWard) {
            this(current, maximum, temporary, arcaneWard, 0);
        }

        public HitPoints {
            if (current < 0 || maximum < 0 || current > maximum || temporary < 0 || arcaneWard < 0 || beast < 0) {
                throw new IllegalArgumentException("Invalid hit point pools");
            }
        }

        public HitPoints heal(int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Healing cannot be negative");
            }
            return new HitPoints(current + Math.min(amount, maximum - current), maximum, temporary, arcaneWard, beast);
        }

        public HitPoints grantTemporary(int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Temporary hit points cannot be negative");
            }
            return new HitPoints(current, maximum, Math.max(temporary, amount), arcaneWard, beast);
        }
    }

    public record Result(HitPoints remaining, Map<DamageType5e, Integer> adjustedDamage,
                         int wardAbsorbed, int beastAbsorbed, int temporaryAbsorbed, int hitPointsLost, int excessDamage, boolean enduranceUsed) {
        public Result {
            adjustedDamage = Map.copyOf(adjustedDamage);
        }

        public int damageTaken() {
            return Math.addExact(beastAbsorbed, Math.addExact(temporaryAbsorbed,
                    Math.addExact(hitPointsLost, Math.addExact(excessDamage, enduranceUsed ? 1 : 0))));
        }
    }

    public static Map<DamageType5e, Integer> rollDamage(Iterable<DamageRoll> rolls, boolean critical,
            IntUnaryOperator roller) {
        Map<DamageType5e, Integer> totals = new EnumMap<>(DamageType5e.class);
        for (DamageRoll roll : rolls) {
            totals.merge(roll.type(), roll.roll(critical, roller), Math::addExact);
        }
        totals.replaceAll((type, total) -> Math.max(0, total));
        return Map.copyOf(totals);
    }

    public static Result apply(Map<DamageType5e, Integer> damage, Defenses defenses, HitPoints hitPoints) {
        return apply(damage, defenses, hitPoints, false);
    }

    public static Result apply(Map<DamageType5e, Integer> damage, Defenses defenses, HitPoints hitPoints,
            boolean relentlessEndurance) {
        Map<DamageType5e, Integer> adjusted = new EnumMap<>(DamageType5e.class);
        int pending = 0;
        for (var entry : damage.entrySet()) {
            int amount = defenses.adjust(entry.getKey(), entry.getValue());
            adjusted.put(entry.getKey(), amount);
            pending = Math.addExact(pending, amount);
        }
        int wardAbsorbed = Math.min(hitPoints.arcaneWard(), pending);
        pending -= wardAbsorbed;
        int beastAbsorbed = Math.min(hitPoints.beast(), pending);
        pending -= beastAbsorbed;
        int temporaryAbsorbed = Math.min(hitPoints.temporary(), pending);
        pending -= temporaryAbsorbed;
        int lost = Math.min(hitPoints.current(), pending);
        int excess = pending - lost;
        boolean survives = relentlessEndurance && hitPoints.current() > 0 && pending >= hitPoints.current()
                && excess < hitPoints.maximum();
        return new Result(
                new HitPoints(hitPoints.current() - lost + (survives ? 1 : 0), hitPoints.maximum(),
                        hitPoints.temporary() - temporaryAbsorbed, hitPoints.arcaneWard() - wardAbsorbed,
                        hitPoints.beast() - beastAbsorbed), adjusted, wardAbsorbed, beastAbsorbed, temporaryAbsorbed,
                lost - (survives ? 1 : 0), excess, survives);
    }

    public static Dice divineSmiteDice(int slot, boolean fiendOrUndead) {
        if (slot < 1 || slot > 9) throw new IllegalArgumentException("Invalid Divine Smite slot");
        return new Dice(Math.min(5, slot + 1) + (fiendOrUndead ? 1 : 0), 8);
    }

    public static Dice sneakAttackDice(int rogueLevel) {
        if (rogueLevel < 1 || rogueLevel > 20) {
            throw new IllegalArgumentException("Rogue level must be between 1 and 20");
        }
        return new Dice((rogueLevel + 1) / 2, 6);
    }

    public static boolean canSneakAttack(WeaponStatsRegistry.Stats weapon, RollMode attackMode,
            boolean otherEnemyWithinFiveFeetAndNotIncapacitated, boolean alreadyUsedThisTurn) {
        boolean eligibleWeapon = weapon.has(WeaponProperty.FINESSE) || weapon.has(WeaponProperty.RANGED);
        return weapon.dealsDamage() && eligibleWeapon && !alreadyUsedThisTurn
                && (attackMode == RollMode.ADVANTAGE
                    || (attackMode == RollMode.NORMAL && otherEnemyWithinFiveFeetAndNotIncapacitated));
    }

    public enum AttackOutcome { MISS, HIT, CRITICAL }

    public static AttackOutcome attackOutcome(int d20, int modifier, int armorClass,
            boolean criticalOnHit) {
        if (d20 < 1 || d20 > 20) {
            throw new IllegalArgumentException("Attack roll must be between 1 and 20");
        }
        if (d20 == 20) {
            return AttackOutcome.CRITICAL;
        }
        if (d20 == 1 || (long) d20 + modifier < armorClass) {
            return AttackOutcome.MISS;
        }
        return criticalOnHit ? AttackOutcome.CRITICAL : AttackOutcome.HIT;
    }
}
