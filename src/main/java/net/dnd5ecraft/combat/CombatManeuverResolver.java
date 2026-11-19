package net.dnd5ecraft.combat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import java.util.function.LongConsumer;
import net.dnd5ecraft.combat.CombatantEntry.Attack;
import net.dnd5ecraft.combat.CombatantEntry.Effect;
import net.dnd5ecraft.combat.CombatantEntry.TimedEffect;
import net.dnd5ecraft.combat.CombatantEntry.Trait;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.combat.ReactionWindowManager.Offer;
import net.dnd5ecraft.combat.ReactionWindowManager.Window;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.entity.BestiaryPreset.Feature;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.rules.character.FeatType;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.character.SubclassType;
import net.dnd5ecraft.rules.conditions.ConditionEffectApplier.Sense;
import net.dnd5ecraft.rules.conditions.ConditionInstance;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.DamagePipeline;
import net.dnd5ecraft.rules.equipment.DamagePipeline.AttackOutcome;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Dice;
import net.dnd5ecraft.rules.equipment.DamagePipeline.DamageRoll;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.spells.AoEGeometry;
import net.dnd5ecraft.rules.spells.AoEGeometry.Template;
import net.dnd5ecraft.rules.spells.Spell;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;

/** Synchronous rules coordinator. World adapters call it from the authoritative game thread. */
import net.dnd5ecraft.combat.CombatManager.EventType;
import net.dnd5ecraft.combat.CombatManager.Maneuver;

final class CombatManeuverResolver {
    private final CombatManager manager;
    private final CombatEncounterState encounter;
    private final IntUnaryOperator roller;

    record Hit(CombatantEntry attacker, CombatantEntry defender, int roll, int modifier,
                       int cover, boolean criticalOnHit, RollMode mode, boolean missile, int first, DamageType5e element) {
        Hit withElement(DamageType5e element) {
            return new Hit(attacker, defender, roll, modifier, cover, criticalOnHit, mode, missile, first, element);
        }
        AttackOutcome outcome() {
            return DamagePipeline.attackOutcome(roll, modifier, defender.armorClass() + cover, criticalOnHit);
        }
    }

    interface HitResolution { void apply(AttackOutcome outcome, boolean dodge, int reduction); }

    private final Map<UUID, Integer> readied = new HashMap<>();

    CombatManeuverResolver(CombatManager manager, CombatEncounterState encounter, IntUnaryOperator roller) {
        this.manager = manager;
        this.encounter = encounter;
        this.roller = roller;
    }

    void removeCombatant(UUID id) {
        readied.remove(id);
    }

    void beginTurn(CombatantEntry actor) {
        readied.remove(actor.id());
    }

    void triggerReadied(CombatantEntry mover, Cell previous, long now) {
        if (mover.dead()) return;
        List<Offer> offered = encounter.initiativeOrder().stream()
                .filter(reactor -> reactor.team() != mover.team() && readied.containsKey(reactor.id()) && reactor.canReact()
                        && manager.canHarm(reactor, mover) && manager.canSee(reactor, mover) && !manager.cover(reactor, mover).blocksTargeting())
                .filter(reactor -> {
                    Attack weapon = reactor.stats().attacks().get(readied.get(reactor.id()));
                    return !weapon.inRange(reactor.cell().distanceFeet(previous)) && weapon.inRange(reactor.cell().distanceFeet(mover.cell()));
                }).map(reactor -> new Offer(reactor.id(), Kind.READIED_ATTACK)).toList();
        List<CombatantEntry> accepted = new ArrayList<>();
        manager.open(offered, now, answer -> answer.reaction().slot() == 0 && encounter.require(answer.reactor()).canReact(), answer ->
                accepted.add(encounter.require(answer.reactor())), time -> fireReadied(mover, accepted, 0, time));
    }

    private void fireReadied(CombatantEntry mover, List<CombatantEntry> accepted, int index, long now) {
        if (index >= accepted.size() || mover.dead()) return;
        CombatantEntry reactor = accepted.get(index);
        Integer profile = readied.remove(reactor.id());
        if (profile != null && reactor.canReact()) {
            spend(reactor, Resource.REACTION);
            strike(reactor, mover, reactor.stats().attacks().get(profile), true, now,
                    time -> fireReadied(mover, accepted, index + 1, time));
        } else fireReadied(mover, accepted, index + 1, now);
    }

    void releaseEndedGrapples() {
        for (CombatantEntry entry : encounter.initiativeOrder()) {
            entry.conditions.removeIf(condition -> condition.type() == ConditionType.GRAPPLED
                    && condition.source().flatMap(encounter::find).map(grappler ->
                        condition.endsAfterGrapplerChanges(grappler.canAct(),
                                entry.cell().distanceFeet(grappler.cell()) <= Math.max(5, grappler.stats().meleeReachFeet())))
                        .orElse(false));
        }
    }

    void maneuver(UUID actor, Maneuver action, UUID targetId, int option, Cell point, long nowMillis) {
        manager.requireIdle(nowMillis);
        CombatantEntry user = manager.acting(actor);
        CombatantEntry target = targetId == null ? null : encounter.require(targetId);
        switch (action) {
            case LUCKY -> {
                if (!user.feat(FeatType.LUCKY) || user.luckyRoll) throw new IllegalStateException("Lucky is unavailable");
                user.spend(ClassFeature.LUCKY, 1);
                user.luckyRoll = true;
            }
            case INSPIRATION -> {
                if (!user.characterResources.inspiration() || user.advantageOnNextRoll) {
                    throw new IllegalStateException("Inspiration is unavailable");
                }
                user.characterResources = user.characterResources.withInspiration(false);
                user.advantageOnNextRoll = true;
            }
            case DODGE -> { spend(user, Resource.ACTION); user.dodging = true; }
            case HELP -> {
                requireAdjacent(user, target, true);
                spend(user, Resource.ACTION);
                manager.spells().grantAid(user, target);
            }
            case HIDE -> {
                if (encounter.initiativeOrder().stream().anyMatch(entry -> entry.team() != user.team() && manager.canSee(entry, user))) {
                    throw new IllegalStateException("You cannot hide while a foe can see you clearly");
                }
                spend(user, Resource.ACTION);
                user.stealth = abilityTotal(user, Skill.STEALTH);
            }
            case READY -> {
                if (option < 0 || option >= user.stats().attacks().size()) throw new IllegalArgumentException("Invalid readied weapon");
                spend(user, Resource.ACTION);
                readied.put(user.id(), option);
            }
            case GRAPPLE, SHOVE, KNOCK_PRONE -> {
                requireAdjacent(user, target, true);
                spendAttack(user);
                int strength = abilityTotal(user, Skill.ATHLETICS);
                Skill defense = target.skillModifier(Skill.ATHLETICS) >= target.skillModifier(Skill.ACROBATICS)
                        ? Skill.ATHLETICS : Skill.ACROBATICS;
                if (!target.canAct() || strength > abilityTotal(target, defense)) {
                    if (action == Maneuver.SHOVE) manager.push(user, target, 1);
                    else manager.applyCondition(target.id(), new ConditionInstance(action == Maneuver.GRAPPLE
                            ? ConditionType.GRAPPLED : ConditionType.PRONE, Optional.of(user.id()), ConditionInstance.INDEFINITE));
                }
            }
            case STABILIZE -> {
                requireAdjacent(user, target, false);
                if (target.death.state() != DeathSaveTracker.State.DYING) throw new IllegalStateException("Target is not dying");
                spend(user, Resource.ACTION);
                target.death = target.death.medicine(abilityTotal(user, Skill.MEDICINE));
            }
            case SECOND_WIND -> {
                resource(user, ClassFeature.SECOND_WIND, 1, Resource.BONUS_ACTION);
                manager.heal(user, user, AbilityCheckEngine.rollDie(10, roller) + user.stats().classes().getOrDefault(ClassType.FIGHTER, 0));
            }
            case RAGE -> {
                if (user.has(Effect.RAGING)) throw new IllegalStateException("Rage is active");
                resource(user, ClassFeature.RAGE, 1, Resource.BONUS_ACTION);
                manager.endConcentration(user.id());
                user.effects.add(new TimedEffect(Effect.RAGING, user.id(), manager.nextEffectId(), user.id(), user.turns + 10, true, 0, null));
            }
            case WILD_SHAPE -> {
                if (user.hp.beast() > 0) throw new IllegalStateException("Wild Shape is active");
                resource(user, ClassFeature.WILD_SHAPE, 1, Resource.ACTION);
                user.hp = new HitPoints(user.hp.current(), user.hp.maximum(), user.hp.temporary(), user.hp.arcaneWard(), 11);
            }
            case LAY_ON_HANDS -> {
                requireAdjacent(user, target, false);
                if (target.stats().traits().contains(Trait.UNDEAD) || target.stats().traits().contains(Trait.CONSTRUCT)) {
                    throw new IllegalArgumentException("Lay on Hands needs a living creature");
                }
                resource(user, ClassFeature.LAY_ON_HANDS, option, Resource.ACTION);
                manager.heal(user, target, option);
            }
            case INSPIRE -> {
                if (target == null || target == user || target.team() != user.team() || target.inspirationDie != 0
                        || target.cell().distanceFeet(user.cell()) > 60 || target.dead()) throw new IllegalArgumentException("Invalid inspiration target");
                resource(user, ClassFeature.BARDIC_INSPIRATION, 1, Resource.BONUS_ACTION);
                int bard = user.stats().classes().getOrDefault(ClassType.BARD, 0);
                target.inspirationDie = bard < 5 ? 6 : bard < 10 ? 8 : bard < 15 ? 10 : 12;
            }
            case TURN_UNDEAD -> {
                int cleric = user.stats().classes().getOrDefault(ClassType.CLERIC, 0);
                int paladin = user.stats().classes().getOrDefault(ClassType.PALADIN, 0);
                if (cleric < 2 && paladin < 3) throw new IllegalStateException("Channel Divinity unavailable");
                resource(user, ClassFeature.CHANNEL_DIVINITY, 1, Resource.ACTION);
                int dc = 8 + user.stats().proficiencyBonus() + user.stats().abilityModifier(cleric >= 2 ? Ability.WISDOM : Ability.CHARISMA);
                for (CombatantEntry enemy : encounter.initiativeOrder()) {
                    if (!enemy.dead() && enemy.team() != user.team() && enemy.cell().distanceFeet(user.cell()) <= 30
                            && enemy.stats().traits().contains(Trait.UNDEAD) && !manager.saveAgainstFear(enemy, dc)) {
                        manager.applyCondition(enemy.id(), new ConditionInstance(ConditionType.FRIGHTENED, Optional.of(user.id()), 10));
                        enemy.effects.add(new TimedEffect(Effect.NO_REACTIONS, user.id(), manager.nextEffectId(),
                                enemy.id(), enemy.turns + 10, true, 0, null));
                    }
                }
            }
            case PATIENT_DEFENSE -> {
                resource(user, ClassFeature.KI_POINTS, 1, Resource.BONUS_ACTION);
                user.dodging = true;
            }
            case STEP_OF_WIND -> {
                resource(user, ClassFeature.KI_POINTS, 1, Resource.BONUS_ACTION);
                if (option == 0) user.turn.extraDash();
                else user.turn.extraDisengage();
            }
            case DRACONIC_BREATH -> dragonbornBreath(user, point);
            case WEAPON_STRIKE -> manager.spells().moveSpiritualWeapon(user, target, nowMillis);
            case MOVE_MOONBEAM -> manager.spells().moveMoonbeam(user, point);
            case RETARGET_MARK -> manager.spells().retargetMark(user, target);
            case BREAK_WEB -> {
                var web = user.effects.stream().filter(e -> e.effect() == Effect.WEBBED).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No restraining web"));
                spend(user, Resource.ACTION);
                if ((web.effect() == Effect.NETTED ? manager.d20(user, RollMode.NORMAL) + user.stats().abilityModifier(Ability.STRENGTH)
                        : abilityTotal(user, Skill.ATHLETICS)) >= web.saveDc()) user.effects.remove(web);
            }
            case QUICKEN_SPELL -> {
                if (user.quickenNextSpell) user.quickenNextSpell = false;
                else {
                    if (user.stats().classes().getOrDefault(ClassType.SORCERER, 0) < 3
                            || user.remaining(ClassFeature.SORCERY_POINTS) < 2 || !user.turn.canSpend(Resource.BONUS_ACTION)) {
                        throw new IllegalStateException("Quickened Spell requires two sorcery points and a bonus action");
                    }
                    user.quickenNextSpell = true;
                }
            }
            case SORCERY_SLOT -> {
                if (option < 1 || option > 5 || !user.slots.canRecover(option)) throw new IllegalArgumentException("Choose a spent slot");
                resource(user, ClassFeature.SORCERY_POINTS, new int[]{0, 2, 3, 5, 6, 7}[option], Resource.BONUS_ACTION);
                user.slots.recover(option);
            }
            case OFFHAND -> offhand(user, target, nowMillis);
            case POWER_ATTACK, SMITE -> specialAttack(user, target, option, action, nowMillis);
        }
    }

    static void spend(CombatantEntry user, Resource resource) {
        if (!user.turn.spend(resource)) throw new IllegalStateException("Action already spent");
    }

    private static void resource(CombatantEntry user, ClassFeature feature, int amount, Resource action) {
        if (amount <= 0 || user.remaining(feature) < amount || !user.turn.canSpend(action)) {
            throw new IllegalStateException("Class resource or action is unavailable");
        }
        user.spend(feature, amount);
        spend(user, action);
    }

    private void requireAdjacent(CombatantEntry user, CombatantEntry target, boolean hostile) {
        if (target == null || target.dead() || user.cell().distanceFeet(target.cell()) > 5 ||
                (target != user && manager.cover(user, target).blocksTargeting()) || hostile && !manager.canHarm(user, target)) {
            throw new IllegalArgumentException("Target must be within reach");
        }
    }

    private int abilityTotal(CombatantEntry user, Skill skill) {
        boolean advantage = user.has(Effect.RAGING) && skill.ability() == Ability.STRENGTH;
        var flags = user.conditionEffects().abilityCheck(Sense.NONE, manager.frightSourceVisible(user), advantage,
                skill == Skill.STEALTH && user.armorStealthDisadvantage);
        int bonus = user.skillModifier(skill);
        if (user.has(Effect.GUIDANCE)) {
            var guidance = user.effects.stream().filter(effect -> effect.effect() == Effect.GUIDANCE).findFirst().orElseThrow();
            bonus += AbilityCheckEngine.rollDie(4, roller);
            manager.spells().endConcentration(guidance.caster());
        }
        return manager.d20(user, flags.mode()) + bonus;
    }

    private void spendAttack(CombatantEntry attacker) {
        if (attacker.attacksRemaining == 0) {
            if (attacker.turn.canSpend(Resource.ACTION)) {
                spend(attacker, Resource.ACTION);
                attacker.attacksRemaining = attacker.attacksPerAction();
            } else if (attacker.hasteAction) {
                attacker.hasteAction = false;
                attacker.attacksRemaining = 1;
            } else throw new IllegalStateException("Action already spent");
            attacker.unleashedThisAction = false;
        }
        attacker.attacksRemaining--;
        attacker.tookAttackAction = true;
    }

    Optional<Window> attack(UUID actor, UUID target, int profile, long nowMillis) {
        manager.requireIdle(nowMillis);
        CombatantEntry attacker = manager.acting(actor);
        CombatantEntry defender = encounter.require(target);
        Attack attack = attacker.stats().attacks().get(profile);
        int distance = attacker.cell().distanceFeet(defender.cell());
        manager.validateHostileTarget(attacker, defender);
        if (!attack.inRange(distance) || manager.cover(attacker, defender).blocksTargeting()) {
            var echo = attack.melee() ? manager.echoCell(attacker) : null;
            if (echo == null) throw new IllegalArgumentException("Target is out of range or behind total cover");
            attackFrom(attacker, defender, attack, echo, true, nowMillis);
            return manager.reactionWindow();
        }
        if (!manager.canUseWeapon(attacker, attack)) throw new IllegalStateException("This weapon is broken or unequipped");
        spendAttack(attacker);
        strike(attacker, defender, attack, true, nowMillis, ignored -> {});
        return manager.reactionWindow();
    }

    void attackFrom(CombatantEntry attacker, CombatantEntry defender, Attack attack, Cell origin, boolean spend, long time) {
        manager.validateHostileTarget(attacker, defender);
        if (!attack.inRange(origin.distanceFeet(defender.cell())) || manager.coverFrom(origin, defender).blocksTargeting()
                || !manager.canUseWeapon(attacker, attack)) throw new IllegalArgumentException("Echo attack is blocked");
        if (spend) spendAttack(attacker);
        strikeFrom(attacker, defender, attack, origin, true, time, ignored -> {}, false, 0, false, () -> {});
    }

    void strike(CombatantEntry attacker, CombatantEntry defender, Attack attack, boolean prompt,
            long nowMillis, LongConsumer after) {
        strike(attacker, defender, attack, prompt, nowMillis, after, false, 0);
    }

    void strike(CombatantEntry attacker, CombatantEntry defender, Attack attack, boolean prompt,
            long nowMillis, LongConsumer after, boolean power, int smiteSlot) {
        strike(attacker, defender, attack, prompt, nowMillis, after, power, smiteSlot, false);
    }

    void strike(CombatantEntry attacker, CombatantEntry defender, Attack attack, boolean prompt,
            long nowMillis, LongConsumer after, boolean power, int smiteSlot, boolean opportunity) {
        strike(attacker, defender, attack, prompt, nowMillis, after, power, smiteSlot, opportunity, () -> {});
    }

    void strike(CombatantEntry attacker, CombatantEntry defender, Attack attack, boolean prompt,
            long nowMillis, LongConsumer after, boolean power, int smiteSlot, boolean opportunity, Runnable onHit) {
        strikeFrom(attacker, defender, attack, attacker.cell(), prompt, nowMillis, after, power, smiteSlot, opportunity, onHit);
    }

    void strikeFrom(CombatantEntry attacker, CombatantEntry defender, Attack attack, Cell origin, boolean prompt,
            long nowMillis, LongConsumer after, boolean power, int smiteSlot, boolean opportunity, Runnable onHit) {
        if (!manager.canUseWeapon(attacker, attack)) {
            after.accept(nowMillis);
            return;
        }
        if (attack.melee() && attacker.feat(FeatType.MOBILE)) attacker.threatenedByMobile.add(defender.id());
        boolean ignoreCover = !attack.melee() && attacker.feat(FeatType.SHARPSHOOTER);
        int distance = origin.distanceFeet(defender.cell());
        Cell real = attacker.cell;
        Hit base;
        try {
            attacker.cell = origin;
            base = rollAttack(attacker, defender, attack.attackBonus() - (power ? 5 : 0), !attack.melee(),
                    weaponAdvantage(attacker, defender, attack),
                    !attack.melee() && !ignoreCover && distance > attack.normalRangeFeet());
        } finally {
            attacker.cell = real;
        }
        if (!origin.equals(real)) manager.echoSwing(attacker);
        Hit hit = new Hit(attacker, defender, base.roll(), base.modifier(), ignoreCover ? 0 : base.cover(),
                base.criticalOnHit(), base.mode(), !attack.melee(), base.first(), attack.damage().stream()
                        .map(DamageRoll::type).filter(CombatSpellZoneResolver::absorbable).findFirst().orElse(null));
        resolveHit(hit, prompt, nowMillis, (outcome, dodge, reduction) -> {
            if (outcome != AttackOutcome.MISS) {
                onHit.run();
                if (attack.weaponKey().equals("dnd-5e-craft:net")) {
                    if (manager.nettable(defender)) defender.effects.add(new CombatantEntry.TimedEffect(Effect.NETTED,
                            attacker.id(), manager.nextEffectId(), defender.id(), Long.MAX_VALUE, true, 10, null));
                    return;
                }
                if (attack.melee()) attacker.campaignLastHit = defender.id();
                attacker.campaignFist = attack.weaponKey().equals("fist");
                manager.weaponHit(attacker, defender, attack);
                List<DamageRoll> rolls = new ArrayList<>(withSneakAttack(attacker, defender, attack, hit.mode()));
                DamageType5e weaponType = attack.damage().get(0).type();
                if (attack.melee()) {
                    var absorbed = attacker.effects.stream().filter(effect -> effect.effect() == Effect.ABSORBED).findFirst();
                    absorbed.ifPresent(charge -> {
                        rolls.add(new DamageRoll(charge.resistance(), new Dice(1, 6), 0));
                        attacker.effects.remove(charge);
                    });
                }
                if (power) rolls.add(new DamageRoll(weaponType, new Dice(0, 0), 10));
                if (attacker.has(Effect.RAGING) && attack.melee() && attack.strengthBased()) {
                    int barbarian = attacker.stats().classes().getOrDefault(ClassType.BARBARIAN, 0);
                    rolls.add(new DamageRoll(weaponType, new Dice(0, 0), barbarian < 9 ? 2 : barbarian < 16 ? 3 : 4));
                }
                if (defender.effects.stream().anyMatch(effect -> effect.effect() == Effect.MARKED && effect.caster().equals(attacker.id()))) {
                    rolls.add(new DamageRoll(weaponType, new Dice(1, 6), 0));
                }
                if (outcome == AttackOutcome.CRITICAL && attack.melee() && attacker.race == RaceType.HALF_ORC) {
                    Dice baseDice = attack.damage().get(0).dice();
                    if (baseDice.count() > 0) rolls.add(new DamageRoll(weaponType, new Dice(0, 0),
                            AbilityCheckEngine.rollDie(baseDice.sides(), roller)));
                }
                if (smiteSlot > 0 && attacker.slots.spend(Pool.REGULAR, smiteSlot)) {
                    boolean holy = defender.stats().traits().contains(Trait.UNDEAD) || defender.stats().traits().contains(Trait.FIEND);
                    rolls.add(new DamageRoll(DamageType5e.RADIANT, DamagePipeline.divineSmiteDice(smiteSlot, holy), 0));
                }
                Map<DamageType5e, Integer> damage = DamagePipeline.rollDamage(rolls, outcome == AttackOutcome.CRITICAL, roller);
                manager.dealDamage(attacker, defender, reduceDamage(damage, reduction), outcome == AttackOutcome.CRITICAL, dodge);
                if (opportunity && attacker.feat(FeatType.SENTINEL)) defender.speedLockedTurn = defender.turns;
                if (!defender.dead()) bestiaryRider(attacker, defender, attack);
            }
        }, after);
    }

    private void bestiaryRider(CombatantEntry attacker, CombatantEntry target, Attack attack) {
        var preset = attacker.bestiary().map(state -> state.preset()).orElse(null);
        if (preset == null) return;
        if ((preset == BestiaryPreset.WOLF || preset == BestiaryPreset.DIRE_WOLF)
                && !manager.save(target, Ability.STRENGTH, preset == BestiaryPreset.WOLF ? 11 : 13, 0, false, false)) {
            manager.applyCondition(target.id(), new ConditionInstance(ConditionType.PRONE, Optional.of(attacker.id()), ConditionInstance.INDEFINITE));
        }
        if (preset == BestiaryPreset.GHOUL && attack.name().equals("Claws")
                && target.race != RaceType.ELF && !target.stats().traits().contains(Trait.UNDEAD)
                && !target.has(Effect.GHOUL_PARALYSIS) && !manager.save(target, Ability.CONSTITUTION, 10, 0, false, false)) {
            target.effects.add(new TimedEffect(Effect.GHOUL_PARALYSIS, attacker.id(), 0, target.id(), target.turns + 10,
                    true, 10, null));
            manager.spells().endConcentration(target.id());
            releaseEndedGrapples();
        }
    }

    private void offhand(CombatantEntry user, CombatantEntry target, long now) {
        requireAdjacent(user, target, true);
        if (!user.tookAttackAction || user.stats().attacks().size() < 2) throw new IllegalStateException("Off-hand attack needs a first attack");
        var main = user.stats().attacks().get(0);
        var off = user.stats().attacks().get(1);
        if (!main.melee() || !main.light() || !off.melee() || !off.light()) throw new IllegalStateException("Use two light melee weapons");
        spend(user, Resource.BONUS_ACTION);
        var dice = off.damage().stream().map(roll -> new DamageRoll(roll.type(), roll.dice(), Math.min(0, roll.modifier()))).toList();
        strike(user, target, new Attack(off.name(), off.attackBonus(), off.reachFeet(), 0, 0, dice, off.finesse(),
                true, false, off.strengthBased()), true, now, ignored -> {});
    }

    private void specialAttack(CombatantEntry user, CombatantEntry target, int option, Maneuver maneuver, long now) {
        int profile = maneuver == Maneuver.SMITE ? 0 : option;
        if (target == null || profile < 0 || profile >= user.stats().attacks().size()) throw new IllegalArgumentException("Choose a weapon and target");
        var attack = user.stats().attacks().get(profile);
        manager.validateHostileTarget(user, target);
        if (!attack.inRange(user.cell().distanceFeet(target.cell())) || manager.cover(user, target).blocksTargeting()) {
            throw new IllegalArgumentException("Target is out of range or behind total cover");
        }
        boolean power = maneuver == Maneuver.POWER_ATTACK;
        if (power && !(attack.melee() ? attack.heavy() && user.feat(FeatType.GREAT_WEAPON_MASTER)
                : user.feat(FeatType.SHARPSHOOTER))) throw new IllegalStateException("Weapon or feat does not support a power attack");
        if (!power && (!attack.melee() || user.stats().classes().getOrDefault(ClassType.PALADIN, 0) < 2
                || option < 1 || option > 9 || user.slots.available(Pool.REGULAR, option) == 0)) {
            throw new IllegalStateException("No eligible spell slot for Divine Smite");
        }
        spendAttack(user);
        strike(user, target, attack, true, now, ignored -> {}, power, power ? 0 : option);
    }

    private void dragonbornBreath(CombatantEntry user, Cell point) {
        if (point == null || point.equals(user.cell())) throw new IllegalArgumentException("Aim the breath away from yourself");
        var origin = user.cell().center();
        var shape = new Template(AoEGeometry.CONE, 15).place(origin, point.center().minus(origin), user.bounds(), false);
        resource(user, ClassFeature.DRACONIC_BREATH, 1, Resource.ACTION);
        int level = user.stats().level();
        int dice = level >= 16 ? 5 : level >= 11 ? 4 : level >= 6 ? 3 : 2;
        manager.resolveBestiaryFire(user, shape, 8 + user.stats().abilityModifier(Ability.CONSTITUTION)
                + user.stats().proficiencyBonus(), new Dice(dice, 6));
    }

    private List<DamageRoll> withSneakAttack(CombatantEntry attacker, CombatantEntry defender, Attack attack, RollMode mode) {
        int rogue = attacker.stats().classes().getOrDefault(ClassType.ROGUE, 0);
        if (rogue == 0 || (!attack.finesse() && attack.melee()) || attacker.lastSneakAttack == manager.turnSequence()
                || mode == RollMode.DISADVANTAGE) return attack.damage();
        boolean distracted = encounter.initiativeOrder().stream().anyMatch(other -> other != attacker
                && other.team() != defender.team() && other.canAct() && other.cell().distanceFeet(defender.cell()) <= 5);
        if (mode != RollMode.ADVANTAGE && !distracted) return attack.damage();
        attacker.lastSneakAttack = manager.turnSequence();
        List<DamageRoll> rolls = new ArrayList<>(attack.damage());
        rolls.add(new DamageRoll(rolls.get(0).type(), DamagePipeline.sneakAttackDice(rogue), 0));
        return rolls;
    }

    boolean weaponAdvantage(CombatantEntry attacker, CombatantEntry defender, Attack attack) {
        boolean packAdvantage = attacker.bestiary().filter(state -> state.has(Feature.PACK_TACTICS)).isPresent()
                && encounter.initiativeOrder().stream().anyMatch(ally -> ally != attacker && ally.team() == attacker.team()
                        && ally.canAct() && ally.cell().distanceFeet(defender.cell()) <= 5);
        return packAdvantage || attack.melee() && attack.strengthBased() && attacker.reckless || defender.reckless;
    }

    Hit rollAttack(CombatantEntry attacker, CombatantEntry defender, int bonus, boolean ranged,
            boolean advantage, boolean disadvantage) {
        var flags = attackFlags(attacker, defender, ranged, advantage, disadvantage);
        var roll = manager.rolledD20(attacker, flags.mode());
        attacker.stealth = -1;
        manager.spells().aidFor(attacker, defender).ifPresent(manager.spells()::consumeAid);
        if (attacker.has(Effect.MOCKED)) attacker.effects.removeIf(effect -> effect.effect() == Effect.MOCKED);
        if (defender.has(Effect.GUIDING_LIGHT)) defender.effects.removeIf(effect -> effect.effect() == Effect.GUIDING_LIGHT);
        return new Hit(attacker, defender, roll.kept(), bonus + manager.luck(attacker), manager.cover(attacker, defender).bonus(),
                flags.criticalOnHit(), roll.mode(), false, roll.dice().get(0), null);
    }

    net.dnd5ecraft.rules.conditions.ConditionEffectApplier.AttackEffect attackFlags(CombatantEntry attacker,
            CombatantEntry defender, boolean ranged, boolean advantage, boolean disadvantage) {
        int distance = attacker.cell().distanceFeet(defender.cell());
        boolean guiding = defender.has(Effect.GUIDING_LIGHT);
        var helper = manager.spells().aidFor(attacker, defender);
        boolean mocked = attacker.has(Effect.MOCKED);
        return attacker.conditionEffects().attackAgainst(defender.conditionEffects(), distance,
                manager.canSee(attacker, defender), defender.feat(FeatType.ALERT) || manager.canSee(defender, attacker),
                attacker.originatingCondition(ConditionType.CHARMED, defender.id()), manager.frightSourceVisible(attacker),
                advantage || guiding || defender.has(Effect.REVEALED) || helper.isPresent(),
                disadvantage || mocked || (defender.dodging && defender.speedFeet() > 0 && defender.canAct() && manager.canSee(defender, attacker))
                        || (ranged && adjacentHostile(attacker))
                        || (attacker.stats().traits().contains(Trait.UNDEAD) && attacker.effects.stream()
                            .anyMatch(effect -> effect.effect() == Effect.CHILLED_UNDEAD && effect.caster().equals(defender.id()))));
    }

    void resolveHit(Hit hit, boolean prompt, long nowMillis, HitResolution effect, LongConsumer after) {
        if (prompt) {
            manager.campaign().editRoll(hit.attacker, hit.roll, hit.first, hit.mode, nowMillis, (edited, moment) ->
                    resolveDefenses(new Hit(hit.attacker, hit.defender, edited.natural(), hit.modifier + edited.adjustment(),
                            hit.cover, hit.criticalOnHit, edited.mode(), hit.missile, hit.first, hit.element), true, moment, effect, after));
        } else resolveDefenses(hit, false, nowMillis, effect, after);
    }

    private void resolveDefenses(Hit hit, boolean prompt, long nowMillis, HitResolution effect, LongConsumer after) {
        AttackOutcome outcome = hit.outcome();
        List<Offer> offers = new ArrayList<>();
        if (prompt && outcome != AttackOutcome.MISS && hit.defender.canReact()) {
            if (manager.spells().hasReactionSlot(hit.defender, "shield")) offers.add(new Offer(hit.defender.id(), Kind.SHIELD));
            if (CombatSpellZoneResolver.absorbable(hit.element) && manager.spells().hasReactionSlot(hit.defender, "absorb_elements")) {
                offers.add(new Offer(hit.defender.id(), Kind.ABSORB_ELEMENTS));
            }
            if (canDodge(hit)) offers.add(new Offer(hit.defender.id(), Kind.UNCANNY_DODGE));
        }
        if (prompt && outcome != AttackOutcome.MISS && hit.missile && hit.defender.canReact()
                && hit.defender.stats().classes().getOrDefault(ClassType.MONK, 0) >= 3) {
            offers.add(new Offer(hit.defender.id(), Kind.DEFLECT_MISSILES));
        }
        if (prompt && outcome != AttackOutcome.MISS) encounter.initiativeOrder().stream()
                .filter(bard -> bard.team() == hit.defender.team() && bard.canReact() && bard.subclasses.contains(SubclassType.LORE)
                        && bard.remaining(ClassFeature.BARDIC_INSPIRATION) > 0 && bard.cell().distanceFeet(hit.attacker.cell()) <= 60
                        && manager.canSee(bard, hit.attacker) && !hit.attacker.conditionEffects().has(ConditionType.DEAFENED))
                .forEach(bard -> offers.add(new Offer(bard.id(), Kind.CUTTING_WORDS)));
        boolean[] dodge = {false};
        int[] penalties = {0, 0};
        manager.open(offers, nowMillis, response -> {
            var reactor = encounter.require(response.reactor());
            return switch (response.reaction().kind()) {
                case SHIELD -> manager.spells().canPayReaction(hit.defender, "shield", response.reaction());
                case ABSORB_ELEMENTS -> manager.spells().canPayReaction(hit.defender, "absorb_elements", response.reaction());
                case UNCANNY_DODGE -> response.reaction().slot() == 0 && canDodge(hit);
                case CUTTING_WORDS -> response.reaction().slot() == 0 && reactor.canReact()
                        && reactor.remaining(ClassFeature.BARDIC_INSPIRATION) > 0;
                case DEFLECT_MISSILES -> response.reaction().slot() == 0 && hit.defender.canReact();
                default -> false;
            };
        }, response -> {
            var reactor = encounter.require(response.reactor());
            switch (response.reaction().kind()) {
                case SHIELD -> manager.spells().shield(hit.defender, response.reaction());
                case ABSORB_ELEMENTS -> manager.spells().absorb(hit.defender, response.reaction(), hit.element);
                case UNCANNY_DODGE -> {
                    spend(reactor, Resource.REACTION);
                    dodge[0] = true;
                }
                case CUTTING_WORDS -> {
                    spend(reactor, Resource.REACTION);
                    reactor.spend(ClassFeature.BARDIC_INSPIRATION, 1);
                    int bard = reactor.stats().classes().getOrDefault(ClassType.BARD, 0);
                    penalties[0] += AbilityCheckEngine.rollDie(bard < 5 ? 6 : bard < 10 ? 8 : bard < 15 ? 10 : 12, roller);
                }
                case DEFLECT_MISSILES -> {
                    spend(reactor, Resource.REACTION);
                    penalties[1] = AbilityCheckEngine.rollDie(10, roller) + reactor.stats().abilityModifier(Ability.DEXTERITY)
                            + reactor.stats().classes().getOrDefault(ClassType.MONK, 0);
                }
                default -> throw new IllegalStateException("Unexpected reaction");
            }
        }, time -> {
            AttackOutcome finalOutcome = DamagePipeline.attackOutcome(hit.roll(), hit.modifier() - penalties[0],
                    hit.defender.armorClass() + hit.cover(), hit.criticalOnHit());
            manager.event(finalOutcome == AttackOutcome.MISS ? EventType.MISS : EventType.HIT,
                    hit.attacker, hit.defender, finalOutcome.name(), hit.roll);
            effect.apply(finalOutcome, dodge[0], Math.max(0, penalties[1]));
            after.accept(time);
        });
    }

    private static Map<DamageType5e, Integer> reduceDamage(Map<DamageType5e, Integer> original, int reduction) {
        if (reduction == 0) return original;
        var result = new EnumMap<DamageType5e, Integer>(DamageType5e.class);
        for (var type : DamageType5e.values()) {
            int raw = original.getOrDefault(type, 0);
            if (raw == 0) continue;
            int absorbed = Math.min(reduction, raw);
            result.put(type, raw - absorbed);
            reduction -= absorbed;
        }
        return result;
    }

    private boolean canDodge(Hit hit) {
        return hit.defender.canReact() && hit.defender.stats().traits().contains(Trait.CAN_UNCANNY_DODGE)
                && manager.canSee(hit.defender, hit.attacker);
    }

    private boolean adjacentHostile(CombatantEntry attacker) {
        return encounter.initiativeOrder().stream().anyMatch(enemy -> enemy.team() != attacker.team() && enemy.canAct()
                && attacker.cell().distanceFeet(enemy.cell()) <= 5 && manager.canSee(enemy, attacker));
    }
}
