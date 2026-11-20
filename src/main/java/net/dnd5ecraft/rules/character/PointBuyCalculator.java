package net.dnd5ecraft.rules.character;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;

public final class PointBuyCalculator {
    public static final int BUDGET = 27;
    public static final List<Integer> STANDARD_ARRAY = List.of(15, 14, 13, 12, 10, 8);
    private static final int[] COSTS = {0, 1, 2, 3, 4, 5, 7, 9};

    private PointBuyCalculator() {}

    public static int cost(int score) {
        if (score < 8 || score > 15) {
            throw new IllegalArgumentException("Point-buy scores must be between 8 and 15");
        }
        return COSTS[score - 8];
    }

    public static int pointsRemaining(Map<Ability, Integer> scores) {
        int spent = 0;
        for (Ability ability : Ability.values()) {
            Integer score = scores.get(ability);
            if (score == null) {
                throw new IllegalArgumentException("Missing ability score: " + ability);
            }
            spent += cost(score);
        }
        return BUDGET - spent;
    }

    public static boolean isStandardArray(Map<Ability, Integer> scores) {
        return scores.keySet().equals(Set.of(Ability.values()))
                && scores.values().stream().allMatch(Objects::nonNull)
                && scores.values().stream().sorted(Comparator.reverseOrder()).toList().equals(STANDARD_ARRAY);
    }

    public static int rollStartingScore(IntUnaryOperator roller) {
        int[] rolls = new int[4];
        Arrays.setAll(rolls, index -> AbilityCheckEngine.rollDie(6, roller));
        Arrays.sort(rolls);
        return rolls[1] + rolls[2] + rolls[3];
    }
}
