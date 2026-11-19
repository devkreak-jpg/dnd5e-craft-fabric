package net.dnd5ecraft.rules.character;

import java.util.EnumMap;
import java.util.Map;
import net.dnd5ecraft.rules.spells.SpellSlotTable.SpentSlots;

public record CharacterResources(Map<ClassType, Integer> hitDice, Map<ClassFeature, Integer> spent, boolean inspiration,
        SpentSlots slots) {
    public CharacterResources(Map<ClassType, Integer> hitDice, Map<ClassFeature, Integer> spent, boolean inspiration) {
        this(hitDice, spent, inspiration, SpentSlots.EMPTY);
    }
    public CharacterResources {
        java.util.Objects.requireNonNull(slots);
        hitDice = Map.copyOf(hitDice);
        spent = Map.copyOf(spent);
        if (hitDice.values().stream().anyMatch(value -> value < 0 || value > 20)
                || spent.values().stream().anyMatch(value -> value < 0)) throw new IllegalArgumentException("Invalid class resources");
    }

    public static CharacterResources full(Map<ClassType, Integer> levels) {
        return new CharacterResources(levels, Map.of(), false);
    }

    public int remaining(ClassFeature feature, Map<ClassType, Integer> levels, int charisma, RaceType race) {
        int max = feature.capacity(levels, charisma, race);
        return max == Integer.MAX_VALUE ? max : Math.max(0, max - spent.getOrDefault(feature, 0));
    }

    public CharacterResources spend(ClassFeature feature, int amount, Map<ClassType, Integer> levels, int charisma, RaceType race) {
        int available = remaining(feature, levels, charisma, race);
        if (amount < 1 || amount > available) throw new IllegalStateException("Class resource is unavailable");
        if (available == Integer.MAX_VALUE) return this;
        var updated = new EnumMap<ClassFeature, Integer>(ClassFeature.class);
        updated.putAll(spent);
        updated.merge(feature, amount, Math::addExact);
        return new CharacterResources(hitDice, updated, inspiration, slots);
    }

    public CharacterResources withInspiration(boolean value) { return new CharacterResources(hitDice, spent, value, slots); }

    public CharacterResources spendHitDie(ClassType type) {
        if (hitDice.getOrDefault(type, 0) == 0) throw new IllegalStateException("No hit dice of that class");
        var updated = new EnumMap<ClassType, Integer>(ClassType.class);
        updated.putAll(hitDice);
        updated.compute(type, (key, value) -> value - 1);
        return new CharacterResources(updated, spent, inspiration, slots);
    }

    public CharacterResources rested(Map<ClassType, Integer> levels, boolean longRest) {
        var dice = new EnumMap<ClassType, Integer>(ClassType.class);
        dice.putAll(hitDice);
        var remaining = new EnumMap<ClassFeature, Integer>(ClassFeature.class);
        remaining.putAll(spent);
        if (longRest) {
            remaining.clear();
            int recover = Math.max(1, levels.values().stream().mapToInt(Integer::intValue).sum() / 2);
            for (ClassType type : ClassType.values()) {
                int recovered = Math.min(recover, levels.getOrDefault(type, 0) - dice.getOrDefault(type, 0));
                if (recovered > 0) {
                    dice.merge(type, recovered, Integer::sum);
                    recover -= recovered;
                }
            }
        } else remaining.keySet().removeIf(feature -> feature.shortRest(levels));
        return new CharacterResources(dice, remaining, inspiration,
                longRest ? SpentSlots.EMPTY : new SpentSlots(slots.regular(), 0));
    }

    public CharacterResources withSlots(SpentSlots updated) {
        return slots.equals(updated) ? this : new CharacterResources(hitDice, spent, inspiration, updated);
    }

    public CharacterResources addHitDie(ClassType type) {
        var dice = new EnumMap<ClassType, Integer>(ClassType.class);
        dice.putAll(hitDice);
        dice.merge(type, 1, Integer::sum);
        return new CharacterResources(dice, spent, inspiration, slots);
    }
}
