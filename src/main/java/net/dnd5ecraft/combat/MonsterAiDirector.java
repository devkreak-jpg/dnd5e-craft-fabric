package net.dnd5ecraft.combat;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.dnd5ecraft.combat.CombatantEntry.Attack;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.GridMovementTracker.Path;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.entity.BestiaryPreset.Feature;
import net.dnd5ecraft.entity.NpcCombatRole;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.campaign.TacticalFeedback;
import net.dnd5ecraft.rules.spells.SpellRegistry5e;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;

public final class MonsterAiDirector {
    public enum Action { ATTACK, MOVE, DASH, DISENGAGE, SHOVE, CAST, END_TURN }
    public record Decision(Action action, UUID target, int attackIndex, Cell destination, String spell, int slot) {
        public Decision(Action action, UUID target, int attackIndex, Cell destination) {
            this(action, target, attackIndex, destination, null, 0);
        }
    }

    private record Candidate(CombatantEntry target, int index, double score) {}
    private record Position(Cell cell, int cost, double score) {}

    private MonsterAiDirector() {}

    public static Decision plan(CombatManager combat, UUID actor) {
        CombatantEntry monster = combat.combatant(actor);
        if (!combat.snapshot().current().equals(Optional.of(actor))) {
            throw new IllegalStateException("AI can only plan the active creature's turn");
        }
        if (!monster.canAct() && monster.speedFeet() == 0) return endTurn(monster);
        List<CombatantEntry> enemies = combat.combatants().stream()
                .filter(target -> target.team() != monster.team() && target.hitPoints().current() > 0 && !target.dead())
                .toList();
        if (enemies.isEmpty()) return endTurn(monster);
        var role = monster.bestiary().map(state -> state.preset().combatRole()).orElse(NpcCombatRole.MELEE_BRUTE);
        var attack = chooseAttack(combat, monster, enemies, role);
        int movement = monster.turn.remainingMovement(monster.speedFeet());
        var step = nextStep(combat, monster, enemies, movement, role, monster.canAttack());
        boolean actionAvailable = monster.canAct() && monster.turn.canSpend(Resource.ACTION);

        if (role == NpcCombatRole.CASTER_SUPPORT && monster.canAct()) {
            var spell = chooseSpell(combat, monster, enemies);
            if (spell.isPresent()) return spell.orElseThrow();
        }
        if (role == NpcCombatRole.RANGED_SKIRMISHER && step.isPresent()) {
            if (actionAvailable && threatened(monster.cell(), enemies))
                return new Decision(Action.DISENGAGE, null, -1, monster.cell());
            return new Decision(Action.MOVE, null, -1, step.orElseThrow());
        }
        if (monster.canAttack() && attack.isPresent()) {
            var candidate = attack.orElseThrow();
            var target = candidate.target;
            boolean shove = role == NpcCombatRole.MELEE_BRUTE && target.armorClass() >= 18
                    && target.cell().distanceFeet(monster.cell()) <= 5
                    && target.stats().abilityModifier(net.dnd5ecraft.rules.ability.Ability.STRENGTH)
                       < monster.stats().abilityModifier(net.dnd5ecraft.rules.ability.Ability.STRENGTH);
            return new Decision(shove ? Action.SHOVE : Action.ATTACK, target.id(), candidate.index, monster.cell());
        }
        if (step.isPresent()) return new Decision(Action.MOVE, null, -1, step.orElseThrow());
        if (actionAvailable && monster.speedFeet() > 0
                && nextStep(combat, monster, enemies, movement + monster.speedFeet(), role, false).isPresent()) {
            return new Decision(Action.DASH, null, -1, monster.cell());
        }
        return endTurn(monster);
    }

    public static Optional<ReactionWindowManager.Window> execute(CombatManager combat, UUID actor, long nowMillis) {
        combat.tick(nowMillis);
        if (combat.reactionWindow().isPresent()) return combat.reactionWindow();
        Decision decision = plan(combat, actor);
        switch (decision.action) {
            case ATTACK -> { return combat.attack(actor, decision.target, decision.attackIndex, nowMillis); }
            case MOVE -> { return combat.move(actor, decision.destination, nowMillis); }
            case CAST -> {
                var spell = SpellRegistry5e.require(decision.spell);
                var targets = decision.spell.equals("magic_missile")
                        ? java.util.Collections.nCopies(spell.targetsAt(decision.slot, combat.combatant(actor).stats().level()), decision.target)
                        : List.of(decision.target);
                return combat.cast(actor, new CombatManager.CastRequest(decision.spell, decision.slot, targets), nowMillis);
            }
            case DISENGAGE -> combat.disengage(actor, false, nowMillis);
            case SHOVE -> combat.maneuver(actor, CombatManager.Maneuver.SHOVE, decision.target, 0, null, nowMillis);
            case DASH -> combat.dash(actor, false, nowMillis);
            case END_TURN -> combat.endTurn(actor, nowMillis);
        }
        return Optional.empty();
    }

    private static Decision endTurn(CombatantEntry monster) {
        return new Decision(Action.END_TURN, null, -1, monster.cell());
    }

    private static Optional<Candidate> chooseAttack(CombatManager combat, CombatantEntry monster, List<CombatantEntry> enemies, NpcCombatRole role) {
        Candidate best = null;
        for (CombatantEntry enemy : enemies) {
            int distance = monster.cell().distanceFeet(enemy.cell());
            List<Attack> attacks = monster.stats().attacks();
            if (attacks.stream().noneMatch(attack -> attack.inRange(distance))
                    || !combat.canHarm(monster, enemy) || !combat.canSee(monster, enemy)) continue;
            var cover = combat.cover(monster, enemy);
            if (cover.blocksTargeting()) continue;
            for (int index = 0; index < attacks.size(); index++) {
                Attack attack = attacks.get(index);
                if (!attack.inRange(distance)) continue;
                double mean = attack.damage().stream().mapToDouble(damage ->
                        damage.dice().count() * (damage.dice().sides() + 1) / 2.0 + damage.modifier()).sum();
                boolean pack = packOpportunity(combat, monster, enemy, monster.cell());
                boolean disadvantage = !attack.melee() && (distance > attack.normalRangeFeet() || distance <= 5);
                double chance = TacticalFeedback.hitChance(enemy.armorClass() + cover.bonus(), attack.attackBonus(),
                        RollMode.from(pack && monster.bestiary().filter(state -> state.has(Feature.PACK_TACTICS)).isPresent(), disadvantage));
                double score = Math.min(Math.max(0, mean), enemy.hitPoints().current()) * chance
                        + (mean >= enemy.hitPoints().current() ? chance : 0);
                if (role == NpcCombatRole.MELEE_BRUTE) score += (pack ? 25 : 0) + (!enemy.stats().spells().isEmpty() ? 15 : 0);
                if (role == NpcCombatRole.MINI_BOSS) score += combat.concentration(enemy.id()).isPresent() ? 20 : 0;
                if (best == null || score > best.score) best = new Candidate(enemy, index, score);
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<Decision> chooseSpell(CombatManager combat, CombatantEntry caster, List<CombatantEntry> enemies) {
        var ally = combat.combatants().stream().filter(other -> other.team() == caster.team() && !other.dead()
                        && (long) other.hitPoints().current() * 2 < other.hitPoints().maximum()
                        && !other.stats().traits().contains(CombatantEntry.Trait.UNDEAD)
                        && !other.stats().traits().contains(CombatantEntry.Trait.CONSTRUCT))
                .filter(other -> legalSpell(combat, caster, other, "healing_word", 1))
                .min(Comparator.<CombatantEntry>comparingInt(other ->
                        other.bestiary().map(state -> state.preset().combatRole()).orElse(null) == NpcCombatRole.MINI_BOSS ? 0 : 1)
                        .thenComparingDouble(other -> (double) other.hitPoints().current() / other.hitPoints().maximum()));
        if (ally.isPresent()) return Optional.of(cast(caster, ally.orElseThrow(), "healing_word", 1));

        var ordered = enemies.stream().sorted(Comparator.<CombatantEntry, Boolean>comparing(enemy ->
                combat.concentration(enemy.id()).isPresent()).reversed().thenComparingInt(enemy -> enemy.hitPoints().current())).toList();
        for (CombatantEntry target : ordered) {
            boolean holding = combat.concentration(caster.id()).isPresent();
            boolean concentrating = combat.concentration(target.id()).isPresent();
            if (!holding && concentrating && target.stats().traits().contains(CombatantEntry.Trait.HUMANOID)
                    && legalSpell(combat, caster, target, "hold_person", 2))
                return Optional.of(cast(caster, target, "hold_person", 2));
            if (concentrating && legalSpell(combat, caster, target, "magic_missile", 1))
                return Optional.of(cast(caster, target, "magic_missile", 1));
            if (!holding && legalSpell(combat, caster, target, "bane", 1))
                return Optional.of(cast(caster, target, "bane", 1));
            if (legalSpell(combat, caster, target, "fire_bolt", 0))
                return Optional.of(cast(caster, target, "fire_bolt", 0));
        }
        return Optional.empty();
    }

    private static Decision cast(CombatantEntry caster, CombatantEntry target, String spell, int slot) {
        return new Decision(Action.CAST, target.id(), -1, caster.cell(), spell, slot);
    }

    private static boolean legalSpell(CombatManager combat, CombatantEntry caster, CombatantEntry target, String id, int slot) {
        var spell = SpellRegistry5e.require(id);
        return caster.stats().spells().containsKey(id) && (slot == 0 || caster.slots.available(Pool.REGULAR, slot) > 0)
                && caster.turn.canCast(spell) && caster.cell().distanceFeet(target.cell()) <= spell.rangeFeet()
                && combat.canSee(caster, target) && !combat.cover(caster, target).blocksTargeting()
                && (caster.team() == target.team() || combat.canHarm(caster, target));
    }

    private static Optional<Cell> nextStep(CombatManager combat, CombatantEntry monster, List<CombatantEntry> enemies,
            int budget, NpcCombatRole role, boolean actionAvailable) {
        if (budget <= 0) return Optional.empty();
        var terrain = combat.movementTerrain(monster);
        boolean crawl = monster.conditionEffects().mustCrawl();
        Map<Cell, Integer> reachable = GridMovementTracker.reachable(monster.cell(), budget, terrain, crawl);
        double start = score(combat, monster, enemies, monster.cell(), role, actionAvailable);
        Comparator<Position> rank = Comparator.comparingDouble(Position::score).reversed().thenComparingInt(Position::cost)
                .thenComparingInt(position -> position.cell.x()).thenComparingInt(position -> position.cell.y())
                .thenComparingInt(position -> position.cell.z());
        return reachable.entrySet().stream().filter(entry -> entry.getValue() > 0).map(entry -> {
            Cell cell = entry.getKey();
            return new Position(cell, entry.getValue(), score(combat, monster, enemies, cell, role, actionAvailable));
        }).filter(position -> position.score > start).min(rank)
                .flatMap(position -> GridMovementTracker.path(monster.cell(), position.cell, budget, terrain, crawl))
                .map(Path::cells).map(path -> path.get(1));
    }

    private static double score(CombatManager combat, CombatantEntry monster, List<CombatantEntry> enemies, Cell cell,
            NpcCombatRole role, boolean actionAvailable) {
        int nearest = enemies.stream().mapToInt(enemy -> cell.distanceFeet(enemy.cell())).min().orElseThrow();
        boolean shot = actionAvailable && enemies.stream().anyMatch(enemy -> combat.canHarm(monster, enemy)
                && monster.stats().attacks().stream().anyMatch(attack -> attack.inRange(cell.distanceFeet(enemy.cell())))
                && combat.canSeeFrom(monster, cell, enemy) && !combat.coverFrom(cell, enemy).blocksTargeting());
        return switch (role) {
            case RANGED_SKIRMISHER -> {
                var cover = enemies.stream().map(enemy -> combat.coverAt(enemy.cell(), monster, cell))
                        .min(Comparator.comparingInt(Enum::ordinal)).orElse(CoverRaycaster.Cover.NONE);
                yield rangedTileScore(cover, threatened(cell, enemies), shot, nearest);
            }
            case CASTER_SUPPORT -> -Math.max(0, 20 - nearest) - Math.max(0, nearest - 60) + (shot ? 20 : 0);
            case MELEE_BRUTE, MINI_BOSS -> (shot ? 40 : 0) + enemies.stream().mapToInt(enemy ->
                    -cell.distanceFeet(enemy.cell()) + (packOpportunity(combat, monster, enemy, cell) ? 25 : 0)
                    + (role == NpcCombatRole.MELEE_BRUTE && !enemy.stats().spells().isEmpty() ? 15 : 0)).max().orElseThrow();
        };
    }

    public static int rangedTileScore(CoverRaycaster.Cover cover, boolean adjacent, boolean hasShot, int distanceFeet) {
        return (cover == CoverRaycaster.Cover.HALF || cover == CoverRaycaster.Cover.THREE_QUARTERS ? 35 : 0)
                - (adjacent ? 45 : 0) + (hasShot ? 40 : 0)
                - Math.max(0, 20 - distanceFeet) - Math.max(0, distanceFeet - 60);
    }

    private static boolean threatened(Cell cell, List<CombatantEntry> enemies) {
        return enemies.stream().anyMatch(enemy -> enemy.canAct() && cell.distanceFeet(enemy.cell()) <= 5
                && enemy.stats().attacks().stream().anyMatch(Attack::melee));
    }

    private static boolean packOpportunity(CombatManager combat, CombatantEntry monster, CombatantEntry target, Cell cell) {
        return cell.distanceFeet(target.cell()) <= 5 && combat.combatants().stream().anyMatch(ally ->
                ally != monster && ally.team() == monster.team() && ally.canAct() && ally.cell().distanceFeet(target.cell()) <= 5
                && (monster.bestiary().filter(state -> state.has(Feature.PACK_TACTICS)).isPresent()
                    || (long) (cell.x() - target.cell().x()) * (ally.cell().x() - target.cell().x())
                        + (long) (cell.z() - target.cell().z()) * (ally.cell().z() - target.cell().z()) < 0));
    }
}
