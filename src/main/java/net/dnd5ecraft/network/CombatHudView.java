package net.dnd5ecraft.network;

import java.util.List;
import net.dnd5ecraft.network.Protocol.Combat;
import net.dnd5ecraft.network.Protocol.Creature;

/** Presentation rules shared by the HUD and its headless checks. */
public final class CombatHudView {
    private CombatHudView() {}

    public static List<Creature> initiative(Combat combat, int capacity) {
        var roster = combat.initiative();
        int count = Math.min(capacity, roster.size());
        if (count <= 0) return List.of();
        int active = 0;
        for (int i = 0; i < roster.size(); i++) {
            if (roster.get(i).id().equals(combat.activeActor())) {
                active = i;
                break;
            }
        }
        int start = Math.min(Math.max(0, active - count / 2), roster.size() - count);
        return roster.subList(start, start + count);
    }

    public static boolean allied(Combat combat, Creature actor) {
        var self = combat.creature(combat.controlledActor());
        return actor.team() == (self == null ? 0 : self.team());
    }

    public static double healthFraction(int current, int maximum) {
        return maximum <= 0 ? 0 : Math.max(0, Math.min(1, current / (double) maximum));
    }
}
