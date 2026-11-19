package net.dnd5ecraft.combat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import java.util.function.LongConsumer;
import net.dnd5ecraft.combat.CombatantEntry.Effect;
import net.dnd5ecraft.combat.CombatantEntry.TimedEffect;
import net.dnd5ecraft.combat.CombatantEntry.Trait;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.combat.ReactionWindowManager.Offer;
import net.dnd5ecraft.combat.ReactionWindowManager.Window;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.rules.character.SubclassType;
import net.dnd5ecraft.rules.conditions.ConditionEffectApplier.Sense;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.DamagePipeline.AttackOutcome;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Dice;
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
import net.dnd5ecraft.combat.CombatManager.Battlefield;
import net.dnd5ecraft.combat.CombatManager.CastRequest;
import net.dnd5ecraft.combat.CombatManager.EventType;
import net.dnd5ecraft.combat.CombatManager.Reaction;
import net.dnd5ecraft.combat.CombatManager.Response;
import net.dnd5ecraft.combat.CombatManeuverResolver.Hit;

final class CombatSpellZoneResolver {
    private final CombatManager manager;
    private final CombatEncounterState encounter;
    private final IntUnaryOperator roller;
    private final Battlefield world;

    private record Aid(UUID target, long expiry) {}

    private record SpellZone(Cast cast, Area area, Map<UUID, Long> lastAffected) {}

    private record SpiritualWeapon(Cast cast, Cell cell) {}

    private record Cast(CombatantEntry caster, Spell spell, int slot, Ability ability,
                        List<CombatantEntry> targets, Optional<String> choice, Optional<Vec3> areaOrigin, long id) {}

    private static final Set<DamageType5e> PROTECTION_TYPES = Set.of(DamageType5e.ACID, DamageType5e.COLD,
            DamageType5e.FIRE, DamageType5e.LIGHTNING, DamageType5e.THUNDER);
    private static final Set<ConditionType> RESTORABLE = Set.of(ConditionType.BLINDED, ConditionType.DEAFENED,
            ConditionType.PARALYZED, ConditionType.POISONED);

    private final ConcentrationManager concentration = new ConcentrationManager();
    private final Map<Long, Long> concentrationExpiry = new HashMap<>();
    private final Map<UUID, Long> temporaryProvider = new HashMap<>();
    private final Map<Long, SpellZone> zones = new LinkedHashMap<>();
    private final Map<UUID, SpiritualWeapon> spiritualWeapons = new HashMap<>();
    private final Map<UUID, Aid> aids = new HashMap<>();

    CombatSpellZoneResolver(CombatManager manager, CombatEncounterState encounter, IntUnaryOperator roller, Battlefield world) {
        this.manager = manager;
        this.encounter = encounter;
        this.roller = roller;
        this.world = world;
    }

    void removeCombatant(UUID id) {
        aids.remove(id);
        aids.values().removeIf(aid -> aid.target.equals(id));
        spiritualWeapons.remove(id);
        refreshGuardians();
    }

    void beginTurn(CombatantEntry actor) {
        expireEffects(actor, true);
        var aid = aids.get(actor.id());
        if (aid != null && actor.turns >= aid.expiry) aids.remove(actor.id());
        spiritualWeapons.computeIfPresent(actor.id(), (id, weapon) ->
                actor.effects.stream().noneMatch(effect -> effect.castId() == weapon.cast.id) ? null : weapon);
        expireConcentration(actor);
    }

    void expireConcentration(CombatantEntry actor) {
        concentration.current(actor.id()).filter(cast -> concentrationExpiry.get(cast.castId()) <= actor.turns)
                .ifPresent(this::dropConcentration);
    }

    void grantAid(CombatantEntry helper, CombatantEntry target) {
        aids.put(helper.id(), new Aid(target.id(), helper.turns + 1));
    }

    Optional<UUID> aidFor(CombatantEntry attacker, CombatantEntry target) {
        return aids.entrySet().stream()
                .filter(entry -> entry.getValue().target.equals(target.id()))
                .filter(entry -> encounter.find(entry.getKey()).filter(ally -> !ally.id().equals(attacker.id())
                        && ally.team() == attacker.team() && ally.turns < entry.getValue().expiry).isPresent())
                .map(Map.Entry::getKey).findFirst();
    }

    void consumeAid(UUID helper) { aids.remove(helper); }

    boolean difficultTerrain(Cell cell) {
        return zones.values().stream().anyMatch(zone ->
                zone.cast.spell.id().equals("web") && area(zone).contains(cell.center()));
    }

    Optional<ConcentrationManager.Active> concentration(UUID caster) { return concentration.current(caster); }

    void checkConcentration(CombatantEntry target, int damage, int modifier, RollMode mode) {
        concentration.onDamage(target.id(), damage, false, modifier, mode, roller)
                .ifPresent(this::removeConcentrationEffects);
    }

    Optional<Window> cast(UUID actor, CastRequest request, long nowMillis) {
        manager.requireIdle(nowMillis);
        CombatantEntry caster = manager.acting(actor);
        Spell spell = SpellRegistry5e.require(request.spellId());
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
        return manager.open(offers, nowMillis, response -> canCounter(encounter.require(response.reactor()), caster)
                && canPayReaction(encounter.require(response.reactor()), "counterspell", response.reaction()),
                response -> accepted.put(response.reactor(), response.reaction()), time -> {
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
        CombatantEntry reactor = encounter.require(response.reactor());
        LongConsumer next = time -> resolveCounters(caster, spellId, slot, counters, index + 1, time, onSuccess, onCountered);
        if (!canCounter(reactor, caster) || !canPayReaction(reactor, "counterspell", response.reaction())) {
            next.accept(nowMillis);
            return;
        }
        payReaction(reactor, "counterspell", response.reaction());
        intercept(reactor, "counterspell", response.reaction().slot(), nowMillis, time -> {
            Ability ability = reactor.stats().spells().get("counterspell");
            RollMode mode = reactor.conditionEffects().abilityCheck(
                    Sense.NONE,
                    manager.frightSourceVisible(reactor), false, false).mode();
            if (response.reaction().slot() >= slot
                    || AbilityCheckEngine.check(reactor.stats().abilityModifier(ability), 10 + slot, mode, roller).succeeds()) {
                manager.event(EventType.COUNTERED, reactor, caster, spellId, slot);
                onCountered.accept(time);
            } else next.accept(time);
        }, next);
    }

    /** Resolves a completed long cast or ritual. The caller tracks interruption and elapsed time outside initiative. */
    void castOutsideCombat(UUID actor, CastRequest request, boolean ritual, int elapsedMinutes, long nowMillis) {
        manager.requireIdle(nowMillis);
        if (encounter.active() || elapsedMinutes < 0) {
            throw new IllegalArgumentException("This casting entry point requires downtime");
        }
        CombatantEntry caster = encounter.require(actor);
        Spell spell = SpellRegistry5e.require(request.spellId());
        int minimum = ritual ? spell.ritualCastingMinutes() : spell.castingTime() == CastingTime.TEN_MINUTES ? 10 : 0;
        if (!caster.canAct() || elapsedMinutes < minimum || spell.castingTime() == CastingTime.REACTION
                || (ritual && !caster.stats().rituals().contains(spell.id()))) {
            throw new IllegalStateException("Caster cannot complete the requested casting");
        }
        CastRequest validated = ritual
                ? new CastRequest(request.spellId(), spell.level(), request.pool(), request.targets(), request.aim(), request.choice())
                : request;
        Cast cast = prepareCast(caster, spell, validated, ritual);
        if (minimum > 0) endConcentration(caster.id());
        if (!ritual) pay(caster, validated);
        beginSpell(cast);
        resolveCast(cast, nowMillis);
    }

    private Cast prepareCast(CombatantEntry caster, Spell spell, CastRequest request, boolean ritual) {
        Ability ability = caster.stats().spells().get(spell.id());
        if (ability == null || !spell.canUseSlot(request.slot())
                || (!ritual && request.slot() > 0 && caster.slots.available(request.pool(), request.slot()) == 0)) {
            throw new IllegalArgumentException("Unknown spell or unavailable slot");
        }
        validateChoice(spell, request.choice());
        List<CombatantEntry> targets = targets(caster, spell, request);
        Optional<Vec3> areaOrigin = request.aim().map(aim -> spell.rangeFeet() == 0 ? caster.cell().center() : aim);
        return new Cast(caster, spell, request.slot(), ability, targets, request.choice(), areaOrigin, manager.nextEffectId());
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
        int cap = spell.targetsAt(request.slot(), caster.stats().level());
        if (spell.targeting() == Targeting.SELF) {
            if (request.aim().isPresent() || (!request.targets().isEmpty() && !request.targets().equals(List.of(caster.id())))) {
                throw new IllegalArgumentException("A self-targeted spell applies to the caster");
            }
            return List.of(caster);
        }
        List<CombatantEntry> selected = request.targets().stream().map(encounter::require).toList();
        boolean multipleHits = projectiles(spell);
        if (selected.size() > cap || (multipleHits && selected.size() != cap)
                || (!multipleHits && selected.stream().distinct().count() != selected.size())) {
            throw new IllegalArgumentException("Invalid target count for " + spell.id());
        }
        if (spell.targeting() == Targeting.AREA) {
            Vec3 aim = request.aim().orElseThrow(() -> new IllegalArgumentException("Area spell requires an aim"));
            var shape = spell.area().orElseThrow();
            Vec3 source = caster.cell().center();
            Vec3 origin = spell.rangeFeet() == 0 ? source : aim;
            if (spell.rangeFeet() > 0 && (origin.minus(source).length() * 5 > spell.rangeFeet() + 1e-9
                    || !CoverRaycaster.clearRay(CombatManager.eye(caster), origin, world::blocksCover)
                    || world.blocksCover(CombatManager.cellAt(origin)))) {
                throw new IllegalArgumentException("Area origin is out of range or blocked");
            }
            if (spell.effect().kind() == EffectKind.TELEPORT) {
                if (!manager.movementTerrain(caster, false).canOccupy(CombatManager.cellAt(origin))) throw new IllegalArgumentException("Teleport destination is occupied");
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
                        entry -> entry.cell().center(), point -> world.blocksCover(CombatManager.cellAt(point)))
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
        if (request.aim().isPresent() || selected.isEmpty()) throw new IllegalArgumentException("Choose creature targets");
        for (CombatantEntry entry : selected) {
            if (caster.cell().distanceFeet(entry.cell()) > spell.rangeFeet() || manager.cover(caster, entry).blocksTargeting()) {
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
            manager.validateHostileTarget(caster, target);
        }
        if (Set.of("sacred_flame", "magic_missile", "hold_person", "poison_spray", "healing_word", "mass_healing_word",
                "prayer_of_healing", "blindness_deafness", "bane").contains(spell.id()) && !manager.canSee(caster, target)) {
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
        if (request.slot() > 0 && !caster.slots.spend(request.pool(), request.slot())) {
            throw new IllegalStateException("The requested spell slot is unavailable");
        }
        reinforceWard(caster, SpellRegistry5e.require(request.spellId()), request.slot());
    }

    private void beginSpell(Cast cast) {
        if (cast.spell.concentration()) endConcentration(cast.caster.id());
        manager.event(EventType.CAST, cast.caster, cast.caster, cast.spell.id(), cast.slot);
    }

    private boolean canCounter(CombatantEntry reactor, CombatantEntry caster) {
        return manager.canHarm(reactor, caster) && reactor.cell().distanceFeet(caster.cell()) <= 60 && manager.canSee(reactor, caster)
                && hasReactionSlot(reactor, "counterspell");
    }

    boolean hasReactionSlot(CombatantEntry reactor, String spellId) {
        Spell spell = SpellRegistry5e.require(spellId);
        if (!reactor.canReact() || !reactor.stats().spells().containsKey(spellId) || !reactor.turn.canCast(spell)) return false;
        for (int slot = spell.level(); slot <= 9; slot++) {
            if (reactor.slots.available(Pool.REGULAR, slot) > 0 || reactor.slots.available(Pool.PACT, slot) > 0) return true;
        }
        return false;
    }

    boolean canPayReaction(CombatantEntry reactor, String spellId, Reaction response) {
        Spell spell = SpellRegistry5e.require(spellId);
        return reactor.canReact() && reactor.stats().spells().containsKey(spellId) && reactor.turn.canCast(spell)
                && spell.canUseSlot(response.slot()) && reactor.slots.available(response.pool(), response.slot()) > 0;
    }

    private void payReaction(CombatantEntry reactor, String spellId, Reaction response) {
        reactor.turn.cast(SpellRegistry5e.require(spellId));
        reactor.slots.spend(response.pool(), response.slot());
        reinforceWard(reactor, SpellRegistry5e.require(spellId), response.slot());
        manager.event(EventType.CAST, reactor, reactor, spellId, response.slot());
    }

    void shield(CombatantEntry reactor, Reaction reaction) {
        payReaction(reactor, "shield", reaction);
        reactor.effects.add(new TimedEffect(Effect.SHIELD, reactor.id(), manager.nextEffectId(), reactor.id(),
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
        manager.open(offers, nowMillis, response -> canPayReaction(encounter.require(response.reactor()), "shield", response.reaction()),
                response -> shield(encounter.require(response.reactor()), response.reaction()), ignored -> {
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
        Hit hit = manager.maneuvers().rollAttack(cast.caster, target, cast.caster.stats().proficiencyBonus() + castingModifier(cast),
                cast.spell.rangeFeet() > 5, metalAdvantage, false);
        manager.maneuvers().resolveHit(hit, encounter.active(), nowMillis, (outcome, dodge, reduction) -> {
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
                ? CoverRaycaster.cover(cast.areaOrigin.orElseGet(() -> CombatManager.eye(cast.caster)), target.bounds(),
                    world::blocksCover).bonus() : 0;
        boolean disadvantage = cast.spell.id().equals("shatter") && target.stats().traits().contains(Trait.INORGANIC);
        return manager.save(target, ability, saveDc(cast), bonus, disadvantage, true);
    }

    private void applySpell(Cast cast, CombatantEntry target, int amount, boolean saved, boolean critical, boolean dodge) {
        if (target.dead() && cast.spell.effect().kind() != EffectKind.REVIVE) return;
        switch (cast.spell.effect().kind()) {
            case DAMAGE, SPIRITUAL_WEAPON -> {
                manager.dealDamage(cast.caster, target, Map.of(cast.spell.effect().damageType().orElseThrow(), amount), critical, dodge);
                if (!target.dead() && !saved) applyRider(cast, target);
            }
            case HEAL -> manager.heal(cast.caster, target, amount);
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
                manager.maneuvers().releaseEndedGrapples();
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
                var cell = CombatManager.cellAt(cast.areaOrigin.orElseThrow());
                if (manager.movementTerrain(cast.caster, false).canOccupy(cell)) manager.relocate(cast.caster, cell);
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
            case PUSH_TEN_FEET -> manager.push(cast.caster, target, 2);
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

    void refreshGuardians() {
        for (CombatantEntry entry : encounter.initiativeOrder()) {
            entry.guardiansSlow = zones.values().stream().anyMatch(zone -> zone.cast.spell.id().equals("spirit_guardians")
                    && zone.cast.caster.team() != entry.team() && area(zone).contains(entry.cell().center()));
            entry.effects.removeIf(effect -> effect.effect() == Effect.WEBBED
                    && (!zones.containsKey(effect.castId()) || !area(zones.get(effect.castId())).contains(entry.cell().center())));
        }
    }

    void triggerZones(CombatantEntry entry, Cell previous) {
        if (entry.dead()) return;
        for (SpellZone zone : List.copyOf(zones.values())) {
            boolean guardians = zone.cast.spell.id().equals("spirit_guardians");
            Area area = area(zone);
            if (guardians && zone.cast.caster.team() == entry.team()) continue;
            if (!area.contains(entry.cell().center()) || previous != null && area.contains(previous.center())
                    || zone.lastAffected.getOrDefault(entry.id(), -1L) == manager.turnSequence()) continue;
            zone.lastAffected.put(entry.id(), manager.turnSequence());
            if (zone.cast.spell.id().equals("web")) {
                if (!manager.save(entry, Ability.DEXTERITY, saveDc(zone.cast), 0, false, true)
                        && entry.effects.stream().noneMatch(effect -> effect.effect() == Effect.WEBBED && effect.castId() == zone.cast.id)) {
                    addEffect(zone.cast, entry, Effect.WEBBED, zone.cast.caster, true, saveDc(zone.cast), null);
                }
            } else {
                int damage = rollSpellAmount(zone.cast, false);
                if (manager.save(entry, guardians ? Ability.WISDOM : Ability.CONSTITUTION, saveDc(zone.cast), 0,
                        !guardians && entry.stats().traits().contains(Trait.SHAPECHANGER), true)) damage /= 2;
                manager.dealDamage(zone.cast.caster, entry, Map.of(DamageType5e.RADIANT, damage), false, false);
            }
        }
    }

    void retargetMark(CombatantEntry caster, CombatantEntry target) {
        var active = concentration.current(caster.id()).filter(spell -> spell.spellId().equals("hunters_mark"))
                .orElseThrow(() -> new IllegalStateException("No Hunter's Mark is active"));
        if (target == null || target.dead() || !manager.canHarm(caster, target) || !manager.canSee(caster, target)
                || caster.cell().distanceFeet(target.cell()) > 90 || manager.cover(caster, target).blocksTargeting()) {
            throw new IllegalArgumentException("Choose a visible hostile target within 90 feet");
        }
        var old = encounter.initiativeOrder().stream().filter(entry -> entry.effects.stream().anyMatch(effect ->
                effect.effect() == Effect.MARKED && effect.castId() == active.castId())).findFirst();
        if (old.isPresent() && !old.orElseThrow().dead()) throw new IllegalStateException("The marked target is still alive");
        CombatManeuverResolver.spend(caster, Resource.BONUS_ACTION);
        old.ifPresent(entry -> entry.effects.removeIf(effect -> effect.castId() == active.castId()));
        target.effects.add(new TimedEffect(Effect.MARKED, caster.id(), active.castId(), caster.id(),
                concentrationExpiry.get(active.castId()), true, 0, null));
    }

    void moveMoonbeam(CombatantEntry caster, Cell destination) {
        if (destination == null || world.blocksCover(destination)) throw new IllegalArgumentException("Select an open destination");
        var beam = zones.values().stream().filter(zone -> zone.cast.caster == caster && zone.cast.spell.id().equals("moonbeam"))
                .findFirst().orElseThrow(() -> new IllegalStateException("No moonbeam is active"));
        if (beam.area.origin().minus(destination.center()).length() > 12) throw new IllegalArgumentException("Moonbeam can move up to 60 feet");
        CombatManeuverResolver.spend(caster, Resource.ACTION);
        zones.put(beam.cast.id, new SpellZone(beam.cast, zoneArea(beam.cast, destination.center()), beam.lastAffected));
    }

    void moveSpiritualWeapon(CombatantEntry caster, CombatantEntry target, long time) {
        var weapon = spiritualWeapons.get(caster.id());
        if (weapon == null || target == null || target.dead() || weapon.cell.distanceFeet(target.cell()) > 25
                || caster.effects.stream().noneMatch(effect -> effect.castId() == weapon.cast.id)) {
            throw new IllegalStateException("Spiritual Weapon or target is unavailable");
        }
        manager.validateHostileTarget(caster, target);
        CombatManeuverResolver.spend(caster, Resource.BONUS_ACTION);
        spiritualWeapons.put(caster.id(), new SpiritualWeapon(weapon.cast, target.cell()));
        var attack = new Cast(caster, weapon.cast.spell, weapon.cast.slot, weapon.cast.ability,
                List.of(target), Optional.empty(), Optional.empty(), weapon.cast.id);
        resolveSpellAttack(attack, 0, time);
    }

    private void addEffect(Cast cast, CombatantEntry target, Effect effect, CombatantEntry expiryActor,
            boolean atStart, int dc, DamageType5e resistance) {
        target.effects.add(new TimedEffect(effect, cast.caster.id(), cast.id, expiryActor.id(),
                expiryActor.turns + cast.spell.durationRounds(), atStart, dc, resistance));
    }

    void expireEffects(CombatantEntry actor, boolean atStart) {
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

    void repeatSaves(CombatantEntry actor) {
        if (actor.dead()) return;
        actor.effects.removeIf(effect -> effect.effect() != Effect.WEBBED && effect.saveDc() != 0 && manager.save(actor,
                effect.effect() == Effect.PARALYZED ? Ability.WISDOM : Ability.CONSTITUTION, effect.saveDc(), 0, false,
                effect.effect() != Effect.GHOUL_PARALYSIS));
    }

    private static void restore(CombatantEntry target, ConditionType type) {
        target.conditions.removeIf(condition -> condition.type() == type);
        target.effects.removeIf(effect -> effect.effect().name().equals(type.name())
                || type == ConditionType.PARALYZED && effect.effect() == Effect.GHOUL_PARALYSIS);
    }

    List<UUID> detectedMagic(UUID actor) {
        CombatantEntry caster = encounter.require(actor);
        if (!caster.has(Effect.DETECT_MAGIC)) return List.of();
        return encounter.initiativeOrder().stream().filter(entry -> entry != caster && !entry.dead()
                && (!entry.effects.isEmpty() || entry.stats().traits().contains(Trait.MAGICAL))
                && caster.cell().distanceFeet(entry.cell()) <= 30
                && CoverRaycaster.clearRay(CombatManager.eye(caster), CombatManager.eye(entry), world::blocksMagic)).map(CombatantEntry::id).toList();
    }

    void endConcentration(UUID caster) {
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
                entry.effects.add(new TimedEffect(Effect.LETHARGY, entry.id(), manager.nextEffectId(), entry.id(), entry.turns + 1, false, 0, null));
                if (!entry.id().equals(cast.caster())) endConcentration(entry.id());
            }
        }
        refreshGuardians();
        CombatantEntry caster = encounter.require(cast.caster());
        manager.event(EventType.CONCENTRATION_ENDED, caster, caster, cast.spellId(), 0);
    }
}
