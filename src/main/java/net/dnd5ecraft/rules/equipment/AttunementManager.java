package net.dnd5ecraft.rules.equipment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

public record AttunementManager(Map<UUID, String> items) {
    public static final int SLOT_LIMIT = 3;
    public static final AttunementManager EMPTY = new AttunementManager(Map.of());

    public AttunementManager {
        items = Map.copyOf(items);
        if (items.size() > SLOT_LIMIT || items.values().stream().anyMatch(String::isBlank)
                || new HashSet<>(items.values()).size() != items.size()) {
            throw new IllegalArgumentException("Attunement requires at most three distinct item types");
        }
    }

    public boolean canAttune(UUID itemId, String itemType) {
        return itemId != null && itemType != null && !itemType.isBlank() && items.size() < SLOT_LIMIT
                && !items.containsKey(itemId) && !items.containsValue(itemType);
    }

    public AttunementManager attune(UUID itemId, String itemType) {
        if (!canAttune(itemId, itemType)) {
            throw new IllegalStateException("Cannot attune to this item");
        }
        Map<UUID, String> updated = new HashMap<>(items);
        updated.put(itemId, itemType);
        return new AttunementManager(updated);
    }

    public AttunementManager release(UUID itemId) {
        Map<UUID, String> updated = new HashMap<>(items);
        updated.remove(itemId);
        return new AttunementManager(updated);
    }
}
