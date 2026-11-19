package net.dnd5ecraft.combat;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import net.dnd5ecraft.combat.CombatManager.CastRequest;
import net.dnd5ecraft.combat.CombatManager.EventType;
import net.dnd5ecraft.combat.CombatManager.Reaction;
import net.dnd5ecraft.combat.CombatantEntry.Attack;
import net.dnd5ecraft.combat.CombatantEntry.Stats;
import net.dnd5ecraft.combat.CombatantEntry.Trait;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.LightingVisionEvaluator.Light;
import net.dnd5ecraft.combat.OpportunityAttackDetector.Movement;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.combat.ReactionWindowManager.Offer;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.conditions.ConditionInstance;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.DamagePipeline.DamageRoll;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Dice;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.equipment.ExhaustionLevel;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.dnd5ecraft.rules.spells.SpellRegistry5e;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class CombatManagerTest {
    private static final Attack SWORD = new Attack("Sword", 3, 5, 0, 0,
            List.of(new DamageRoll(DamageType5e.SLASHING, new Dice(1, 8), 2)), true);
    private static final IntUnaryOperator FIXED_DICE = sides -> sides == 20 ? 12 : sides;

    @ParameterizedTest
    @CsvSource({"acid_splash,0,12", "chill_touch,0,16", "eldritch_blast,0,20", "fire_bolt,0,20",
        "poison_spray,0,24", "ray_of_frost,0,16", "sacred_flame,0,16", "shocking_grasp,0,16",
        "vicious_mockery,0,8", "toll_the_dead,0,16", "burning_hands,1,18", "thunderwave,1,16", "inflict_wounds,1,30", "guiding_bolt,1,24",
        "magic_missile,1,15", "scorching_ray,2,36", "shatter,2,24", "fireball,3,48",
        "lightning_bolt,3,48", "cone_of_cold,5,64"})
    void resolvesEveryRegisteredDamageSpell(String id, int slot, int damage) {
        CombatantEntry caster = caster(1, 0, 20, cell(0, 0), Set.of(id));
        CombatantEntry target = creature(2, 1, 10, cell(1, 0), 100, 12, Set.of(Trait.HUMANOID), -5);
        var combat = encounter(FIXED_DICE, caster, target);
        combat.start();
        var spell = SpellRegistry5e.require(id);
        CastRequest request;
        if (spell.area().isPresent()) {
            request = new CastRequest(id, slot, Pool.REGULAR, List.of(), Optional.of(target.cell().center()), Optional.empty());
        } else {
            int targets = spell.effect().cantripBeams() || id.equals("scorching_ray") || id.equals("magic_missile")
                    ? spell.targetsAt(slot, 9) : 1;
            request = new CastRequest(id, slot, java.util.Collections.nCopies(targets, target.id()));
        }
        assertTrue(combat.cast(caster.id(), request, 0).isEmpty());
        assertEquals(100 - damage, target.hitPoints().current());
        if (id.equals("thunderwave")) assertEquals(cell(3, 0), target.cell());
        if (id.equals("ray_of_frost")) assertEquals(20, target.speedFeet());
        if (id.equals("shocking_grasp")) assertFalse(target.canReact());
    }

    @ParameterizedTest
    @CsvSource({"cure_wounds,2,20", "healing_word,2,12", "prayer_of_healing,2,20",
        "mass_healing_word,3,8", "mass_cure_wounds,5,28"})
    void resolvesEveryHealingSpellAndUpcast(String id, int slot, int health) {
        CombatantEntry caster = caster(1, 0, 20, cell(0, 0), Set.of(id));
        CombatantEntry target = creature(2, 0, 10, cell(1, 0), 10, 12, Set.of(Trait.HUMANOID), 0);
        var combat = encounter(FIXED_DICE, caster, target);
        var spell = SpellRegistry5e.require(id);
        var request = new CastRequest(id, slot, Pool.REGULAR, List.of(target.id()),
                spell.area().map(area -> target.cell().center()), Optional.empty());
        if (id.equals("prayer_of_healing")) {
            assertThrows(IllegalStateException.class, () -> combat.castOutsideCombat(caster.id(), request, false, 9, 0));
            combat.castOutsideCombat(caster.id(), request, false, 10, 0);
        } else {
            combat.start();
            combat.cast(caster.id(), request, 0);
        }
        assertEquals(10 + health, target.hitPoints().current());
    }

    @Test
    void validatesTargetsBeforeSpendingResourcesAndSharesAreaDamageAcrossSaves() {
        CombatantEntry caster = caster(1, 0, 20, cell(0, 0), Set.of("fireball", "acid_splash"));
        CombatantEntry fail = creature(2, 1, 10, cell(8, 0), 100, 12, Set.of(Trait.HUMANOID), -5);
        CombatantEntry save = creature(3, 1, 9, cell(9, 0), 100, 12, Set.of(Trait.HUMANOID), 6);
        CombatantEntry outside = creature(4, 1, 8, cell(15, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        AtomicInteger d6 = new AtomicInteger();
        var combat = encounter(sides -> {
            if (sides == 6) d6.incrementAndGet();
            return FIXED_DICE.applyAsInt(sides);
        }, caster, fail, save, outside);
        combat.start();
        assertThrows(IllegalArgumentException.class, () -> combat.cast(caster.id(),
                new CastRequest("acid_splash", 0, List.of(fail.id(), outside.id())), 0));
        assertThrows(IllegalArgumentException.class, () -> combat.cast(caster.id(),
                new CastRequest("fireball", 3, List.of()), 0));
        assertEquals(3, caster.slotsAvailable(Pool.REGULAR, 3));
        combat.cast(caster.id(), new CastRequest("fireball", 4, Pool.REGULAR, List.of(),
                Optional.of(fail.cell().center()), Optional.empty()), 0);
        assertEquals(9, d6.get());
        assertEquals(46, fail.hitPoints().current());
        assertEquals(73, save.hitPoints().current());
        assertEquals(100, outside.hitPoints().current());
        assertEquals(2, caster.slotsAvailable(Pool.REGULAR, 4));
    }

    @Test
    void ritualIsSlotFreeAndItsConcentrationExpires() {
        CombatantEntry caster = caster(1, 0, 20, cell(0, 0), Set.of("detect_magic"));
        CombatantEntry aura = creature(2, 0, 10, cell(5, 0), 30, 12, Set.of(Trait.MAGICAL), 0);
        CombatantEntry far = creature(3, 0, 9, cell(7, 0), 30, 12, Set.of(Trait.MAGICAL), 0);
        var combat = encounter(FIXED_DICE, caster, aura, far);
        var ritual = new CastRequest("detect_magic", 0, List.of());
        assertThrows(IllegalStateException.class, () -> combat.castOutsideCombat(caster.id(), ritual, true, 9, 0));
        combat.castOutsideCombat(caster.id(), ritual, true, 10, 0);
        assertEquals(4, caster.slotsAvailable(Pool.REGULAR, 1));
        assertEquals(List.of(aura.id()), combat.detectedMagic(caster.id()));
        combat.advanceDowntimeRounds(99, 0);
        assertTrue(combat.concentration(caster.id()).isPresent());
        combat.advanceDowntimeRounds(1, 0);
        assertTrue(combat.concentration(caster.id()).isEmpty());
        assertTrue(combat.detectedMagic(caster.id()).isEmpty());
    }

    @Test
    void concentrationReplacesBuffsResistsDamageAndClearsEffectsOnFailure() {
        CombatantEntry caster = caster(1, 0, 20, cell(0, 0), Set.of("bless", "bane", "protection_from_energy"));
        CombatantEntry target = creature(2, 1, 10, cell(1, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        AtomicInteger d20 = new AtomicInteger(12);
        var combat = encounter(sides -> sides == 20 ? d20.get() : sides, caster, target);
        combat.start();
        combat.cast(caster.id(), new CastRequest("bless", 1, List.of(caster.id())), 0);
        assertTrue(caster.has(CombatantEntry.Effect.BLESS));
        combat.endTurn(caster.id(), 1);
        combat.endTurn(target.id(), 2);
        combat.cast(caster.id(), new CastRequest("bane", 1, List.of(target.id())), 3);
        assertFalse(caster.has(CombatantEntry.Effect.BLESS));
        assertTrue(target.has(CombatantEntry.Effect.BANE));
        combat.endTurn(caster.id(), 4);
        combat.endTurn(target.id(), 5);
        combat.cast(caster.id(), new CastRequest("protection_from_energy", 3, Pool.REGULAR, List.of(caster.id()),
                Optional.empty(), Optional.of("FIRE")), 6);
        assertFalse(target.has(CombatantEntry.Effect.BANE));
        assertEquals(4, combat.damage(target.id(), caster.id(), Map.of(DamageType5e.FIRE, 9), false).damageTaken());
        assertTrue(combat.concentration(caster.id()).isPresent());
        d20.set(1);
        assertEquals(4, combat.damage(target.id(), caster.id(), Map.of(DamageType5e.FIRE, 9), false).damageTaken());
        assertTrue(combat.concentration(caster.id()).isEmpty());
        assertEquals(9, combat.damage(target.id(), caster.id(), Map.of(DamageType5e.FIRE, 9), false).damageTaken());
    }

    @Test
    void paralysisRepeatsSavesAndRestorationClearsSpellConditions() {
        CombatantEntry caster = caster(1, 0, 20, cell(0, 0), Set.of("hold_person", "blindness_deafness", "lesser_restoration"));
        CombatantEntry target = creature(2, 1, 10, cell(1, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        AtomicInteger d20 = new AtomicInteger(12);
        var combat = encounter(sides -> sides == 20 ? d20.get() : sides, caster, target);
        combat.start();
        combat.cast(caster.id(), new CastRequest("hold_person", 2, List.of(target.id())), 0);
        assertTrue(target.conditionEffects().has(ConditionType.PARALYZED));
        assertFalse(target.canAct());
        combat.endTurn(caster.id(), 1);
        d20.set(20);
        combat.endTurn(target.id(), 2);
        assertTrue(target.canAct());
        d20.set(12);
        combat.cast(caster.id(), new CastRequest("blindness_deafness", 2, Pool.REGULAR, List.of(target.id()),
                Optional.empty(), Optional.of("DEAFENED")), 3);
        assertTrue(target.conditionEffects().has(ConditionType.DEAFENED));
        combat.endTurn(caster.id(), 4);
        combat.endTurn(target.id(), 5);
        combat.cast(caster.id(), new CastRequest("lesser_restoration", 2, Pool.REGULAR, List.of(target.id()),
                Optional.empty(), Optional.of("DEAFENED")), 6);
        assertFalse(target.conditionEffects().has(ConditionType.DEAFENED));
    }

    @Test
    void mageArmorAndTemporaryHitPointsAreAppliedAndExpire() {
        CombatantEntry caster = caster(1, 0, 20, cell(0, 0), Set.of("mage_armor", "false_life"));
        var combat = encounter(FIXED_DICE, caster);
        combat.castOutsideCombat(caster.id(), new CastRequest("mage_armor", 1, List.of(caster.id())), false, 0, 0);
        assertEquals(17, caster.armorClass());
        combat.castOutsideCombat(caster.id(), new CastRequest("false_life", 2, List.of()), false, 0, 0);
        assertEquals(13, caster.hitPoints().temporary());
        combat.advanceDowntimeRounds(600, 0);
        assertEquals(0, caster.hitPoints().temporary());
        assertEquals(17, caster.armorClass());
        combat.advanceDowntimeRounds(4200, 0);
        assertEquals(12, caster.armorClass());
    }

    @Test
    void spareTheDyingStabilizesAndNaturalTwentyRecoversDuringTheStartOfATurn() {
        CombatantEntry caster = caster(1, 0, 20, cell(0, 0), Set.of("spare_the_dying"));
        CombatantEntry stable = creature(2, 0, 10, cell(1, 0), 0, 12, Set.of(Trait.HUMANOID), 0);
        CombatantEntry dying = creature(3, 0, 9, cell(2, 0), 0, 12, Set.of(Trait.HUMANOID), 0);
        var combat = encounter(sides -> sides, caster, stable, dying);
        combat.start();
        combat.cast(caster.id(), new CastRequest("spare_the_dying", 0, List.of(stable.id())), 0);
        assertEquals(DeathSaveTracker.STABLE, stable.deathSaves());
        combat.endTurn(caster.id(), 1);
        assertEquals(0, stable.hitPoints().current());
        combat.endTurn(stable.id(), 2);
        assertEquals(DeathSaveTracker.CONSCIOUS, dying.deathSaves());
        assertEquals(1, dying.hitPoints().current());
        assertTrue(combat.drainEvents().stream().anyMatch(event -> event.type() == EventType.DEATH_SAVE && event.value() == 20));
    }

    @Test
    void opportunityAttackTriggersBeforeTheStepAndCanStopMovement() {
        CombatantEntry mover = creature(1, 0, 20, cell(0, 0), 10, 12, Set.of(Trait.HUMANOID), 0);
        CombatantEntry guard = creature(2, 1, 10, cell(-1, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        var combat = encounter(FIXED_DICE, mover, guard);
        combat.start();
        assertEquals(List.of(), OpportunityAttackDetector.leavingReach(mover, cell(1, 0), Movement.FORCED,
                false, List.of(guard), (one, two) -> true));
        assertEquals(List.of(), OpportunityAttackDetector.leavingReach(mover, cell(1, 0), Movement.TELEPORT,
                false, List.of(guard), (one, two) -> true));
        assertEquals(List.of(), OpportunityAttackDetector.leavingReach(mover, cell(1, 0), Movement.VOLUNTARY,
                false, List.of(guard), (one, two) -> false));
        var window = combat.move(mover.id(), cell(1, 0), 100).orElseThrow();
        assertEquals(List.of(new Offer(guard.id(), Kind.OPPORTUNITY_ATTACK)), window.offers());
        assertEquals(cell(0, 0), mover.cell());
        assertThrows(IllegalStateException.class, () -> combat.endTurn(mover.id(), 100));
        assertTrue(combat.answerReaction(window.id(), guard.id(), Optional.of(new Reaction(Kind.OPPORTUNITY_ATTACK)), 101));
        assertEquals(DeathSaveTracker.DYING, mover.deathSaves());
        assertEquals(cell(0, 0), mover.cell());
        assertFalse(guard.canReact());
        combat.endTurn(mover.id(), 102);
        assertTrue(guard.canReact());
    }

    @Test
    void expiredOrDisengagedOpportunityWindowFinishesTheStep() {
        CombatantEntry mover = creature(1, 0, 20, cell(0, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        CombatantEntry guard = creature(2, 1, 10, cell(-1, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        var combat = encounter(FIXED_DICE, mover, guard);
        combat.start();
        var window = combat.move(mover.id(), cell(1, 0), 1).orElseThrow();
        assertFalse(combat.answerReaction(window.id(), guard.id(), Optional.of(new Reaction(Kind.OPPORTUNITY_ATTACK)), 3501));
        assertEquals(cell(1, 0), mover.cell());
        assertEquals(100, mover.hitPoints().current());
        assertTrue(guard.canReact());
        assertTrue(combat.relocate(mover.id(), cell(0, 0), Movement.TELEPORT, 3502));
        combat.disengage(mover.id(), false, 3503);
        assertTrue(combat.move(mover.id(), cell(1, 0), 3504).isEmpty());
        assertEquals(10, mover.view().resources().movementSpentFeet());
    }

    @Test
    void shieldCanTurnAHitIntoAMissAndExpiresAtItsOwnersTurn() {
        CombatantEntry attacker = creature(1, 0, 20, cell(0, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        CombatantEntry mage = caster(2, 1, 10, cell(1, 0), Set.of("shield"));
        var combat = encounter(FIXED_DICE, attacker, mage);
        combat.start();
        var window = combat.attack(attacker.id(), mage.id(), 0, 10).orElseThrow();
        assertFalse(combat.answerReaction(window.id(), mage.id(), Optional.of(new Reaction(Kind.SHIELD)), 11));
        assertTrue(combat.answerReaction(window.id(), mage.id(), Optional.of(new Reaction(Kind.SHIELD, 1, Pool.REGULAR)), 12));
        assertEquals(17, mage.armorClass());
        assertEquals(100, mage.hitPoints().current());
        assertEquals(3, mage.slotsAvailable(Pool.REGULAR, 1));
        assertTrue(combat.drainEvents().stream().anyMatch(event -> event.type() == EventType.MISS));
        combat.endTurn(attacker.id(), 13);
        assertEquals(12, mage.armorClass());
    }

    @Test
    void shieldBlocksAllMagicMissilesAndUncannyDodgeHalvesAHitsDamage() {
        CombatantEntry attacker = caster(1, 0, 20, cell(0, 0), Set.of("magic_missile"));
        CombatantEntry mage = caster(2, 1, 10, cell(1, 0), Set.of("shield"));
        var combat = encounter(FIXED_DICE, attacker, mage);
        combat.start();
        var window = combat.cast(attacker.id(),
                new CastRequest("magic_missile", 1, List.of(mage.id(), mage.id(), mage.id())), 0).orElseThrow();
        assertTrue(combat.answerReaction(window.id(), mage.id(), Optional.of(new Reaction(Kind.SHIELD, 1, Pool.REGULAR)), 1));
        assertEquals(100, mage.hitPoints().current());

        CombatantEntry sword = creature(3, 0, 20, cell(0, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        CombatantEntry rogue = creature(4, 1, 10, cell(1, 0), 100, 12, Set.of(Trait.CAN_UNCANNY_DODGE), 0);
        combat = encounter(FIXED_DICE, sword, rogue);
        combat.start();
        var dodge = combat.attack(sword.id(), rogue.id(), 0, 0).orElseThrow();
        assertTrue(combat.answerReaction(dodge.id(), rogue.id(), Optional.of(new Reaction(Kind.UNCANNY_DODGE)), 1));
        assertEquals(95, rogue.hitPoints().current());
    }

    @Test
    void counterspellConsumesTheInterruptedSlotAndCanItselfBeCountered() {
        CombatantEntry original = caster(1, 0, 20, cell(0, 0), Set.of("fireball", "counterspell"));
        CombatantEntry enemy = caster(2, 1, 10, cell(4, 0), Set.of("counterspell"));
        var combat = encounter(FIXED_DICE, original, enemy);
        var request = new CastRequest("fireball", 3, Pool.REGULAR, List.of(),
                Optional.of(new Vec3(8.5, .5, .5)), Optional.empty());
        combat.start();
        var first = combat.cast(original.id(), request, 0).orElseThrow();
        var counter = Optional.of(new Reaction(Kind.COUNTERSPELL, 3, Pool.REGULAR));
        assertTrue(combat.answerReaction(first.id(), enemy.id(), counter, 1));
        var reply = combat.reactionWindow().orElseThrow();
        assertEquals(List.of(new Offer(original.id(), Kind.COUNTERSPELL)), reply.offers());
        assertTrue(combat.answerReaction(reply.id(), original.id(), Optional.empty(), 2));
        assertEquals(100, enemy.hitPoints().current());
        assertEquals(2, original.slotsAvailable(Pool.REGULAR, 3));
        assertEquals(2, enemy.slotsAvailable(Pool.REGULAR, 3));
        assertTrue(combat.drainEvents().stream().anyMatch(event -> event.type() == EventType.COUNTERED && event.detail().equals("fireball")));

        combat.endTurn(original.id(), 3);
        combat.endTurn(enemy.id(), 4);
        var roundTwo = combat.cast(original.id(), request, 5).orElseThrow();
        assertTrue(combat.answerReaction(roundTwo.id(), enemy.id(), counter, 6));
        assertTrue(combat.answerReaction(combat.reactionWindow().orElseThrow().id(), original.id(), counter, 7));
        assertEquals(52, enemy.hitPoints().current());
        assertEquals(0, original.slotsAvailable(Pool.REGULAR, 3));
    }

    @Test
    void fighterSplitsAttacksAcrossMovementAndRefreshesActionSurgeOnRest() {
        CombatantEntry fighter = entry(1, 0, 20, cell(0, 0), 100, 12, 5, Map.of(ClassType.FIGHTER, 5),
                Set.of(), Set.of(Trait.HUMANOID), 0);
        CombatantEntry first = creature(2, 1, 10, cell(1, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        CombatantEntry second = creature(3, 1, 9, cell(0, 2), 100, 12, Set.of(Trait.HUMANOID), 0);
        var combat = encounter(FIXED_DICE, fighter, first, second);
        combat.start();
        combat.attack(fighter.id(), first.id(), 0, 0);
        combat.move(fighter.id(), cell(0, 1), 1);
        combat.attack(fighter.id(), second.id(), 0, 2);
        assertThrows(IllegalStateException.class, () -> combat.attack(fighter.id(), second.id(), 0, 3));
        combat.actionSurge(fighter.id(), 4);
        assertThrows(IllegalStateException.class, () -> combat.actionSurge(fighter.id(), 5));
        combat.attack(fighter.id(), second.id(), 0, 6);
        combat.attack(fighter.id(), second.id(), 0, 7);
        assertEquals(90, first.hitPoints().current());
        assertEquals(70, second.hitPoints().current());
        combat.finish(8);
        combat.restoreSpellSlots(fighter.id(), false, 9);
        combat.start();
        combat.actionSurge(fighter.id(), 10);
        assertTrue(fighter.view().resources().available().contains(Resource.ACTION));
    }

    @Test
    void aiAttacksAnAvailableTargetAndMovesOrDashesTowardDistantOpponents() {
        CombatantEntry monster = creature(1, 1, 20, cell(0, 0), 100, 12, Set.of(), 0);
        CombatantEntry target = creature(2, 0, 10, cell(1, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        var melee = encounter(FIXED_DICE, monster, target);
        melee.start();
        assertEquals(MonsterAiDirector.Action.ATTACK, MonsterAiDirector.plan(melee, monster.id()).action());
        MonsterAiDirector.execute(melee, monster.id(), 0);
        assertEquals(90, target.hitPoints().current());

        CombatantEntry mover = creature(3, 1, 20, cell(0, 0), 100, 12, Set.of(), 0);
        CombatantEntry distant = creature(4, 0, 10, cell(20, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        var closing = encounter(FIXED_DICE, mover, distant);
        closing.start();
        for (int i = 0; i < 6; i++) {
            assertEquals(MonsterAiDirector.Action.MOVE, MonsterAiDirector.plan(closing, mover.id()).action());
            MonsterAiDirector.execute(closing, mover.id(), i);
        }
        assertEquals(70, mover.cell().distanceFeet(distant.cell()));
        assertEquals(MonsterAiDirector.Action.DASH, MonsterAiDirector.plan(closing, mover.id()).action());
        MonsterAiDirector.execute(closing, mover.id(), 7);
        assertEquals(1, mover.view().resources().dashes());
    }

    @Test
    void initiativeIsStableAndMovementSnapshotsReflectProneAndTerrain() {
        CombatantEntry actor = creature(1, 0, 20, cell(0, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        CombatantEntry next = creature(2, 0, 20, cell(4, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        var world = new CombatManager.Battlefield() {
            @Override public boolean canOccupy(Cell cell) { return cell.y() == 0; }
            @Override public boolean difficult(Cell cell) { return cell.equals(cell(1, 0)); }
            @Override public boolean blocksCover(Cell cell) { return false; }
            @Override public Light lightAt(Cell cell) { return Light.BRIGHT; }
        };
        var combat = new CombatManager(new CombatEncounterState(List.of(next, actor)), world, FIXED_DICE);
        assertEquals(16, CombatEncounterState.rollInitiative(18,
                RollMode.NORMAL, FIXED_DICE));
        combat.applyCondition(actor.id(), new ConditionInstance(ConditionType.PRONE, Optional.empty(), 2));
        combat.start();
        assertEquals(Optional.of(actor.id()), combat.snapshot().current());
        assertEquals(List.of(actor.id(), next.id()), combat.snapshot().initiative().stream().map(CombatantEntry.View::id).toList());
        assertEquals(15, combat.reachable(actor.id()).get(cell(1, 0)).intValue());
        combat.move(actor.id(), cell(1, 0), 0);
        assertEquals(15, actor.view().resources().movementRemainingFeet());
        combat.stand(actor.id(), 1);
        assertEquals(0, actor.view().resources().movementRemainingFeet());
        assertFalse(actor.conditionEffects().has(ConditionType.PRONE));
        combat.endTurn(actor.id(), 2);
        combat.endTurn(next.id(), 3);
        assertEquals(2, combat.snapshot().round());
    }

    @Test
    void damageAndIncapacitationReleaseGrapplesAndEndConcentration() {
        CombatantEntry caster = caster(1, 0, 20, cell(0, 0), Set.of("bless"));
        CombatantEntry ally = creature(2, 0, 10, cell(1, 0), 100, 12, Set.of(Trait.HUMANOID), 0);
        var combat = encounter(FIXED_DICE, caster, ally);
        combat.start();
        combat.cast(caster.id(), new CastRequest("bless", 1, List.of(ally.id())), 0);
        combat.applyCondition(ally.id(), new ConditionInstance(ConditionType.GRAPPLED,
                Optional.of(caster.id()), ConditionInstance.INDEFINITE));
        assertEquals(0, ally.speedFeet());
        combat.applyCondition(caster.id(), new ConditionInstance(ConditionType.STUNNED, Optional.empty(), 1));
        assertFalse(ally.conditionEffects().has(ConditionType.GRAPPLED));
        assertFalse(ally.has(CombatantEntry.Effect.BLESS));
        assertTrue(combat.concentration(caster.id()).isEmpty());
        assertTrue(combat.drainEvents().stream().anyMatch(e -> e.type() == EventType.CONCENTRATION_ENDED));
    }

    @Test
    void sneakAttackAppliesOncePerTurnAndCanApplyAgainOnAnOpportunityAttack() {
        CombatantEntry rogue = entry(1, 0, 20, cell(0, 0), 100, 12, 10,
                Map.of(ClassType.ROGUE, 5, ClassType.FIGHTER, 5), Set.of(), Set.of(Trait.CAN_UNCANNY_DODGE), 0);
        CombatantEntry foe = creature(2, 1, 10, cell(1, 0), 100, 12, Set.of(), 0);
        CombatantEntry ally = creature(3, 0, 5, cell(1, 1), 100, 12, Set.of(), 0);
        var combat = encounter(FIXED_DICE, rogue, foe, ally);
        combat.start();
        combat.attack(rogue.id(), foe.id(), 0, 0);
        assertEquals(72, foe.hitPoints().current());
        combat.attack(rogue.id(), foe.id(), 0, 1);
        assertEquals(62, foe.hitPoints().current());
        combat.endTurn(rogue.id(), 2);
        assertEquals(List.of(rogue.id()), combat.opportunityThreats(foe.id(), cell(2, 0)));
        var opportunity = combat.move(foe.id(), cell(2, 0), 3).orElseThrow();
        assertTrue(combat.answerReaction(opportunity.id(), rogue.id(), Optional.of(new Reaction(Kind.OPPORTUNITY_ATTACK)), 4));
        assertEquals(34, foe.hitPoints().current());
    }

    @Test
    void movingCasterCanShieldAgainstAnOpportunityAttackBeforeMovingOn() {
        CombatantEntry mage = caster(1, 0, 20, cell(0, 0), Set.of("shield"));
        CombatantEntry foe = creature(2, 1, 10, cell(-1, 0), 100, 12, Set.of(), 0);
        var combat = encounter(FIXED_DICE, mage, foe);
        combat.start();
        var opportunity = combat.move(mage.id(), cell(1, 0), 0).orElseThrow();
        assertTrue(combat.answerReaction(opportunity.id(), foe.id(), Optional.of(new Reaction(Kind.OPPORTUNITY_ATTACK)), 1));
        assertEquals(cell(0, 0), mage.cell());
        var shield = combat.reactionWindow().orElseThrow();
        assertEquals(List.of(new Offer(mage.id(), Kind.SHIELD)), shield.offers());
        assertTrue(combat.answerReaction(shield.id(), mage.id(), Optional.of(new Reaction(Kind.SHIELD, 1, Pool.REGULAR)), 2));
        assertEquals(cell(1, 0), mage.cell());
        assertEquals(100, mage.hitPoints().current());
    }

    @Test
    void lowerLevelCounterspellCanFailAndStillConsumesItsSlot() {
        CombatantEntry mage = caster(1, 0, 20, cell(0, 0), Set.of("fireball"));
        CombatantEntry foe = caster(2, 1, 10, cell(4, 0), Set.of("counterspell"));
        var combat = encounter(sides -> sides == 20 ? 10 : sides, mage, foe);
        combat.start();
        var reaction = combat.cast(mage.id(), new CastRequest("fireball", 5, Pool.REGULAR, List.of(),
                Optional.of(new Vec3(8.5, .5, .5)), Optional.empty()), 0).orElseThrow();
        assertTrue(combat.answerReaction(reaction.id(), foe.id(), Optional.of(new Reaction(Kind.COUNTERSPELL, 3, Pool.REGULAR)), 1));
        assertEquals(40, foe.hitPoints().current());
        assertEquals(2, foe.slotsAvailable(Pool.REGULAR, 3));
        assertFalse(combat.drainEvents().stream().anyMatch(e -> e.type() == EventType.COUNTERED));
    }

    @Test
    void surprisedCreaturesLoseTheirFirstTurnAndRemovalMaintainsOrder() {
        CombatantEntry lead = creature(1, 0, 20, cell(0, 0), 20, 12, Set.of(), 0);
        CombatantEntry second = creature(2, 1, 15, cell(1, 0), 20, 12, Set.of(), 0);
        CombatantEntry third = creature(3, 0, 10, cell(2, 0), 20, 12, Set.of(), 0);
        var combat = encounter(FIXED_DICE, lead, second, third);
        combat.start(Set.of(lead.id()));
        assertFalse(lead.canAct());
        assertFalse(lead.canReact());
        assertThrows(IllegalStateException.class, () -> combat.attack(lead.id(), second.id(), 0, 0));
        combat.endTurn(lead.id(), 0);
        assertTrue(lead.canReact());
        combat.removeCombatant(second.id());
        assertEquals(third.id(), combat.snapshot().current().orElseThrow());
        assertEquals(1, combat.snapshot().round());
        combat.endTurn(third.id(), 0);
        assertEquals(lead.id(), combat.snapshot().current().orElseThrow());
        assertEquals(2, combat.snapshot().round());
        assertTrue(lead.canAct());
    }

    @Test
    void hasteEndsWithLethargyAndMistyStepDoesNotProvoke() {
        CombatantEntry caster = caster(1, 0, 20, cell(0, 0), Set.of("haste", "misty_step"));
        CombatantEntry enemy = creature(2, 1, 10, cell(1, 0), 30, 12, Set.of(), 0);
        var combat = encounter(FIXED_DICE, caster, enemy);
        combat.start();
        combat.cast(caster.id(), new CastRequest("haste", 3, List.of(caster.id())), 0);
        assertEquals(60, caster.speedFeet());
        assertEquals(14, caster.armorClass());
        combat.endConcentration(caster.id());
        assertEquals(0, caster.speedFeet());
        combat.endTurn(caster.id(), 1);
        combat.endTurn(enemy.id(), 1);
        combat.endTurn(caster.id(), 1);
        combat.endTurn(enemy.id(), 1);
        var destination = cell(4, 0);
        var blink = new CastRequest("misty_step", 2, Pool.REGULAR, List.of(), Optional.of(destination.center()), Optional.empty());
        assertTrue(combat.cast(caster.id(), blink, 2).isEmpty());
        assertEquals(destination, caster.cell());
    }

    @Test
    void quickenedSpellUsesSorceryPointsAndFollowsTheBonusActionSpellRule() {
        var sorcerer = entry(1, 0, 20, cell(0, 0), 100, 12, 3, Map.of(ClassType.SORCERER, 3),
                Set.of("magic_missile", "fire_bolt"), Set.of(), 0);
        var enemy = creature(2, 1, 10, cell(3, 0), 100, 12, Set.of(), 0);
        var combat = encounter(FIXED_DICE, sorcerer, enemy);
        var missile = new CastRequest("magic_missile", 1, List.of(enemy.id(), enemy.id(), enemy.id()));
        combat.start();
        combat.maneuver(sorcerer.id(), CombatManager.Maneuver.QUICKEN_SPELL, null, 0, null, 0);
        assertThrows(IllegalArgumentException.class, () ->
                combat.cast(sorcerer.id(), new CastRequest("magic_missile", 1, List.of()), 0));
        assertEquals(3, sorcerer.remaining(net.dnd5ecraft.rules.character.ClassFeature.SORCERY_POINTS));
        combat.cast(sorcerer.id(), missile, 0);
        assertEquals(1, sorcerer.remaining(net.dnd5ecraft.rules.character.ClassFeature.SORCERY_POINTS));
        assertFalse(sorcerer.turn.canSpend(Resource.BONUS_ACTION));
        assertTrue(sorcerer.turn.canSpend(Resource.ACTION));
        assertThrows(IllegalStateException.class, () -> combat.cast(sorcerer.id(), missile, 0));
        combat.cast(sorcerer.id(), new CastRequest("fire_bolt", 0, List.of(enemy.id())), 0);
        assertFalse(sorcerer.turn.canSpend(Resource.ACTION));
    }

    @Test
    void paladinAuraProtectsNearbyAllies() {
        var caster = caster(1, 0, 20, cell(0, 0), Set.of("sacred_flame"));
        var nearby = creature(2, 1, 15, cell(2, 0), 100, 12, Set.of(), 3);
        var distant = creature(3, 1, 11, cell(1, 4), 100, 12, Set.of(), 3);
        var paladin = entry(4, 1, 10, cell(3, 0), 100, 12, 6, Map.of(ClassType.PALADIN, 6), Set.of(), Set.of(), 0);
        var secondPaladin = entry(5, 1, 8, cell(2, 1), 100, 12, 6, Map.of(ClassType.PALADIN, 6), Set.of(), Set.of(), 0);
        var combat = encounter(FIXED_DICE, caster, nearby, distant, paladin, secondPaladin);
        combat.start();
        combat.cast(caster.id(), new CastRequest("sacred_flame", 0, List.of(nearby.id())), 0);
        assertEquals(100, nearby.hitPoints().current());
        for (var actor : List.of(caster, nearby, distant, paladin, secondPaladin)) combat.endTurn(actor.id(), 0);
        combat.cast(caster.id(), new CastRequest("sacred_flame", 0, List.of(distant.id())), 0);
        assertEquals(84, distant.hitPoints().current());
    }

    @Test
    void harlequinOpensAnAttackWindowBeforeDamageAndAppliesChaosSign() {
        var attacker = creature(1, 0, 20, cell(0, 0), 100, 12, Set.of(), 0);
        var enemy = creature(2, 1, 15, cell(1, 0), 100, 16, Set.of(), 0);
        var harlequin = net.dnd5ecraft.rules.campaign.CampaignHero.HARLEQUIN.create();
        var listener = CombatantEntry.fromSheet(new UUID(0, 3), 0, 9, cell(3, 0), harlequin,
                13, false, Map.of(), Set.of(), Set.of(), List.of(SWORD), Defenses.NONE);
        var combat = encounter(FIXED_DICE, attacker, enemy, listener);
        combat.start();
        assertEquals(1, listener.remaining(net.dnd5ecraft.rules.character.ClassFeature.CHAOS_POINTS));
        var window = combat.attack(attacker.id(), enemy.id(), 0, 100).orElseThrow();
        assertTrue(window.offers().contains(new Offer(listener.id(), Kind.IMPROMPTU_ADD)));
        assertEquals(100, enemy.hitPoints().current());
        combat.answerReaction(window.id(), listener.id(), Optional.of(new Reaction(Kind.IMPROMPTU_ADD)), 120);
        assertEquals(90, enemy.hitPoints().current());
        assertEquals(0, listener.remaining(net.dnd5ecraft.rules.character.ClassFeature.CHAOS_POINTS));
        assertEquals(10, listener.chaosShift);
    }

    @Test
    void absorbElementsHalvesIncomingFireAndChargesTheNextMeleeHit() {
        var attacker = caster(1, 0, 20, cell(0, 0), Set.of("fire_bolt"));
        var defender = caster(2, 1, 10, cell(1, 0), Set.of("absorb_elements"));
        var combat = encounter(FIXED_DICE, attacker, defender);
        combat.start();
        var window = combat.cast(attacker.id(), new CastRequest("fire_bolt", 0, List.of(defender.id())), 0).orElseThrow();
        assertEquals(List.of(new Offer(defender.id(), Kind.ABSORB_ELEMENTS)), window.offers());
        combat.answerReaction(window.id(), defender.id(), Optional.of(new Reaction(Kind.ABSORB_ELEMENTS, 1, Pool.REGULAR)), 5);
        assertEquals(90, defender.hitPoints().current());
        combat.endTurn(attacker.id(), 10);
        combat.attack(defender.id(), attacker.id(), 0, 11);
        assertEquals(84, attacker.hitPoints().current());
        assertFalse(defender.has(CombatantEntry.Effect.ABSORBED));
    }

    @Test
    void opportunityAttackCanOriginateFromTheEcho() {
        var owner = creature(1, 1, 10, cell(4, 0), 100, 12, Set.of(), 0);
        var intruder = creature(2, 0, 20, cell(1, 0), 100, 12, Set.of(), 0);
        var world = new CombatManager.Battlefield() {
            @Override public boolean canOccupy(Cell cell) { return cell.y() == 0; }
            @Override public boolean difficult(Cell cell) { return false; }
            @Override public boolean blocksCover(Cell cell) { return false; }
            @Override public Light lightAt(Cell cell) { return Light.BRIGHT; }
            @Override public Cell echoCell(CombatantEntry actor) { return actor == owner ? cell(0, 0) : null; }
        };
        var combat = new CombatManager(new CombatEncounterState(List.of(intruder, owner)), world, FIXED_DICE);
        combat.start();
        var destination = cell(2, 0);
        assertEquals(List.of(owner.id()), combat.opportunityThreats(intruder.id(), destination));
        var reaction = combat.move(intruder.id(), destination, 0).orElseThrow();
        combat.answerReaction(reaction.id(), owner.id(), Optional.of(new Reaction(Kind.OPPORTUNITY_ATTACK)), 1);
        assertEquals(90, intruder.hitPoints().current());
        assertEquals(destination, intruder.cell());
        assertEquals(cell(4, 0), owner.cell());
    }

    @Test
    void sceneChangeChargesOnePointAndAllowsOneDirectorPairOnTheSameTurn() {
        var sheet = net.dnd5ecraft.rules.campaign.CampaignHero.HARLEQUIN.create();
        var jester = CombatantEntry.fromSheet(new UUID(0, 3), 0, 20, cell(0, 0), sheet,
                13, false, Map.of(), Set.of(), Set.of(), List.of(SWORD), Defenses.NONE);
        var first = creature(1, 0, 10, cell(1, 0), 100, 12, Set.of(), 0);
        var second = creature(2, 1, 9, cell(2, 0), 100, 12, Set.of(), 0);
        var combat = encounter(FIXED_DICE, jester, first, second);
        combat.start();
        assertThrows(IllegalStateException.class, () -> combat.directorScenePair(first.id(), second.cell(), 0));
        var action = net.dnd5ecraft.server.CampaignActions.Action.SCENE_CHANGE;
        combat.campaignManeuver(jester.id(), action, first.id(), second.cell(), 1, 0);
        assertEquals(cell(2, 0), first.cell());
        assertEquals(cell(1, 0), second.cell());
        assertEquals(0, jester.remaining(net.dnd5ecraft.rules.character.ClassFeature.CHAOS_POINTS));
        assertEquals(jester.id(), combat.directorScenePair(first.id(), second.cell(), 2));
        assertEquals(cell(1, 0), first.cell());
        assertThrows(IllegalStateException.class, () -> combat.directorScenePair(first.id(), second.cell(), 3));
    }

    @Test
    void tritonFogSpendsTheRacialChargeWithoutSpendingASlot() {
        var sheet = net.dnd5ecraft.rules.campaign.CampaignHero.FINNEAS.create();
        var finneas = CombatantEntry.fromSheet(new UUID(0, 1), 0, 20, cell(0, 0), sheet, 18, true,
                Map.of("fog_cloud", Ability.INTELLIGENCE), Set.of(), Set.of(), List.of(SWORD), Defenses.NONE);
        var enemy = creature(2, 1, 10, cell(3, 0), 100, 12, Set.of(), 0);
        var combat = encounter(FIXED_DICE, finneas, enemy);
        combat.start();
        combat.castTritonFog(finneas.id(), cell(2, 0), 0);
        assertEquals("fog_cloud", combat.concentration(finneas.id()).orElseThrow().spellId());
        assertEquals(0, finneas.remaining(net.dnd5ecraft.rules.character.ClassFeature.TRITON_FOG));
        assertEquals(4, finneas.slotsAvailable(Pool.REGULAR, 1));
        assertFalse(combat.canSee(finneas, enemy));
    }

    private static CombatantEntry caster(int id, int team, int initiative, Cell cell, Set<String> spells) {
        return entry(id, team, initiative, cell, 100, 12, 9, Map.of(ClassType.WIZARD, 9),
                spells, Set.of(Trait.HUMANOID), 0);
    }

    private static CombatantEntry creature(int id, int team, int initiative, Cell cell, int hp, int ac,
            Set<Trait> traits, int saves) {
        return entry(id, team, initiative, cell, hp, ac, 1, Map.of(), Set.of(), traits, saves);
    }

    private static CombatantEntry entry(int id, int team, int initiative, Cell cell, int hp, int ac, int level,
            Map<ClassType, Integer> classes, Set<String> spells, Set<Trait> traits, int saves) {
        Map<String, Ability> known = new java.util.HashMap<>();
        spells.forEach(spell -> known.put(spell, Ability.INTELLIGENCE));
        Map<Ability, Integer> savingThrows = new EnumMap<>(Ability.class);
        for (Ability ability : Ability.values()) savingThrows.put(ability, saves);
        var stats = new Stats(30, ac, traits.contains(Trait.METAL_ARMOR), 0, level,
                Map.of(Ability.INTELLIGENCE, 18, Ability.DEXTERITY, 18), savingThrows, classes, known,
                spells.contains("detect_magic") ? Set.of("detect_magic") : Set.of(), traits, List.of(SWORD));
        return new CombatantEntry(new UUID(0, id), "Creature " + id, team, initiative, cell, stats,
                new HitPoints(hp, 100, 0, 0), hp == 0 ? DeathSaveTracker.DYING : DeathSaveTracker.CONSCIOUS,
                Defenses.NONE, ExhaustionLevel.NONE, List.of());
    }

    private static Cell cell(int x, int z) { return new Cell(x, 0, z); }

    private static CombatManager encounter(IntUnaryOperator roller, CombatantEntry... entries) {
        return new CombatManager(new CombatEncounterState(List.of(entries)), field(cell -> false), roller);
    }

    private static CombatManager.Battlefield field(Predicate<Cell> blocked) {
        return new CombatManager.Battlefield() {
            @Override public boolean canOccupy(Cell cell) { return cell.y() == 0 && !blocked.test(cell); }
            @Override public boolean difficult(Cell cell) { return false; }
            @Override public boolean blocksCover(Cell cell) { return blocked.test(cell); }
            @Override public Light lightAt(Cell cell) { return Light.BRIGHT; }
        };
    }
}
