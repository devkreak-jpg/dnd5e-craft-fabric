package net.dnd5ecraft.network;

import java.util.List;
import net.dnd5ecraft.network.Protocol.Combat;
import net.dnd5ecraft.network.Protocol.Creature;

/** Presentation rules shared by the HUD and its headless checks. */
public final class CombatHudView {
    private CombatHudView() {}

    public record Layout(int vitalsTop, int oxygenTop, int resourcesTop, int resourcesHeight,
                         int reactionsTop, int reactionsBottom, boolean compactReactions) {}

    public static Layout layout(int height, int choices) {
        int vitals = height - 54;
        int oxygen = vitals - 9;
        int resourcesHeight = height < 220 ? 21 : 33;
        int resources = oxygen - 5 - resourcesHeight;
        int reactionsBottom = resources - 6;
        int full = 29 + Math.max(0, choices) * 12;
        boolean compact = reactionsBottom - full < 53;
        int top = Math.max(53, reactionsBottom - (compact ? 26 : full));
        return new Layout(vitals, oxygen, resources, resourcesHeight, top, reactionsBottom, compact);
    }

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
