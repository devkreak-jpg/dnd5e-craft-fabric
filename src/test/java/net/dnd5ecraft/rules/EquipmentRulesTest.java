package net.dnd5ecraft.rules;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.equipment.*;
import net.dnd5ecraft.rules.equipment.ArmorFormulaEngine.Formula;
import net.dnd5ecraft.rules.equipment.DamagePipeline.AttackOutcome;
import net.dnd5ecraft.rules.equipment.DamagePipeline.DamageRoll;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Dice;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static net.dnd5ecraft.rules.equipment.DamageType5e.*;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentRulesTest {
    @ParameterizedTest
    @CsvSource({
        "UNARMORED,18,16,14,false,14", "UNARMORED,8,16,14,true,11",
        "BARBARIAN,18,16,14,false,17", "BARBARIAN,18,16,14,true,19",
        "MONK,18,16,14,false,16", "MONK,18,16,14,true,16", "MONK,18,16,20,true,16",
        "STUDDED_LEATHER,18,16,14,false,16", "LEATHER,8,16,14,true,12",
        "HALF_PLATE,20,16,14,false,17", "BREASTPLATE,8,16,14,true,15",
        "PLATE,6,16,14,false,18", "CHAIN_MAIL,20,16,14,true,18"
    })
    void calculatesArmorFormulas(Formula formula, int dexterity, int constitution, int wisdom, boolean shield, int ac) {
        assertEquals(ac, ArmorFormulaEngine.armorClass(formula, dexterity, constitution, wisdom, shield));
    }

    @Test
    void checksArmorRequirementsAndEncumbrance() {
        assertEquals(10, Formula.CHAIN_MAIL.speedPenaltyFeet(12, false));
        assertEquals(0, Formula.CHAIN_MAIL.speedPenaltyFeet(13, false));
        assertEquals(0, Formula.CHAIN_MAIL.speedPenaltyFeet(8, true));
        assertTrue(Formula.PADDED.stealthDisadvantage());
        assertFalse(Formula.BREASTPLATE.stealthDisadvantage());
        assertEquals(150, ArmorFormulaEngine.carryingCapacityPounds(10));
        assertEquals(0, ArmorFormulaEngine.variantEncumbranceSpeedPenalty(10, 50));
        assertEquals(10, ArmorFormulaEngine.variantEncumbranceSpeedPenalty(10, 50.01));
        assertEquals(20, ArmorFormulaEngine.variantEncumbranceSpeedPenalty(10, 100.01));
    }

    @ParameterizedTest
    @EnumSource(DamageType5e.class)
    void appliesDefenseForEveryDamageType(DamageType5e type) {
        HitPoints hp = new HitPoints(30, 30, 3, 2);
        var resistant = DamagePipeline.apply(Map.of(type, 13), new Defenses(Set.of(), Set.of(type), Set.of()), hp);
        assertEquals(Map.of(type, 6), resistant.adjustedDamage());
        assertEquals(2, resistant.wardAbsorbed());
        assertEquals(3, resistant.temporaryAbsorbed());
        assertEquals(1, resistant.hitPointsLost());
        assertEquals(4, resistant.damageTaken());
        assertEquals(new HitPoints(29, 30, 0, 0), resistant.remaining());

        var vulnerable = new Defenses(Set.of(), Set.of(), Set.of(type));
        assertEquals(26, vulnerable.adjust(type, 13));
        assertEquals(12, new Defenses(Set.of(), Set.of(type), Set.of(type)).adjust(type, 13));
        assertEquals(0, new Defenses(Set.of(type), Set.of(type), Set.of(type)).adjust(type, 13));
    }

    @Test
    void resolvesMixedDamageAndOverkillWithoutStackingTemporaryHp() {
        var hp = new HitPoints(9, 14, 4, 1);
        var result = DamagePipeline.apply(Map.of(FIRE, 11, COLD, 10),
                new Defenses(Set.of(FIRE), Set.of(), Set.of(COLD)), hp);
        assertEquals(new HitPoints(0, 14, 0, 0), result.remaining());
        assertEquals(6, result.excessDamage());
        assertEquals(19, result.damageTaken());
        assertEquals(4, hp.grantTemporary(3).temporary());
        assertEquals(7, hp.grantTemporary(7).temporary());
        assertEquals(14, hp.heal(20).current());
        assertEquals(13, DamageType5e.values().length);
    }

    @Test
    void doublesAllCriticalDiceAndAppliesFlatModifiersOnce() {
        List<DamageRoll> parts = List.of(new DamageRoll(PIERCING, new Dice(1, 8), 3),
                new DamageRoll(PIERCING, DamagePipeline.sneakAttackDice(3), 0),
                new DamageRoll(FIRE, new Dice(1, 4), 2));
        AtomicInteger rolls = new AtomicInteger();
        var critical = DamagePipeline.rollDamage(parts, true, sides -> {
            rolls.incrementAndGet();
            return sides;
        });
        assertEquals(Map.of(PIERCING, 43, FIRE, 10), critical);
        assertEquals(8, rolls.get());
        assertEquals(Map.of(PIERCING, 23, FIRE, 6), DamagePipeline.rollDamage(parts, false, sides -> sides));
        assertEquals(Map.of(PIERCING, 5), DamagePipeline.rollDamage(List.of(
                new DamageRoll(PIERCING, new Dice(1, 8), -2),
                new DamageRoll(PIERCING, new Dice(1, 6), 0)), false, sides -> sides == 6 ? 6 : 1));
        assertEquals(AttackOutcome.CRITICAL, DamagePipeline.attackOutcome(20, -3, 30, false));
        assertEquals(AttackOutcome.MISS, DamagePipeline.attackOutcome(1, 20, 5, false));
        assertEquals(AttackOutcome.MISS, DamagePipeline.attackOutcome(9, 2, 12, true));
        assertEquals(AttackOutcome.CRITICAL, DamagePipeline.attackOutcome(10, 2, 12, true));
        assertEquals(AttackOutcome.HIT, DamagePipeline.attackOutcome(10, 2, 12, false));
    }

    @Test
    void validatesSneakAttackAndWeaponProperties() {
        var dagger = Weapon.DAGGER.stats();
        assertTrue(DamagePipeline.canSneakAttack(dagger, RollMode.ADVANTAGE, false, false));
        assertTrue(DamagePipeline.canSneakAttack(dagger, RollMode.NORMAL, true, false));
        assertFalse(DamagePipeline.canSneakAttack(dagger, RollMode.DISADVANTAGE, true, false));
        assertFalse(DamagePipeline.canSneakAttack(dagger, RollMode.ADVANTAGE, true, true));
        assertFalse(DamagePipeline.canSneakAttack(Weapon.JAVELIN.stats(), RollMode.ADVANTAGE, true, false));
        assertTrue(DamagePipeline.canSneakAttack(Weapon.SHORTBOW.stats(), RollMode.ADVANTAGE, false, false));
        assertFalse(DamagePipeline.canSneakAttack(Weapon.NET.stats(), RollMode.ADVANTAGE, false, false));
        assertEquals(new Dice(10, 6), DamagePipeline.sneakAttackDice(20));
        assertEquals(Ability.DEXTERITY, dagger.preferredAttackAbility(8, 18));
        assertTrue(dagger.allowsAttackAbility(Ability.STRENGTH));
        assertTrue(dagger.allowsAttackAbility(Ability.DEXTERITY));
        assertFalse(dagger.allowsAttackAbility(Ability.INTELLIGENCE));
        assertTrue(Weapon.NET.stats().damageType().isEmpty());
        assertEquals(Ability.STRENGTH, Weapon.JAVELIN.stats().preferredAttackAbility(8, 18));
        assertEquals(60, dagger.longRangeFeet());
        assertEquals(10, Weapon.WHIP.stats().meleeReachFeet());
        assertEquals(new Dice(1, 10), Weapon.LONGSWORD.stats().damageDice(true));
        assertEquals(new Dice(1, 8), Weapon.LONGSWORD.stats().damageDice(false));
        assertEquals(Weapon.HAND_CROSSBOW.stats(), WeaponStatsRegistry.find("hand crossbow").orElseThrow());
        assertEquals(1, Weapon.BLOWGUN.stats().flatDamage());
    }

    @Test
    void convertsCoinsAndEnforcesAttunementSlots() {
        assertEquals(1161L, new CoinPurse(1, 1, 1, 1, 1).valueInCopper());
        var purse = new CoinPurse(0, 0, 0, 0, 1);
        assertEquals(999L, purse.exchangeAndSpend(1).orElseThrow().valueInCopper());
        assertTrue(purse.exchangeAndSpend(1001).isEmpty());
        assertEquals(1.0, new CoinPurse(50, 0, 0, 0, 0).weightInPounds());

        UUID ring = new UUID(0, 1);
        UUID staff = new UUID(0, 2);
        UUID cloak = new UUID(0, 3);
        UUID boots = new UUID(0, 4);
        var one = AttunementManager.EMPTY.attune(ring, "ring");
        assertFalse(one.canAttune(staff, "ring"));
        assertFalse(one.canAttune(ring, "staff"));
        var full = one.attune(staff, "staff").attune(cloak, "cloak");
        assertThrows(IllegalStateException.class, () -> full.attune(boots, "boots"));
        assertTrue(full.release(cloak).canAttune(boots, "boots"));
    }
}
