package net.dnd5ecraft.combat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.rules.equipment.ExhaustionLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OnlineCombatTest {
    @Test
    void anOfflineTurnCanBeSkippedWithoutRemovingThePlayer() {
        var first = actor(1, 20, 0);
        var second = actor(2, 15, 0);
        var enemy = actor(3, 10, 1);
        var combat = manager(first, second, enemy);
        combat.start();
        assertFalse(combat.skipOfflineTurn(0));
        combat.connection(second.id(), true, 1);
        assertFalse(combat.skipOfflineTurn(2));
        combat.endTurn(first.id(), 3);
        assertEquals(second.id(), combat.snapshot().current().orElseThrow());
        assertFalse(second.canAct());
        assertFalse(second.canReact());
        assertFalse(combat.canHarm(enemy, second));
        assertTrue(combat.skipOfflineTurn(4));
        assertEquals(enemy.id(), combat.snapshot().current().orElseThrow());
        assertEquals(3, combat.snapshot().initiative().size());
        combat.connection(second.id(), false, 5);
        assertTrue(second.canAct());
        assertTrue(combat.canHarm(enemy, second));
        assertFalse(combat.skipOfflineTurn(6));
    }

    @Test
    void aiReturnsEmptyForMissingActorsAndOneCellPaths() {
        var foe = actor(1, 20, 1);
        var combat = manager(foe, actor(2, 10, 0));
        combat.start();
        assertTrue(MonsterAiDirector.plan(combat, null).isEmpty());
        assertTrue(MonsterAiDirector.plan(combat, UUID.randomUUID()).isEmpty());
        assertTrue(MonsterAiDirector.execute(combat, null, 0).isEmpty());
        assertTrue(MonsterAiDirector.firstStep(List.of(foe.cell())).isEmpty());
        assertEquals(new Cell(2, 0, 0), MonsterAiDirector.firstStep(List.of(foe.cell(), new Cell(2, 0, 0))).orElseThrow());
    }

    private static CombatantEntry actor(int id, int initiative, int team) {
        return new CombatantEntry(new UUID(0, id), "Actor " + id, team, initiative, new Cell(id, 0, 0),
                BestiaryPreset.GOBLIN.combatStats(), new HitPoints(10, 10, 0, 0), DeathSaveTracker.CONSCIOUS, Defenses.NONE,
                ExhaustionLevel.NONE, List.of());
    }

    private static CombatManager manager(CombatantEntry... entries) {
        return new CombatManager(new CombatEncounterState(List.of(entries)), new CombatManager.Battlefield() {
            public boolean canOccupy(Cell cell) { return true; }
            public boolean difficult(Cell cell) { return false; }
            public boolean blocksCover(Cell cell) { return false; }
            public LightingVisionEvaluator.Light lightAt(Cell cell) { return LightingVisionEvaluator.Light.BRIGHT; }
        }, sides -> sides);
    }
}
