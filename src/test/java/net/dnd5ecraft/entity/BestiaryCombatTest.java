package net.dnd5ecraft.entity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import net.dnd5ecraft.combat.CombatEncounterState;
import net.dnd5ecraft.combat.CombatManager;
import net.dnd5ecraft.combat.CombatantEntry;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.LightingVisionEvaluator.Light;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.rules.equipment.ExhaustionLevel;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BestiaryCombatTest {
    @Test
    void packTacticsAppliesWhenAnotherConsciousAllyThreatensTheTarget() {
        var wolf = monster(1, 25, 0, 0, BestiaryPreset.WOLF);
        var companion = monster(2, 15, 1, 1, BestiaryPreset.WOLF);
        var prey = hero(3, 10, 1, 0, false);
        AtomicInteger checks = new AtomicInteger();
        int[] d20s = {3, 16, 4};
        var combat = combat(sides -> sides == 20 ? d20s[checks.getAndIncrement()] : sides, wolf, companion, prey);
        combat.start();
        combat.attack(wolf.id(), prey.id(), 0, 0);
        assertEquals(3, checks.get());
        assertEquals(90, prey.hitPoints().current());
        assertTrue(prey.view().conditions().contains(net.dnd5ecraft.rules.conditions.ConditionType.PRONE));
    }

    @Test
    void magicResistanceAndLegendaryResistanceChangeActualSpellSaves() {
        var cleric = hero(1, 25, 0, 0, true);
        var imp = monster(2, 10, 2, 0, BestiaryPreset.IMP);
        AtomicInteger saves = new AtomicInteger();
        var combat = combat(sides -> sides == 20 ? (saves.getAndIncrement() == 0 ? 1 : 20) : sides, cleric, imp);
        combat.start();
        combat.cast(cleric.id(), new CombatManager.CastRequest("sacred_flame", 0, List.of(imp.id())), 0);
        assertEquals(2, saves.get());
        assertEquals(10, imp.hitPoints().current());

        var otherCleric = hero(3, 25, 0, 0, true);
        var dragon = monster(4, 10, 5, 0, BestiaryPreset.ADULT_RED_DRAGON);
        var battle = combat(sides -> sides == 20 ? 1 : sides, otherCleric, dragon);
        battle.start();
        battle.cast(otherCleric.id(), new CombatManager.CastRequest("sacred_flame", 0, List.of(dragon.id())), 0);
        assertEquals(256, dragon.hitPoints().current());
        assertEquals(2, dragon.bestiary().orElseThrow().snapshot().legendaryResistances());
    }

    @Test
    void fireBreathUsesTheConeActionAndRecharge() {
        var hound = monster(1, 25, 0, 0, BestiaryPreset.HELL_HOUND);
        var front = hero(2, 15, 2, 0, false);
        var rear = hero(3, 10, -2, 0, false);
        var combat = combat(sides -> sides == 20 ? 1 : sides, hound, front, rear);
        combat.start();
        combat.useBreath(hound.id(), front.cell().center(), 0);
        assertEquals(64, front.hitPoints().current());
        assertEquals(100, rear.hitPoints().current());
        assertFalse(hound.bestiary().orElseThrow().canUseBreath());
        assertThrows(IllegalStateException.class, () -> combat.useBreath(hound.id(), front.cell().center(), 1));
        combat.endTurn(hound.id(), 2);
        combat.endTurn(front.id(), 3);
        combat.endTurn(rear.id(), 4);
        assertTrue(hound.bestiary().orElseThrow().canUseBreath());
    }

    @Test
    void lairActionLosesInitiativeTiesAndFiresOncePerRound() {
        var dragon = monster(1, 25, 0, 0, BestiaryPreset.ADULT_RED_DRAGON);
        var tied = hero(2, 20, 8, 0, false);
        var lower = hero(3, 10, 12, 0, false);
        dragon.bestiary().orElseThrow().setLairEnabled(true);
        var combat = combat(sides -> sides == 20 ? 1 : sides, dragon, tied, lower);
        combat.start();
        combat.endTurn(dragon.id(), 1);
        assertEquals(100, tied.hitPoints().current());
        combat.endTurn(tied.id(), 2);
        assertEquals(64, tied.hitPoints().current());
        assertEquals(100, lower.hitPoints().current());
        assertEquals(1L, combat.drainEvents().stream().filter(event -> event.detail().equals("lair_magma")).count());
        combat.endTurn(lower.id(), 3);
        assertEquals(64, tied.hitPoints().current());
    }

    @Test
    void legendaryAttackSpendsAChargeAtTheEndOfAnEnemyTurn() {
        var vampire = monster(1, 25, 0, 0, BestiaryPreset.VAMPIRE);
        var foe = hero(2, 20, 1, 0, false);
        var otherFoe = hero(3, 10, 5, 0, false);
        var combat = combat(sides -> sides == 20 ? 12 : sides, vampire, foe, otherFoe);
        combat.start();
        combat.endTurn(vampire.id(), 1);
        assertEquals(100, foe.hitPoints().current());
        combat.endTurn(foe.id(), 2);
        assertEquals(88, foe.hitPoints().current());
        assertEquals(2, vampire.bestiary().orElseThrow().legendaryActions());
        combat.endTurn(otherFoe.id(), 3);
        assertEquals(3, vampire.bestiary().orElseThrow().legendaryActions());
    }

    @Test
    void trollAtZeroRegeneratesUnlessAcidOrFireBlocksItsNextTurn() {
        var first = hero(1, 25, 0, 0, false);
        var troll = monster(2, 10, 2, 0, BestiaryPreset.TROLL);
        var combat = combat(sides -> sides, first, troll);
        combat.start();
        combat.damage(first.id(), troll.id(), Map.of(DamageType5e.SLASHING, 200), false);
        assertEquals(DeathSaveTracker.DYING, troll.deathSaves());
        combat.endTurn(first.id(), 1);
        assertEquals(10, troll.hitPoints().current());
        combat.damage(first.id(), troll.id(), Map.of(DamageType5e.ACID, 10), false);
        combat.endTurn(troll.id(), 2);
        combat.endTurn(first.id(), 3);
        assertEquals(DeathSaveTracker.DEAD, troll.deathSaves());
    }

    @Test
    void healingDraughtUsesOneActionOnItsOwnersTurn() {
        var hero = hero(1, 25, 0, 0, false);
        var enemy = monster(2, 10, 2, 0, BestiaryPreset.GOBLIN);
        var combat = combat(sides -> sides, hero, enemy);
        combat.start();
        combat.damage(enemy.id(), hero.id(), Map.of(DamageType5e.SLASHING, 10), false);
        assertThrows(IllegalStateException.class, () -> combat.useHealingItem(enemy.id(), 7, 0));
        combat.useHealingItem(hero.id(), 7, 1);
        assertEquals(97, hero.hitPoints().current());
        assertThrows(IllegalStateException.class, () -> combat.useHealingItem(hero.id(), 7, 2));
    }

    private static CombatantEntry monster(int id, int initiative, int x, int z, BestiaryPreset preset) {
        return entry(id, 1, initiative, x, z, preset.maxHp(), preset.combatStats(), preset.defenses(),
                new BestiaryTurnState(preset));
    }

    private static CombatantEntry hero(int id, int initiative, int x, int z, boolean canCast) {
        var stats = new CombatantEntry.Stats(30, 12, false, 0, 5, Map.of(Ability.WISDOM, 18),
                Map.of(), Map.of(ClassType.CLERIC, 5), canCast ? Map.of("sacred_flame", Ability.WISDOM) : Map.of(),
                Set.of(), Set.of(CombatantEntry.Trait.HUMANOID), List.of());
        return entry(id, 0, initiative, x, z, 100, stats, Defenses.NONE, null);
    }

    private static CombatantEntry entry(int id, int team, int initiative, int x, int z, int hp,
            CombatantEntry.Stats stats, Defenses defenses, BestiaryTurnState bestiary) {
        return new CombatantEntry(new UUID(0, id), "Creature " + id, team, initiative, new Cell(x, 0, z), stats,
                new HitPoints(hp, hp, 0, 0), DeathSaveTracker.CONSCIOUS, defenses, ExhaustionLevel.NONE, List.of(), bestiary);
    }

    private static CombatManager combat(IntUnaryOperator roller, CombatantEntry... actors) {
        return new CombatManager(new CombatEncounterState(List.of(actors)), new CombatManager.Battlefield() {
            @Override public boolean canOccupy(Cell cell) { return true; }
            @Override public boolean difficult(Cell cell) { return false; }
            @Override public boolean blocksCover(Cell cell) { return false; }
            @Override public Light lightAt(Cell cell) { return Light.BRIGHT; }
        }, roller);
    }
}
