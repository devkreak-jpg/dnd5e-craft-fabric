package net.dnd5ecraft.combat;

import java.util.ArrayList;
import java.util.EnumMap;
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
import net.dnd5ecraft.rules.ability.TabletopRolls;
import net.dnd5ecraft.rules.ability.TabletopRolls.Outcome;
import net.dnd5ecraft.rules.ability.PassiveSenseTracker;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.rules.character.FeatType;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.conditions.ConditionInstance;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.DamagePipeline;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Dice;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.spells.AoEGeometry;
import net.dnd5ecraft.rules.spells.AoEGeometry.Area;
import net.dnd5ecraft.rules.spells.AoEGeometry.Template;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.dnd5ecraft.rules.spells.AoETargetResolver;
import net.dnd5ecraft.rules.spells.ConcentrationManager;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;

/** Synchronous rules coordinator. World adapters call it from the authoritative game thread. */
public final class CombatManager {
    public interface Battlefield extends Terrain {
        boolean blocksCover(Cell cell);
        Light lightAt(Cell cell);

        default boolean canOccupy(CombatantEntry mover, Cell cell) { return canOccupy(cell); }
        default boolean blocksSight(Cell cell) { return blocksCover(cell); }
        default boolean blocksMagic(Cell cell) { return blocksCover(cell); }

        default boolean canUseWeapon(CombatantEntry actor, CombatantEntry.Attack attack) { return true; }
        default void weaponHit(CombatantEntry actor, CombatantEntry target, CombatantEntry.Attack attack) {}
        default boolean canSwap(CombatantEntry mover, CombatantEntry other) { return canOccupy(mover, other.cell()); }
        default Cell echoCell(CombatantEntry actor) { return null; }
        default void echoSwing(CombatantEntry actor) {}
        default boolean nettable(CombatantEntry target) { return true; }
        default void spellVisual(String id, CombatantEntry caster, CombatantEntry target) {}
        default void zoneVisual(String id, Area area) {}

        default boolean manualDeathSaves(CombatantEntry target) { return false; }

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

    public enum EventType { TURN, MOVED, HIT, MISS, DAMAGE, HEAL, CAST, COUNTERED, CONCENTRATION_ENDED, DEATH_SAVE, BESTIARY, CAMPAIGN, DEFEATED, SAVE }

    public record Event(EventType type, UUID actor, UUID target, String detail, int value, DamageType5e damageType, boolean critical) {
        public Event(EventType type, UUID actor, UUID target, String detail, int value) {
            this(type, actor, target, detail, value, null, false);
        }
    }

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

    record Response(UUID reactor, Reaction reaction) {}
    private record Pending(Predicate<Response> validate, Consumer<Response> respond, LongConsumer resume) {}

    private final CombatEncounterState encounter;
    private final Battlefield world;
    private final IntUnaryOperator roller;
    private final CombatSpellZoneResolver spells;
    private final CombatManeuverResolver maneuvers;
    private final CampaignCombat campaign;
    private final ReactionWindowManager reactions;
    private final List<Event> events = new ArrayList<>();
    private Pending pending;
    private long turnSequence;
    private UUID pendingDeathSave;
    private long effectSequence;
    private int lairActionRound;

    public CombatManager(CombatEncounterState encounter, Battlefield world, IntUnaryOperator roller) {
        this(encounter, world, roller, ReactionWindowManager.WINDOW_MILLIS);
    }

    public CombatManager(CombatEncounterState encounter, Battlefield world, IntUnaryOperator roller, int reactionWindowMs) {
        this.reactions = new ReactionWindowManager(reactionWindowMs);
        this.encounter = Objects.requireNonNull(encounter);
        this.world = Objects.requireNonNull(world);
        this.roller = Objects.requireNonNull(roller);
        this.spells = new CombatSpellZoneResolver(this, encounter, roller, world);
        this.maneuvers = new CombatManeuverResolver(this, encounter, roller);
        this.campaign = new CampaignCombat(this, encounter, roller);
    }

    CombatSpellZoneResolver spells() { return spells; }
    CombatManeuverResolver maneuvers() { return maneuvers; }
    boolean canUseWeapon(CombatantEntry actor, CombatantEntry.Attack attack) { return world.canUseWeapon(actor, attack); }
    void weaponHit(CombatantEntry actor, CombatantEntry defender, CombatantEntry.Attack attack) { world.weaponHit(actor, defender, attack); }
    boolean nettable(CombatantEntry target) { return world.nettable(target); }
    CampaignCombat campaign() { return campaign; }
    long turnSequence() { return turnSequence; }
    long nextEffectId() { return ++effectSequence; }

    public CombatEncounterState.Snapshot snapshot() { return encounter.snapshot(); }
    public Optional<Window> reactionWindow() { return reactions.current(); }
    public Optional<ConcentrationManager.Active> concentration(UUID caster) { return spells.concentration(caster); }

    public List<Event> drainEvents() {
        List<Event> drained = List.copyOf(events);
        events.clear();
        return drained;
    }

    public void start() { start(Set.of()); }

    public void start(Set<UUID> surprised) {
        lairActionRound = 0;
        campaign.atInitiative();
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
        maneuvers.removeCombatant(id);
        spells.removeCombatant(id);
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
        if (pendingDeathSave().filter(actor::equals).isPresent()) resolveDeathSave(actor, RollMode.NORMAL);
        spells.repeatSaves(current);
        spells.expireEffects(current, false);
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
            maneuvers.strike(attacker, finished, attack, true, nowMillis, time -> legendaryAfterTurn(finished, next, time));
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
        maneuvers.beginTurn(current);
        CampaignCombat.beginTurn(current);
        spells.beginTurn(current);
        current.hasteAction = current.has(Effect.HASTE);
        if (!current.dead()) current.bestiary().ifPresent(state -> {
            int recovery = state.beginTurn(current.hp.current(), world.suppressesRegeneration(current), roller);
            if (recovery > 0) heal(current, current, recovery);
            else if (state.preset() == BestiaryPreset.TROLL && current.hp.current() == 0) current.death = DeathSaveTracker.DEAD;
        });
        spells.triggerZones(current, null);
        if (!current.canAct()) endConcentration(current.id());
        pendingDeathSave = null;
        if (!current.dead() && current.death.state() == DeathSaveTracker.State.DYING) {
            pendingDeathSave = current.id();
            if (!world.manualDeathSaves(current)) resolveDeathSave(current.id(), RollMode.NORMAL);
        }
        event(EventType.TURN, current, current, "", encounter.round());
    }

    public Optional<UUID> pendingDeathSave() {
        return encounter.currentEntry().filter(entry -> entry.id().equals(pendingDeathSave)
                && !entry.dead() && entry.death.state() == DeathSaveTracker.State.DYING).map(CombatantEntry::id);
    }

    public Outcome resolveDeathSave(UUID actor, RollMode requested) {
        if (pendingDeathSave().filter(actor::equals).isEmpty()) throw new IllegalStateException("No death save is due");
        var current = encounter.require(actor);
        RollMode mode = current.conditionEffects().savingThrow(Ability.CONSTITUTION,
                requested == RollMode.ADVANTAGE, requested == RollMode.DISADVANTAGE).mode();
        var roll = rolledD20(current, mode);
        int natural = roll.kept();
        int modifier = luck(current) + auraOfProtection(current);
        var result = current.death.roll(natural, modifier);
        current.death = result.tracker();
        pendingDeathSave = null;
        if (current.dead()) current.diedAtRound = encounter.round();
        if (result.hitPointsRestored() > 0) current.hp = current.hp.heal(result.hitPointsRestored());
        event(EventType.DEATH_SAVE, current, current, current.death.state().name(), natural);
        return new Outcome(roll.dice(), natural, modifier,
                natural + modifier, 20, roll.mode(), false);
    }

    public void spendTabletopInspiration(UUID actor) {
        var entry = encounter.require(actor);
        if (!entry.characterResources.inspiration()) throw new IllegalStateException("Inspiration is unavailable");
        entry.characterResources = entry.characterResources.withInspiration(false);
    }

    public CombatantEntry.View campaignView(UUID id) { return encounter.require(id).view(); }

    public void campaignTurn(UUID id, long time) { requireIdle(time); acting(id); }

    public void campaignSpend(UUID id, Resource resource, long time) {
        requireIdle(time);
        CombatManeuverResolver.spend(acting(id), resource);
    }

    public void campaignEchoMove(UUID id, long time) {
        requireIdle(time);
        var entry = acting(id);
        if (entry.echoMoved) throw new IllegalStateException("Echo already moved this turn");
        entry.echoMoved = true;
    }

    public void payCampaignSpell(UUID id, int slot, ClassFeature racial, long time) {
        requireIdle(time);
        var actor = acting(id);
        boolean free = racial != null && actor.remaining(racial) > 0;
        if (!free && slot > 0 && actor.slots.available(Pool.REGULAR, slot) == 0) throw new IllegalStateException("No spell slots remain");
        CombatManeuverResolver.spend(actor, Resource.ACTION);
        if (free) actor.spend(racial, 1);
        else if (slot > 0) actor.slots.spend(Pool.REGULAR, slot);
    }

    public void validateCannonPayment(UUID id) {
        var actor = encounter.require(id);
        if (actor.remaining(ClassFeature.ELDRITCH_CANNON) == 0 && actor.slots.available(Pool.REGULAR, 1) == 0) {
            throw new IllegalStateException("A first-level slot is required");
        }
    }

    public void payCannon(UUID id) {
        validateCannonPayment(id);
        var actor = encounter.require(id);
        if (actor.remaining(ClassFeature.ELDRITCH_CANNON) > 0) actor.spend(ClassFeature.ELDRITCH_CANNON, 1);
        else actor.slots.spend(Pool.REGULAR, 1);
    }

    public void campaignManeuver(UUID id, net.dnd5ecraft.server.CampaignActions.Action action, UUID target, Cell point,
                                 int option, long time) {
        requireIdle(time);
        var actor = action == net.dnd5ecraft.server.CampaignActions.Action.AUDIENCE_LAUGHTER ? encounter.require(id) : acting(id);
        campaign.action(actor, action, target, point, option, time);
    }

    public UUID directorScenePair(UUID target, Cell second, long time) {
        requireIdle(time);
        return campaign.directorPair(target, second);
    }

    public boolean campaignSave(UUID receiver, Ability ability, int dc) { return save(encounter.require(receiver), ability, dc, 0, false, true); }
    public record AttackPreview(Cover cover, RollMode mode, int bonus, int distance, boolean inRange, int armor, int hp, int maxHp) {}

    public AttackPreview previewAttack(UUID actorId, UUID targetId, int index, CombatantEntry outsider) {
        var actor = encounter.require(actorId);
        var target = encounter.find(targetId).orElse(outsider);
        if (target == null || target.team() == actor.team() || index < 0 || index >= actor.stats().attacks().size()) return null;
        var attack = actor.stats().attacks().get(index);
        int distance = actor.cell().distanceFeet(target.cell());
        boolean sharp = !attack.melee() && actor.feat(FeatType.SHARPSHOOTER);
        var cover = cover(actor, target);
        var flags = maneuvers.attackFlags(actor, target, !attack.melee(), maneuvers.weaponAdvantage(actor, target, attack),
                !attack.melee() && !sharp && distance > attack.normalRangeFeet());
        var mode = actor.advantageOnNextRoll ? RollMode.from(true, flags.mode() == RollMode.DISADVANTAGE) : flags.mode();
        return new AttackPreview(sharp && !cover.blocksTargeting() ? Cover.NONE : cover, mode, attack.attackBonus(),
                distance, attack.inRange(distance), target.armorClass(), target.hp.current(), target.hp.maximum());
    }

    public DamagePipeline.AttackOutcome campaignHit(UUID source, UUID target, int bonus) {
        var actor = encounter.require(source);
        var defender = encounter.require(target);
        var hit = maneuvers.rollAttack(actor, defender, bonus, true, false, false);
        var outcome = hit.outcome();
        event(outcome == DamagePipeline.AttackOutcome.MISS ? EventType.MISS : EventType.HIT, actor, defender, outcome.name(), hit.roll());
        return outcome;
    }

    public void castTritonFog(UUID caster, Cell point, long time) { spells.castTritonFog(caster, point.center(), time); }

    public void restoreFromVanillaMagic(UUID actor, int amount) {
        var target = encounter.require(actor);
        if (!target.dead()) heal(target, target, amount);
    }

    public void campaignTempHp(UUID receiver, int amount) {
        var actor = encounter.require(receiver);
        actor.hp = actor.hp.grantTemporary(amount);
    }

    public boolean allied(UUID first, UUID second) {
        return encounter.find(first).flatMap(actor -> encounter.find(second).map(target -> actor.team() == target.team())).orElse(false);
    }

    public void campaignSwap(UUID actor, Cell destination, int cost, long time) {
        requireIdle(time);
        var entry = acting(actor);
        if (!movementTerrain(entry, false).canOccupy(destination) || !entry.turn.canSpend(Resource.BONUS_ACTION)
                || !entry.turn.move(cost, entry.speedFeet())) {
            throw new IllegalArgumentException("Echo swap needs a bonus action, fifteen feet of movement, and an open destination");
        }
        entry.turn.spend(Resource.BONUS_ACTION);
        relocate(entry, destination);
    }

    void requireSwap(CombatantEntry first, CombatantEntry second) {
        if (!world.canSwap(first, second) || !world.canSwap(second, first)) {
            throw new IllegalArgumentException("Swap destination is blocked");
        }
    }

    void swapActors(CombatantEntry a, CombatantEntry b) {
        requireSwap(a, b);
        var first = a.cell;
        a.cell = b.cell;
        b.cell = first;
        spells.refreshGuardians();
        maneuvers.releaseEndedGrapples();
        event(EventType.MOVED, a, a, "", 0);
        event(EventType.MOVED, b, b, "", 0);
    }

    public void echoAttack(UUID actor, UUID target, Cell origin, boolean unleash, long time) {
        requireIdle(time);
        var user = acting(actor);
        var defender = encounter.require(target);
        var attack = user.stats().attacks().stream().filter(CombatantEntry.Attack::melee).findFirst()
                .orElseThrow(() -> new IllegalStateException("A melee weapon is required"));
        if (unleash) {
            int limit = Math.max(0, user.stats().abilityModifier(Ability.CONSTITUTION));
            int spent = user.characterResources.spent().getOrDefault(ClassFeature.UNLEASH_INCARNATION, 0);
            if (!user.tookAttackAction || user.unleashedThisAction || spent >= limit) throw new IllegalStateException("Unleash Incarnation is unavailable");
            maneuvers.attackFrom(user, defender, attack, origin, false, time);
            user.unleashedThisAction = true;
            user.characterResources = user.characterResources.withSpent(ClassFeature.UNLEASH_INCARNATION, spent + 1);
        } else maneuvers.attackFrom(user, defender, attack, origin, true, time);
    }

    public void dash(UUID actor, boolean bonusAction, long nowMillis) {
        requireIdle(nowMillis);
        CombatantEntry current = acting(actor);
        checkCunningAction(current, bonusAction);
        if (!bonusAction && !current.turn.canSpend(Resource.ACTION) && current.hasteAction) {
            current.hasteAction = false;
            current.turn.extraDash();
        } else if (!current.turn.dash(actionResource(bonusAction))) throw new IllegalStateException("Dash resource unavailable");
        current.campaignDash = true;
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
                time -> resolveOpportunityAttacks(mover, destination, encounter.initiativeOrder().stream().filter(accepted::contains).toList(),
                        0, time, moment -> {
                            if (mover.turn.move(cost, mover.speedFeet())) {
                                Cell previous = mover.cell();
                                spells.boomingMovement(mover);
                                if (mover.hp.current() > 0) relocate(mover, destination);
                                maneuvers.triggerReadied(mover, previous, moment);
                            }
                        }));
    }

    public List<UUID> opportunityThreats(UUID actor, Cell destination) {
        return opportunityThreats(current(actor), destination);
    }

    private List<UUID> opportunityThreats(CombatantEntry mover, Cell destination) {
        return encounter.initiativeOrder().stream()
                .filter(reactor -> reactor.team() != mover.team() && reactor.canReact() && canHarm(reactor, mover)
                        && (!mover.turn.disengaged() || reactor.feat(FeatType.SENTINEL))
                        && !mover.threatenedByMobile.contains(reactor.id()))
                .filter(reactor -> opportunityOrigin(reactor, mover, destination) != null)
                .map(CombatantEntry::id).toList();
    }

    private Cell opportunityOrigin(CombatantEntry reactor, CombatantEntry mover, Cell destination) {
        if (leavesReach(reactor, reactor.cell(), mover, destination)) return reactor.cell();
        var echo = echoCell(reactor);
        return echo != null && leavesReach(reactor, echo, mover, destination) ? echo : null;
    }

    private boolean leavesReach(CombatantEntry reactor, Cell from, CombatantEntry mover, Cell destination) {
        int reach = reactor.stats().meleeReachFeet();
        return from.distanceFeet(mover.cell()) <= reach && from.distanceFeet(destination) > reach
                && canSeeFrom(reactor, from, mover) && !coverFrom(from, mover).blocksTargeting();
    }

    private void resolveOpportunityAttacks(CombatantEntry mover, Cell destination, List<CombatantEntry> reactors, int index,
            long nowMillis, LongConsumer after) {
        if (index == reactors.size() || mover.hp.current() == 0) {
            after.accept(nowMillis);
            return;
        }
        CombatantEntry reactor = reactors.get(index);
        LongConsumer next = time -> resolveOpportunityAttacks(mover, destination, reactors, index + 1, time, after);
        Cell origin = opportunityOrigin(reactor, mover, destination);
        if (!reactor.canReact() || !canHarm(reactor, mover) || origin == null) {
            next.accept(nowMillis);
            return;
        }
        var attack = reactor.stats().attacks().stream().filter(Attack::melee)
                .filter(profile -> profile.inRange(origin.distanceFeet(mover.cell())) && canUseWeapon(reactor, profile))
                .findFirst().orElse(null);
        if (attack == null) {
            next.accept(nowMillis);
            return;
        }
        reactor.turn.spend(Resource.REACTION);
        maneuvers.strikeFrom(reactor, mover, attack, origin, true, nowMillis, next, false, 0, true, () -> {});
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

    void relocate(CombatantEntry mover, Cell destination) {
        Cell previous = mover.cell;
        mover.cell = destination;
        spells.refreshGuardians();
        spells.triggerZones(mover, previous);
        maneuvers.releaseEndedGrapples();
        event(EventType.MOVED, mover, mover, "", 0);
    }

    void push(CombatantEntry caster, CombatantEntry target, int squares) {
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

    void pull(CombatantEntry caster, CombatantEntry target, int squares) {
        for (int step = 0; step < squares; step++) {
            var from = target.cell();
            if (from.distanceFeet(caster.cell()) <= 5) return;
            var next = new Cell(from.x() + Integer.compare(caster.cell().x(), from.x()), from.y(),
                    from.z() + Integer.compare(caster.cell().z(), from.z()));
            var terrain = movementTerrain(target, false);
            if (!terrain.neighbors(from).contains(next) || !terrain.canStep(from, next)) return;
            relocate(target, next);
        }
    }

    Terrain movementTerrain(CombatantEntry mover) { return movementTerrain(mover, true); }

    Terrain movementTerrain(CombatantEntry mover, boolean voluntary) {
        return new Terrain() {
            @Override public boolean canOccupy(Cell to) {
                return world.canOccupy(mover, to) && encounter.initiativeOrder().stream()
                        .noneMatch(entry -> entry != mover && !entry.dead() && entry.cell().equals(to));
            }
            @Override public boolean difficult(Cell cell) { return world.difficult(cell) || spells.difficultTerrain(cell); }
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
        maneuvers.maneuver(actor, action, targetId, option, point, nowMillis);
    }

    int d20(CombatantEntry user, RollMode mode) {
        int roll = rolledD20(user, mode).kept();
        campaign.onNatural(user, roll);
        return roll;
    }

    Outcome rolledD20(CombatantEntry user, RollMode mode) {
        boolean halfling = user.race == RaceType.HALFLING;
        if (user.advantageOnNextRoll) mode = RollMode.from(true, mode == RollMode.DISADVANTAGE);
        user.advantageOnNextRoll = false;
        var result = TabletopRolls.d20(new DiceRoller(roller), mode, 0, halfling);
        if (!user.luckyRoll) return result;
        user.luckyRoll = false;
        int reroll = DiceRoller.naturalD20(roller, halfling);
        var dice = new ArrayList<>(result.dice());
        dice.add(reroll);
        int kept = Math.max(result.kept(), reroll);
        return new Outcome(dice, kept, 0, kept, 20, mode, false);
    }

    public Optional<Window> attack(UUID actor, UUID target, int profile, long nowMillis) {
        return maneuvers.attack(actor, target, profile, nowMillis);
    }


    Cell echoCell(CombatantEntry actor) { return world.echoCell(actor); }
    void echoSwing(CombatantEntry actor) { world.echoSwing(actor); }

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
                CoverRaycaster.clearRay(eye(from), eye(target), cell -> world.blocksSight(cell) || spells.obscures(cell)),
                observer.conditionEffects().has(ConditionType.BLINDED)).visible();
    }

    static Vec3 eye(CombatantEntry observer) { return eye(observer.cell()); }
    static Cell cellAt(Vec3 point) {
        return new Cell((int) Math.floor(point.x()), (int) Math.floor(point.y()), (int) Math.floor(point.z()));
    }
    static Vec3 eye(Cell cell) { return cell.center().plus(new Vec3(0, .95, 0)); }

    boolean frightSourceVisible(CombatantEntry actor) {
        return actor.conditions.stream().anyMatch(condition -> condition.type() == ConditionType.FRIGHTENED
                && condition.source().flatMap(encounter::find).map(source -> canSee(actor, source)).orElse(false));
    }

    boolean canHarm(CombatantEntry actor, CombatantEntry target) {
        return actor != target && !target.dead()
                && actor.conditionEffects().canTargetWithHarmfulEffect(actor.originatingCondition(ConditionType.CHARMED, target.id()));
    }

    void validateHostileTarget(CombatantEntry actor, CombatantEntry target) {
        if (!canHarm(actor, target)) {
            throw new IllegalArgumentException("Invalid target for a hostile effect");
        }
    }

    private CombatantEntry current(UUID actor) {
        CombatantEntry current = encounter.currentEntry().orElseThrow(() -> new IllegalStateException("No active encounter"));
        if (!current.id().equals(actor)) throw new IllegalStateException("It is another creature's turn");
        return current;
    }

    CombatantEntry acting(UUID actor) {
        CombatantEntry current = current(actor);
        if (!current.canAct()) throw new IllegalStateException("Creature cannot act");
        return current;
    }

    void requireIdle(long nowMillis) {
        tick(nowMillis);
        if (pending != null) throw new IllegalStateException("Waiting for reactions");
    }

    public void tick(long nowMillis) {
        if (!reactions.closeIfResolved(nowMillis)) return;
        Pending complete = pending;
        pending = null;
        complete.resume.accept(nowMillis);
    }

    public Reaction commandReaction(UUID actor, Reaction requested) {
        int slot = requested.slot();
        if (slot == 0) return requested;
        var user = encounter.require(actor);
        if (user.slotsAvailable(Pool.REGULAR, slot) > 0) return requested;
        for (int level = slot; level <= 9; level++) {
            if (user.slotsAvailable(Pool.PACT, level) > 0) return new Reaction(requested.kind(), level, Pool.PACT);
        }
        return requested;
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

    Optional<Window> open(List<Offer> offers, long nowMillis, Predicate<Response> valid,
            Consumer<Response> responder, LongConsumer resume) {
        if (offers.isEmpty()) {
            resume.accept(nowMillis);
            return reactions.current();
        }
        Window window = reactions.open(offers, nowMillis);
        pending = new Pending(valid, responder, resume);
        return Optional.of(window);
    }

    void event(EventType type, CombatantEntry actor, CombatantEntry target, String detail, int amount) {
        events.add(new Event(type, actor.id(), target.id(), detail, amount));
    }

    public Optional<Window> cast(UUID actor, CastRequest request, long nowMillis) {
        return spells.cast(actor, request, nowMillis);
    }

    /** Resolves a completed long cast or ritual. The caller tracks interruption and elapsed time outside initiative. */
    public void castOutsideCombat(UUID actor, CastRequest request, boolean ritual, int elapsedMinutes, long nowMillis) {
        spells.castOutsideCombat(actor, request, ritual, elapsedMinutes, nowMillis);
    }

    public void advanceDowntimeRounds(int rounds, long nowMillis) {
        requireIdle(nowMillis);
        if (encounter.active() || rounds < 0) throw new IllegalArgumentException("Invalid downtime interval");
        for (CombatantEntry entry : encounter.initiativeOrder()) {
            entry.turns = Math.addExact(entry.turns, rounds);
            spells.expireEffects(entry, true);
            spells.expireEffects(entry, false);
            spells.expireConcentration(entry);
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

    boolean save(CombatantEntry target, Ability ability, int dc, int bonus, boolean disadvantage, boolean magic) {
        var flags = saveFlags(target, ability, disadvantage, magic, false);
        return finishSave(target, ability, dc, bonus, flags.automaticFailure(), flags.automaticFailure() ? 0 : d20(target, flags.mode()));
    }

    boolean saveAgainstFear(CombatantEntry target, int dc) {
        var flags = saveFlags(target, Ability.WISDOM, false, true, true);
        return finishSave(target, Ability.WISDOM, dc, 0, flags.automaticFailure(), flags.automaticFailure() ? 0 : d20(target, flags.mode()));
    }

    void campaignSpellSave(CombatantEntry target, Ability ability, int dc, int bonus, boolean disadvantage, boolean serenity,
            long time, java.util.function.BiConsumer<Boolean, Long> after) {
        var flags = saveFlags(target, ability, disadvantage, true, serenity);
        if (!encounter.active() || flags.automaticFailure()) {
            after.accept(finishSave(target, ability, dc, bonus, flags.automaticFailure(),
                    flags.automaticFailure() ? 0 : d20(target, flags.mode())), time);
            return;
        }
        var roll = rolledD20(target, flags.mode());
        campaign.editRoll(target, roll.kept(), roll.dice().get(0), roll.mode(), time, (edited, moment) ->
                after.accept(finishSave(target, ability, dc, bonus, false, edited.natural() + edited.adjustment()), moment));
    }

    private net.dnd5ecraft.rules.conditions.ConditionEffectApplier.RollEffect saveFlags(CombatantEntry target, Ability ability,
            boolean disadvantage, boolean magic, boolean serenity) {
        var state = target.bestiary().orElse(null);
        boolean advantages = magic && (state != null && state.has(Feature.MAGIC_RESISTANCE)
                || target.race == RaceType.GNOME && Set.of(Ability.INTELLIGENCE, Ability.WISDOM, Ability.CHARISMA).contains(ability))
                || ability == Ability.DEXTERITY && (target.has(Effect.HASTE) || target.dodging && target.canAct() && target.speedFeet() > 0)
                || ability == Ability.STRENGTH && target.has(Effect.RAGING)
                || target.race == RaceType.LOXODON && serenity;
        return target.conditionEffects().savingThrow(ability, advantages, disadvantage);
    }

    private boolean finishSave(CombatantEntry target, Ability ability, int dc, int bonus, boolean automaticFailure, int roll) {
        var state = target.bestiary().orElse(null);
        boolean succeeds = !automaticFailure
                && abilityOutcome(target, roll + target.stats().saveModifier(ability) + bonus + luck(target) + auraOfProtection(target), dc);
        if (!succeeds && state != null && world.useLegendaryResistance(target) && state.useLegendaryResistance()) {
            event(EventType.BESTIARY, target, target, "legendary_resistance", state.snapshot().legendaryResistances());
            succeeds = true;
        }
        event(EventType.SAVE, target, target, ability.name(), succeeds ? 1 : 0);
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

    int luck(CombatantEntry actor) {
        return (actor.has(Effect.BLESS) ? AbilityCheckEngine.rollDie(4, roller) : 0)
                - (actor.has(Effect.BANE) ? AbilityCheckEngine.rollDie(4, roller) : 0);
    }

    public List<UUID> detectedMagic(UUID actor) {
        return spells.detectedMagic(actor);
    }

    public void endConcentration(UUID caster) {
        spells.endConcentration(caster);
    }

    public void applyCondition(UUID actor, ConditionInstance condition) {
        CombatantEntry target = encounter.require(actor);
        if (target.dead() || (condition.type() == ConditionType.POISONED
                && !target.conditionEffects().canAcquirePoisonOrDisease())) return;
        target.conditions.add(condition);
        if (!target.canAct()) {
            endConcentration(actor);
            maneuvers.releaseEndedGrapples();
        }
    }

    public DamagePipeline.Result damage(UUID source, UUID target, Map<DamageType5e, Integer> damage, boolean critical) {
        return dealDamage(encounter.require(source), encounter.require(target), damage, critical, false);
    }

    DamagePipeline.Result dealDamage(CombatantEntry source, CombatantEntry target,
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
        if (target.dead()) {
            target.diedAtRound = encounter.round();
            event(EventType.DEFEATED, source, target, "", 0);
        }
        if (!target.canAct()) {
            endConcentration(target.id());
            maneuvers.releaseEndedGrapples();
        } else if (result.damageTaken() > 0 && spells.concentration(target.id()).isPresent()) {
            var save = target.conditionEffects().savingThrow(Ability.CONSTITUTION, target.feat(FeatType.WAR_CASTER), false);
            spells.checkConcentration(target, result.damageTaken(),
                    target.stats().saveModifier(Ability.CONSTITUTION) + luck(target) + auraOfProtection(target), save.mode());
        }
        var type = result.adjustedDamage().entrySet().stream().max(Map.Entry.<DamageType5e, Integer>comparingByValue()
                .thenComparing(Map.Entry::getKey)).map(Map.Entry::getKey).orElse(DamageType5e.BLUDGEONING);
        events.add(new Event(EventType.DAMAGE, source.id(), target.id(), "", result.damageTaken(), type, critical));
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

    void resolveBestiaryFire(CombatantEntry attacker, Area area, int dc, Dice dice) {
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

    void heal(CombatantEntry caster, CombatantEntry target, int amount) {
        if (target.dead() || target.has(Effect.NO_HEALING)) return;
        int previous = target.hp.current();
        target.hp = target.hp.heal(amount);
        target.death = target.death.afterHealing(target.hp.current());
        event(EventType.HEAL, caster, target, "", target.hp.current() - previous);
    }
}
