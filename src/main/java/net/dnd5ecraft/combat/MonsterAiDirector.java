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

public final class MonsterAiDirector {
    public enum Action { ATTACK, MOVE, DASH, END_TURN }
    public record Decision(Action action, UUID target, int attackIndex, Cell destination) {}

    private record Candidate(CombatantEntry target, int index, double score) {}
    private record Position(Cell cell, int cost, int distance, boolean canAttack) {}

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
        boolean canAttack = monster.canAttack();
        if (canAttack) {
            Optional<Candidate> attack = chooseAttack(combat, monster, enemies);
            if (attack.isPresent()) return new Decision(Action.ATTACK, attack.get().target.id(), attack.get().index, monster.cell());
        }

        int movement = monster.turn.remainingMovement(monster.speedFeet());
        if (movement > 0) {
            Optional<Cell> next = nextStep(combat, monster, enemies, movement, canAttack);
            if (next.isPresent()) return new Decision(Action.MOVE, null, -1, next.get());
        }
        if (monster.canAct() && monster.turn.canSpend(Resource.ACTION) && monster.speedFeet() > 0
                && nextStep(combat, monster, enemies, Math.addExact(movement, monster.speedFeet()), false).isPresent()) {
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
            case DASH -> combat.dash(actor, false, nowMillis);
            case END_TURN -> combat.endTurn(actor, nowMillis);
        }
        return Optional.empty();
    }

    private static Decision endTurn(CombatantEntry monster) {
        return new Decision(Action.END_TURN, null, -1, monster.cell());
    }

    private static Optional<Candidate> chooseAttack(CombatManager combat, CombatantEntry monster, List<CombatantEntry> enemies) {
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
                double chance = Math.max(.05, Math.min(.95, (21 + attack.attackBonus() - enemy.armorClass() - cover.bonus()) / 20.0));
                if (!attack.melee() && (distance > attack.normalRangeFeet() || distance <= 5)) chance *= chance;
                double score = Math.min(Math.max(0, mean), enemy.hitPoints().current()) * chance
                        + (mean >= enemy.hitPoints().current() ? chance : 0);
                if (best == null || score > best.score) best = new Candidate(enemy, index, score);
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<Cell> nextStep(CombatManager combat, CombatantEntry monster, List<CombatantEntry> enemies,
            int budget, boolean actionAvailable) {
        var terrain = combat.movementTerrain(monster);
        boolean crawl = monster.conditionEffects().mustCrawl();
        Map<Cell, Integer> reachable = GridMovementTracker.reachable(monster.cell(), budget, terrain, crawl);
        int startDistance = nearest(monster.cell(), enemies);
        Comparator<Position> rank = Comparator.comparing(Position::canAttack).reversed()
                .thenComparingInt(Position::distance).thenComparingInt(Position::cost);
        return reachable.entrySet().stream().filter(entry -> entry.getValue() > 0).map(entry -> {
            Cell cell = entry.getKey();
            boolean canAttack = actionAvailable && enemies.stream().anyMatch(enemy -> combat.canHarm(monster, enemy)
                    && monster.stats().attacks().stream().anyMatch(attack -> attack.inRange(cell.distanceFeet(enemy.cell())))
                    && combat.canSeeFrom(monster, cell, enemy) && !combat.coverFrom(cell, enemy).blocksTargeting());
            return new Position(cell, entry.getValue(), nearest(cell, enemies), canAttack);
        }).filter(position -> position.canAttack || position.distance < startDistance).min(rank)
                .flatMap(position -> GridMovementTracker.path(monster.cell(), position.cell, budget, terrain, crawl))
                .map(Path::cells).map(path -> path.get(1));
    }

    private static int nearest(Cell cell, List<CombatantEntry> enemies) {
        return enemies.stream().mapToInt(enemy -> cell.distanceFeet(enemy.cell())).min().orElseThrow();
    }
}
