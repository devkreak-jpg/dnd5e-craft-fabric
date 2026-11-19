package net.dnd5ecraft.entity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.dnd5ecraft.combat.EncounterDifficultyCalculator;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.entity.BestiaryPreset.Feature;
import net.dnd5ecraft.entity.TriggerZoneSpec.Mode;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TriggerAndBestiaryTest {
    @Test
    void triggerOnlyRegistersNewEntrantsAndRespectsCooldownAndOneShot() {
        Cell a = new Cell(5, 1, 5);
        Cell b = new Cell(0, 3, 1);
        TriggerZoneSpec trap = TriggerZoneSpec.between(a, b, Mode.TRAP);
        assertTrue(trap.contains(a));
        assertTrue(trap.contains(b));
        assertFalse(trap.contains(new Cell(0, 4, 1)));
        assertThrows(IllegalArgumentException.class, () -> TriggerZoneSpec.between(a, new Cell(37, 1, 5), Mode.TRAP));
        UUID one = new UUID(0, 1);
        UUID two = new UUID(0, 2);
        var occupants = new TriggerOccupancy(true, 0);
        assertEquals(List.of(one), occupants.entrants(List.of(one), 10));
        occupants.activated(trap, 10);
        assertEquals(110L, occupants.nextActivation());
        assertEquals(List.of(), occupants.entrants(List.of(one, two), 100));
        assertEquals(List.of(), occupants.entrants(List.of(one, two), 110));
        assertEquals(List.of(), occupants.entrants(List.of(two), 111));
        assertEquals(List.of(one), occupants.entrants(List.of(one, two), 112));
        occupants.activated(TriggerZoneSpec.between(a, b, Mode.AMBUSH), 112);
        assertFalse(occupants.armed());
    }

    @Test
    void allFifteenPresetsProvideDistinctIdsAndPlayableStats() {
        assertEquals(15, BestiaryRegistry5e.presets().size());
        for (BestiaryPreset preset : BestiaryPreset.values()) {
            assertEquals(preset, BestiaryPreset.fromId(preset.id()));
            assertTrue(EncounterDifficultyCalculator.xp(preset.challenge()) > 0);
            assertTrue(preset.maxHp() > 0);
            assertEquals(6, preset.combatStats().abilityScores().size());
            assertTrue(preset.combatStats().attacks().stream().anyMatch(attack -> attack.melee()));
        }
        assertTrue(BestiaryPreset.WOLF.features().contains(Feature.PACK_TACTICS));
        assertTrue(BestiaryPreset.IMP.features().contains(Feature.MAGIC_RESISTANCE));
        assertEquals(Set.of(DamageType5e.POISON), BestiaryPreset.SKELETON.defenses().immunities());
    }

    @Test
    void monsterResourcesRoundTripAndRechargeIndependently() {
        var dragon = new BestiaryTurnState(BestiaryPreset.ADULT_RED_DRAGON);
        assertTrue(dragon.spendBreath());
        assertFalse(dragon.spendBreath());
        dragon.beginTurn(100, false, sides -> 4);
        assertFalse(dragon.canUseBreath());
        dragon.beginTurn(100, false, sides -> 5);
        assertTrue(dragon.canUseBreath());
        for (int i = 0; i < 3; i++) {
            assertTrue(dragon.useLegendaryResistance());
            assertTrue(dragon.spendLegendaryAction());
        }
        assertFalse(dragon.useLegendaryResistance());
        assertFalse(dragon.spendLegendaryAction());
        dragon.beginTurn(100, false, sides -> 1);
        assertEquals(3, dragon.legendaryActions());
        assertFalse(dragon.useLegendaryResistance());
        dragon.setLairEnabled(true);
        var loaded = new BestiaryTurnState(BestiaryPreset.ADULT_RED_DRAGON, dragon.snapshot());
        assertEquals(dragon.snapshot(), loaded.snapshot());
        loaded.recoverDailyResources();
        assertTrue(loaded.useLegendaryResistance());
        assertThrows(IllegalArgumentException.class, () -> new BestiaryTurnState(BestiaryPreset.WOLF).setLairEnabled(true));
    }

    @Test
    void regenerationUsesDamageTypesAndTheRelevantTurnBoundary() {
        var troll = new BestiaryTurnState(BestiaryPreset.TROLL);
        troll.onDamage(Map.of(DamageType5e.FIRE, 1));
        assertEquals(0, troll.beginTurn(10, false, sides -> 1));
        assertEquals(10, troll.beginTurn(10, false, sides -> 1));
        assertEquals(10, troll.beginTurn(0, false, sides -> 1));
        var vampire = new BestiaryTurnState(BestiaryPreset.VAMPIRE);
        vampire.onDamage(Map.of(DamageType5e.RADIANT, 0));
        assertEquals(20, vampire.beginTurn(10, false, sides -> 1));
        assertEquals(0, vampire.beginTurn(10, true, sides -> 1));
        assertEquals(0, vampire.beginTurn(0, false, sides -> 1));
    }
}
