package net.dnd5ecraft.combat;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;

public final class CombatEncounterState {
    public record Snapshot(boolean active, int round, Optional<UUID> current, List<CombatantEntry.View> initiative) {
        public Snapshot { initiative = List.copyOf(initiative); }
    }

    private final List<CombatantEntry> order;
    private final Map<UUID, CombatantEntry> entries;
    private int index = -1;
    private int round;

    public CombatEncounterState(List<CombatantEntry> combatants) {
        if (combatants.isEmpty()) throw new IllegalArgumentException("An encounter needs creatures");
        order = new ArrayList<>(combatants.stream().sorted(Comparator.comparingInt(CombatantEntry::initiative).reversed()
                .thenComparing(Comparator.comparingInt((CombatantEntry c) -> c.stats().abilityModifier(Ability.DEXTERITY)).reversed())
                .thenComparing(CombatantEntry::id)).toList());
        entries = new HashMap<>(order.stream().collect(Collectors.toMap(CombatantEntry::id, Function.identity())));
        var positions = new HashSet<GridMovementTracker.Cell>();
        if (order.stream().anyMatch(entry -> !positions.add(entry.cell()))) {
            throw new IllegalArgumentException("Two combatants occupy the same starting cell");
        }
    }

    public static int rollInitiative(int dexterityScore, RollMode mode, IntUnaryOperator roller) {
        return AbilityCheckEngine.rollD20(mode, roller) + Ability.modifier(dexterityScore);
    }

    public boolean active() { return index >= 0; }
    public int round() { return round; }
    public List<CombatantEntry> initiativeOrder() { return List.copyOf(order); }

    Optional<CombatantEntry> currentEntry() {
        return active() ? Optional.of(order.get(index)) : Optional.empty();
    }

    Optional<CombatantEntry> find(UUID id) { return Optional.ofNullable(entries.get(id)); }

    public CombatantEntry require(UUID id) {
        CombatantEntry entry = entries.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown combatant: " + id);
        return entry;
    }

    CombatantEntry begin() {
        if (active()) throw new IllegalStateException("Encounter is already active");
        index = 0;
        round = 1;
        return order.get(index);
    }

    CombatantEntry advance() {
        if (!active()) throw new IllegalStateException("Encounter is inactive");
        index = (index + 1) % order.size();
        if (index == 0) round = Math.incrementExact(round);
        return order.get(index);
    }

    void remove(UUID id) {
        CombatantEntry entry = entries.remove(id);
        if (entry == null) return;
        int location = order.indexOf(entry);
        order.remove(location);
        if (!active()) return;
        if (order.isEmpty()) end();
        else if (location < index) index--;
        else if (index == order.size()) {
            index = 0;
            round = Math.incrementExact(round);
        }
    }

    void end() { index = -1; }

    public Snapshot snapshot() {
        return new Snapshot(active(), round, currentEntry().map(CombatantEntry::id),
                order.stream().map(CombatantEntry::view).toList());
    }
}
