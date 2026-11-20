package net.dnd5ecraft.rules;

import net.dnd5ecraft.rules.campaign.TacticalFeedback;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import java.util.regex.Pattern;
import net.dnd5ecraft.combat.EncounterDifficultyCalculator;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.BestiaryRegistry5e;
import net.dnd5ecraft.entity.CrowdTheme;
import net.dnd5ecraft.entity.NpcBehaviorMode;
import net.dnd5ecraft.rules.campaign.CampaignHero;
import net.dnd5ecraft.rules.campaign.CampaignRules;
import net.dnd5ecraft.rules.spells.SpellRegistry5e;
import net.dnd5ecraft.network.CombatHudView;
import net.dnd5ecraft.network.PlayerSessionStore;
import net.dnd5ecraft.network.Protocol;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.CheckResult;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.DiceRoller;
import net.dnd5ecraft.rules.ability.PassiveSenseTracker;
import net.dnd5ecraft.rules.ability.ProficiencyLevel;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.ability.TabletopRolls;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.MulticlassValidator;
import net.dnd5ecraft.rules.character.PointBuyCalculator;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.ArmorFormulaEngine;
import net.dnd5ecraft.rules.equipment.ArmorFormulaEngine.Formula;
import net.dnd5ecraft.rules.equipment.DamagePipeline;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.equipment.ExhaustionLevel;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon;
import net.dnd5ecraft.rules.spells.AoEGeometry;
import net.dnd5ecraft.rules.spells.AoEGeometry.Box;
import net.dnd5ecraft.rules.spells.AoEGeometry.Template;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.dnd5ecraft.rules.spells.ConcentrationManager;

import static net.dnd5ecraft.rules.ability.Ability.*;
import static net.dnd5ecraft.rules.equipment.DamageType5e.*;

public final class RulesSelfTestRunner {
    private int checks;

    private RulesSelfTestRunner() {}

    public static void main(String[] arguments) {
        var runner = new RulesSelfTestRunner();
        runner.abilities();
        runner.characters();
        runner.armor();
        runner.damage();
        runner.conditions();
        runner.geometry();
        runner.difficulty();
        runner.hardening();
        runner.tabletop();
        runner.campaign();
        runner.studio();
        runner.masterwork();
        runner.online();
        System.out.printf("%d assertions passed%n", runner.checks);
    }

    private void studio() {
        int[] expected = {70, 91, 49, 5, 95};
        RollMode[] modes = {RollMode.NORMAL, RollMode.ADVANTAGE, RollMode.DISADVANTAGE, RollMode.NORMAL, RollMode.NORMAL};
        int[] armor = {15, 15, 15, 80, 1};
        int[] bonus = {8, 8, 8, -5, 50};
        for (int i = 0; i < modes.length; i++)
            check("5e hit chance " + i, (long)expected[i], Math.round(100 * TacticalFeedback.hitChance(armor[i], bonus[i], modes[i])));
        var limiter = new TacticalFeedback.PingLimiter();
        check("first ping", true, limiter.take(0));
        check("early ping", false, limiter.take(999_999_999));
        check("one second ping", true, limiter.take(1_000_000_000));
        check("ruler 5 blocks", 25, TacticalFeedback.rulerFeet(TacticalFeedback.rulerBlocks(4.9)));
        try {
            var atlas = javax.imageio.ImageIO.read(Path.of("src/main/resources/assets/dnd-5e-craft/textures/entity/racial_cosmetics_atlas.png").toFile());
            check("64px RGBA atlas", List.of(64, 64, 4, true), List.of(atlas.getWidth(), atlas.getHeight(),
                    atlas.getColorModel().getNumComponents(), atlas.getColorModel().hasAlpha()));
            for (var renderer : List.of("Spinning3dDieRenderer", "FloatingCombatBarRenderer")) {
                var file = Path.of("src/client/java/net/dnd5ecraft/client/render", renderer + ".java");
                check("shader-safe " + renderer, false, Files.readString(file).contains("RenderType.debugQuads()"));
            }
        } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
    }

    private void masterwork() {
        var state = new net.dnd5ecraft.entity.BestiaryTurnState(net.dnd5ecraft.entity.BestiaryPreset.CAPTAIN_MORGAN);
        check("Morgan has two legendary actions", 2, state.legendaryActions());
        check("Morgan above half health", false, state.enterBloodied(43));
        check("Morgan enters phase two", true, state.enterBloodied(42));
        check("Morgan only changes phase once", false, state.enterBloodied(30));
        var half = net.dnd5ecraft.combat.CoverRaycaster.Cover.HALF;
        var open = net.dnd5ecraft.combat.CoverRaycaster.Cover.NONE;
        check("skirmisher prefers partial cover", 35,
                net.dnd5ecraft.combat.MonsterAiDirector.rangedTileScore(half, false, true, 30)
                - net.dnd5ecraft.combat.MonsterAiDirector.rangedTileScore(open, false, true, 30));

        Path textures = Path.of("src/main/resources/assets/dnd-5e-craft/textures");
        for (var weapon : net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon.values())
            verifyPng(textures.resolve("item/" + weapon.name().toLowerCase(java.util.Locale.ROOT) + ".png"), 32, 32);
        var armor = java.util.List.of("studded_leather_armor", "scale_mail", "breastplate", "half_plate", "splint_armor", "plate_armor");
        for (String item : armor) {
            verifyPng(textures.resolve("item/" + item + ".png"), 32, 32);
            for (int layer = 1; layer <= 2; layer++)
                verifyPng(textures.resolve("models/armor/" + item + "_layer_" + layer + ".png"), 64, 32);
        }
        for (String item : java.util.List.of("sentinel_shield", "repeating_light_crossbow", "worldbreaker_wraps", "harlequin_deck_wand",
                "echo_sigil", "eldritch_cannon_controller", "crowd_spawner_staff", "dm_master_wand", "bag_of_holding"))
            verifyPng(textures.resolve("item/" + item + ".png"), 32, 32);
        var skins = net.dnd5ecraft.entity.NpcSkinCatalog.ALL;
        check("grand bestiary skin count", true, skins.size() >= 64);
        for (var skin : skins) verifyPng(textures.resolve("entity/skins/" + skin.id() + ".png"), 64, 64);
    }

    private void online() {
        var catalog = net.dnd5ecraft.entity.BestiaryRegistry5e.presets();
        check("grand bestiary template count", true, catalog.size() >= 40);
        var assigned = new java.util.HashSet<net.dnd5ecraft.entity.NpcSkinCatalog>();
        for (var preset : catalog) {
            int xp = net.dnd5ecraft.combat.EncounterDifficultyCalculator.xp(preset.challenge());
            check(preset.id() + " template", true, xp >= 25 && xp <= 8400 && preset.maxHp() > 0
                    && preset.armor() > 0 && preset.combatRole() != null && !preset.attacks().isEmpty()
                    && preset.scale() >= .65f && preset.scale() <= 2.2f
                    && net.dnd5ecraft.entity.BestiaryRegistry5e.defaultSkin(preset).filter(assigned::add).isPresent());
        }
        var hashes = new java.util.HashSet<String>();
        for (var skin : net.dnd5ecraft.entity.NpcSkinCatalog.ALL) {
            var path = Path.of("src/main/resources/assets/dnd-5e-craft/textures/entity/skins", skin.id() + ".png");
            try {
                var image = javax.imageio.ImageIO.read(path.toFile());
                var bytes = java.nio.ByteBuffer.allocate(64 * 64 * Integer.BYTES);
                for (int pixel : image.getRGB(0, 0, 64, 64, null, 0, 64)) bytes.putInt(pixel);
                check(skin.id() + " unique skin pixels", true, hashes.add(java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes.array()))));
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
                throw new AssertionError(path.toString(), failure);
            }
        }
        var coins = new net.dnd5ecraft.rules.equipment.CoinPurse(0, 0, 0, 22, 0);
        var unit = net.dnd5ecraft.rules.equipment.CoinPurse.Denomination.GP;
        var transfer = coins.transfer(coins, 7, unit).orElseThrow();
        check("paid exact coins", 15L, transfer.payer().gold());
        check("received exact coins", 29L, transfer.payee().gold());
        check("insufficient coins", true, coins.transfer(coins, 23, unit).isEmpty());

        var participants = java.util.List.of(new java.util.UUID(0, 1), new java.util.UUID(0, 2));
        var ready = new net.dnd5ecraft.rules.campaign.PartyReadyCheck("Test", participants);
        ready.respond(ready.id(), participants.get(0), true);
        check("waiting for another player", false, ready.allReady());
        ready.respond(ready.id(), participants.get(1), true);
        check("everyone ready", true, ready.allReady());

        var left = onlineActor(participants.get(0), 0, 20);
        var right = onlineActor(participants.get(1), 1, 15);
        var combat = new net.dnd5ecraft.combat.CombatManager(new net.dnd5ecraft.combat.CombatEncounterState(
                java.util.List.of(left, right)), new net.dnd5ecraft.combat.CombatManager.Battlefield() {
            public boolean canOccupy(net.dnd5ecraft.combat.GridMovementTracker.Cell cell) { return true; }
            public boolean difficult(net.dnd5ecraft.combat.GridMovementTracker.Cell cell) { return false; }
            public boolean blocksCover(net.dnd5ecraft.combat.GridMovementTracker.Cell cell) { return false; }
            public net.dnd5ecraft.combat.LightingVisionEvaluator.Light lightAt(net.dnd5ecraft.combat.GridMovementTracker.Cell cell) {
                return net.dnd5ecraft.combat.LightingVisionEvaluator.Light.BRIGHT;
            }
        }, sides -> sides);
        combat.start();
        check("can only skip offline actors", false, combat.skipOfflineTurn(0));
        combat.connection(left.id(), true, 1);
        check("offline turn advances", true, combat.skipOfflineTurn(2));
        check("offline actor stays in initiative", 2, combat.snapshot().initiative().size());
        check("AI with null actor", true, net.dnd5ecraft.combat.MonsterAiDirector.plan(combat, null).isEmpty());
    }

    private static net.dnd5ecraft.combat.CombatantEntry onlineActor(java.util.UUID id, int team, int initiative) {
        return new net.dnd5ecraft.combat.CombatantEntry(id, "Tester " + team, team, initiative,
                new net.dnd5ecraft.combat.GridMovementTracker.Cell(team, 0, 0),
                net.dnd5ecraft.entity.BestiaryPreset.GOBLIN.combatStats(),
                new net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints(10, 10, 0, 0),
                net.dnd5ecraft.rules.conditions.DeathSaveTracker.CONSCIOUS,
                net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses.NONE,
                net.dnd5ecraft.rules.equipment.ExhaustionLevel.NONE, java.util.List.of());
    }

    private void verifyPng(Path path, int width, int height) {
        try {
            var png = Files.readAllBytes(path);
            var image = javax.imageio.ImageIO.read(path.toFile());
            check(path.getFileName() + " RGBA dimensions", true, png[25] == 6 && image != null
                    && image.getWidth() == width && image.getHeight() == height && image.getColorModel().hasAlpha());
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot read " + path, failure);
        }
    }

    private void campaign() {
        var finneas = CampaignHero.FINNEAS;
        check("Finneas classes", Map.of(ClassType.ARTIFICER, 3, ClassType.WIZARD, 1), finneas.levels());
        check("Finneas scores", List.of(11, 12, 15, 15, 10, 12), finneas.scores());
        check("Finneas vitality", List.of(30, 18), List.of(finneas.hp(), finneas.armor()));
        check("Finneas spells", 14, finneas.spells().size());
        for (var spell : finneas.spells()) check("campaign spell " + spell, true, SpellRegistry5e.find(spell).isPresent());
        check("Triton swim speed", 40, finneas.race().swimmingFeet());
        check("Triton darkvision", 60, finneas.race().darkvisionFeet());
        check("Cannon health", 15, CampaignRules.cannonHealth(3));
        var alexander = CampaignHero.ALEXANDER;
        check("Alexander vitality", List.of(50, 18), List.of(alexander.hp(), alexander.armor()));
        check("Level 4 fist", 6, CampaignRules.worldbreakerDie(4));
        check("Burying Hands save", 15, CampaignRules.saveDc(2, 5));
        check("Chaos limit", 6, CampaignRules.chaosLimit(4));
        check("Helpful to ally", -1, CampaignRules.chaosChange(true, true, true));
        check("Helpful to enemy", 1, CampaignRules.chaosChange(false, true, true));
        check("No gain when target saves", 0, CampaignRules.chaosChange(true, false, false));
        check("Minotaur vitality", List.of(40, 17), List.of(CampaignHero.MINOTAUR.hp(), CampaignHero.MINOTAUR.armor()));
        check("Echo swap", 15, CampaignRules.echoSwapCostFeet());
        check("Echo in radius", true, CampaignRules.echoInRange(6));
        check("Echo out of radius", false, CampaignRules.echoInRange(6.01));
        check("Weapons", 37, Weapon.values().length);
        for (var weapon : Weapon.values()) check("Weapon " + weapon, weapon != Weapon.NET, weapon.stats().damageType().isPresent());
        check("30 ft speed", 1.0, CampaignRules.walkMultiplier(30, false, ExhaustionLevel.NONE, false));
        check("25 ft speed", .85, CampaignRules.walkMultiplier(25, false, ExhaustionLevel.NONE, false));
        check("40 ft speed", 4.0 / 3, CampaignRules.walkMultiplier(40, false, ExhaustionLevel.NONE, false));
        check("Exhaustion halves speed", .5, CampaignRules.walkMultiplier(30, false, ExhaustionLevel.TWO, false));
        check("Exhaustion stops movement", 0.0, CampaignRules.walkMultiplier(30, false, ExhaustionLevel.FIVE, false));
        check("Five feet per block", 6.0, finneas.race().walkingBlocks());
        for (var behavior : NpcBehaviorMode.values()) check("Behavior " + behavior, behavior, NpcBehaviorMode.saved(behavior.name()));
        var random = new java.util.Random(17);
        for (var theme : CrowdTheme.values()) {
            var visitor = theme.random(random);
            check("Crowd scale " + theme, true, visitor.scale() >= .75 && visitor.scale() <= 1.15);
            check("Crowd radius " + theme, true, visitor.radius() >= 4 && visitor.radius() <= 16);
        }
    }

    private void abilities() {
        check("score 1", -5, Ability.modifier(1));
        check("score 9", -1, Ability.modifier(9));
        check("score 10", 0, Ability.modifier(10));
        check("score 19", 4, Ability.modifier(19));
        check("score 30", 10, Ability.modifier(30));
        check("proficiency level 1", 2, ProficiencyLevel.bonusAtLevel(1));
        check("proficiency level 9", 4, ProficiencyLevel.bonusAtLevel(9));
        check("proficiency level 20", 6, ProficiencyLevel.bonusAtLevel(20));
        check("Jack of All Trades", 2, AbilityCheckEngine.jackOfAllTrades(2, 9, ProficiencyLevel.NONE));
        check("Jack of All Trades preserves proficiency", 4, AbilityCheckEngine.jackOfAllTrades(2, 9, ProficiencyLevel.PROFICIENT));
        Map<Skill, Ability> skills = Map.ofEntries(
                Map.entry(Skill.ACROBATICS, DEX), Map.entry(Skill.ANIMAL_HANDLING, WIS), Map.entry(Skill.ARCANA, INT),
                Map.entry(Skill.ATHLETICS, STR), Map.entry(Skill.DECEPTION, CHA), Map.entry(Skill.HISTORY, INT),
                Map.entry(Skill.INSIGHT, WIS), Map.entry(Skill.INTIMIDATION, CHA), Map.entry(Skill.INVESTIGATION, INT),
                Map.entry(Skill.MEDICINE, WIS), Map.entry(Skill.NATURE, INT), Map.entry(Skill.PERCEPTION, WIS),
                Map.entry(Skill.PERFORMANCE, CHA), Map.entry(Skill.PERSUASION, CHA), Map.entry(Skill.RELIGION, INT),
                Map.entry(Skill.SLEIGHT_OF_HAND, DEX), Map.entry(Skill.STEALTH, DEX), Map.entry(Skill.SURVIVAL, WIS));
        check("skill count", 18, Skill.values().length);
        for (Skill skill : Skill.values()) check(skill.name(), skills.get(skill), skill.ability());
        check("passive perception", 14, PassiveSenseTracker.score(4, RollMode.NORMAL));
        check("passive investigation with advantage", 17, PassiveSenseTracker.score(2, RollMode.ADVANTAGE));
        check("passive insight with disadvantage", 8, PassiveSenseTracker.score(3, RollMode.DISADVANTAGE));
        check("halfling rerolls natural one", 16, RaceType.HALFLING.naturalD20(dice(1, 16)));
        check("lucky rerolls only once", 1, new DiceRoller(dice(1, 1, 20)).d20(RollMode.NORMAL, 0, true).total());
        check("great weapon fighting rerolls only once", 8, new DiceRoller(dice(1, 2, 6)).roll("2d6", false, true).total());
        check("4d6 keep three", 15, new DiceRoller(dice(1, 4, 5, 6)).roll("4d6kh3").total());
        check("dice with flat bonus", 11, new DiceRoller(dice(2, 6)).roll("2d6+3").total());
        check("seed repeatability", new DiceRoller(31).roll("2d6"), new DiceRoller(31).roll("2d6"));
        check("help cancels disadvantage", RollMode.NORMAL, AbilityCheckEngine.withHelp(true, false, true));
        check("group passes exactly half", true, AbilityCheckEngine.groupSucceeds(List.of(new CheckResult(10, 0, 10), new CheckResult(9, 0, 10))));
        check("contested tie preserves defender", false, AbilityCheckEngine.winsContest(new CheckResult(11, 0, 0), new CheckResult(10, 1, 0)));
    }

    private void characters() {
        var stats = new EnumMap<Ability, Integer>(Ability.class);
        for (Ability ability : Ability.values()) stats.put(ability, PointBuyCalculator.STANDARD_ARRAY.get(ability.ordinal()));
        check("standard array", true, PointBuyCalculator.isStandardArray(stats));
        check("27 point buy", 0, PointBuyCalculator.pointsRemaining(stats));
        stats.put(STR, 8);
        stats.put(DEX, 13);
        check("fighter alternate prerequisite", true, MulticlassValidator.qualifies(ClassType.FIGHTER, stats));
        check("paladin dual requirement", false, MulticlassValidator.qualifies(ClassType.PALADIN, stats));
        check("combined caster level", 5, MulticlassValidator.spellcasterLevel(Map.of(ClassType.WIZARD, 3, ClassType.PALADIN, 4)));
        check("fighter extra attack", 3, ClassType.FIGHTER.attacks(11));
        check("paladin lay on hands", 20, ClassFeature.LAY_ON_HANDS.capacity(Map.of(ClassType.PALADIN, 4), 0, RaceType.HUMAN));
    }

    private void armor() {
        check("unarmored", 14, ac(Formula.UNARMORED, false, false));
        check("barbarian defense", 17, ac(Formula.BARBARIAN, false, false));
        check("monk defense", 16, ac(Formula.MONK, false, false));
        check("mage armor", 17, ac(Formula.MAGE_ARMOR, false, false));
        check("light armor", 15, ac(Formula.LEATHER, false, false));
        check("medium armor", 16, ac(Formula.BREASTPLATE, false, false));
        check("medium armor master", 17, ac(Formula.BREASTPLATE, false, true));
        check("heavy armor", 18, ac(Formula.PLATE, false, false));
        check("shield", 20, ac(Formula.PLATE, true, false));
        check("heavy armor hinders stealth", true, Formula.PLATE.stealthDisadvantage());
        check("heavy armor speed penalty", 10, Formula.PLATE.speedPenaltyFeet(14, false));
        check("dwarf ignores heavy armor speed penalty", 0, Formula.PLATE.speedPenaltyFeet(8, true));
    }

    private void damage() {
        var hp = new HitPoints(10, 10, 3, 2, 4);
        check("immunity", 0, new Defenses(Set.of(FIRE), Set.of(), Set.of()).adjust(FIRE, 33));
        check("resistance rounds down", 3, new Defenses(Set.of(), Set.of(FIRE), Set.of()).adjust(FIRE, 7));
        check("vulnerability", 14, new Defenses(Set.of(), Set.of(), Set.of(FIRE)).adjust(FIRE, 7));
        var delta = DamagePipeline.apply(Map.of(FIRE, 12), Defenses.NONE, hp);
        check("ward precedes beast HP", 2, delta.wardAbsorbed());
        check("beast precedes temp HP", 4, delta.beastAbsorbed());
        check("temp HP precedes character HP", 3, delta.temporaryAbsorbed());
        check("beast overflow", 7, delta.remaining().current());
        check("temporary HP does not stack", 3, hp.grantTemporary(2).temporary());
        var humanoid = new HitPoints(5, 10, 0, 0);
        var enduring = DamagePipeline.apply(Map.of(FIRE, 5), Defenses.NONE, humanoid, true);
        check("Relentless Endurance uses its charge", true, enduring.enduranceUsed());
        check("Relentless Endurance leaves a single hit point", 1, enduring.remaining().current());
        var lethal = DamagePipeline.apply(Map.of(FIRE, 15), Defenses.NONE, humanoid, true);
        check("instant death beats Relentless Endurance", DeathSaveTracker.DEAD, DeathSaveTracker.CONSCIOUS.afterDamage(humanoid, lethal, false));
        var unconscious = DamagePipeline.apply(Map.of(FIRE, 5), Defenses.NONE, humanoid);
        check("zero HP stays zero", 0, unconscious.remaining().current());
        check("zero HP is dying", DeathSaveTracker.DYING, DeathSaveTracker.CONSCIOUS.afterDamage(humanoid, unconscious, false));
        check("critical doubles dice", 12, new DamagePipeline.Dice(1, 6).roll(true, sides -> 6));
        check("sneak attack with ally", true, DamagePipeline.canSneakAttack(Weapon.DAGGER.stats(), RollMode.NORMAL, true, false));
        check("sneak attack denied with disadvantage", false, DamagePipeline.canSneakAttack(Weapon.DAGGER.stats(), RollMode.DISADVANTAGE, true, false));
        check("sneak attack once per turn", false, DamagePipeline.canSneakAttack(Weapon.DAGGER.stats(), RollMode.ADVANTAGE, true, true));
        check("divine smite against undead", new DamagePipeline.Dice(4, 8), DamagePipeline.divineSmiteDice(2, true));
        check("concentration minimum", 10, ConcentrationManager.damageDc(17));
        check("concentration rounds damage down", 11, ConcentrationManager.damageDc(23));
    }

    private void conditions() {
        check("natural 1 death save", 2, DeathSaveTracker.DYING.roll(1, 0).tracker().failures());
        check("natural 20 death save restores 1 HP", 1, DeathSaveTracker.DYING.roll(20, 0).hitPointsRestored());
        check("three death saves stabilize", DeathSaveTracker.STABLE, new DeathSaveTracker(DeathSaveTracker.State.DYING, 2, 0).roll(10, 0).tracker());
        check("three failed death saves kill", DeathSaveTracker.DEAD, new DeathSaveTracker(DeathSaveTracker.State.DYING, 0, 2).roll(9, 0).tracker());
        check("medicine stabilizes", DeathSaveTracker.STABLE, DeathSaveTracker.DYING.medicine(10));
        check("exhaustion checks", true, ExhaustionLevel.of(1).abilityCheckDisadvantage());
        check("exhaustion speed", 15, ExhaustionLevel.of(2).speedFeet(30));
        check("exhaustion attacks", true, ExhaustionLevel.of(3).attackDisadvantage());
        check("exhaustion max HP", 10, ExhaustionLevel.of(4).maxHitPoints(20));
        check("exhaustion speed lock", 0, ExhaustionLevel.of(5).speedFeet(30));
        check("exhaustion death", true, ExhaustionLevel.of(6).dead());
    }

    private void geometry() {
        var origin = new Vec3(0, 0, 0);
        var direction = new Vec3(1, 0, 0);
        for (AoEGeometry shape : List.of(AoEGeometry.CONE, AoEGeometry.SPHERE, AoEGeometry.CUBE, AoEGeometry.LINE,
                AoEGeometry.CYLINDER, AoEGeometry.EMANATION)) {
            double other = shape == AoEGeometry.CYLINDER || shape == AoEGeometry.LINE ? 10 : 0;
            var area = new Template(shape, 15, other).place(origin, direction, Box.point(origin), true);
            check(shape + " inside", true, area.contains(new Vec3(1, 0, 0)));
            check(shape + " outside", false, area.contains(new Vec3(8, 0, 0)));
        }
    }

    private void difficulty() {
        var monster = new EncounterDifficultyCalculator.Monster("1");
        check("DMG number multiplier", 1.5, EncounterDifficultyCalculator.assess(List.of(3, 3, 3, 3), List.of(monster, monster)).multiplier());
        check("DMG small group adjustment", 2.0, EncounterDifficultyCalculator.assess(List.of(3, 3), List.of(monster, monster)).multiplier());
        check("DMG adjusted XP", 600.0, EncounterDifficultyCalculator.assess(List.of(3, 3, 3, 3), List.of(monster, monster)).adjustedXp());
    }

    private static int ac(Formula formula, boolean shield, boolean mediumArmorMaster) {
        return ArmorFormulaEngine.armorClass(formula, 18, 16, 14, shield, mediumArmorMaster);
    }

    private static IntUnaryOperator dice(int... values) {
        AtomicInteger index = new AtomicInteger();
        return sides -> values[index.getAndIncrement()];
    }

    private void hardening() {
        UUID encounter = new UUID(0, 1);
        var empty = new Protocol.Combat(encounter, null, null, 1, false, List.of(), Set.of(),
                0, Map.of(), Map.of(), List.of(), List.of(), Protocol.Slots.EMPTY, null);
        check("empty initiative ribbon", List.of(), CombatHudView.initiative(empty, 6));
        UUID actor = new UUID(0, 2);
        var ally = new Protocol.Creature(actor, "Fighter", 0, 19, new Cell(0, 0, 0), 10, 10, 0, 12, 5,
                DeathSaveTracker.State.CONSCIOUS, Set.of());
        var observer = new Protocol.Combat(encounter, null, actor, 1, false, List.of(ally), Set.of(),
                0, Map.of(), Map.of(), List.of(), List.of(), Protocol.Slots.EMPTY, null);
        check("observer initiative ribbon", List.of(ally), CombatHudView.initiative(observer, 6));
        check("observer faction color", true, CombatHudView.allied(observer, ally));
        check("observer has no turn", false, observer.ownsTurn());
        check("zero maximum HP", 0.0, CombatHudView.healthFraction(1, 0));
        check("custom reaction duration", 0.25, new Protocol.Reaction(1, 2_000, List.of(), 8_000).progress(2_000));

        check("negative NPC attitude", NpcAttitude.INDIFFERENT, NpcAttitude.fromIndex(-1));
        check("out-of-range NPC attitude", NpcAttitude.INDIFFERENT, NpcAttitude.fromIndex(99));
        check("three civilians", 3, BestiaryRegistry5e.civilians().size());
        for (var preset : BestiaryRegistry5e.civilians()) {
            check(preset.id() + " attack damage", 0.0, preset.baseAttackDamage());
            check(preset.id() + " starter dialogue", 3, BestiaryRegistry5e.starterDialogue(preset).nodes().size());
        }

        record Player(UUID id) {}
        var original = new Player(actor);
        var replacement = new Player(actor);
        var sessions = new PlayerSessionStore<List<Integer>>();
        var pendingRolls = sessions.getOrCreate(original.id(), () -> List.of(17));
        var restored = sessions.getOrCreate(replacement.id(), List::of);
        check("replacement player retains pending rolls", pendingRolls, restored);
        sessions.remove(replacement.id());
        check("disconnect clears session", List.of(), sessions.getOrCreate(actor, List::of));

        var english = localizationKeys("en_us");
        check("observer badge translation", true, english.contains("dnd-5e-craft.ui.hud.dm_observing"));
        check("localization key parity", english, localizationKeys("ru_ru"));
    }

    private void tabletop() {
        check("private roll reaches its player", true, TabletopRolls.visible(true, true, false));
        check("private roll reaches an operator", true, TabletopRolls.visible(true, false, true));
        check("private roll is hidden from another player", false, TabletopRolls.visible(true, false, false));
        check("tray minimum", new TabletopRolls.DicePool(1, 4), TabletopRolls.tray("1d4"));
        check("tray maximum pool", new TabletopRolls.DicePool(8, 20), TabletopRolls.tray("8d20"));
        check("tray percentile", new TabletopRolls.DicePool(1, 100), TabletopRolls.tray("1d100"));
        check("Shift forces advantage", RollMode.ADVANTAGE, TabletopRolls.clickMode(RollMode.NORMAL, true, false));
        check("Alt forces disadvantage", RollMode.DISADVANTAGE, TabletopRolls.clickMode(RollMode.NORMAL, false, true));
        var weapon = new DamagePipeline.DamageRoll(SLASHING, new DamagePipeline.Dice(1, 8), 3);
        var crit = TabletopRolls.weaponDamage(new DiceRoller((IntUnaryOperator) sides -> 4), weapon, true, 0);
        check("critical double dice", List.of(4, 4), crit.dice());
        check("critical keeps the modifier once", 11, crit.total());
    }

    private Set<String> localizationKeys(String locale) {
        var path = Path.of("src/main/resources/assets/dnd-5e-craft/lang", locale + ".json");
        // Locale entries in this project use one JSON property per line.
        var property = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"(.*)\",?");
        Set<String> keys = new HashSet<>();
        try {
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank() || line.equals("{") || line.equals("}")) continue;
                var match = property.matcher(line.strip());
                if (!match.matches() || match.group(2).isBlank() || !keys.add(match.group(1))) {
                    throw new AssertionError("Invalid, blank, or duplicate " + locale + " locale entry: " + line);
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Run from the repository root to verify " + path, error);
        }
        check(locale + " has nonblank, unique translations", true, !keys.isEmpty());
        return keys;
    }

    private void check(String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(label + " expected " + expected + ", got " + actual);
        checks++;
        System.out.println("PASS " + label);
    }
}
