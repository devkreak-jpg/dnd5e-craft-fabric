package net.dnd5ecraft.rules;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.PassiveSenseTracker;
import net.dnd5ecraft.rules.ability.ProficiencyLevel;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.character.*;
import net.dnd5ecraft.rules.conditions.ConditionInstance;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.AttunementManager;
import net.dnd5ecraft.rules.equipment.CoinPurse;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.rules.equipment.ExhaustionLevel;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static net.dnd5ecraft.rules.ability.Ability.*;
import static org.junit.jupiter.api.Assertions.*;

class AbilityAndCharacterTest {
    @ParameterizedTest
    @CsvSource({"1,-5", "3,-4", "8,-1", "9,-1", "10,0", "11,0", "12,1", "17,3", "20,5", "30,10"})
    void computesAbilityModifier(int score, int result) {
        assertEquals(result, Ability.modifier(score));
    }

    @ParameterizedTest
    @CsvSource({"1,2", "4,2", "5,3", "8,3", "9,4", "12,4", "13,5", "16,5", "17,6", "20,6"})
    void scalesProficiencyByTotalLevel(int level, int bonus) {
        assertEquals(bonus, ProficiencyLevel.bonusAtLevel(level));
        assertEquals(0, ProficiencyLevel.NONE.contribution(level));
        assertEquals(bonus / 2, ProficiencyLevel.HALF.contribution(level));
        assertEquals(bonus, ProficiencyLevel.PROFICIENT.contribution(level));
        assertEquals(2 * bonus, ProficiencyLevel.EXPERTISE.contribution(level));
    }

    @Test
    void resolvesD20AndPassiveChecks() {
        AtomicInteger die = new AtomicInteger(9);
        assertEquals(10, AbilityCheckEngine.rollD20(RollMode.ADVANTAGE, sides -> die.getAndIncrement()));
        assertEquals(11, AbilityCheckEngine.rollD20(RollMode.DISADVANTAGE, sides -> die.getAndIncrement()));
        assertEquals(RollMode.NORMAL, RollMode.from(true, true));
        assertTrue(AbilityCheckEngine.check(12, 13, RollMode.NORMAL, sides -> 1).succeeds());
        assertFalse(AbilityCheckEngine.check(-2, 20, RollMode.NORMAL, sides -> 20).succeeds());
        assertThrows(IllegalArgumentException.class, () -> AbilityCheckEngine.rollDie(20, sides -> 0));
        assertEquals(13, PassiveSenseTracker.score(3, RollMode.NORMAL));
        assertEquals(18, PassiveSenseTracker.score(3, RollMode.ADVANTAGE));
        assertEquals(8, PassiveSenseTracker.score(3, RollMode.DISADVANTAGE));
        assertTrue(PassiveSenseTracker.notices(18, 3, RollMode.ADVANTAGE));
        assertFalse(PassiveSenseTracker.notices(19, 3, RollMode.ADVANTAGE));
    }

    @Test
    void handlesStartingScoresAndPointBuy() {
        int[] costs = {0, 1, 2, 3, 4, 5, 7, 9};
        for (int score = 8; score <= 15; score++) {
            assertEquals(costs[score - 8], PointBuyCalculator.cost(score));
        }
        assertThrows(IllegalArgumentException.class, () -> PointBuyCalculator.cost(7));
        assertThrows(IllegalArgumentException.class, () -> PointBuyCalculator.cost(16));
        assertEquals(0, PointBuyCalculator.pointsRemaining(scores(15, 15, 15, 8, 8, 8)));
        assertEquals(-9, PointBuyCalculator.pointsRemaining(scores(15, 15, 15, 15, 8, 8)));
        assertThrows(IllegalArgumentException.class, () -> PointBuyCalculator.pointsRemaining(Map.of()));
        assertTrue(PointBuyCalculator.isStandardArray(scores(15, 14, 13, 12, 10, 8)));
        assertFalse(PointBuyCalculator.isStandardArray(scores(15, 13, 13, 12, 10, 8)));
        int[] rolls = {1, 4, 5, 6};
        AtomicInteger index = new AtomicInteger();
        assertEquals(15, PointBuyCalculator.rollStartingScore(sides -> {
            assertEquals(6, sides);
            return rolls[index.getAndIncrement()];
        }));
    }

    @Test
    void validatesMulticlassPrerequisitesAndCreationDefaults() {
        var abilityScores = scores(8, 14, 12, 13, 13, 12);
        var sheet = CharacterSheetData.create("Lune", RaceType.ELF, BackgroundType.SAGE, ClassType.FIGHTER, abilityScores);
        assertEquals(11, sheet.hitPoints().current());
        assertEquals(3, sheet.skillModifier(Skill.ARCANA));
        assertEquals(1, sheet.savingThrowModifier(STRENGTH));
        assertTrue(MulticlassValidator.qualifies(ClassType.FIGHTER, abilityScores));
        assertTrue(MulticlassValidator.canTakeLevel(sheet, ClassType.WIZARD));
        assertTrue(MulticlassValidator.canTakeLevel(sheet, ClassType.MONK));
        assertEquals(Set.of(ClassType.PALADIN), MulticlassValidator.unmetPrerequisites(sheet, ClassType.PALADIN));
        assertFalse(FeatType.GRAPPLER.meetsPrerequisites(sheet));

        var unqualifiedFighter = CharacterSheetData.create("Rhea", RaceType.HUMAN, BackgroundType.SAGE,
                ClassType.FIGHTER, scores(8, 8, 14, 14, 14, 14));
        assertEquals(Set.of(ClassType.FIGHTER),
                MulticlassValidator.unmetPrerequisites(unqualifiedFighter, ClassType.WIZARD));
        assertTrue(MulticlassValidator.canTakeLevel(unqualifiedFighter, ClassType.FIGHTER));
    }

    @Test
    void retainsTheSecondHalflingLuckyRoll() {
        AtomicInteger rolls = new AtomicInteger();
        assertEquals(1, RaceType.HALFLING.naturalD20(sides -> {
            rolls.incrementAndGet();
            return 1;
        }));
        assertEquals(2, rolls.get());
        assertEquals(0.68, RaceType.HALFLING.scale());
        assertEquals(60, RaceType.HALF_ORC.darkvisionFeet());
    }

    @Test
    void roundTripsEverySheetFieldThroughNbt() {
        UUID item = new UUID(0, 1);
        UUID source = new UUID(0, 2);
        var sheet = new CharacterSheetData("Астра", RaceType.TIEFLING, BackgroundType.SAGE,
                new CharacterSheetData.Personality("Freedom", "My crew", "Impatient"), ClassType.WIZARD,
                Map.of(ClassType.WIZARD, 4, ClassType.ROGUE, 3),
                Map.of(ClassType.WIZARD, SubclassType.ABJURATION, ClassType.ROGUE, SubclassType.THIEF),
                scores(10, 14, 12, 16, 11, 13), Map.of(Skill.ARCANA, ProficiencyLevel.EXPERTISE),
                Set.of(INTELLIGENCE, WISDOM), Set.of(FeatType.ALERT), 24_000,
                new HitPoints(0, 28, 2, 4), new CoinPurse(1, 2, 3, 4, 5),
                AttunementManager.EMPTY.attune(item, "dnd-5e-craft:moon_ring"), ExhaustionLevel.TWO,
                List.of(new ConditionInstance(ConditionType.GRAPPLED, Optional.of(source), 3),
                        new ConditionInstance(ConditionType.POISONED, Optional.empty(), ConditionInstance.INDEFINITE)),
                new DeathSaveTracker(DeathSaveTracker.State.DYING, 1, 2));
        CompoundTag nbt = sheet.toNbt();
        assertEquals(sheet, CharacterSheetData.fromNbt(nbt));
        assertEquals(9, sheet.skillModifier(Skill.ARCANA));
        nbt.putInt("Version", 2);
        assertThrows(IllegalArgumentException.class, () -> CharacterSheetData.fromNbt(nbt));
    }

    @Test
    void persistsSpentSlotsAndRestoresTheCorrectPools() {
        var sheet = CharacterSheetData.create("Lune", RaceType.HUMAN, BackgroundType.SAGE, ClassType.WIZARD,
                scores(8, 14, 12, 15, 13, 13)).levelUp(ClassType.WARLOCK, true, sides -> 4);
        var ledger = new net.dnd5ecraft.rules.spells.SpellSlotTable.Ledger(sheet.classLevels());
        assertTrue(ledger.spend(net.dnd5ecraft.rules.spells.SpellSlotTable.Pool.REGULAR, 1));
        assertTrue(ledger.spend(net.dnd5ecraft.rules.spells.SpellSlotTable.Pool.PACT, 1));
        sheet = sheet.withResources(sheet.resources().withSlots(ledger.spent())).withPersonality("Remembers every promise");
        var saved = CharacterSheetData.fromNbt(sheet.toNbt());
        assertEquals(sheet, saved);
        var shortRest = saved.shortRest(Optional.empty(), sides -> 4);
        assertEquals(0, shortRest.resources().slots().pact());
        assertEquals(1, shortRest.resources().slots().regular().get(0));
        var longRest = shortRest.longRest(true);
        assertEquals(net.dnd5ecraft.rules.spells.SpellSlotTable.SpentSlots.EMPTY, longRest.resources().slots());
        assertEquals("Remembers every promise", longRest.personality().description());
    }

    @Test
    void limitsFeatChoicesAndRaisesToughHitPointsAtFutureLevels() {
        var sheet = CharacterSheetData.create("Lune", RaceType.HUMAN, BackgroundType.SAGE, ClassType.FIGHTER,
                scores(15, 14, 12, 13, 13, 8));
        var original = sheet;
        assertThrows(IllegalStateException.class, () -> original.takeFeat(FeatType.TOUGH, null));
        for (int i = 0; i < 3; i++) sheet = sheet.levelUp(ClassType.FIGHTER, true, sides -> 4);
        var tough = sheet.takeFeat(FeatType.TOUGH, null);
        assertEquals(sheet.hitPoints().maximum() + 8, tough.hitPoints().maximum());
        assertThrows(IllegalStateException.class, () -> tough.takeFeat(FeatType.ALERT, null));
        var next = tough.levelUp(ClassType.FIGHTER, true, sides -> 4);
        assertEquals(tough.hitPoints().maximum() + 7, next.hitPoints().maximum());
        assertEquals(tough, CharacterSheetData.fromNbt(tough.toNbt()));
    }

    private static Map<Ability, Integer> scores(int... values) {
        Map<Ability, Integer> scores = new EnumMap<>(Ability.class);
        for (Ability ability : Ability.values()) {
            scores.put(ability, values[ability.ordinal()]);
        }
        return scores;
    }
}
