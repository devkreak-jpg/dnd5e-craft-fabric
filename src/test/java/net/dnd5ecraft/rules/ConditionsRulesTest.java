package net.dnd5ecraft.rules;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.conditions.ConditionEffectApplier;
import net.dnd5ecraft.rules.conditions.ConditionEffectApplier.Sense;
import net.dnd5ecraft.rules.conditions.ConditionInstance;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.DamagePipeline;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.equipment.ExhaustionLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static net.dnd5ecraft.rules.ability.Ability.DEXTERITY;
import static net.dnd5ecraft.rules.ability.Ability.STRENGTH;
import static net.dnd5ecraft.rules.conditions.ConditionType.*;
import static org.junit.jupiter.api.Assertions.*;

class ConditionsRulesTest {
    private static final ConditionEffectApplier CLEAR = effects();

    @ParameterizedTest
    @EnumSource(ConditionType.class)
    void appliesEachCondition(ConditionType type) {
        var applied = type == EXHAUSTION
                ? new ConditionEffectApplier(Set.of(), ExhaustionLevel.ONE) : effects(type);
        assertTrue(applied.has(type));

        switch (type) {
            case BLINDED -> {
                assertTrue(applied.abilityCheck(Sense.SIGHT, false, false, false).automaticFailure());
                assertFalse(applied.abilityCheck(Sense.HEARING, false, false, false).automaticFailure());
                assertEquals(RollMode.DISADVANTAGE, attack(applied, CLEAR, 5).mode());
                assertEquals(RollMode.ADVANTAGE, attack(CLEAR, applied, 5).mode());
            }
            case CHARMED -> {
                assertTrue(applied.grantsSocialAdvantageToCharmer(true));
                assertFalse(applied.canTargetWithHarmfulEffect(true));
                assertFalse(applied.grantsSocialAdvantageToCharmer(false));
                assertFalse(applied.attackAgainst(CLEAR, 5, true, true, true, false, false, false).permitted());
                assertTrue(attack(applied, CLEAR, 5).permitted());
            }
            case DEAFENED -> {
                assertTrue(applied.abilityCheck(Sense.HEARING, false, false, false).automaticFailure());
                assertFalse(applied.abilityCheck(Sense.SIGHT, false, false, false).automaticFailure());
            }
            case EXHAUSTION -> assertEquals(RollMode.DISADVANTAGE,
                    applied.abilityCheck(Sense.NONE, false, false, false).mode());
            case FRIGHTENED -> {
                assertFalse(applied.canApproachFrightSource());
                assertEquals(RollMode.NORMAL, applied.abilityCheck(Sense.NONE, false, false, false).mode());
                assertEquals(RollMode.DISADVANTAGE, applied.abilityCheck(Sense.NONE, true, false, false).mode());
                assertEquals(RollMode.DISADVANTAGE,
                        applied.attackAgainst(CLEAR, 5, true, true, false, true, false, false).mode());
            }
            case GRAPPLED -> {
                assertEquals(0, applied.speedFeet(30));
                assertTrue(applied.canAct());
                assertEquals(RollMode.NORMAL, attack(applied, CLEAR, 5).mode());
            }
            case INCAPACITATED -> {
                assertFalse(applied.canAct());
                assertFalse(applied.canReact());
                assertEquals(30, applied.speedFeet(30));
                assertEquals(RollMode.NORMAL, attack(CLEAR, applied, 5).mode());
            }
            case INVISIBLE -> {
                assertFalse(applied.visiblyPresent(false));
                assertTrue(applied.visiblyPresent(true));
                assertEquals(RollMode.ADVANTAGE, attack(applied, CLEAR, 5).mode());
                assertEquals(RollMode.DISADVANTAGE, attack(CLEAR, applied, 5).mode());
                assertEquals(RollMode.NORMAL,
                        applied.attackAgainst(CLEAR, 5, true, true, false, false, false, false).mode());
            }
            case PARALYZED -> {
                assertDisabled(applied);
                assertFalse(applied.canSpeak());
                assertTrue(attack(CLEAR, applied, 5).criticalOnHit());
                assertFalse(attack(CLEAR, applied, 10).criticalOnHit());
            }
            case PETRIFIED -> {
                assertDisabled(applied);
                assertFalse(applied.awareOfSurroundings());
                assertEquals(10, applied.weightMultiplier());
                assertFalse(applied.canAcquirePoisonOrDisease());
                assertEquals(Set.of(DamageType5e.values()), applied.adjustDefenses(Defenses.NONE).resistances());
                assertFalse(attack(CLEAR, applied, 5).criticalOnHit());
            }
            case POISONED -> {
                assertEquals(RollMode.DISADVANTAGE, applied.abilityCheck(Sense.NONE, false, false, false).mode());
                assertEquals(RollMode.DISADVANTAGE, attack(applied, CLEAR, 5).mode());
                assertEquals(RollMode.NORMAL, applied.savingThrow(STRENGTH, false, false).mode());
            }
            case PRONE -> {
                assertEquals(15, applied.standingCostFeet(30));
                assertEquals(2, applied.crawlCostPerFoot());
                assertEquals(RollMode.DISADVANTAGE, attack(applied, CLEAR, 5).mode());
                assertEquals(RollMode.ADVANTAGE, attack(CLEAR, applied, 5).mode());
                assertEquals(RollMode.DISADVANTAGE, attack(CLEAR, applied, 10).mode());
            }
            case RESTRAINED -> {
                assertEquals(0, applied.speedFeet(30));
                assertEquals(RollMode.DISADVANTAGE, attack(applied, CLEAR, 5).mode());
                assertEquals(RollMode.ADVANTAGE, attack(CLEAR, applied, 5).mode());
                assertEquals(RollMode.DISADVANTAGE, applied.savingThrow(DEXTERITY, false, false).mode());
                assertEquals(RollMode.NORMAL, applied.savingThrow(STRENGTH, false, false).mode());
            }
            case STUNNED -> {
                assertDisabled(applied);
                assertTrue(applied.speechIsFaltering());
                assertFalse(attack(CLEAR, applied, 5).criticalOnHit());
            }
            case UNCONSCIOUS -> {
                assertDisabled(applied);
                assertFalse(applied.awareOfSurroundings());
                assertFalse(applied.canSpeak());
                assertTrue(applied.dropsHeldItems());
                assertTrue(applied.fallsProne());
                assertTrue(attack(CLEAR, applied, 5).criticalOnHit());
                assertEquals(RollMode.NORMAL, attack(CLEAR, applied, 10).mode());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"1,31,21,false,false", "2,15,21,false,false", "3,15,21,true,false",
        "4,15,10,true,false", "5,0,10,true,false", "6,0,10,true,true"})
    void accumulatesExhaustionEffects(int level, int speed, int hp, boolean combatDisadvantage, boolean dead) {
        ExhaustionLevel exhaustion = ExhaustionLevel.of(level);
        var effects = new ConditionEffectApplier(Set.of(), exhaustion);
        assertTrue(exhaustion.abilityCheckDisadvantage());
        assertEquals(speed, effects.speedFeet(31));
        assertEquals(hp, exhaustion.maxHitPoints(21));
        assertEquals(combatDisadvantage, exhaustion.attackDisadvantage());
        assertEquals(combatDisadvantage, exhaustion.savingThrowDisadvantage());
        assertEquals(dead, exhaustion.dead());
        assertEquals(!dead, effects.canAct());
        assertEquals(exhaustion, exhaustion.afterLongRest(false));
        assertEquals(dead ? exhaustion : ExhaustionLevel.of(level - 1), exhaustion.afterLongRest(true));
        assertEquals(ExhaustionLevel.of(Math.min(level + 1, 6)), exhaustion.gainLevel());
    }

    @Test
    void keepsConditionSourcesAndDurationSeparate() {
        UUID one = new UUID(0, 1);
        UUID two = new UUID(0, 2);
        var shortGrapple = new ConditionInstance(GRAPPLED, Optional.of(one), 1);
        var ongoingGrapple = new ConditionInstance(GRAPPLED, Optional.of(two), ConditionInstance.INDEFINITE);
        assertTrue(shortGrapple.advanceRound().isEmpty());
        assertTrue(ongoingGrapple.comesFrom(two));
        assertFalse(ongoingGrapple.comesFrom(one));
        assertEquals(ongoingGrapple, ongoingGrapple.advanceRound().orElseThrow());
        assertTrue(ongoingGrapple.endsAfterGrapplerChanges(false, true));
        assertTrue(ongoingGrapple.endsAfterGrapplerChanges(true, false));
        assertFalse(ongoingGrapple.endsAfterGrapplerChanges(true, true));
        assertEquals(1, new ConditionInstance(POISONED, Optional.empty(), 2).advanceRound().orElseThrow().roundsRemaining());
        assertEquals(15, ConditionType.values().length);
    }

    @Test
    void resolvesDeathSaveSuccessFailureAndNaturalRolls() {
        var dying = DeathSaveTracker.DYING;
        var oneSuccess = dying.roll(8, 2).tracker();
        var twoSuccesses = oneSuccess.roll(10, 0).tracker();
        assertEquals(2, twoSuccesses.successes());
        assertEquals(DeathSaveTracker.STABLE, twoSuccesses.roll(19, 0).tracker());
        assertEquals(DeathSaveTracker.STABLE, oneSuccess.stabilize());
        assertEquals(new DeathSaveTracker.Outcome(DeathSaveTracker.CONSCIOUS, 1), twoSuccesses.roll(20, -50));

        var twoFailures = dying.roll(1, 50).tracker();
        assertEquals(2, twoFailures.failures());
        assertEquals(DeathSaveTracker.DEAD, twoFailures.roll(9, 0).tracker());
        assertEquals(twoFailures, twoFailures.afterHealing(0));
        assertEquals(DeathSaveTracker.CONSCIOUS, twoFailures.afterHealing(3));
        assertEquals(DeathSaveTracker.DEAD, DeathSaveTracker.DEAD.afterHealing(3));
        assertEquals(0, DeathSaveTracker.STABLE.recoverAfterHours(2, 3).hitPointsRestored());
        assertEquals(1, DeathSaveTracker.STABLE.recoverAfterHours(3, 3).hitPointsRestored());
        assertThrows(IllegalStateException.class, () -> DeathSaveTracker.STABLE.roll(15, 0));
    }

    @Test
    void handlesZeroHitPointsMassiveDamageAndFurtherHits() {
        HitPoints before = new HitPoints(5, 12, 0, 0);
        assertEquals(DeathSaveTracker.DYING,
                DeathSaveTracker.CONSCIOUS.afterDamage(before, damage(before, 16), false));
        assertEquals(DeathSaveTracker.DEAD,
                DeathSaveTracker.CONSCIOUS.afterDamage(before, damage(before, 17), false));

        HitPoints zero = new HitPoints(0, 12, 0, 0);
        var critical = DeathSaveTracker.STABLE.afterDamage(zero, damage(zero, 1), true);
        assertEquals(DeathSaveTracker.State.DYING, critical.state());
        assertEquals(2, critical.failures());
        assertEquals(DeathSaveTracker.DEAD, critical.afterDamage(zero, damage(zero, 1), false));
        assertEquals(DeathSaveTracker.DEAD, DeathSaveTracker.DYING.afterDamage(zero, damage(zero, 12), false));

        HitPoints warded = new HitPoints(0, 12, 0, 3);
        assertEquals(DeathSaveTracker.STABLE, DeathSaveTracker.STABLE.afterDamage(warded, damage(warded, 2), false));
        HitPoints temporary = new HitPoints(0, 12, 3, 0);
        assertEquals(1, DeathSaveTracker.STABLE.afterDamage(temporary, damage(temporary, 2), false).failures());
    }

    private static DamagePipeline.Result damage(HitPoints before, int amount) {
        return DamagePipeline.apply(Map.of(DamageType5e.FORCE, amount), Defenses.NONE, before);
    }

    private static ConditionEffectApplier effects(ConditionType... types) {
        return new ConditionEffectApplier(Set.of(types), ExhaustionLevel.NONE);
    }

    private static ConditionEffectApplier.AttackEffect attack(ConditionEffectApplier attacker,
            ConditionEffectApplier defender, int distance) {
        return attacker.attackAgainst(defender, distance, defender.visiblyPresent(false),
                attacker.visiblyPresent(false), false, false, false, false);
    }

    private static void assertDisabled(ConditionEffectApplier applied) {
        assertFalse(applied.canAct());
        assertEquals(0, applied.speedFeet(30));
        assertTrue(applied.savingThrow(Ability.STRENGTH, false, false).automaticFailure());
        assertTrue(applied.savingThrow(Ability.DEXTERITY, false, false).automaticFailure());
        assertFalse(applied.savingThrow(Ability.WISDOM, false, false).automaticFailure());
        assertEquals(RollMode.ADVANTAGE, attack(CLEAR, applied, 5).mode());
    }
}
