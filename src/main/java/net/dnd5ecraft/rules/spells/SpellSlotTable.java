package net.dnd5ecraft.rules.spells;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import net.dnd5ecraft.rules.character.ClassType;

public final class SpellSlotTable {
    private SpellSlotTable() {}

    private static final int[][] SLOTS = {
        {}, {2}, {3}, {4,2}, {4,3}, {4,3,2}, {4,3,3}, {4,3,3,1}, {4,3,3,2}, {4,3,3,3,1},
        {4,3,3,3,2}, {4,3,3,3,2,1}, {4,3,3,3,2,1}, {4,3,3,3,2,1,1}, {4,3,3,3,2,1,1},
        {4,3,3,3,2,1,1,1}, {4,3,3,3,2,1,1,1}, {4,3,3,3,2,1,1,1,1}, {4,3,3,3,3,1,1,1,1},
        {4,3,3,3,3,2,1,1,1}, {4,3,3,3,3,2,2,1,1}
    };

    public enum Pool { REGULAR, PACT }

    public record PactSlots(int level, int count) {}

    public static int[] regularSlots(int effectiveLevel) {
        checkLevel(effectiveLevel);
        return Arrays.copyOf(SLOTS[effectiveLevel], 9);
    }

    public static int effectiveLevel(Map<ClassType, Integer> levels) {
        int total = 0;
        int full = 0;
        int half = 0;
        int spellcastingClasses = 0;
        for (var entry : levels.entrySet()) {
            int level = entry.getValue();
            checkLevel(level);
            total += level;
            switch (entry.getKey()) {
                case BARD, CLERIC, DRUID, SORCERER, WIZARD -> {
                    full += level;
                    if (level > 0) spellcastingClasses++;
                }
                case PALADIN, RANGER -> {
                    if (level >= 2) {
                        half += level / 2;
                        spellcastingClasses++;
                    }
                }
                default -> { /* Pact Magic and martial classes use no regular slots. */ }
            }
        }
        checkLevel(total);
        if (spellcastingClasses == 1 && full == 0) {
            int level = Math.max(levels.getOrDefault(ClassType.PALADIN, 0), levels.getOrDefault(ClassType.RANGER, 0));
            return (level + 1) / 2;
        }
        return full + half;
    }

    public static PactSlots pactSlots(int warlockLevel) {
        checkLevel(warlockLevel);
        if (warlockLevel == 0) return new PactSlots(0, 0);
        int capacity = warlockLevel == 1 ? 1 : warlockLevel < 11 ? 2 : warlockLevel < 17 ? 3 : 4;
        return new PactSlots(Math.min(5, (warlockLevel + 1) / 2), capacity);
    }

    public record SpentSlots(java.util.List<Integer> regular, int pact) {
        public static final SpentSlots EMPTY = new SpentSlots(java.util.Collections.nCopies(9, 0), 0);

        public SpentSlots {
            regular = java.util.List.copyOf(regular);
            if (regular.size() != 9 || regular.stream().anyMatch(slot -> slot < 0 || slot > 4) || pact < 0 || pact > 4) {
                throw new IllegalArgumentException("Invalid spent spell slots");
            }
        }
    }

    public static final class Ledger {
        private final int[] maximum;
        private final int[] regular;
        private final PactSlots pact;
        private int pactRemaining;

        public Ledger(Map<ClassType, Integer> levels) { this(levels, SpentSlots.EMPTY); }

        public Ledger(Map<ClassType, Integer> levels, SpentSlots spent) {
            maximum = regularSlots(effectiveLevel(levels));
            regular = maximum.clone();
            pact = pactSlots(levels.getOrDefault(ClassType.WARLOCK, 0));
            pactRemaining = Math.max(0, pact.count - spent.pact());
            for (int i = 0; i < regular.length; i++) regular[i] = Math.max(0, regular[i] - spent.regular().get(i));
        }

        public SpentSlots spent() {
            return new SpentSlots(java.util.stream.IntStream.range(0, 9).mapToObj(i -> maximum[i] - regular[i]).toList(),
                    pact.count - pactRemaining);
        }

        public int available(Pool pool, int level) {
            Objects.requireNonNull(pool);
            if (level < 1 || level > 9) throw new IllegalArgumentException("Invalid slot level");
            return pool == Pool.PACT ? (level == pact.level ? pactRemaining : 0) : regular[level - 1];
        }

        public boolean canRecover(int level) {
            return level >= 1 && level <= 9 && available(Pool.REGULAR, level) < maximum[level - 1];
        }

        public void recover(int level) {
            if (!canRecover(level)) throw new IllegalStateException("No used slot at that level");
            regular[level - 1]++;
        }

        public boolean spend(Pool pool, int level) {
            if (available(pool, level) == 0) return false;
            if (pool == Pool.PACT) pactRemaining--;
            else regular[level - 1]--;
            return true;
        }

        public void shortRest() {
            pactRemaining = pact.count;
        }

        public void longRest() {
            shortRest();
            System.arraycopy(maximum, 0, regular, 0, regular.length);
        }
    }

    private static void checkLevel(int level) {
        if (level < 0 || level > 20) throw new IllegalArgumentException("Caster level must be between 0 and 20");
    }
}
