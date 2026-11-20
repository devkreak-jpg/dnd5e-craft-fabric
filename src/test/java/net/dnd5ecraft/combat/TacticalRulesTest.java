package net.dnd5ecraft.combat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.dnd5ecraft.combat.CoverRaycaster.Cover;
import net.dnd5ecraft.combat.EncounterDifficultyCalculator.Difficulty;
import net.dnd5ecraft.combat.EncounterDifficultyCalculator.Monster;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.LightingVisionEvaluator.Light;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.combat.ReactionWindowManager.Offer;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.spells.AoEGeometry.Box;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.dnd5ecraft.rules.spells.SpellRegistry5e;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TacticalRulesTest {
    private static Cell cell(int x, int z) { return new Cell(x, 0, z); }

    @Test
    void movementFindsCheaperDetoursAndDoesNotCutBlockedCorners() {
        Cell origin = cell(0, 0);
        Cell goal = cell(2, 0);
        GridMovementTracker.Terrain terrain = new GridMovementTracker.Terrain() {
            @Override public boolean canOccupy(Cell cell) { return cell.z() >= 0 && !cell.equals(cell(0, 2)); }
            @Override public boolean difficult(Cell cell) { return cell.equals(cell(1, 0)); }
        };
        assertEquals(10, GridMovementTracker.stepCost(origin, cell(1, 0), terrain, false));
        assertEquals(15, GridMovementTracker.stepCost(origin, cell(1, 0), terrain, true));
        var path = GridMovementTracker.path(origin, goal, 10, terrain, false).orElseThrow();
        assertEquals(List.of(origin, cell(1, 1), goal), path.cells());
        assertEquals(10, path.costFeet());
        assertTrue(GridMovementTracker.path(origin, goal, 9, terrain, false).isEmpty());
        assertEquals(10, GridMovementTracker.reachable(origin, 10, terrain, false).get(goal).intValue());
        assertThrows(IllegalArgumentException.class, () -> GridMovementTracker.stepCost(cell(0, 1), cell(1, 2), terrain, false));
        assertThrows(IllegalArgumentException.class, () -> GridMovementTracker.stepCost(origin, cell(3, 0), terrain, false));
    }

    @Test
    void coverCountsAllEightRaysAndHandlesReverseTraversal() {
        var from = new Vec3(.5, 1.5, 1.5);
        var target = new Box(new Vec3(5.1, .05, .05), new Vec3(5.95, 3.95, 3.95));
        assertEquals(Cover.NONE, CoverRaycaster.cover(from, target, voxel -> false));
        assertEquals(Cover.HALF, CoverRaycaster.cover(from, target, voxel -> voxel.x() == 3 && voxel.y() <= 1));
        assertEquals(Cover.THREE_QUARTERS,
                CoverRaycaster.cover(from, target, voxel -> voxel.x() == 3 && (voxel.y() <= 1 || voxel.z() <= 1)));
        assertEquals(Cover.TOTAL, CoverRaycaster.cover(from, target, voxel -> voxel.x() == 3));
        assertEquals(2, Cover.HALF.bonus());
        assertEquals(5, Cover.THREE_QUARTERS.bonus());
        assertFalse(CoverRaycaster.clearRay(new Vec3(-.3, 1, 1), new Vec3(-4.1, 1, 1), voxel -> voxel.x() == -3));
        assertTrue(CoverRaycaster.clearRay(from, from, voxel -> true));
    }

    @Test
    void adjustsSightForLightingAndDarkvision() {
        assertEquals(RollMode.NORMAL, LightingVisionEvaluator.evaluate(Light.BRIGHT, 100, 0, true, false).sightCheckMode());
        assertEquals(RollMode.DISADVANTAGE, LightingVisionEvaluator.evaluate(Light.DIM, 30, 0, true, false).sightCheckMode());
        var dim = LightingVisionEvaluator.evaluate(Light.DIM, 30, 60, true, false);
        assertEquals(RollMode.NORMAL, dim.sightCheckMode());
        assertTrue(dim.colorVisible());
        var dimFromDarkvision = LightingVisionEvaluator.evaluate(Light.DARK, 60, 60, true, false);
        assertTrue(dimFromDarkvision.visible());
        assertFalse(dimFromDarkvision.colorVisible());
        assertEquals(RollMode.DISADVANTAGE, dimFromDarkvision.sightCheckMode());
        assertFalse(LightingVisionEvaluator.evaluate(Light.DARK, 65, 60, true, false).visible());
        assertFalse(LightingVisionEvaluator.evaluate(Light.MAGICAL_DARKNESS, 5, 60, true, false).visible());
        assertFalse(LightingVisionEvaluator.evaluate(Light.BRIGHT, 5, 0, false, false).visible());
        assertFalse(LightingVisionEvaluator.evaluate(Light.BRIGHT, 5, 0, true, true).visible());
    }

    @Test
    void turnEconomyHandlesSplitMovementDashAndOutOfTurnReactions() {
        var turn = new TurnEconomy();
        assertTrue(turn.spend(Resource.REACTION));
        assertFalse(turn.spend(Resource.REACTION));
        turn.startTurn();
        assertEquals(Set.of(Resource.values()), turn.snapshot(30).available());
        assertTrue(turn.move(10, 30));
        assertTrue(turn.dash(Resource.ACTION));
        assertTrue(turn.move(10, 30));
        assertEquals(40, turn.remainingMovement(30));
        assertEquals(10, turn.remainingMovement(15));
        assertTrue(turn.disengage(Resource.BONUS_ACTION));
        assertTrue(turn.disengaged());
        assertTrue(turn.spend(Resource.OBJECT_INTERACTION));
        assertFalse(turn.spend(Resource.OBJECT_INTERACTION));
        turn.endTurn();
        assertFalse(turn.canSpend(Resource.ACTION));
        assertFalse(turn.move(1, 30));
        assertTrue(turn.canSpend(Resource.REACTION));
        assertFalse(turn.disengaged());
    }

    @Test
    void bonusSpellRuleIsBidirectionalAndDoesNotPreventTwoActionSpells() {
        var turn = new TurnEconomy();
        var bonus = SpellRegistry5e.require("healing_word");
        var action = SpellRegistry5e.require("cure_wounds");
        var cantrip = SpellRegistry5e.require("fire_bolt");
        var reaction = SpellRegistry5e.require("shield");
        turn.startTurn();
        assertTrue(turn.cast(bonus));
        assertFalse(turn.canCast(action));
        assertFalse(turn.canCast(reaction));
        assertTrue(turn.cast(cantrip));
        turn.endTurn();
        assertTrue(turn.canCast(reaction));
        turn.startTurn();
        assertTrue(turn.cast(action));
        assertFalse(turn.canCast(bonus));
        turn.grantAction();
        assertTrue(turn.cast(action));
        turn.endTurn();
        turn.startTurn();
        assertTrue(turn.cast(cantrip));
        assertTrue(turn.cast(bonus));
    }

    @Test
    void reactionsHaveOneAnswerPerCreatureAndAnExactDeadline() {
        var reactions = new ReactionWindowManager();
        var one = new UUID(0, 1);
        var two = new UUID(0, 2);
        var window = reactions.open(List.of(new Offer(one, Kind.SHIELD), new Offer(one, Kind.UNCANNY_DODGE),
                new Offer(two, Kind.COUNTERSPELL)), 500);
        assertEquals(4000L, window.deadlineMillis());
        assertFalse(reactions.answer(window.id() + 1, one, Kind.SHIELD, 500));
        assertFalse(reactions.answer(window.id(), two, Kind.SHIELD, 500));
        assertTrue(reactions.answer(window.id(), one, Kind.SHIELD, 3999));
        assertFalse(reactions.answer(window.id(), one, Kind.UNCANNY_DODGE, 3999));
        assertFalse(reactions.closeIfResolved(3999));
        assertFalse(reactions.answer(window.id(), two, Kind.COUNTERSPELL, 4000));
        assertTrue(reactions.closeIfResolved(4000));
        assertTrue(reactions.current().isEmpty());
    }

    @Test
    void difficultyUsesPartySizeRatingsAndInclusiveThresholds() {
        var party = List.of(1, 1, 1, 1);
        assertEquals(Difficulty.EASY, EncounterDifficultyCalculator.assess(party, List.of(new Monster("1/2"))).difficulty());
        assertEquals(Difficulty.MEDIUM, EncounterDifficultyCalculator.assess(party, List.of(new Monster("1"))).difficulty());
        var pair = EncounterDifficultyCalculator.assess(party, List.of(new Monster("1/2"), new Monster("1/2")));
        assertEquals(200, pair.baseXp());
        assertEquals(300.0, pair.adjustedXp());
        assertEquals(Difficulty.HARD, pair.difficulty());
        assertEquals(2.0, EncounterDifficultyCalculator.assess(List.of(1, 1), List.of(new Monster("1"), new Monster("1"))).multiplier());
        assertEquals(.5, EncounterDifficultyCalculator.assess(List.of(1, 1, 1, 1, 1, 1), List.of(new Monster("1"))).multiplier());
        assertEquals(1.0, EncounterDifficultyCalculator.assess(party, List.of(new Monster("1"), new Monster("0", false))).multiplier());
        assertEquals(155_000, EncounterDifficultyCalculator.xp("30"));
        assertThrows(IllegalArgumentException.class, () -> EncounterDifficultyCalculator.xp("31"));
    }
}
