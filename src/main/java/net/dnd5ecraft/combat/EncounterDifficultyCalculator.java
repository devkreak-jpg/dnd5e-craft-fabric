package net.dnd5ecraft.combat;

import java.util.List;
import java.util.Map;

public final class EncounterDifficultyCalculator {
    public enum Difficulty { TRIVIAL, EASY, MEDIUM, HARD, DEADLY }

    public record Monster(String challengeRating, boolean countsTowardMultiplier) {
        public Monster(String challengeRating) { this(challengeRating, true); }
    }

    public record Thresholds(int easy, int medium, int hard, int deadly) {}
    public record Result(int baseXp, double adjustedXp, double multiplier, Thresholds thresholds, Difficulty difficulty) {}

    private static final int[][] THRESHOLDS = {
        {25, 50, 75, 100}, {50, 100, 150, 200}, {75, 150, 225, 400}, {125, 250, 375, 500},
        {250, 500, 750, 1100}, {300, 600, 900, 1400}, {350, 750, 1100, 1700}, {450, 900, 1400, 2100},
        {550, 1100, 1600, 2400}, {600, 1200, 1900, 2800}, {800, 1600, 2400, 3600},
        {1000, 2000, 3000, 4500}, {1100, 2200, 3400, 5100}, {1250, 2500, 3800, 5700},
        {1400, 2800, 4300, 6400}, {1600, 3200, 4800, 7200}, {2000, 3900, 5900, 8800},
        {2100, 4200, 6300, 9500}, {2400, 4900, 7300, 10900}, {2800, 5700, 8500, 12700}
    };
    private static final int[] XP = {10, 200, 450, 700, 1100, 1800, 2300, 2900, 3900, 5000, 5900,
        7200, 8400, 10000, 11500, 13000, 15000, 18000, 20000, 22000, 25000,
        33000, 41000, 50000, 62000, 75000, 90000, 105000, 120000, 135000, 155000};
    private static final Map<String, Integer> FRACTIONS = Map.of("1/8", 25, "1/4", 50, "1/2", 100);
    private static final double[] MULTIPLIERS = {.5, 1, 1.5, 2, 2.5, 3, 4, 5};

    private EncounterDifficultyCalculator() {}

    public static int xp(String challengeRating) {
        Integer fractional = FRACTIONS.get(challengeRating);
        if (fractional != null) return fractional;
        try {
            int index = Integer.parseInt(challengeRating);
            if (index >= 0 && index < XP.length) return XP[index];
        } catch (NumberFormatException ignored) {
            // The common validation message also handles malformed fractional ratings.
        }
        throw new IllegalArgumentException("Unknown challenge rating: " + challengeRating);
    }

    public static Result assess(List<Integer> partyLevels, List<Monster> monsters) {
        if (partyLevels.isEmpty()) throw new IllegalArgumentException("Encounter requires a party");
        int[] sums = new int[4];
        for (int level : partyLevels) {
            if (level < 1 || level > 20) throw new IllegalArgumentException("Invalid party level");
            for (int i = 0; i < sums.length; i++) sums[i] = Math.addExact(sums[i], THRESHOLDS[level - 1][i]);
        }
        int baseXp = 0;
        int counted = 0;
        for (Monster monster : monsters) {
            baseXp = Math.addExact(baseXp, xp(monster.challengeRating));
            if (monster.countsTowardMultiplier) counted++;
        }
        int index = counted <= 1 ? 1 : counted == 2 ? 2 : counted <= 6 ? 3 : counted <= 10 ? 4 : counted <= 14 ? 5 : 6;
        index += partyLevels.size() < 3 ? 1 : partyLevels.size() >= 6 ? -1 : 0;
        double multiplier = MULTIPLIERS[index];
        double adjusted = baseXp * multiplier;
        Difficulty difficulty = adjusted >= sums[3] ? Difficulty.DEADLY : adjusted >= sums[2] ? Difficulty.HARD
                : adjusted >= sums[1] ? Difficulty.MEDIUM : adjusted >= sums[0] ? Difficulty.EASY : Difficulty.TRIVIAL;
        return new Result(baseXp, adjusted, multiplier, new Thresholds(sums[0], sums[1], sums[2], sums[3]), difficulty);
    }
}
