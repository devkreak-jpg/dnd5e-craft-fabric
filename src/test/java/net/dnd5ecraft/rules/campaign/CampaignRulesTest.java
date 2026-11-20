package net.dnd5ecraft.rules.campaign;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.dnd5ecraft.entity.CrowdTheme;
import net.dnd5ecraft.entity.EldritchCannonEntity.Mode;
import net.dnd5ecraft.entity.NpcBehaviorMode;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.character.SubclassType;
import net.dnd5ecraft.rules.equipment.ArmorFormulaEngine;
import net.dnd5ecraft.rules.equipment.ArmorFormulaEngine.Formula;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.equipment.ExhaustionLevel;
import net.dnd5ecraft.rules.equipment.WeaponProperty;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon;
import net.dnd5ecraft.rules.spells.SpellAccess;
import net.dnd5ecraft.rules.spells.SpellSlotTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CampaignRulesTest {
    @Test
    void finneasHasExactMulticlassScoresSpellsAndDefenses() {
        var character = CampaignHero.FINNEAS.create();
        assertEquals(Map.of(ClassType.ARTIFICER, 3, ClassType.WIZARD, 1), character.classLevels());
        assertEquals(SubclassType.ARTILLERIST, character.subclasses().get(ClassType.ARTIFICER));
        assertEquals(List.of(11, 12, 15, 15, 10, 12), scores(character));
        assertEquals(30, character.hitPoints().maximum());
        assertEquals(18, 1 + ArmorFormulaEngine.armorClass(Formula.SCALE_MAIL, 12, 15, 10, true));
        assertEquals(12, 10 + character.skillModifier(Skill.PERCEPTION));
        assertEquals(40, character.race().swimmingFeet());
        assertEquals(60, character.race().darkvisionFeet());
        assertEquals(Set.of(DamageType5e.COLD), character.race().resistances());
        assertEquals(Set.of("mending", "fire_bolt", "message", "lightning_lure", "prestidigitation", "booming_blade",
                "thunderwave", "shield", "absorb_elements", "cure_wounds", "faerie_fire", "grease", "disguise_self", "fog_cloud"),
                SpellAccess.available(character).keySet());
        assertEquals(15, CampaignRules.cannonHealth(character.classLevels().get(ClassType.ARTIFICER)));
        assertEquals(Set.of(Mode.FLAMETHROWER, Mode.FORCE_BALLISTA, Mode.PROTECTOR), Set.of(Mode.values()));
        assertEquals(1, character.remaining(ClassFeature.ELDRITCH_CANNON));
        assertEquals(1, character.remaining(ClassFeature.ARCANE_RECOVERY));
        assertEquals(4, new SpellSlotTable.Ledger(character.classLevels()).available(SpellSlotTable.Pool.REGULAR, 1));
    }

    @Test
    void alexanderAndMinotaurPreserveTheirAbilityScoresAndArmorCalculations() {
        var alex = CampaignHero.ALEXANDER.create();
        assertEquals(List.of(20, 18, 18, 10, 12, 10), scores(alex));
        assertEquals(50, alex.hitPoints().maximum());
        assertEquals(18, ArmorFormulaEngine.armorClass(Formula.BARBARIAN, 18, 18, 12, false));
        assertEquals(15, CampaignRules.saveDc(2, Ability.modifier(alex.score(Ability.STRENGTH))));
        assertEquals(List.of(6, 8, 10, 12), List.of(4, 6, 10, 14).stream().map(CampaignRules::worldbreakerDie).toList());

        var minotaur = CampaignHero.MINOTAUR.create();
        assertEquals(List.of(16, 12, 16, 8, 12, 10), scores(minotaur));
        assertEquals(40, minotaur.hitPoints().maximum());
        assertEquals(17, ArmorFormulaEngine.armorClass(Formula.SPLINT, 12, 16, 12, false));
        assertEquals(3, minotaur.remaining(ClassFeature.UNLEASH_INCARNATION));
        assertEquals(16, CampaignRules.echoArmor(2));
        assertEquals(15, CampaignRules.echoSwapCostFeet());
        assertTrue(CampaignRules.echoInRange(6));
        assertFalse(CampaignRules.echoInRange(6.01));
    }

    @Test
    void harlequinResourcesHonorTheSignRuleAndRoleExpiresAtLongRest() {
        var sheet = CampaignHero.HARLEQUIN.create();
        assertEquals(0, sheet.remaining(ClassFeature.CHAOS_POINTS));
        assertEquals(List.of(3, 4, 5, 6, 7), java.util.stream.IntStream.rangeClosed(1, 5).map(CampaignRules::chaosLimit).boxed().toList());
        assertEquals(-1, CampaignRules.chaosChange(true, true, true));
        assertEquals(-1, CampaignRules.chaosChange(false, false, true));
        assertEquals(1, CampaignRules.chaosChange(true, false, true));
        assertEquals(1, CampaignRules.chaosChange(false, true, true));
        assertEquals(0, CampaignRules.chaosChange(true, false, false));

        var role = sheet.castOfTheDie(Ability.DEXTERITY);
        assertEquals(20, role.score(Ability.DEXTERITY));
        assertThrows(IllegalStateException.class, () -> role.castOfTheDie(Ability.CHARISMA));
        assertEquals(20, role.shortRest(java.util.Optional.empty(), sides -> 1).score(Ability.DEXTERITY));
        assertEquals(16, role.longRest(true).score(Ability.DEXTERITY));
        assertEquals(1, role.longRest(true).remaining(ClassFeature.CAST_OF_DIE));
    }

    @Test
    void speedAndCrowdProfilesStayWithinTheirDesignBounds() {
        assertEquals(6, RaceType.TRITON.walkingBlocks());
        assertEquals(1, CampaignRules.walkMultiplier(30, false, ExhaustionLevel.NONE, false));
        assertEquals(.85, CampaignRules.walkMultiplier(25, false, ExhaustionLevel.NONE, false));
        assertEquals(4.0 / 3, CampaignRules.walkMultiplier(40, false, ExhaustionLevel.NONE, false));
        assertEquals(.67, CampaignRules.walkMultiplier(30, true, ExhaustionLevel.NONE, false), 1e-10);
        assertEquals(.5, CampaignRules.walkMultiplier(30, false, ExhaustionLevel.TWO, false));
        assertEquals(0, CampaignRules.walkMultiplier(30, false, ExhaustionLevel.FIVE, false));
        assertEquals(0, CampaignRules.walkMultiplier(30, false, ExhaustionLevel.NONE, true));
        for (var behavior : NpcBehaviorMode.values()) assertEquals(behavior, NpcBehaviorMode.saved(behavior.name()));
        assertEquals(NpcBehaviorMode.WANDER, NpcBehaviorMode.saved("unknown"));

        var random = new Random(70);
        for (var theme : CrowdTheme.values()) for (int i = 0; i < 100; i++) {
            var sample = theme.random(random);
            assertFalse(sample.name().isBlank());
            assertTrue(sample.texture().matches("dnd-5e-craft:textures/entity/crowd/" + theme.id() + "_[0-2]\\.png"));
            assertTrue(sample.scale() >= .75f && sample.scale() <= 1.15f);
            assertTrue(sample.radius() >= 4 && sample.radius() <= 16);
            assertNotNull(sample.weapon().stats());
        }
    }

    @Test
    void weaponCatalogDefinesAllDamageAndVersatileProfiles() {
        assertEquals(37, Weapon.values().length);
        for (var weapon : Weapon.values()) {
            var stats = weapon.stats();
            assertEquals(weapon != Weapon.NET, stats.damageType().isPresent());
            assertEquals(stats.has(WeaponProperty.VERSATILE), stats.twoHandedDamage().isPresent());
            assertTrue(stats.longRangeFeet() >= stats.normalRangeFeet());
        }
        assertEquals(1, Weapon.BLOWGUN.stats().flatDamage());
        assertEquals(2, Weapon.GREATSWORD.stats().damage().count());
        assertEquals(6, Weapon.GREATSWORD.stats().damage().sides());
        assertTrue(Weapon.WHIP.stats().has(WeaponProperty.REACH));
        assertTrue(Weapon.NET.stats().has(WeaponProperty.SPECIAL));
    }

    private static List<Integer> scores(CharacterSheetData sheet) {
        return java.util.Arrays.stream(Ability.values()).map(sheet::score).toList();
    }
}
