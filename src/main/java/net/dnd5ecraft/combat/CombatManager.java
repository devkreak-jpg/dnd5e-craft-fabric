package net.dnd5ecraft.combat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import net.dnd5ecraft.combat.CombatantEntry.Attack;
import net.dnd5ecraft.combat.CombatantEntry.Effect;
import net.dnd5ecraft.combat.CombatantEntry.TimedEffect;
import net.dnd5ecraft.combat.CombatantEntry.Trait;
import net.dnd5ecraft.combat.CoverRaycaster.Cover;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.GridMovementTracker.Terrain;
import net.dnd5ecraft.combat.LightingVisionEvaluator.Light;
import net.dnd5ecraft.combat.OpportunityAttackDetector.Movement;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.combat.ReactionWindowManager.Offer;
import net.dnd5ecraft.combat.ReactionWindowManager.Window;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.entity.BestiaryPreset.Feature;
import net.dnd5ecraft.entity.BestiaryTurnState;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.ability.DiceRoller;
import net.dnd5ecraft.rules.ability.PassiveSenseTracker;
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
import net.dnd5ecraft.rules.spells.AoEGeometry.Area;
import net.dnd5ecraft.rules.spells.AoEGeometry.Template;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.dnd5ecraft.rules.spells.AoETargetResolver;
import net.dnd5ecraft.rules.spells.ConcentrationManager;
import net.dnd5ecraft.rules.spells.Spell;
import net.dnd5ecraft.rules.spells.Spell.CastingTime;
import net.dnd5ecraft.rules.spells.Spell.EffectKind;
import net.dnd5ecraft.rules.spells.Spell.Resolution;
import net.dnd5ecraft.rules.spells.Spell.Targeting;
import net.dnd5ecraft.rules.spells.SpellRegistry5e;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;

/** Synchronous rules coordinator. World adapters call it from the authoritative game thread. */
public final class CombatManager {
    public interface Battlefield extends Terrain {
        boolean blocksCover(Cell cell);
        Light lightAt(Cell cell);

        default boolean canOccupy(CombatantEntry mover, Cell cell) { return canOccupy(cell); }
        default boolean blocksSight(Cell cell) { return blocksCover(cell); }
        default boolean blocksMagic(Cell cell) { return blocksCover(cell); }

        default boolean suppressesRegeneration(CombatantEntry target) { return false; }
        default boolean useLegendaryResistance(CombatantEntry target) { return true; }

        default boolean isWilling(CombatantEntry caster, CombatantEntry target) {
            return caster.team() == target.team();
        }
    }

    public enum Maneuver {
        DODGE, HELP, HIDE, READY, LUCKY, INSPIRATION, GRAPPLE, SHOVE, KNOCK_PRONE, STABILIZE, SECOND_WIND, RAGE,
        WILD_SHAPE, LAY_ON_HANDS, INSPIRE, TURN_UNDEAD, PATIENT_DEFENSE, STEP_OF_WIND, DRACONIC_BREATH,
        WEAPON_STRIKE, MOVE_MOONBEAM, RETARGET_MARK, BREAK_WEB, QUICKEN_SPELL, SORCERY_SLOT, OFFHAND, POWER_ATTACK, SMITE
    }

    public enum EventType { TURN, MOVED, HIT, MISS, DAMAGE, HEAL, CAST, COUNTERED, CONCENTRATION_ENDED, DEATH_SAVE, BESTIARY }

    public record Event(EventType type, UUID actor, UUID target, String detail, int value) {}

    /** Aim is required for area spells. Choice is the condition to inflict or remove, or the damage type to resist. */
    public record CastRequest(String spellId, int slot, Pool pool, List<UUID> targets, Optional<Vec3> aim,
                              Optional<String> choice) {
        public CastRequest {
            Objects.requireNonNull(spellId);
            Objects.requireNonNull(pool);
            targets = List.copyOf(targets);
            Objects.requireNonNull(aim);
            Objects.requireNonNull(choice);
        }

        public CastRequest(String spellId, int slot, List<UUID> targets) {
            this(spellId, slot, Pool.REGULAR, targets, Optional.empty(), Optional.empty());
        }
    }

    public record Reaction(Kind kind, int slot, Pool pool) {
        public Reaction { Objects.requireNonNull(kind); Objects.requireNonNull(pool); }
        public Reaction(Kind kind) { this(kind, 0, Pool.REGULAR); }
    }

    private record Response(UUID reactor, Reaction reaction) {}
    private record Aid(UUID target, long expiry) {}
    private record SpellZone(Cast cast, Area area, Map<UUID, Long> lastAffected) {}
    private record SpiritualWeapon(Cast cast, Cell cell) {}
    private record Pending(Predicate<Response> validate, Consumer<Response> respond, LongConsumer resume) {}
    private record Cast(CombatantEntry caster, Spell spell, int slot, Ability ability,
                        List<CombatantEntry> targets, Optional<String> choice, Optional<Vec3> areaOrigin, long id) {}
    private record Hit(CombatantEntry attacker, CombatantEntry defender, int roll, int modifier,
                       int cover, boolean criticalOnHit, RollMode mode, boolean missile) {
        AttackOutcome outcome() {
            return DamagePipeline.attackOutcome(roll, modifier, defender.armorClass() + cover, criticalOnHit);
        }
    }

    private static final Set<DamageType5e> PROTECTION_TYPES = Set.of(DamageType5e.ACID, DamageType5e.COLD,
            DamageType5e.FIRE, DamageType5e.LIGHTNING, DamageType5e.THUNDER);
    private static final Set<ConditionType> RESTORABLE = Set.of(ConditionType.BLINDED, ConditionType.DEAFENED,
            ConditionType.PARALYZED, ConditionType.POISONED);

    private final CombatEncounterState encounter;
    private final Battlefield world;
    private final IntUnaryOperator roller;
    private final ReactionWindowManager reactions = new ReactionWindowManager();
    private final ConcentrationManager concentration = new ConcentrationManager();
    private final Map<Long, Long> concentrationExpiry = new HashMap<>();
    private final Map<UUID, Long> temporaryProvider = new HashMap<>();
    private final List<Event> events = new ArrayList<>();
    private Pending pending;
    private final Map<UUID, Aid> aids = new HashMap<>();
    private final Map<UUID, Integer> readied = new HashMap<>();
    private final Map<Long, SpellZone> zones = new LinkedHashMap<>();
    private final Map<UUID, SpiritualWeapon> spiritualWeapons = new HashMap<>();
    private long castSequence;
    private long turnSequence;
    private int lairActionRound;

    public CombatManager(CombatEncounterState encounter, Battlefield world, IntUnaryOperator roller) {
        this.encounter = Objects.requireNonNull(encounter);
        this.world = Objects.requireNonNull(world);
        this.roller = Objects.requireNonNull(roller);
    }

    public CombatEncounterState.Snapshot snapshot() { return encounter.snapshot(); }
    public Optional<Window> reactionWindow() { return reactions.current(); }
    public Optional<ConcentrationManager.Active> concentration(UUID caster) { return concentration.current(caster); }

    public List<Event> drainEvents() {
        List<Event> drained = List.copyOf(events);
        events.clear();
        return drained;
    }

    public void start() { start(Set.of()); }

    public void start(Set<UUID> surprised) {
        lairActionRound = 0;
        for (CombatantEntry entry : encounter.initiativeOrder()) entry.surprised = surprised.contains(entry.id()) && !entry.feat(FeatType.ALERT);
        beginTurn(encounter.begin());
    }

    public void removeCombatant(UUID id) {
        if (encounter.find(id).isEmpty()) return;
        boolean current = encounter.currentEntry().map(entry -> entry.id().equals(id)).orElse(false);
        if (pending != null) {
            reactions.cancel();
            pending = null;
        }
        endConcentration(id);
        encounter.remove(id);
        aids.remove(id);
        readied.remove(id);
        spiritualWeapons.remove(id);
        refreshGuardians();
        for (CombatantEntry entry : encounter.initiativeOrder()) {
            entry.conditions.removeIf(condition -> condition.type() == ConditionType.GRAPPLED
                    && condition.source().filter(id::equals).isPresent());
        }
        if (current) encounter.currentEntry().ifPresent(this::beginTurn);
    }

    public void finish(long nowMillis) {
        requireIdle(nowMillis);
        encounter.currentEntry().orElseThrow(() -> new IllegalStateException("No active encounter")).turn.endTurn();
        encounter.end();
    }

    public void endTurn(UUID actor, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry current = current(actor);
        repeatSaves(current);
        expireEffects(current, false);
        List<ConditionInstance> remaining = current.conditions.stream().flatMap(c -> c.advanceRound().stream()).toList();
        current.conditions.clear();
        current.conditions.addAll(remaining);
        current.attacksRemaining = 0;
        current.hasteAction = false;
        current.quickenNextSpell = false;
        current.surprised = false;
        current.turn.endTurn();
        if (encounter.initiativeOrder().stream().allMatch(entry -> entry.initiative() >= 20)
                && current == encounter.initiativeOrder().get(encounter.initiativeOrder().size() - 1)
                && lairActionRound != encounter.round()) {
            lairActionRound = encounter.round();
            resolveLairActions();
        }
        legendaryAfterTurn(current, 0, nowMillis);
    }

    private void legendaryAfterTurn(CombatantEntry finished, int index, long nowMillis) {
        List<CombatantEntry> order = encounter.initiativeOrder();
        for (int i = index; i < order.size(); i++) {
            CombatantEntry attacker = order.get(i);
            if (attacker.team() == finished.team() || finished.hp.current() == 0
                    || !attacker.canAct() || !canHarm(attacker, finished)) continue;
            var state = attacker.bestiary().orElse(null);
            if (state == null || state.legendaryActions() == 0) continue;
            Attack attack = state.preset() == BestiaryPreset.ADULT_RED_DRAGON
                    ? attacker.stats().attacks().get(2) : attacker.stats().attacks().get(0);
            if (!attack.inRange(attacker.cell().distanceFeet(finished.cell()))
                    || cover(attacker, finished).blocksTargeting()) continue;
            state.spendLegendaryAction();
            event(EventType.BESTIARY, attacker, finished, "legendary_attack", state.legendaryActions());
            int next = i + 1;
            strike(attacker, finished, attack, true, nowMillis, time -> legendaryAfterTurn(finished, next, time));
            return;
        }
        beginTurn(encounter.advance());
    }

    private void beginTurn(CombatantEntry current) {
        if (current.initiative() < 20 && lairActionRound != encounter.round()) {
            lairActionRound = encounter.round();
            resolveLairActions();
        }
        current.turns++;
        turnSequence++;
        current.attacksRemaining = 0;
        current.surgedThisTurn = false;
        current.dodging = false;
        current.threatenedByMobile.clear();
        current.tookAttackAction = false;
        current.turn.startTurn();
        readied.remove(current.id());
        expireEffects(current, true);
        current.hasteAction = current.has(Effect.HASTE);
        spiritualWeapons.computeIfPresent(current.id(), (id, weapon) ->
                current.turns >= weapon.cast.caster.turns && current.effects.stream().noneMatch(effect -> effect.castId() == weapon.cast.id)
                        ? null : weapon);
        concentration.current(current.id()).filter(cast -> concentrationExpiry.get(cast.castId()) <= current.turns)
                .ifPresent(this::dropConcentration);
        if (!current.dead()) current.bestiary().ifPresent(state -> {
            int recovery = state.beginTurn(current.hp.current(), world.suppressesRegeneration(current), roller);
            if (recovery > 0) heal(current, current, recovery);
            else if (state.preset() == BestiaryPreset.TROLL && current.hp.current() == 0) current.death = DeathSaveTracker.DEAD;
        });
        triggerZones(current, null);
        if (!current.canAct()) endConcentration(current.id());
        if (!current.dead() && current.death.state() == DeathSaveTracker.State.DYING) {
            RollMode mode = current.conditionEffects().savingThrow(Ability.CONSTITUTION, false, false).mode();
            int roll = d20(current, mode);
            var result = current.death.roll(roll, luck(current) + auraOfProtection(current));
            current.death = result.tracker();
            if (current.dead()) current.diedAtRound = encounter.round();
            if (result.hitPointsRestored() > 0) current.hp = current.hp.heal(result.hitPointsRestored());
            event(EventType.DEATH_SAVE, current, current, current.death.state().name(), roll);
        }
        event(EventType.TURN, current, current, "", encounter.round());
    }

    public void dash(UUID actor, boolean bonusAction, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry current = acting(actor);
        checkCunningAction(current, bonusAction);
        if (!bonusAction && !current.turn.canSpend(Resource.ACTION) && current.hasteAction) {
            current.hasteAction = false;
            current.turn.extraDash();
        } else if (!current.turn.dash(actionResource(bonusAction))) throw new IllegalStateException("Dash resource unavailable");
    }

    public void disengage(UUID actor, boolean bonusAction, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry current = acting(actor);
        checkCunningAction(current, bonusAction);
        if (!bonusAction && !current.turn.canSpend(Resource.ACTION) && current.hasteAction) {
            current.hasteAction = false;
            current.turn.extraDisengage();
        } else if (!current.turn.disengage(actionResource(bonusAction))) throw new IllegalStateException("Disengage resource unavailable");
    }

    private static Resource actionResource(boolean bonus) { return bonus ? Resource.BONUS_ACTION : Resource.ACTION; }

    private static void checkCunningAction(CombatantEntry actor, boolean bonus) {
        if (bonus && actor.stats().classes().getOrDefault(ClassType.ROGUE, 0) < 2) {
            throw new IllegalStateException("Cunning Action is unavailable");
        }
    }

    public void actionSurge(UUID actor, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry current = acting(actor);
        if (current.remaining(ClassFeature.ACTION_SURGE) == 0 || current.surgedThisTurn) {
            throw new IllegalStateException("Action Surge is unavailable");
        }
        current.turn.grantAction();
        current.surgedThisTurn = true;
        current.spend(ClassFeature.ACTION_SURGE, 1);
    }

    public void interact(UUID actor, long nowMillis) {
        requireIdle(nowMillis);
        if (!acting(actor).turn.spend(Resource.OBJECT_INTERACTION)) {
            throw new IllegalStateException("Object interaction already spent");
        }
    }

    public void stand(UUID actor, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry current = acting(actor);
        if (!current.conditionEffects().has(ConditionType.PRONE)) throw new IllegalStateException("Creature is not prone");
        int speed = current.speedFeet();
        if (speed == 0 || !current.turn.move(speed / 2, speed)) throw new IllegalStateException("Movement resource unavailable");
        current.conditions.removeIf(condition -> condition.type() == ConditionType.PRONE);
    }

    public Map<Cell, Integer> reachable(UUID actor) {
        CombatantEntry mover = current(actor);
        return GridMovementTracker.reachable(mover.cell(), mover.turn.remainingMovement(mover.speedFeet()),
                movementTerrain(mover), mover.conditionEffects().mustCrawl());
    }

    public Optional<GridMovementTracker.Path> path(UUID actor, Cell destination) {
        CombatantEntry mover = current(actor);
        return GridMovementTracker.path(mover.cell(), destination, mover.turn.remainingMovement(mover.speedFeet()),
                movementTerrain(mover), mover.conditionEffects().mustCrawl());
    }

    public Optional<Window> move(UUID actor, Cell destination, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry mover = current(actor);
        int cost = GridMovementTracker.stepCost(mover.cell(), destination, movementTerrain(mover),
                mover.conditionEffects().mustCrawl());
        if (cost > mover.turn.remainingMovement(mover.speedFeet())) throw new IllegalStateException("Movement resource unavailable");
        List<Offer> offers = opportunityThreats(mover, destination).stream()
                .map(reactor -> new Offer(reactor, Kind.OPPORTUNITY_ATTACK)).toList();
        List<CombatantEntry> accepted = new ArrayList<>();
        return open(offers, nowMillis, response -> encounter.require(response.reactor).canReact()
                && response.reaction.slot == 0, response -> accepted.add(encounter.require(response.reactor)),
                time -> resolveOpportunityAttacks(mover, encounter.initiativeOrder().stream().filter(accepted::contains).toList(),
                        0, time, moment -> {
                            if (mover.turn.move(cost, mover.speedFeet())) {
                                Cell previous = mover.cell();
                                relocate(mover, destination);
                                triggerReadied(mover, previous, moment);
                            }
                        }));
    }

    private void triggerReadied(CombatantEntry mover, Cell previous, long now) {
        if (mover.dead()) return;
        List<Offer> offered = encounter.initiativeOrder().stream()
                .filter(reactor -> reactor.team() != mover.team() && readied.containsKey(reactor.id()) && reactor.canReact()
                        && canHarm(reactor, mover) && canSee(reactor, mover) && !cover(reactor, mover).blocksTargeting())
                .filter(reactor -> {
                    Attack weapon = reactor.stats().attacks().get(readied.get(reactor.id()));
                    return !weapon.inRange(reactor.cell().distanceFeet(previous)) && weapon.inRange(reactor.cell().distanceFeet(mover.cell()));
                }).map(reactor -> new Offer(reactor.id(), Kind.READIED_ATTACK)).toList();
        List<CombatantEntry> accepted = new ArrayList<>();
        open(offered, now, answer -> answer.reaction.slot == 0 && encounter.require(answer.reactor).canReact(), answer ->
                accepted.add(encounter.require(answer.reactor)), time -> fireReadied(mover, accepted, 0, time));
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

    public List<UUID> opportunityThreats(UUID actor, Cell destination) {
        return opportunityThreats(current(actor), destination);
    }

    private List<UUID> opportunityThreats(CombatantEntry mover, Cell destination) {
        return OpportunityAttackDetector.leavingReach(mover, destination, Movement.VOLUNTARY,
                false, encounter.initiativeOrder(), this::canSee).stream()
                .filter(reactor -> canHarm(reactor, mover) && !cover(reactor, mover).blocksTargeting()
                        && (!mover.turn.disengaged() || reactor.feat(FeatType.SENTINEL))
                        && !mover.threatenedByMobile.contains(reactor.id()))
                .map(CombatantEntry::id).toList();
    }

    private void resolveOpportunityAttacks(CombatantEntry mover, List<CombatantEntry> reactors, int index,
            long nowMillis, LongConsumer after) {
        if (index == reactors.size() || mover.hp.current() == 0) {
            after.accept(nowMillis);
            return;
        }
        CombatantEntry reactor = reactors.get(index);
        LongConsumer next = time -> resolveOpportunityAttacks(mover, reactors, index + 1, time, after);
        if (!reactor.canReact() || !canHarm(reactor, mover) || cover(reactor, mover).blocksTargeting()) {
            next.accept(nowMillis);
            return;
        }
        Attack attack = reactor.stats().attacks().stream().filter(Attack::melee)
                .filter(profile -> profile.inRange(reactor.cell().distanceFeet(mover.cell()))).findFirst().orElseThrow();
        reactor.turn.spend(Resource.REACTION);
        strike(reactor, mover, attack, true, nowMillis, next, false, 0, true);
    }

    /** Applies forced movement or a teleport without spending movement or provoking. */
    public boolean relocate(UUID actor, Cell destination, Movement movement, long nowMillis) {
        requireIdle(nowMillis);
        if (movement == Movement.VOLUNTARY) throw new IllegalArgumentException("Use move for voluntary movement");
        CombatantEntry mover = encounter.require(actor);
        Terrain terrain = movementTerrain(mover, false);
        boolean legal = movement == Movement.TELEPORT ? terrain.canOccupy(destination)
                : mover.cell().distanceFeet(destination) == GridMovementTracker.FEET_PER_BLOCK
                    && terrain.neighbors(mover.cell()).contains(destination) && terrain.canStep(mover.cell(), destination);
        if (legal) relocate(mover, destination);
        return legal;
    }

    private void relocate(CombatantEntry mover, Cell destination) {
        Cell previous = mover.cell;
        mover.cell = destination;
        refreshGuardians();
        triggerZones(mover, previous);
        releaseEndedGrapples();
        event(EventType.MOVED, mover, mover, "", 0);
    }

    private void releaseEndedGrapples() {
        for (CombatantEntry entry : encounter.initiativeOrder()) {
            entry.conditions.removeIf(condition -> condition.type() == ConditionType.GRAPPLED
                    && condition.source().flatMap(encounter::find).map(grappler ->
                        condition.endsAfterGrapplerChanges(grappler.canAct(),
                                entry.cell().distanceFeet(grappler.cell()) <= Math.max(5, grappler.stats().meleeReachFeet())))
                        .orElse(false));
        }
    }

    Terrain movementTerrain(CombatantEntry mover) { return movementTerrain(mover, true); }

    private Terrain movementTerrain(CombatantEntry mover, boolean voluntary) {
        return new Terrain() {
            @Override public boolean canOccupy(Cell to) {
                return world.canOccupy(mover, to) && encounter.initiativeOrder().stream()
                        .noneMatch(entry -> entry != mover && !entry.dead() && entry.cell().equals(to));
            }
            @Override public boolean difficult(Cell cell) { return world.difficult(cell) || zones.values().stream().anyMatch(zone ->
                            zone.cast.spell.id().equals("web") && area(zone).contains(cell.center())); }
            @Override public List<Cell> neighbors(Cell from) { return world.neighbors(from); }
            @Override public boolean canStep(Cell from, Cell to) {
                return Terrain.super.canStep(from, to) && world.canStep(from, to) && (!voluntary
                        || mover.conditions.stream().noneMatch(condition -> condition.type() == ConditionType.FRIGHTENED
                            && condition.source().flatMap(encounter::find).map(source ->
                                to.distanceFeet(source.cell()) < from.distanceFeet(source.cell())).orElse(false)));
            }
        };
    }

    public void maneuver(UUID actor, Maneuver action, UUID targetId, int option, Cell point, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry user = acting(actor);
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
                aids.put(user.id(), new Aid(target.id(), user.turns + 1));
            }
            case HIDE -> {
                if (encounter.initiativeOrder().stream().anyMatch(entry -> entry.team() != user.team() && canSee(entry, user))) {
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
                    if (action == Maneuver.SHOVE) push(user, target, 1);
                    else applyCondition(target.id(), new ConditionInstance(action == Maneuver.GRAPPLE
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
                heal(user, user, AbilityCheckEngine.rollDie(10, roller) + user.stats().classes().getOrDefault(ClassType.FIGHTER, 0));
            }
            case RAGE -> {
                if (user.has(Effect.RAGING)) throw new IllegalStateException("Rage is active");
                resource(user, ClassFeature.RAGE, 1, Resource.BONUS_ACTION);
                endConcentration(user.id());
                user.effects.add(new TimedEffect(Effect.RAGING, user.id(), ++castSequence, user.id(), user.turns + 10, true, 0, null));
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
                heal(user, target, option);
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
                            && enemy.stats().traits().contains(Trait.UNDEAD) && !save(enemy, Ability.WISDOM, dc, 0, false, true)) {
                        applyCondition(enemy.id(), new ConditionInstance(ConditionType.FRIGHTENED, Optional.of(user.id()), 10));
                        enemy.effects.add(new TimedEffect(Effect.NO_REACTIONS, user.id(), ++castSequence,
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
            case WEAPON_STRIKE -> moveSpiritualWeapon(user, target, nowMillis);
            case MOVE_MOONBEAM -> moveMoonbeam(user, point);
            case RETARGET_MARK -> retargetMark(user, target);
            case BREAK_WEB -> {
                var web = user.effects.stream().filter(e -> e.effect() == Effect.WEBBED).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No restraining web"));
                spend(user, Resource.ACTION);
                if (abilityTotal(user, Skill.ATHLETICS) >= web.saveDc()) user.effects.remove(web);
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

    private static void spend(CombatantEntry user, Resource resource) {
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
                (target != user && cover(user, target).blocksTargeting()) || hostile && !canHarm(user, target)) {
            throw new IllegalArgumentException("Target must be within reach");
        }
    }

    private int d20(CombatantEntry user, RollMode mode) {
        boolean halfling = user.race == RaceType.HALFLING;
        boolean inspired = user.advantageOnNextRoll;
        user.advantageOnNextRoll = false;
        var dice = new DiceRoller(roller);
        int total = dice.d20(inspired ? RollMode.from(true, mode == RollMode.DISADVANTAGE) : mode, 0, halfling).total();
        if (user.luckyRoll) {
            user.luckyRoll = false;
            return Math.max(total, DiceRoller.naturalD20(roller, halfling));
        }
        return total;
    }

    private int abilityTotal(CombatantEntry user, Skill skill) {
        boolean advantage = user.has(Effect.RAGING) && skill.ability() == Ability.STRENGTH;
        var flags = user.conditionEffects().abilityCheck(Sense.NONE, frightSourceVisible(user), advantage,
                skill == Skill.STEALTH && user.armorStealthDisadvantage);
        int bonus = user.skillModifier(skill);
        if (user.has(Effect.GUIDANCE)) {
            var guidance = user.effects.stream().filter(effect -> effect.effect() == Effect.GUIDANCE).findFirst().orElseThrow();
            bonus += AbilityCheckEngine.rollDie(4, roller);
            endConcentration(guidance.caster());
        }
        return d20(user, flags.mode()) + bonus;
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
        }
        attacker.attacksRemaining--;
        attacker.tookAttackAction = true;
    }

    public Optional<Window> attack(UUID actor, UUID target, int profile, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry attacker = acting(actor);
        CombatantEntry defender = encounter.require(target);
        Attack attack = attacker.stats().attacks().get(profile);
        int distance = attacker.cell().distanceFeet(defender.cell());
        validateHostileTarget(attacker, defender);
        if (!attack.inRange(distance) || cover(attacker, defender).blocksTargeting()) {
            throw new IllegalArgumentException("Target is out of range or behind total cover");
        }
        spendAttack(attacker);
        strike(attacker, defender, attack, true, nowMillis, ignored -> {});
        return reactions.current();
    }

    private void strike(CombatantEntry attacker, CombatantEntry defender, Attack attack, boolean prompt,
            long nowMillis, LongConsumer after) {
        strike(attacker, defender, attack, prompt, nowMillis, after, false, 0);
    }

    private void strike(CombatantEntry attacker, CombatantEntry defender, Attack attack, boolean prompt,
            long nowMillis, LongConsumer after, boolean power, int smiteSlot) {
        strike(attacker, defender, attack, prompt, nowMillis, after, power, smiteSlot, false);
    }

    private void strike(CombatantEntry attacker, CombatantEntry defender, Attack attack, boolean prompt,
            long nowMillis, LongConsumer after, boolean power, int smiteSlot, boolean opportunity) {
        if (attack.melee() && attacker.feat(FeatType.MOBILE)) attacker.threatenedByMobile.add(defender.id());
        int distance = attacker.cell().distanceFeet(defender.cell());
        boolean packAdvantage = attacker.bestiary().filter(state -> state.has(Feature.PACK_TACTICS)).isPresent()
                && encounter.initiativeOrder().stream().anyMatch(ally -> ally != attacker && ally.team() == attacker.team()
                    && ally.canAct() && ally.cell().distanceFeet(defender.cell()) <= 5);
        boolean ignoreCover = !attack.melee() && attacker.feat(FeatType.SHARPSHOOTER);
        Hit base = rollAttack(attacker, defender, attack.attackBonus() - (power ? 5 : 0), !attack.melee(), packAdvantage,
                !attack.melee() && !ignoreCover && distance > attack.normalRangeFeet());
        Hit hit = new Hit(attacker, defender, base.roll(), base.modifier(), ignoreCover ? 0 : base.cover(),
                base.criticalOnHit(), base.mode(), !attack.melee());
        resolveHit(hit, prompt, nowMillis, (outcome, dodge, reduction) -> {
            if (outcome != AttackOutcome.MISS) {
                List<DamageRoll> rolls = new ArrayList<>(withSneakAttack(attacker, defender, attack, hit.mode()));
                DamageType5e weaponType = attack.damage().get(0).type();
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
                dealDamage(attacker, defender, reduceDamage(damage, reduction), outcome == AttackOutcome.CRITICAL, dodge);
                if (opportunity && attacker.feat(FeatType.SENTINEL)) defender.speedLockedTurn = defender.turns;
                if (!defender.dead()) bestiaryRider(attacker, defender, attack);
            }
        }, after);
    }

    private void bestiaryRider(CombatantEntry attacker, CombatantEntry target, Attack attack) {
        var preset = attacker.bestiary().map(state -> state.preset()).orElse(null);
        if (preset == null) return;
        if ((preset == BestiaryPreset.WOLF || preset == BestiaryPreset.DIRE_WOLF)
                && !save(target, Ability.STRENGTH, preset == BestiaryPreset.WOLF ? 11 : 13, 0, false, false)) {
            applyCondition(target.id(), new ConditionInstance(ConditionType.PRONE, Optional.of(attacker.id()), ConditionInstance.INDEFINITE));
        }
        if (preset == BestiaryPreset.GHOUL && attack.name().equals("Claws")
                && target.race != RaceType.ELF && !target.stats().traits().contains(Trait.UNDEAD)
                && !target.has(Effect.GHOUL_PARALYSIS) && !save(target, Ability.CONSTITUTION, 10, 0, false, false)) {
            target.effects.add(new TimedEffect(Effect.GHOUL_PARALYSIS, attacker.id(), 0, target.id(), target.turns + 10,
                    true, 10, null));
            endConcentration(target.id());
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
        validateHostileTarget(user, target);
        if (!attack.inRange(user.cell().distanceFeet(target.cell())) || cover(user, target).blocksTargeting()) {
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
        resolveBestiaryFire(user, shape, 8 + user.stats().abilityModifier(Ability.CONSTITUTION)
                + user.stats().proficiencyBonus(), new Dice(dice, 6));
    }

    private List<DamageRoll> withSneakAttack(CombatantEntry attacker, CombatantEntry defender, Attack attack, RollMode mode) {
        int rogue = attacker.stats().classes().getOrDefault(ClassType.ROGUE, 0);
        if (rogue == 0 || (!attack.finesse() && attack.melee()) || attacker.lastSneakAttack == turnSequence
                || mode == RollMode.DISADVANTAGE) return attack.damage();
        boolean distracted = encounter.initiativeOrder().stream().anyMatch(other -> other != attacker
                && other.team() != defender.team() && other.canAct() && other.cell().distanceFeet(defender.cell()) <= 5);
        if (mode != RollMode.ADVANTAGE && !distracted) return attack.damage();
        attacker.lastSneakAttack = turnSequence;
        List<DamageRoll> rolls = new ArrayList<>(attack.damage());
        rolls.add(new DamageRoll(rolls.get(0).type(), DamagePipeline.sneakAttackDice(rogue), 0));
        return rolls;
    }

    private Hit rollAttack(CombatantEntry attacker, CombatantEntry defender, int bonus, boolean ranged,
            boolean advantage, boolean disadvantage) {
        int distance = attacker.cell().distanceFeet(defender.cell());
        boolean guiding = defender.has(Effect.GUIDING_LIGHT);
        var helper = aids.entrySet().stream().filter(e -> e.getValue().target.equals(defender.id()))
                .filter(e -> encounter.find(e.getKey()).filter(ally -> !ally.id().equals(attacker.id()) && ally.team() == attacker.team()
                        && ally.turns < e.getValue().expiry).isPresent()).map(Map.Entry::getKey).findFirst();
        boolean mocked = attacker.has(Effect.MOCKED);
        var flags = attacker.conditionEffects().attackAgainst(defender.conditionEffects(), distance,
                canSee(attacker, defender), defender.feat(FeatType.ALERT) || canSee(defender, attacker),
                attacker.originatingCondition(ConditionType.CHARMED, defender.id()), frightSourceVisible(attacker),
                advantage || guiding || defender.has(Effect.REVEALED) || helper.isPresent(),
                disadvantage || mocked || (defender.dodging && defender.speedFeet() > 0 && defender.canAct() && canSee(defender, attacker))
                        || (ranged && adjacentHostile(attacker))
                        || (attacker.stats().traits().contains(Trait.UNDEAD) && attacker.effects.stream()
                            .anyMatch(effect -> effect.effect() == Effect.CHILLED_UNDEAD && effect.caster().equals(defender.id()))));
        int roll = d20(attacker, flags.mode());
        attacker.stealth = -1;
        helper.ifPresent(aids::remove);
        if (mocked) attacker.effects.removeIf(effect -> effect.effect() == Effect.MOCKED);
        if (guiding) defender.effects.removeIf(effect -> effect.effect() == Effect.GUIDING_LIGHT);
        return new Hit(attacker, defender, roll, bonus + luck(attacker), cover(attacker, defender).bonus(),
                flags.criticalOnHit(), flags.mode(), false);
    }

    @FunctionalInterface
    private interface HitResolution { void apply(AttackOutcome outcome, boolean dodge, int reduction); }

    private void resolveHit(Hit hit, boolean prompt, long nowMillis, HitResolution effect, LongConsumer after) {
        AttackOutcome outcome = hit.outcome();
        List<Offer> offers = new ArrayList<>();
        if (prompt && outcome != AttackOutcome.MISS && hit.defender.canReact()) {
            if (hasReactionSlot(hit.defender, "shield")) offers.add(new Offer(hit.defender.id(), Kind.SHIELD));
            if (canDodge(hit)) offers.add(new Offer(hit.defender.id(), Kind.UNCANNY_DODGE));
        }
        if (prompt && outcome != AttackOutcome.MISS && hit.missile && hit.defender.canReact()
                && hit.defender.stats().classes().getOrDefault(ClassType.MONK, 0) >= 3) {
            offers.add(new Offer(hit.defender.id(), Kind.DEFLECT_MISSILES));
        }
        if (prompt && outcome != AttackOutcome.MISS) encounter.initiativeOrder().stream()
                .filter(bard -> bard.team() == hit.defender.team() && bard.canReact() && bard.subclasses.contains(SubclassType.LORE)
                        && bard.remaining(ClassFeature.BARDIC_INSPIRATION) > 0 && bard.cell().distanceFeet(hit.attacker.cell()) <= 60
                        && canSee(bard, hit.attacker) && !hit.attacker.conditionEffects().has(ConditionType.DEAFENED))
                .forEach(bard -> offers.add(new Offer(bard.id(), Kind.CUTTING_WORDS)));
        boolean[] dodge = {false};
        int[] penalties = {0, 0};
        open(offers, nowMillis, response -> {
            var reactor = encounter.require(response.reactor);
            return switch (response.reaction.kind) {
                case SHIELD -> canPayReaction(hit.defender, "shield", response.reaction);
                case UNCANNY_DODGE -> response.reaction.slot == 0 && canDodge(hit);
                case CUTTING_WORDS -> response.reaction.slot == 0 && reactor.canReact()
                        && reactor.remaining(ClassFeature.BARDIC_INSPIRATION) > 0;
                case DEFLECT_MISSILES -> response.reaction.slot == 0 && hit.defender.canReact();
                default -> false;
            };
        }, response -> {
            var reactor = encounter.require(response.reactor);
            switch (response.reaction.kind) {
                case SHIELD -> shield(hit.defender, response.reaction);
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
            event(finalOutcome == AttackOutcome.MISS ? EventType.MISS : EventType.HIT,
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
                && canSee(hit.defender, hit.attacker);
    }

    CombatantEntry combatant(UUID id) { return encounter.require(id); }
    List<CombatantEntry> combatants() { return encounter.initiativeOrder(); }

    public Cover cover(CombatantEntry observer, CombatantEntry target) {
        return coverFrom(observer.cell(), target);
    }

    Cover coverFrom(Cell from, CombatantEntry target) {
        return CoverRaycaster.cover(eye(from), target.bounds(), world::blocksCover);
    }

    public boolean canSee(CombatantEntry observer, CombatantEntry target) {
        return canSeeFrom(observer, observer.cell(), target);
    }

    boolean canSeeFrom(CombatantEntry observer, Cell from, CombatantEntry target) {
        if (observer == target) return true;
        if (target.dead() || !target.conditionEffects().visiblyPresent(target.has(Effect.REVEALED))) return false;
        if (target.stealth >= 0 && target.stealth > PassiveSenseTracker.score(observer.skillModifier(Skill.PERCEPTION), RollMode.NORMAL)) return false;
        return LightingVisionEvaluator.evaluate(world.lightAt(target.cell()), from.distanceFeet(target.cell()),
                observer.stats().darkvisionFeet(), observer.stats().traits().contains(Trait.DEVILS_SIGHT) ? 120 : 0,
                CoverRaycaster.clearRay(eye(from), eye(target), world::blocksSight),
                observer.conditionEffects().has(ConditionType.BLINDED)).visible();
    }

    private static Vec3 eye(CombatantEntry observer) { return eye(observer.cell()); }
    private static Cell cellAt(Vec3 point) {
        return new Cell((int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z()));
    }
    private static Vec3 eye(Cell cell) { return cell.center().plus(new Vec3(0, .95, 0)); }

    private boolean adjacentHostile(CombatantEntry attacker) {
        return encounter.initiativeOrder().stream().anyMatch(enemy -> enemy.team() != attacker.team() && enemy.canAct()
                && attacker.cell().distanceFeet(enemy.cell()) <= 5 && canSee(enemy, attacker));
    }

    private boolean frightSourceVisible(CombatantEntry actor) {
        return actor.conditions.stream().anyMatch(condition -> condition.type() == ConditionType.FRIGHTENED
                && condition.source().flatMap(encounter::find).map(source -> canSee(actor, source)).orElse(false));
    }

    boolean canHarm(CombatantEntry actor, CombatantEntry target) {
        return actor != target && !target.dead()
                && actor.conditionEffects().canTargetWithHarmfulEffect(actor.originatingCondition(ConditionType.CHARMED, target.id()));
    }

    private void validateHostileTarget(CombatantEntry actor, CombatantEntry target) {
        if (!canHarm(actor, target)) {
            throw new IllegalArgumentException("Invalid target for a hostile effect");
        }
    }

    private CombatantEntry current(UUID actor) {
        CombatantEntry current = encounter.currentEntry().orElseThrow(() -> new IllegalStateException("No active encounter"));
        if (!current.id().equals(actor)) throw new IllegalStateException("It is another creature's turn");
        return current;
    }

    private CombatantEntry acting(UUID actor) {
        CombatantEntry current = current(actor);
        if (!current.canAct()) throw new IllegalStateException("Creature cannot act");
        return current;
    }

    private void requireIdle(long nowMillis) {
        tick(nowMillis);
        if (pending != null) throw new IllegalStateException("Waiting for reactions");
    }

    public void tick(long nowMillis) {
        if (!reactions.closeIfResolved(nowMillis)) return;
        Pending complete = pending;
        pending = null;
        complete.resume.accept(nowMillis);
    }

    public boolean answerReaction(long id, UUID reactor, Optional<Reaction> response, long nowMillis) {
        Objects.requireNonNull(response);
        tick(nowMillis);
        Kind kind = response.map(Reaction::kind).orElse(null);
        if (!reactions.canAnswer(id, reactor, kind, nowMillis)) return false;
        Response answer = response.map(value -> new Response(reactor, value)).orElse(null);
        if (answer != null && !pending.validate.test(answer)) return false;
        reactions.answer(id, reactor, kind, nowMillis);
        if (answer != null) pending.respond.accept(answer);
        tick(nowMillis);
        return true;
    }

    private Optional<Window> open(List<Offer> offers, long nowMillis, Predicate<Response> valid,
            Consumer<Response> responder, LongConsumer resume) {
        if (offers.isEmpty()) {
            resume.accept(nowMillis);
            return reactions.current();
        }
        Window window = reactions.open(offers, nowMillis);
        pending = new Pending(valid, responder, resume);
        return Optional.of(window);
    }

    private void event(EventType type, CombatantEntry actor, CombatantEntry target, String detail, int amount) {
        events.add(new Event(type, actor.id(), target.id(), detail, amount));
    }

    public Optional<Window> cast(UUID actor, CastRequest request, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry caster = acting(actor);
        Spell spell = SpellRegistry5e.require(request.spellId);
        boolean quickened = caster.quickenNextSpell && spell.castingTime() == CastingTime.ACTION;
        if (spell.castingTime() == CastingTime.REACTION || !caster.turn.canCast(spell, quickened)
                || quickened && caster.remaining(ClassFeature.SORCERY_POINTS) < 2) {
            throw new IllegalStateException("Spell cannot be cast with the current turn resources");
        }
        if (caster.has(Effect.RAGING)) throw new IllegalStateException("Cannot cast during Rage");
        Cast cast = prepareCast(caster, spell, request, false);
        caster.turn.cast(spell, quickened);
        if (quickened) {
            caster.spend(ClassFeature.SORCERY_POINTS, 2);
            caster.quickenNextSpell = false;
        }
        pay(caster, request);
        beginSpell(cast);
        return intercept(caster, spell.id(), cast.slot, nowMillis, time -> resolveCast(cast, time), ignored -> {});
    }

    private Optional<Window> intercept(CombatantEntry caster, String spellId, int slot, long nowMillis,
            LongConsumer onSuccess, LongConsumer onCountered) {
        List<Offer> offers = encounter.initiativeOrder().stream().filter(reactor ->
                reactor.team() != caster.team() && canCounter(reactor, caster)).map(
                reactor -> new Offer(reactor.id(), Kind.COUNTERSPELL)).toList();
        Map<UUID, Reaction> accepted = new HashMap<>();
        return open(offers, nowMillis, response -> canCounter(encounter.require(response.reactor), caster)
                && canPayReaction(encounter.require(response.reactor), "counterspell", response.reaction),
                response -> accepted.put(response.reactor, response.reaction), time -> {
                    List<Response> counters = offers.stream().filter(offer -> accepted.containsKey(offer.reactor()))
                            .map(offer -> new Response(offer.reactor(), accepted.get(offer.reactor()))).toList();
                    resolveCounters(caster, spellId, slot, counters, 0, time, onSuccess, onCountered);
                });
    }

    private void resolveCounters(CombatantEntry caster, String spellId, int slot, List<Response> counters,
            int index, long nowMillis, LongConsumer onSuccess, LongConsumer onCountered) {
        if (index == counters.size()) {
            onSuccess.accept(nowMillis);
            return;
        }
        Response response = counters.get(index);
        CombatantEntry reactor = encounter.require(response.reactor);
        LongConsumer next = time -> resolveCounters(caster, spellId, slot, counters, index + 1, time, onSuccess, onCountered);
        if (!canCounter(reactor, caster) || !canPayReaction(reactor, "counterspell", response.reaction)) {
            next.accept(nowMillis);
            return;
        }
        payReaction(reactor, "counterspell", response.reaction);
        intercept(reactor, "counterspell", response.reaction.slot, nowMillis, time -> {
            Ability ability = reactor.stats().spells().get("counterspell");
            RollMode mode = reactor.conditionEffects().abilityCheck(
                    Sense.NONE,
                    frightSourceVisible(reactor), false, false).mode();
            if (response.reaction.slot >= slot
                    || AbilityCheckEngine.check(reactor.stats().abilityModifier(ability), 10 + slot, mode, roller).succeeds()) {
                event(EventType.COUNTERED, reactor, caster, spellId, slot);
                onCountered.accept(time);
            } else next.accept(time);
        }, next);
    }

    /** Resolves a completed long cast or ritual. The caller tracks interruption and elapsed time outside initiative. */
    public void castOutsideCombat(UUID actor, CastRequest request, boolean ritual, int elapsedMinutes, long nowMillis) {
        requireIdle(nowMillis);
        if (encounter.active() || elapsedMinutes < 0) {
            throw new IllegalArgumentException("This casting entry point requires downtime");
        }
        CombatantEntry caster = encounter.require(actor);
        Spell spell = SpellRegistry5e.require(request.spellId);
        int minimum = ritual ? spell.ritualCastingMinutes() : spell.castingTime() == CastingTime.TEN_MINUTES ? 10 : 0;
        if (!caster.canAct() || elapsedMinutes < minimum || spell.castingTime() == CastingTime.REACTION
                || (ritual && !caster.stats().rituals().contains(spell.id()))) {
            throw new IllegalStateException("Caster cannot complete the requested casting");
        }
        CastRequest validated = ritual
                ? new CastRequest(request.spellId, spell.level(), request.pool, request.targets, request.aim, request.choice)
                : request;
        Cast cast = prepareCast(caster, spell, validated, ritual);
        if (minimum > 0) endConcentration(caster.id());
        if (!ritual) pay(caster, validated);
        beginSpell(cast);
        resolveCast(cast, nowMillis);
    }

    public void advanceDowntimeRounds(int rounds, long nowMillis) {
        requireIdle(nowMillis);
        if (encounter.active() || rounds < 0) throw new IllegalArgumentException("Invalid downtime interval");
        for (CombatantEntry entry : encounter.initiativeOrder()) {
            entry.turns = Math.addExact(entry.turns, rounds);
            expireEffects(entry, true);
            expireEffects(entry, false);
            concentration.current(entry.id()).filter(cast -> concentrationExpiry.get(cast.castId()) <= entry.turns)
                    .ifPresent(this::dropConcentration);
        }
    }

    public void restoreSpellSlots(UUID actor, boolean longRest, long nowMillis) {
        requireIdle(nowMillis);
        if (encounter.active()) throw new IllegalStateException("Resting requires downtime");
        var entry = encounter.require(actor);
        if (longRest) {
            entry.slots.longRest();
            entry.bestiary().ifPresent(BestiaryTurnState::recoverDailyResources);
        } else entry.slots.shortRest();
        entry.characterResources = entry.characterResources.rested(entry.stats().classes(), longRest);
    }

    private Cast prepareCast(CombatantEntry caster, Spell spell, CastRequest request, boolean ritual) {
        Ability ability = caster.stats().spells().get(spell.id());
        if (ability == null || !spell.canUseSlot(request.slot)
                || (!ritual && request.slot > 0 && caster.slots.available(request.pool, request.slot) == 0)) {
            throw new IllegalArgumentException("Unknown spell or unavailable slot");
        }
        validateChoice(spell, request.choice);
        List<CombatantEntry> targets = targets(caster, spell, request);
        Optional<Vec3> areaOrigin = request.aim.map(aim -> spell.rangeFeet() == 0 ? caster.cell().center() : aim);
        return new Cast(caster, spell, request.slot, ability, targets, request.choice, areaOrigin, ++castSequence);
    }

    private static void validateChoice(Spell spell, Optional<String> choice) {
        switch (spell.effect().kind()) {
            case RESISTANCE -> {
                if (choice.isEmpty() || !PROTECTION_TYPES.contains(DamageType5e.valueOf(choice.orElseThrow()))) {
                    throw new IllegalArgumentException("Choose an eligible energy type");
                }
            }
            case BLINDNESS -> {
                if (choice.isEmpty() || !(choice.get().equals("BLINDED") || choice.get().equals("DEAFENED"))) {
                    throw new IllegalArgumentException("Choose blindness or deafness");
                }
            }
            case RESTORE -> {
                if (choice.isEmpty() || !RESTORABLE.contains(ConditionType.valueOf(choice.orElseThrow()))) {
                    throw new IllegalArgumentException("Choose an eligible condition to remove");
                }
            }
            default -> {
                if (choice.isPresent()) throw new IllegalArgumentException("This spell does not accept a choice");
            }
        }
    }

    private List<CombatantEntry> targets(CombatantEntry caster, Spell spell, CastRequest request) {
        int cap = spell.targetsAt(request.slot, caster.stats().level());
        if (spell.targeting() == Targeting.SELF) {
            if (request.aim.isPresent() || (!request.targets.isEmpty() && !request.targets.equals(List.of(caster.id())))) {
                throw new IllegalArgumentException("A self-targeted spell applies to the caster");
            }
            return List.of(caster);
        }
        List<CombatantEntry> selected = request.targets.stream().map(encounter::require).toList();
        boolean multipleHits = projectiles(spell);
        if (selected.size() > cap || (multipleHits && selected.size() != cap)
                || (!multipleHits && selected.stream().distinct().count() != selected.size())) {
            throw new IllegalArgumentException("Invalid target count for " + spell.id());
        }
        if (spell.targeting() == Targeting.AREA) {
            Vec3 aim = request.aim.orElseThrow(() -> new IllegalArgumentException("Area spell requires an aim"));
            var shape = spell.area().orElseThrow();
            Vec3 source = caster.cell().center();
            Vec3 origin = spell.rangeFeet() == 0 ? source : aim;
            if (spell.rangeFeet() > 0 && (origin.minus(source).length() * 5 > spell.rangeFeet() + 1e-9
                    || !CoverRaycaster.clearRay(eye(caster), origin, world::blocksCover)
                    || world.blocksCover(cellAt(origin)))) {
                throw new IllegalArgumentException("Area origin is out of range or blocked");
            }
            if (spell.effect().kind() == EffectKind.TELEPORT) {
                if (!movementTerrain(caster, false).canOccupy(cellAt(origin))) throw new IllegalArgumentException("Teleport destination is occupied");
                if (!selected.isEmpty()) throw new IllegalArgumentException("Teleport requires only a destination");
                return List.of(caster);
            }
            if (spell.effect().kind() == EffectKind.ZONE) {
                if (!selected.isEmpty()) throw new IllegalArgumentException("A persistent area requires only a position");
                return List.of();
            }
            Vec3 direction = aim.minus(source);
            Area area = shape.place(origin, direction.length() < 1e-8 ? new Vec3(1, 0, 0) : direction,
                    caster.bounds(), spell.rangeFeet() != 0);
            List<CombatantEntry> affected = spell.id().equals("fireball")
                    ? AoETargetResolver.resolveSpread(area, encounter.initiativeOrder(),
                        entry -> entry.cell().center(), point -> world.blocksCover(cellAt(point)))
                    : AoETargetResolver.resolve(area, encounter.initiativeOrder(),
                        entry -> entry.cell().center(), (from, to) -> CoverRaycaster.clearRay(from, to, world::blocksCover));
            if (spell.effect().kind() == EffectKind.HEAL) {
                if (selected.isEmpty() || !affected.containsAll(selected)) throw new IllegalArgumentException("Choose creatures inside the area");
                selected.forEach(target -> validateSpellTarget(caster, target, spell));
                return selected;
            }
            if (!selected.isEmpty()) throw new IllegalArgumentException("Area damage determines its own targets");
            return affected.stream().filter(entry -> !entry.dead()).toList();
        }
        if (request.aim.isPresent() || selected.isEmpty()) throw new IllegalArgumentException("Choose creature targets");
        for (CombatantEntry entry : selected) {
            if (caster.cell().distanceFeet(entry.cell()) > spell.rangeFeet() || cover(caster, entry).blocksTargeting()) {
                throw new IllegalArgumentException("Target is out of range or behind total cover");
            }
            validateSpellTarget(caster, entry, spell);
        }
        if (spell.id().equals("acid_splash") && selected.size() == 2
                && selected.get(0).cell().distanceFeet(selected.get(1).cell()) > 5) {
            throw new IllegalArgumentException("Acid Splash targets must be within five feet of each other");
        }
        return selected;
    }

    private void validateSpellTarget(CombatantEntry caster, CombatantEntry target, Spell spell) {
        EffectKind kind = spell.effect().kind();
        if (kind == EffectKind.REVIVE) {
            if (target.death.state() != DeathSaveTracker.State.DEAD || target.diedAtRound < encounter.round() - 10L) {
                throw new IllegalArgumentException("Revivify needs a creature that died within one minute");
            }
            return;
        }
        if (target.dead()) throw new IllegalArgumentException("Target is dead");
        if (kind == EffectKind.DAMAGE || kind == EffectKind.MARK || kind == EffectKind.SPIRITUAL_WEAPON || kind == EffectKind.PARALYSIS || kind == EffectKind.BLINDNESS || kind == EffectKind.BANE) {
            validateHostileTarget(caster, target);
        }
        if (Set.of("sacred_flame", "magic_missile", "hold_person", "poison_spray", "healing_word", "mass_healing_word",
                "prayer_of_healing", "blindness_deafness", "bane").contains(spell.id()) && !canSee(caster, target)) {
            throw new IllegalArgumentException("The spell requires a visible target");
        }
        if ((kind == EffectKind.HEAL || kind == EffectKind.STABILIZE)
                && (target.stats().traits().contains(Trait.UNDEAD) || target.stats().traits().contains(Trait.CONSTRUCT))) {
            throw new IllegalArgumentException("Spell requires a living target");
        }
        if ((kind == EffectKind.AC_FORMULA || kind == EffectKind.RESISTANCE || kind == EffectKind.HASTE || kind == EffectKind.GUIDANCE) && !world.isWilling(caster, target)) {
            throw new IllegalArgumentException("Spell requires a willing target");
        }
        if ((kind == EffectKind.PARALYSIS && !target.stats().traits().contains(Trait.HUMANOID))
                || (kind == EffectKind.STABILIZE && target.hp.current() != 0)
                || (kind == EffectKind.AC_FORMULA && target.stats().armored())) {
            throw new IllegalArgumentException("Target does not meet the spell's requirements");
        }
    }

    private static boolean projectiles(Spell spell) {
        return spell.effect().cantripBeams() || spell.id().equals("scorching_ray") || spell.id().equals("magic_missile");
    }

    private static void reinforceWard(CombatantEntry caster, Spell spell, int level) {
        if (level < 1 || spell.school() != net.dnd5ecraft.rules.spells.SpellSchool.ABJURATION
                || !caster.subclasses.contains(net.dnd5ecraft.rules.character.SubclassType.ABJURATION)) return;
        int max = Math.max(1, 2 * caster.stats().classes().getOrDefault(ClassType.WIZARD, 0)
                + caster.stats().abilityModifier(Ability.INTELLIGENCE));
        int ward = Math.min(max, caster.hp.arcaneWard() + 2 * level);
        if (caster.remaining(ClassFeature.ARCANE_WARD) > 0) {
            caster.spend(ClassFeature.ARCANE_WARD, 1);
            ward = max;
        }
        caster.hp = new HitPoints(caster.hp.current(), caster.hp.maximum(), caster.hp.temporary(), ward, caster.hp.beast());
    }

    private static void pay(CombatantEntry caster, CastRequest request) {
        if (request.slot > 0 && !caster.slots.spend(request.pool, request.slot)) {
            throw new IllegalStateException("The requested spell slot is unavailable");
        }
        reinforceWard(caster, SpellRegistry5e.require(request.spellId), request.slot);
    }

    private void beginSpell(Cast cast) {
        if (cast.spell.concentration()) endConcentration(cast.caster.id());
        event(EventType.CAST, cast.caster, cast.caster, cast.spell.id(), cast.slot);
    }

    private boolean canCounter(CombatantEntry reactor, CombatantEntry caster) {
        return canHarm(reactor, caster) && reactor.cell().distanceFeet(caster.cell()) <= 60 && canSee(reactor, caster)
                && hasReactionSlot(reactor, "counterspell");
    }

    private boolean hasReactionSlot(CombatantEntry reactor, String spellId) {
        Spell spell = SpellRegistry5e.require(spellId);
        if (!reactor.canReact() || !reactor.stats().spells().containsKey(spellId) || !reactor.turn.canCast(spell)) return false;
        for (int slot = spell.level(); slot <= 9; slot++) {
            if (reactor.slots.available(Pool.REGULAR, slot) > 0 || reactor.slots.available(Pool.PACT, slot) > 0) return true;
        }
        return false;
    }

    private boolean canPayReaction(CombatantEntry reactor, String spellId, Reaction response) {
        Spell spell = SpellRegistry5e.require(spellId);
        return reactor.canReact() && reactor.stats().spells().containsKey(spellId) && reactor.turn.canCast(spell)
                && spell.canUseSlot(response.slot) && reactor.slots.available(response.pool, response.slot) > 0;
    }

    private void payReaction(CombatantEntry reactor, String spellId, Reaction response) {
        reactor.turn.cast(SpellRegistry5e.require(spellId));
        reactor.slots.spend(response.pool, response.slot);
        reinforceWard(reactor, SpellRegistry5e.require(spellId), response.slot);
        event(EventType.CAST, reactor, reactor, spellId, response.slot);
    }

    private void shield(CombatantEntry reactor, Reaction reaction) {
        payReaction(reactor, "shield", reaction);
        reactor.effects.add(new TimedEffect(Effect.SHIELD, reactor.id(), ++castSequence, reactor.id(),
                reactor.turns + 1, true, 0, null));
    }

    private void resolveCast(Cast cast, long nowMillis) {
        if (!cast.caster.canAct()) return;
        if (cast.spell.concentration()) {
            concentration.begin(cast.caster.id(), cast.spell, cast.id);
            concentrationExpiry.put(cast.id, cast.caster.turns + cast.spell.durationRounds());
        }
        cast.caster.stealth = -1;
        if (cast.spell.effect().kind() == EffectKind.ZONE) {
            Area area = zoneArea(cast, cast.areaOrigin.orElse(cast.caster.cell().center()));
            zones.put(cast.id, new SpellZone(cast, area, new HashMap<>()));
            refreshGuardians();
        } else if (cast.spell.effect().kind() == EffectKind.SPIRITUAL_WEAPON) {
            var target = cast.targets.get(0);
            spiritualWeapons.put(cast.caster.id(), new SpiritualWeapon(cast, target.cell()));
            addEffect(cast, cast.caster, Effect.SPIRITUAL_WEAPON, cast.caster, true, 0, null);
            resolveSpellAttack(cast, 0, nowMillis);
        } else if (cast.spell.id().equals("magic_missile")) {
            magicMissile(cast, nowMillis);
        } else if (cast.spell.resolution() == Resolution.ATTACK) {
            resolveSpellAttack(cast, 0, nowMillis);
        } else {
            int amount = rollSpellAmount(cast, false);
            for (CombatantEntry target : cast.targets) {
                boolean saved = cast.spell.save().map(ability -> spellSave(cast, target, ability)).orElse(false);
                if (!saved || cast.spell.resolution() == Resolution.SAVE_HALF) {
                    applySpell(cast, target, saved ? amount / 2 : amount, saved, false, false);
                }
            }
        }
    }

    private void magicMissile(Cast cast, long nowMillis) {
        List<Offer> offers = encounter.active() ? cast.targets.stream().distinct()
                .filter(target -> !target.has(Effect.SHIELD) && hasReactionSlot(target, "shield"))
                .map(target -> new Offer(target.id(), Kind.SHIELD)).toList() : List.of();
        open(offers, nowMillis, response -> canPayReaction(encounter.require(response.reactor), "shield", response.reaction),
                response -> shield(encounter.require(response.reactor), response.reaction), ignored -> {
                    int perDart = rollSpellAmount(cast, false);
                    Map<CombatantEntry, Integer> totals = new LinkedHashMap<>();
                    cast.targets.forEach(target -> totals.merge(target, perDart, Math::addExact));
                    totals.forEach((target, amount) -> {
                        if (!target.has(Effect.SHIELD)) applySpell(cast, target, amount, false, false, false);
                    });
                });
    }

    private void resolveSpellAttack(Cast cast, int index, long nowMillis) {
        if (index == cast.targets.size()) return;
        CombatantEntry target = cast.targets.get(index);
        if (target.dead()) {
            resolveSpellAttack(cast, index + 1, nowMillis);
            return;
        }
        boolean metalAdvantage = cast.spell.id().equals("shocking_grasp") && target.stats().traits().contains(Trait.METAL_ARMOR);
        Hit hit = rollAttack(cast.caster, target, cast.caster.stats().proficiencyBonus() + castingModifier(cast),
                cast.spell.rangeFeet() > 5, metalAdvantage, false);
        resolveHit(hit, encounter.active(), nowMillis, (outcome, dodge, reduction) -> {
            if (outcome != AttackOutcome.MISS) {
                boolean critical = outcome == AttackOutcome.CRITICAL;
                applySpell(cast, target, Math.max(0, rollSpellAmount(cast, critical) - reduction), false, critical, dodge);
            }
        }, time -> resolveSpellAttack(cast, index + 1, time));
    }

    private int rollSpellAmount(Cast cast, boolean critical) {
        Spell.Effect data = cast.spell.effect();
        int count = data.diceAt(cast.spell.level(), cast.slot, cast.caster.stats().level());
        int flat = data.flat() + (data.addCastingModifier() ? castingModifier(cast) : 0);
        if (cast.spell.id().equals("false_life")) flat += 5 * (cast.slot - cast.spell.level());
        int sides = data.sides();
        if (cast.spell.id().equals("toll_the_dead") && !cast.targets.isEmpty()
                && cast.targets.get(0).hp.current() < cast.targets.get(0).hp.maximum()) sides = 12;
        if (cast.spell.id().equals("spiritual_weapon")) count += (cast.slot - cast.spell.level()) / 2;
        return Math.max(0, new Dice(count, sides).roll(critical, roller) + flat);
    }

    private int castingModifier(Cast cast) { return cast.caster.stats().abilityModifier(cast.ability); }
    private int saveDc(Cast cast) { return 8 + cast.caster.stats().proficiencyBonus() + castingModifier(cast); }

    private boolean spellSave(Cast cast, CombatantEntry target, Ability ability) {
        int bonus = ability == Ability.DEXTERITY && cast.spell.effect().rider() != Spell.Rider.NO_COVER_BONUS
                ? CoverRaycaster.cover(cast.areaOrigin.orElseGet(() -> eye(cast.caster)), target.bounds(),
                    world::blocksCover).bonus() : 0;
        boolean disadvantage = cast.spell.id().equals("shatter") && target.stats().traits().contains(Trait.INORGANIC);
        return save(target, ability, saveDc(cast), bonus, disadvantage, true);
    }

    private boolean save(CombatantEntry target, Ability ability, int dc, int bonus, boolean disadvantage, boolean magic) {
        var state = target.bestiary().orElse(null);
        boolean advantages = magic && (state != null && state.has(Feature.MAGIC_RESISTANCE)
                || target.race == RaceType.GNOME && Set.of(Ability.INTELLIGENCE, Ability.WISDOM, Ability.CHARISMA).contains(ability))
                || ability == Ability.DEXTERITY && (target.has(Effect.HASTE) || target.dodging && target.canAct() && target.speedFeet() > 0)
                || ability == Ability.STRENGTH && target.has(Effect.RAGING);
        var updated = target.conditionEffects().savingThrow(ability, advantages, disadvantage);
        boolean succeeds = !updated.automaticFailure()
                && abilityOutcome(target, d20(target, updated.mode()) + target.stats().saveModifier(ability) + bonus + luck(target) + auraOfProtection(target), dc);
        if (!succeeds && state != null && world.useLegendaryResistance(target) && state.useLegendaryResistance()) {
            event(EventType.BESTIARY, target, target, "legendary_resistance", state.snapshot().legendaryResistances());
            return true;
        }
        return succeeds;
    }

    private int auraOfProtection(CombatantEntry target) {
        int bonus = 0;
        for (CombatantEntry ally : encounter.initiativeOrder()) {
            int paladin = ally.stats().classes().getOrDefault(ClassType.PALADIN, 0);
            if (ally.team() != target.team() || ally.hp.current() == 0 || paladin < 6
                    || ally.cell().distanceFeet(target.cell()) > (paladin >= 18 ? 30 : 10)) continue;
            bonus = Math.max(bonus, Math.max(1, ally.stats().abilityModifier(Ability.CHARISMA)));
        }
        return bonus;
    }

    private boolean abilityOutcome(CombatantEntry user, int total, int dc) {
        if (total >= dc) return true;
        if (user.inspirationDie == 0) return false;
        int sides = user.inspirationDie;
        user.inspirationDie = 0;
        return total + AbilityCheckEngine.rollDie(sides, roller) >= dc;
    }

    private int luck(CombatantEntry actor) {
        return (actor.has(Effect.BLESS) ? AbilityCheckEngine.rollDie(4, roller) : 0)
                - (actor.has(Effect.BANE) ? AbilityCheckEngine.rollDie(4, roller) : 0);
    }

    private void applySpell(Cast cast, CombatantEntry target, int amount, boolean saved, boolean critical, boolean dodge) {
        if (target.dead() && cast.spell.effect().kind() != EffectKind.REVIVE) return;
        switch (cast.spell.effect().kind()) {
            case DAMAGE, SPIRITUAL_WEAPON -> {
                dealDamage(cast.caster, target, Map.of(cast.spell.effect().damageType().orElseThrow(), amount), critical, dodge);
                if (!target.dead() && !saved) applyRider(cast, target);
            }
            case HEAL -> heal(cast.caster, target, amount);
            case TEMP_HP -> {
                if (amount >= target.hp.temporary()) {
                    target.hp = target.hp.grantTemporary(amount);
                    temporaryProvider.put(target.id(), cast.id);
                    addEffect(cast, target, Effect.FALSE_LIFE, cast.caster, true, 0, null);
                }
            }
            case STABILIZE -> target.death = target.death.stabilize();
            case AC_FORMULA -> addEffect(cast, target, Effect.ARMOR, cast.caster, true, 0, null);
            case BLESS -> addEffect(cast, target, Effect.BLESS, cast.caster, true, 0, null);
            case BANE -> addEffect(cast, target, Effect.BANE, cast.caster, true, 0, null);
            case PARALYSIS -> {
                addEffect(cast, target, Effect.PARALYZED, cast.caster, true, saveDc(cast), null);
                endConcentration(target.id());
                releaseEndedGrapples();
            }
            case BLINDNESS -> {
                Effect status = cast.choice.orElseThrow().equals("BLINDED") ? Effect.BLINDED : Effect.DEAFENED;
                addEffect(cast, target, status, cast.caster, true, saveDc(cast), null);
            }
            case RESTORE -> restore(target, ConditionType.valueOf(cast.choice.orElseThrow()));
            case DETECT_MAGIC -> addEffect(cast, target, Effect.DETECT_MAGIC, cast.caster, true, 0, null);
            case GUIDANCE -> addEffect(cast, target, Effect.GUIDANCE, cast.caster, true, 0, null);
            case MARK -> addEffect(cast, target, Effect.MARKED, cast.caster, true, 0, null);
            case REVEAL -> addEffect(cast, target, Effect.REVEALED, cast.caster, true, 0, null);
            case HASTE -> {
                addEffect(cast, target, Effect.HASTE, cast.caster, true, 0, null);
                if (target == cast.caster) target.hasteAction = true;
            }
            case TELEPORT -> {
                var cell = cellAt(cast.areaOrigin.orElseThrow());
                if (movementTerrain(cast.caster, false).canOccupy(cell)) relocate(cast.caster, cell);
            }
            case REVIVE -> {
                target.death = DeathSaveTracker.CONSCIOUS;
                target.hp = new HitPoints(1, Math.max(1, target.hp.maximum()), 0, 0);
                target.diedAtRound = Long.MIN_VALUE;
            }
            case ZONE -> throw new IllegalStateException("Persistent areas resolve on entry and turn start");
            case RESISTANCE -> addEffect(cast, target, Effect.RESISTANCE, cast.caster, true, 0,
                    DamageType5e.valueOf(cast.choice.orElseThrow()));
            case ARMOR_BONUS, COUNTERSPELL -> throw new IllegalStateException("Reaction spell requires its trigger");
        }
    }

    private void applyRider(Cast cast, CombatantEntry target) {
        switch (cast.spell.effect().rider()) {
            case NO_HEALING -> {
                addEffect(cast, target, Effect.NO_HEALING, cast.caster, true, 0, null);
                if (target.stats().traits().contains(Trait.UNDEAD)) {
                    addEffect(cast, target, Effect.CHILLED_UNDEAD, cast.caster, false, 0, null);
                }
            }
            case SLOW_TEN_FEET -> addEffect(cast, target, Effect.SLOWED, cast.caster, true, 0, null);
            case NO_REACTIONS -> addEffect(cast, target, Effect.NO_REACTIONS, target, true, 0, null);
            case GUIDING_LIGHT -> addEffect(cast, target, Effect.GUIDING_LIGHT, cast.caster, false, 0, null);
            case PUSH_TEN_FEET -> push(cast.caster, target, 2);
            case MOCKERY -> addEffect(cast, target, Effect.MOCKED, cast.caster, false, 0, null);
            case NONE, NO_COVER_BONUS -> { }
        }
    }

    private Area zoneArea(Cast cast, Vec3 origin) {
        var source = cast.caster.cell().center();
        var facing = origin.minus(source);
        var template = cast.spell.area().orElseGet(() -> new Template(AoEGeometry.EMANATION, 15));
        return template.place(origin, facing.length() < 1e-8 ? new Vec3(1, 0, 0) : facing, cast.caster.bounds(), true);
    }

    private Area area(SpellZone zone) {
        return zone.cast.spell.id().equals("spirit_guardians")
                ? zoneArea(zone.cast, zone.cast.caster.cell().center()) : zone.area;
    }

    private void refreshGuardians() {
        for (CombatantEntry entry : encounter.initiativeOrder()) {
            entry.guardiansSlow = zones.values().stream().anyMatch(zone -> zone.cast.spell.id().equals("spirit_guardians")
                    && zone.cast.caster.team() != entry.team() && area(zone).contains(entry.cell().center()));
            entry.effects.removeIf(effect -> effect.effect() == Effect.WEBBED
                    && (!zones.containsKey(effect.castId()) || !area(zones.get(effect.castId())).contains(entry.cell().center())));
        }
    }

    private void triggerZones(CombatantEntry entry, Cell previous) {
        if (entry.dead()) return;
        for (SpellZone zone : List.copyOf(zones.values())) {
            boolean guardians = zone.cast.spell.id().equals("spirit_guardians");
            Area area = area(zone);
            if (guardians && zone.cast.caster.team() == entry.team()) continue;
            if (!area.contains(entry.cell().center()) || previous != null && area.contains(previous.center())
                    || zone.lastAffected.getOrDefault(entry.id(), -1L) == turnSequence) continue;
            zone.lastAffected.put(entry.id(), turnSequence);
            if (zone.cast.spell.id().equals("web")) {
                if (!save(entry, Ability.DEXTERITY, saveDc(zone.cast), 0, false, true)
                        && entry.effects.stream().noneMatch(effect -> effect.effect() == Effect.WEBBED && effect.castId() == zone.cast.id)) {
                    addEffect(zone.cast, entry, Effect.WEBBED, zone.cast.caster, true, saveDc(zone.cast), null);
                }
            } else {
                int damage = rollSpellAmount(zone.cast, false);
                if (save(entry, guardians ? Ability.WISDOM : Ability.CONSTITUTION, saveDc(zone.cast), 0,
                        !guardians && entry.stats().traits().contains(Trait.SHAPECHANGER), true)) damage /= 2;
                dealDamage(zone.cast.caster, entry, Map.of(DamageType5e.RADIANT, damage), false, false);
            }
        }
    }

    private void retargetMark(CombatantEntry caster, CombatantEntry target) {
        var active = concentration.current(caster.id()).filter(spell -> spell.spellId().equals("hunters_mark"))
                .orElseThrow(() -> new IllegalStateException("No Hunter's Mark is active"));
        if (target == null || target.dead() || !canHarm(caster, target) || !canSee(caster, target)
                || caster.cell().distanceFeet(target.cell()) > 90 || cover(caster, target).blocksTargeting()) {
            throw new IllegalArgumentException("Choose a visible hostile target within 90 feet");
        }
        var old = encounter.initiativeOrder().stream().filter(entry -> entry.effects.stream().anyMatch(effect ->
                effect.effect() == Effect.MARKED && effect.castId() == active.castId())).findFirst();
        if (old.isPresent() && !old.orElseThrow().dead()) throw new IllegalStateException("The marked target is still alive");
        spend(caster, Resource.BONUS_ACTION);
        old.ifPresent(entry -> entry.effects.removeIf(effect -> effect.castId() == active.castId()));
        target.effects.add(new TimedEffect(Effect.MARKED, caster.id(), active.castId(), caster.id(),
                concentrationExpiry.get(active.castId()), true, 0, null));
    }

    private void moveMoonbeam(CombatantEntry caster, Cell destination) {
        if (destination == null || world.blocksCover(destination)) throw new IllegalArgumentException("Select an open destination");
        var beam = zones.values().stream().filter(zone -> zone.cast.caster == caster && zone.cast.spell.id().equals("moonbeam"))
                .findFirst().orElseThrow(() -> new IllegalStateException("No moonbeam is active"));
        if (beam.area.origin().minus(destination.center()).length() > 12) throw new IllegalArgumentException("Moonbeam can move up to 60 feet");
        spend(caster, Resource.ACTION);
        zones.put(beam.cast.id, new SpellZone(beam.cast, zoneArea(beam.cast, destination.center()), beam.lastAffected));
    }

    private void moveSpiritualWeapon(CombatantEntry caster, CombatantEntry target, long time) {
        var weapon = spiritualWeapons.get(caster.id());
        if (weapon == null || target == null || target.dead() || weapon.cell.distanceFeet(target.cell()) > 25
                || caster.effects.stream().noneMatch(effect -> effect.castId() == weapon.cast.id)) {
            throw new IllegalStateException("Spiritual Weapon or target is unavailable");
        }
        validateHostileTarget(caster, target);
        spend(caster, Resource.BONUS_ACTION);
        spiritualWeapons.put(caster.id(), new SpiritualWeapon(weapon.cast, target.cell()));
        var attack = new Cast(caster, weapon.cast.spell, weapon.cast.slot, weapon.cast.ability,
                List.of(target), Optional.empty(), Optional.empty(), weapon.cast.id);
        resolveSpellAttack(attack, 0, time);
    }

    private void push(CombatantEntry caster, CombatantEntry target, int squares) {
        int dx = Integer.compare(target.cell().x(), caster.cell().x());
        int dz = Integer.compare(target.cell().z(), caster.cell().z());
        if (dx == 0 && dz == 0) return;
        Terrain terrain = movementTerrain(target, false);
        for (int steps = 0; steps < squares; steps++) {
            Cell cell = target.cell();
            Cell to = new Cell(cell.x() + dx, cell.y(), cell.z() + dz);
            if (!terrain.neighbors(cell).contains(to) || !terrain.canStep(cell, to)) break;
            relocate(target, to);
        }
    }

    private void addEffect(Cast cast, CombatantEntry target, Effect effect, CombatantEntry expiryActor,
            boolean atStart, int dc, DamageType5e resistance) {
        target.effects.add(new TimedEffect(effect, cast.caster.id(), cast.id, expiryActor.id(),
                expiryActor.turns + cast.spell.durationRounds(), atStart, dc, resistance));
    }

    private void expireEffects(CombatantEntry actor, boolean atStart) {
        for (CombatantEntry entry : encounter.initiativeOrder()) {
            entry.effects.removeIf(effect -> {
                if (!effect.expiryActor().equals(actor.id()) || effect.atStart() != atStart
                        || effect.expiryTurn() > actor.turns) return false;
                if (effect.effect() == Effect.FALSE_LIFE && temporaryProvider.remove(entry.id(), effect.castId())) {
                    entry.hp = new HitPoints(entry.hp.current(), entry.hp.maximum(), 0, entry.hp.arcaneWard(), entry.hp.beast());
                }
                return true;
            });
        }
    }

    private void repeatSaves(CombatantEntry actor) {
        if (actor.dead()) return;
        actor.effects.removeIf(effect -> effect.effect() != Effect.WEBBED && effect.saveDc() != 0 && save(actor,
                effect.effect() == Effect.PARALYZED ? Ability.WISDOM : Ability.CONSTITUTION, effect.saveDc(), 0, false,
                effect.effect() != Effect.GHOUL_PARALYSIS));
    }

    private static void restore(CombatantEntry target, ConditionType type) {
        target.conditions.removeIf(condition -> condition.type() == type);
        target.effects.removeIf(effect -> effect.effect().name().equals(type.name())
                || type == ConditionType.PARALYZED && effect.effect() == Effect.GHOUL_PARALYSIS);
    }

    public List<UUID> detectedMagic(UUID actor) {
        CombatantEntry caster = encounter.require(actor);
        if (!caster.has(Effect.DETECT_MAGIC)) return List.of();
        return encounter.initiativeOrder().stream().filter(entry -> entry != caster && !entry.dead()
                && (!entry.effects.isEmpty() || entry.stats().traits().contains(Trait.MAGICAL))
                && caster.cell().distanceFeet(entry.cell()) <= 30
                && CoverRaycaster.clearRay(eye(caster), eye(entry), world::blocksMagic)).map(CombatantEntry::id).toList();
    }

    public void endConcentration(UUID caster) {
        concentration.current(caster).ifPresent(this::dropConcentration);
    }

    private void dropConcentration(ConcentrationManager.Active cast) {
        if (!concentration.end(cast)) return;
        removeConcentrationEffects(cast);
    }

    private void removeConcentrationEffects(ConcentrationManager.Active cast) {
        concentrationExpiry.remove(cast.castId());
        zones.remove(cast.castId());
        for (CombatantEntry entry : encounter.initiativeOrder()) {
            boolean hastened = entry.effects.stream().anyMatch(effect -> effect.castId() == cast.castId() && effect.effect() == Effect.HASTE);
            entry.effects.removeIf(effect -> effect.castId() == cast.castId());
            if (hastened) {
                entry.hasteAction = false;
                entry.effects.add(new TimedEffect(Effect.LETHARGY, entry.id(), ++castSequence, entry.id(), entry.turns + 1, false, 0, null));
                if (!entry.id().equals(cast.caster())) endConcentration(entry.id());
            }
        }
        refreshGuardians();
        CombatantEntry caster = encounter.require(cast.caster());
        event(EventType.CONCENTRATION_ENDED, caster, caster, cast.spellId(), 0);
    }

    public void applyCondition(UUID actor, ConditionInstance condition) {
        CombatantEntry target = encounter.require(actor);
        if (target.dead() || (condition.type() == ConditionType.POISONED
                && !target.conditionEffects().canAcquirePoisonOrDisease())) return;
        target.conditions.add(condition);
        if (!target.canAct()) {
            endConcentration(actor);
            releaseEndedGrapples();
        }
    }

    public DamagePipeline.Result damage(UUID source, UUID target, Map<DamageType5e, Integer> damage, boolean critical) {
        return dealDamage(encounter.require(source), encounter.require(target), damage, critical, false);
    }

    private DamagePipeline.Result dealDamage(CombatantEntry source, CombatantEntry target,
            Map<DamageType5e, Integer> damage, boolean critical, boolean dodge) {
        HitPoints before = target.hp;
        DamagePipeline.Result result = DamagePipeline.apply(dodge ? halveDamage(damage) : damage, target.defenses(), before,
                target.remaining(ClassFeature.RELENTLESS_ENDURANCE) > 0);
        if (target.dead()) return DamagePipeline.apply(Map.of(), target.defenses(), before);
        target.hp = result.remaining();
        if (result.enduranceUsed()) target.spend(ClassFeature.RELENTLESS_ENDURANCE, 1);
        target.bestiary().ifPresent(state -> state.onDamage(result.adjustedDamage()));
        boolean fortitude = target.hp.current() == 0 && before.current() > 0 && !critical
                && result.adjustedDamage().getOrDefault(DamageType5e.RADIANT, 0) == 0
                && target.bestiary().filter(state -> state.preset() == BestiaryPreset.ZOMBIE).isPresent()
                && save(target, Ability.CONSTITUTION, 5 + result.damageTaken(), 0, false, false);
        if (fortitude) target.hp = target.hp.heal(1);
        else if (before.maximum() > 0) target.death = target.death.afterDamage(before, result, critical);
        else if (result.damageTaken() > 0) target.death = DeathSaveTracker.DEAD;
        if (target.death.state() == DeathSaveTracker.State.DEAD
                && target.bestiary().filter(state -> state.preset() == BestiaryPreset.TROLL).isPresent()) {
            target.death = DeathSaveTracker.DYING;
        }
        if (target.dead()) target.diedAtRound = encounter.round();
        if (!target.canAct()) {
            endConcentration(target.id());
            releaseEndedGrapples();
        } else if (result.damageTaken() > 0 && concentration.current(target.id()).isPresent()) {
            var save = target.conditionEffects().savingThrow(Ability.CONSTITUTION, target.feat(FeatType.WAR_CASTER), false);
            concentration.onDamage(target.id(), result.damageTaken(), false,
                    target.stats().saveModifier(Ability.CONSTITUTION) + luck(target) + auraOfProtection(target), save.mode(), roller)
                    .ifPresent(this::removeConcentrationEffects);
        }
        event(EventType.DAMAGE, source, target, "", result.damageTaken());
        return result;
    }

    private static Map<DamageType5e, Integer> halveDamage(Map<DamageType5e, Integer> damage) {
        Map<DamageType5e, Integer> result = new EnumMap<>(DamageType5e.class);
        int oddRemainder = 0;
        for (DamageType5e type : DamageType5e.values()) {
            Integer value = damage.get(type);
            if (value == null) continue;
            if (value < 0) throw new IllegalArgumentException("Damage cannot be negative");
            result.put(type, value / 2 + ((value % 2 + oddRemainder) / 2));
            oddRemainder = (value % 2 + oddRemainder) % 2;
        }
        return result;
    }

    public void useHealingItem(UUID actor, int amount, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry recipient = acting(actor);
        if (amount < 1) throw new IllegalArgumentException("Healing must be positive");
        if (!recipient.turn.spend(Resource.ACTION)) throw new IllegalStateException("Action already spent");
        heal(recipient, recipient, amount);
    }

    public void useBreath(UUID actor, Vec3 destination, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry attacker = acting(actor);
        BestiaryTurnState state = attacker.bestiary().orElseThrow(() -> new IllegalStateException("No breath attack"));
        if (!state.canUseBreath() || !attacker.turn.canSpend(Resource.ACTION)) {
            throw new IllegalStateException("Breath attack is unavailable");
        }
        BestiaryPreset.Breath breath = state.preset().breath();
        Vec3 from = attacker.cell().center();
        Area area = new Template(AoEGeometry.CONE, breath.lengthFeet())
                .place(from, destination.minus(from), attacker.bounds(), false);
        attacker.turn.spend(Resource.ACTION);
        state.spendBreath();
        event(EventType.BESTIARY, attacker, attacker, "fire_breath", breath.saveDc());
        resolveBestiaryFire(attacker, area, breath.saveDc(), breath.dice());
    }

    private void resolveLairActions() {
        for (CombatantEntry owner : encounter.initiativeOrder()) {
            if (!owner.canAct() || owner.bestiary().filter(BestiaryTurnState::isInLair).isEmpty()) continue;
            var target = encounter.initiativeOrder().stream()
                    .filter(foe -> !foe.dead() && foe.team() != owner.team() && owner.cell().distanceFeet(foe.cell()) <= 120)
                    .filter(foe -> !cover(owner, foe).blocksTargeting())
                    .min(java.util.Comparator.comparingInt(foe -> owner.cell().distanceFeet(foe.cell())));
            target.ifPresent(foe -> {
                Vec3 origin = foe.cell().center();
                Area magma = new Template(AoEGeometry.CYLINDER, 5, 20).place(origin, null, owner.bounds(), true);
                event(EventType.BESTIARY, owner, foe, "lair_magma", 20);
                resolveBestiaryFire(owner, magma, 15, new Dice(6, 6));
            });
        }
    }

    private void resolveBestiaryFire(CombatantEntry attacker, Area area, int dc, Dice dice) {
        var targets = AoETargetResolver.resolve(area, encounter.initiativeOrder(), target -> target.cell().center(),
                (from, to) -> CoverRaycaster.clearRay(from, to, world::blocksCover));
        int rolled = dice.roll(false, roller);
        for (CombatantEntry target : targets) {
            if (target == attacker || target.dead()) continue;
            int coverBonus = CoverRaycaster.cover(area.origin(), target.bounds(), world::blocksCover).bonus();
            int amount = save(target, Ability.DEXTERITY, dc, coverBonus, false, false) ? rolled / 2 : rolled;
            dealDamage(attacker, target, Map.of(DamageType5e.FIRE, amount), false, false);
        }
    }

    public void healFully(UUID id) {
        var entry = encounter.require(id);
        if (!entry.dead()) heal(entry, entry, entry.hitPoints().maximum());
    }

    public void grantInspiration(UUID id) {
        var entry = encounter.require(id);
        entry.characterResources = entry.characterResources.withInspiration(true);
    }

    private void heal(CombatantEntry caster, CombatantEntry target, int amount) {
        if (target.dead() || target.has(Effect.NO_HEALING)) return;
        int previous = target.hp.current();
        target.hp = target.hp.heal(amount);
        target.death = target.death.afterHealing(target.hp.current());
        event(EventType.HEAL, caster, target, "", target.hp.current() - previous);
    }
}
