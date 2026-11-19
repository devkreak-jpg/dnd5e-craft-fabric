package net.dnd5ecraft.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import net.dnd5ecraft.combat.CombatantEntry.Attack;
import net.dnd5ecraft.combat.CombatantEntry.Effect;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.combat.ReactionWindowManager.Offer;
import net.dnd5ecraft.rules.campaign.CampaignRules;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.character.SubclassType;
import net.dnd5ecraft.rules.conditions.ConditionInstance;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.equipment.DamagePipeline.DamageRoll;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Dice;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.server.CampaignActions.Action;

final class CampaignCombat {
    private final CombatManager manager;
    private final CombatEncounterState encounter;
    private final IntUnaryOperator roller;
    private UUID pendingDirector;
    private long directorTurn = -1;

    CampaignCombat(CombatManager manager, CombatEncounterState encounter, IntUnaryOperator roller) {
        this.manager = manager;
        this.encounter = encounter;
        this.roller = roller;
    }

    static void beginTurn(CombatantEntry actor) {
        actor.campaignLastHit = null;
        actor.campaignFist = actor.campaignFury = actor.campaignDash = actor.echoMoved = false;
        actor.reckless = false;
    }

    void atInitiative() {
        for (var entry : encounter.initiativeOrder()) {
            if (chaosLevel(entry) > 0 && entry.remaining(ClassFeature.CHAOS_POINTS) == 0) changeChaos(entry, 1);
        }
    }

    static int chaosLevel(CombatantEntry actor) { return actor.stats().classes().getOrDefault(ClassType.HARLEQUIN, 0); }

    static int chaosModifier(CombatantEntry actor) {
        return Math.max(actor.stats().abilityModifier(Ability.DEXTERITY), actor.stats().abilityModifier(Ability.CHARISMA));
    }

    static void changeChaos(CombatantEntry actor, int change) {
        int limit = CampaignRules.chaosLimit(chaosLevel(actor));
        int remaining = actor.remaining(ClassFeature.CHAOS_POINTS);
        if (remaining + change < 0) throw new IllegalStateException("Not enough Chaos Points");
        int next = Math.min(limit, remaining + change);
        actor.characterResources = actor.characterResources.withSpent(ClassFeature.CHAOS_POINTS, limit - next);
        if (chaosLevel(actor) >= 3 && next != remaining) actor.chaosShift = change > 0 ? 15 : 10;
    }

    void action(CombatantEntry user, Action action, UUID id, Cell point, int option, long time) {
        boolean useLastHit = action == Action.FOLLOW_UP_FIST || action == Action.BURYING_HANDS || action == Action.HAMMERING_HORNS;
        UUID targetId = id == null && useLastHit ? user.campaignLastHit : id;
        CombatantEntry target = targetId == null ? null : encounter.require(targetId);
        switch (action) {
            case FOLLOW_UP_FIST -> {
                requireWorldbreaker(user);
                var enemy = lastTarget(user, target);
                if (!user.campaignFist) throw new IllegalStateException("An unarmed hit is required first");
                requireReach(user, enemy, 5);
                var attack = fist(user, false);
                CombatManeuverResolver.spend(user, Resource.BONUS_ACTION);
                manager.maneuvers().strike(user, enemy, attack, true, time, ignored -> {});
            }
            case BURYING_HANDS -> {
                requireWorldbreaker(user);
                var enemy = lastTarget(user, target);
                if (!user.campaignFist || user.campaignFury) throw new IllegalStateException("Gravitational Fury requires an unarmed hit and works once per turn");
                requireReach(user, enemy, 5);
                user.campaignFury = true;
                int dc = CampaignRules.saveDc(user.stats().proficiencyBonus(), user.stats().abilityModifier(Ability.STRENGTH));
                if (!manager.save(enemy, Ability.STRENGTH, dc, 0, false, false)) {
                    condition(user, enemy, ConditionType.PRONE, ConditionInstance.INDEFINITE);
                    condition(user, enemy, ConditionType.RESTRAINED, 1);
                }
                manager.event(CombatManager.EventType.CAMPAIGN, user, enemy, "earth", 0);
            }
            case RECKLESS_ATTACK -> {
                if (!user.stats().classes().containsKey(ClassType.BARBARIAN) || user.tookAttackAction) throw new IllegalStateException("Reckless Attack precedes your first attack");
                user.reckless = !user.reckless;
            }
            case GORING_RUSH -> {
                requireMinotaur(user);
                if (!user.campaignDash || target == null) throw new IllegalStateException("Dash and choose a target first");
                requireReach(user, target, 5);
                CombatManeuverResolver.spend(user, Resource.BONUS_ACTION);
                manager.maneuvers().strike(user, target, fist(user, true), true, time, ignored -> {});
            }
            case HAMMERING_HORNS -> {
                requireMinotaur(user);
                var enemy = lastTarget(user, target);
                requireReach(user, enemy, 5);
                CombatManeuverResolver.spend(user, Resource.BONUS_ACTION);
                if (!manager.save(enemy, Ability.STRENGTH, CampaignRules.saveDc(user.stats().proficiencyBonus(),
                        user.stats().abilityModifier(Ability.STRENGTH)), 0, false, false)) manager.push(user, enemy, 2);
            }
            case CHAOS_QUIP -> {
                if (chaosLevel(user) < 2 || target == null || target.dead() || user.cell().distanceFeet(target.cell()) > 60
                        || option == 0 || Math.abs(option) > CampaignRules.chaosLimit(chaosLevel(user))) {
                    throw new IllegalArgumentException("Choose a creature and a valid Quip");
                }
                boolean helpful = option > 0;
                int points = Math.abs(option);
                int change = CampaignRules.chaosChange(user.team() == target.team(), helpful, true) * points;
                if (user.remaining(ClassFeature.CHAOS_POINTS) + change < 0) throw new IllegalStateException("Not enough Chaos Points");
                CombatManeuverResolver.spend(user, Resource.BONUS_ACTION);
                int amount = new Dice(points, 6).roll(false, roller);
                boolean saved = !helpful && manager.save(target, Ability.WISDOM,
                        CampaignRules.saveDc(user.stats().proficiencyBonus(), chaosModifier(user)), 0, false, true);
                if (helpful) target.hp = target.hp.grantTemporary(amount);
                else manager.dealDamage(user, target, Map.of(DamageType5e.PSYCHIC, saved ? amount / 2 : amount), false, false);
                if (change < 0 || !saved) changeChaos(user, change);
                manager.event(CombatManager.EventType.CAMPAIGN, user, target, "chaos", 0);
            }
            case SCENE_CHANGE -> {
                if (chaosLevel(user) == 0 || user.remaining(ClassFeature.CHAOS_POINTS) == 0) {
                    throw new IllegalStateException("Scene Change requires a Chaos Point");
                }
                var other = requireScenePair(user, target, point);
                manager.requireSwap(target, other);
                CombatManeuverResolver.spend(user, Resource.ACTION);
                changeChaos(user, -1);
                sceneSwap(user, target, other);
                pendingDirector = user.id();
                directorTurn = manager.turnSequence();
            }
            case AUDIENCE_LAUGHTER -> {
                if (chaosLevel(user) < 2 || user.laughterRound != encounter.round()) throw new IllegalStateException("No audience reaction is available");
                user.laughterRound = -encounter.round();
                changeChaos(user, 1);
            }
            case MISE_EN_SCENE -> {
                if (chaosLevel(user) < 3 || point == null || user.chaosShift <= 0 || user.cell().distanceFeet(point) > user.chaosShift
                        || !manager.movementTerrain(user, false).canOccupy(point)) throw new IllegalArgumentException("No eligible shift destination");
                user.chaosShift = 0;
                manager.relocate(user, point);
            }
            default -> throw new IllegalArgumentException("Campaign action unavailable");
        }
    }

    UUID directorPair(UUID targetId, Cell point) {
        var user = pendingDirector == null ? null : encounter.find(pendingDirector).orElse(null);
        if (user == null || directorTurn != manager.turnSequence() || !user.canAct()) {
            throw new IllegalStateException("A Harlequin must use Scene Change this turn");
        }
        var target = targetId == null ? null : encounter.require(targetId);
        var second = requireScenePair(user, target, point);
        sceneSwap(user, target, second);
        pendingDirector = null;
        return user.id();
    }

    private CombatantEntry requireScenePair(CombatantEntry user, CombatantEntry first, Cell second) {
        var other = second == null ? null : encounter.initiativeOrder().stream()
                .filter(actor -> !actor.dead() && actor.cell().equals(second)).findFirst().orElse(null);
        if (first == null || other == null || first == other || first.dead() || user.cell().distanceFeet(first.cell()) > 20
                || user.cell().distanceFeet(other.cell()) > 20) throw new IllegalArgumentException("Choose two creatures within twenty feet");
        return other;
    }

    private void sceneSwap(CombatantEntry user, CombatantEntry first, CombatantEntry second) {
        manager.swapActors(first, second);
        manager.event(CombatManager.EventType.CAMPAIGN, user, first, "chaos", 0);
        manager.event(CombatManager.EventType.CAMPAIGN, user, second, "chaos", 0);
    }

    record RollEdit(int natural, int adjustment, RollMode mode) {}

    void editRoll(CombatantEntry rolled, int natural, int unmodified, RollMode mode,
            long time, java.util.function.BiConsumer<RollEdit, Long> after) {
        var offers = new ArrayList<Offer>();
        for (var jester : encounter.initiativeOrder()) {
            if (chaosLevel(jester) == 0 || !jester.canReact() || jester.cell().distanceFeet(rolled.cell()) > 60) continue;
            for (var choice : List.of(Kind.IMPROMPTU_ADD, Kind.IMPROMPTU_SUBTRACT, Kind.PROMPTERS_EDIT,
                    Kind.FINAL_ACT_20, Kind.FINAL_ACT_1, Kind.IMPROVISATION)) {
                if (canEdit(jester, rolled, choice, mode)) offers.add(new Offer(jester.id(), choice));
            }
        }
        RollEdit[] edited = {new RollEdit(natural, 0, mode)};
        manager.open(offers, time, response -> response.reaction().slot() == 0 && canEdit(encounter.require(response.reactor()),
                rolled, response.reaction().kind(), edited[0].mode()), response -> {
            var jester = encounter.require(response.reactor());
            var choice = response.reaction().kind();
            CombatManeuverResolver.spend(jester, Resource.REACTION);
            var prior = edited[0];
            int face = prior.natural();
            int adjustment = prior.adjustment();
            RollMode nextMode = prior.mode();
            if (choice == Kind.FINAL_ACT_20 || choice == Kind.FINAL_ACT_1) {
                changeChaos(jester, -3);
                jester.spend(ClassFeature.FINAL_ACT, 1);
                face = choice == Kind.FINAL_ACT_20 ? 20 : 1;
                manager.event(CombatManager.EventType.CAMPAIGN, jester, rolled, "final_act", 0);
            } else if (choice == Kind.PROMPTERS_EDIT) {
                changeChaos(jester, CampaignRules.chaosChange(jester.team() == rolled.team(), prior.mode() == RollMode.DISADVANTAGE, true));
                face = unmodified;
                nextMode = RollMode.NORMAL;
            } else if (choice == Kind.IMPROVISATION) {
                int die = AbilityCheckEngine.rollDie(6, roller);
                adjustment += die % 2 == 0 ? die : -die;
            } else {
                boolean helpful = choice == Kind.IMPROMPTU_ADD;
                changeChaos(jester, CampaignRules.chaosChange(jester.team() == rolled.team(), helpful, true));
                int die = AbilityCheckEngine.rollDie(chaosLevel(jester) < 5 ? 4 : 6, roller);
                adjustment += helpful ? die : -die;
            }
            edited[0] = new RollEdit(face, adjustment, nextMode);
        }, moment -> {
            onNatural(rolled, edited[0].natural());
            after.accept(edited[0], moment);
        });
    }

    private static boolean canEdit(CombatantEntry jester, CombatantEntry rolled, Kind choice, RollMode mode) {
        int level = chaosLevel(jester);
        if (!jester.canReact() || level == 0) return false;
        int points = jester.remaining(ClassFeature.CHAOS_POINTS);
        return switch (choice) {
            case FINAL_ACT_20, FINAL_ACT_1 -> level >= 5 && points >= 3 && jester.remaining(ClassFeature.FINAL_ACT) > 0;
            case IMPROVISATION -> level >= 5;
            case PROMPTERS_EDIT -> level >= 3 && mode != RollMode.NORMAL
                    && points + CampaignRules.chaosChange(jester.team() == rolled.team(), mode == RollMode.DISADVANTAGE, true) >= 0;
            case IMPROMPTU_ADD, IMPROMPTU_SUBTRACT -> points
                    + CampaignRules.chaosChange(jester.team() == rolled.team(), choice == Kind.IMPROMPTU_ADD, true) >= 0;
            default -> false;
        };
    }

    void onNatural(CombatantEntry roller, int roll) {
        if (!encounter.active() || roll != 1 && roll != 20) return;
        for (var listener : encounter.initiativeOrder()) {
            if (chaosLevel(listener) >= 2 && Math.abs(listener.laughterRound) != encounter.round()
                    && listener.cell().distanceFeet(roller.cell()) <= 60) {
                listener.laughterRound = encounter.round();
            }
        }
    }

    private static CombatantEntry lastTarget(CombatantEntry user, CombatantEntry target) {
        if (target == null || !target.id().equals(user.campaignLastHit)) throw new IllegalArgumentException("Choose the creature you just hit");
        return target;
    }

    private void requireReach(CombatantEntry user, CombatantEntry target, int range) {
        manager.validateHostileTarget(user, target);
        if (user.cell().distanceFeet(target.cell()) > range || manager.cover(user, target).blocksTargeting()) {
            throw new IllegalArgumentException("Target is beyond reach");
        }
    }

    void condition(CombatantEntry source, CombatantEntry target, ConditionType type, int duration) {
        manager.applyCondition(target.id(), new ConditionInstance(type, Optional.of(source.id()), duration));
    }

    private static void requireWorldbreaker(CombatantEntry actor) {
        if (!actor.subclasses.contains(SubclassType.WORLDBREAKER)) throw new IllegalStateException("Worldbreaker required");
    }

    private static void requireMinotaur(CombatantEntry actor) {
        if (actor.race != RaceType.MINOTAUR) throw new IllegalStateException("Minotaur required");
    }

    private static Attack fist(CombatantEntry actor, boolean horns) {
        int mod = actor.stats().abilityModifier(Ability.STRENGTH);
        var die = new Dice(1, horns ? 6 : CampaignRules.worldbreakerDie(actor.stats().classes().getOrDefault(ClassType.BARBARIAN, 0)));
        return new Attack(horns ? "Horns" : "Fist", mod + actor.stats().proficiencyBonus(), 5, 0, 0,
                List.of(new DamageRoll(horns ? DamageType5e.PIERCING : DamageType5e.BLUDGEONING, die, mod)), false, false, false, true,
                horns ? "horns" : "fist");
    }
}
