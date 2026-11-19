package net.dnd5ecraft.combat;

import java.util.List;
import java.util.function.BiPredicate;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;

public final class OpportunityAttackDetector {
    public enum Movement { VOLUNTARY, FORCED, TELEPORT }

    private OpportunityAttackDetector() {}

    public static List<CombatantEntry> leavingReach(CombatantEntry mover, Cell destination, Movement movement,
            boolean disengaged, List<CombatantEntry> combatants, BiPredicate<CombatantEntry, CombatantEntry> canSee) {
        if (movement != Movement.VOLUNTARY || disengaged) return List.of();
        return combatants.stream()
                .filter(other -> other.team() != mover.team() && other.canReact())
                .filter(other -> mover.cell().distanceFeet(other.cell()) <= other.stats().meleeReachFeet()
                        && destination.distanceFeet(other.cell()) > other.stats().meleeReachFeet())
                .filter(other -> canSee.test(other, mover))
                .toList();
    }
}
