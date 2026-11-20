package net.dnd5ecraft.client.gui.hud;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.network.Protocol.Combat;
import net.dnd5ecraft.network.Protocol.Creature;
import net.dnd5ecraft.network.Protocol.Slots;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatInspectorTest {
    private static final Creature HERO = creature(1, "Hero", 0);
    private static final Creature ENEMY = creature(2, "Enemy", 1);

    @Test
    void dmObserverHoveringOverAnEnemyHasNoAttackPreview() {
        var observer = combat(null, ENEMY.id());
        assertFalse(CombatInspector.canInspect(observer, ENEMY.id(), true));
    }

    @Test
    void missingControlledActorHasNoAttackPreview() {
        var missingActor = new UUID(0, 3);
        assertFalse(CombatInspector.canInspect(combat(missingActor, missingActor), ENEMY.id(), true));
    }

    @Test
    void combatantCanInspectAnEnemyButCannotInspectAnAlly() {
        var player = combat(HERO.id(), HERO.id());
        assertTrue(CombatInspector.canInspect(player, ENEMY.id(), false));
        assertFalse(CombatInspector.canInspect(player, HERO.id(), true));
    }

    private static Combat combat(UUID controlled, UUID active) {
        return new Combat(new UUID(0, 10), controlled, active, 1, false, List.of(HERO, ENEMY),
                Set.of(), 0, Map.of(), Map.of(), List.of("Attack"), List.of(), Slots.EMPTY, null);
    }

    private static Creature creature(int id, String name, int team) {
        return new Creature(new UUID(0, id), name, team, 10, new Cell(id, 0, 0), 10, 10,
                0, 12, 5, State.CONSCIOUS, Set.of());
    }
}
