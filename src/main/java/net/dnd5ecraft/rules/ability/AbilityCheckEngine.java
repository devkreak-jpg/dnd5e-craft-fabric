package net.dnd5ecraft.rules.ability;

import java.util.function.IntUnaryOperator;

public final class AbilityCheckEngine {
    private AbilityCheckEngine() {}

    public enum RollMode {
        NORMAL, ADVANTAGE, DISADVANTAGE;

        public static RollMode from(boolean advantage, boolean disadvantage) {
            if (advantage == disadvantage) {
                return NORMAL;
            }
            return advantage ? ADVANTAGE : DISADVANTAGE;
        }
    }

    public record CheckResult(int naturalRoll, int modifier, int difficulty) {
        public int total() {
            return naturalRoll + modifier;
        }

        public boolean succeeds() {
            return total() >= difficulty;
        }
    }

    public static int modifier(int abilityScore, ProficiencyLevel proficiency, int level) {
        return Ability.modifier(abilityScore) + proficiency.contribution(level);
    }

    public static CheckResult check(int modifier, int difficulty, RollMode mode, IntUnaryOperator roller) {
        return new CheckResult(rollD20(mode, roller), modifier, difficulty);
    }

    public static boolean groupSucceeds(java.util.List<CheckResult> results) {
        if (results.isEmpty()) throw new IllegalArgumentException("A group check requires participants");
        return results.stream().filter(CheckResult::succeeds).count() * 2L >= results.size();
    }

    public static RollMode withHelp(boolean help, boolean advantage, boolean disadvantage) {
        return RollMode.from(help || advantage, disadvantage);
    }

    /** In a tied contest the current situation remains unchanged. */
    public static boolean winsContest(CheckResult challenger, CheckResult defender) {
        return challenger.total() > defender.total();
    }

    public static CheckResult savingThrow(int modifier, int difficulty, RollMode mode, IntUnaryOperator roller) {
        return check(modifier, difficulty, mode, roller);
    }

    public static int jackOfAllTrades(int bardLevel, int totalLevel, ProficiencyLevel proficiency) {
        return bardLevel >= 2 && proficiency == ProficiencyLevel.NONE
                ? ProficiencyLevel.HALF.contribution(totalLevel) : proficiency.contribution(totalLevel);
    }

    public static int rollD20(RollMode mode, IntUnaryOperator roller) {
        int first = rollDie(20, roller);
        return switch (mode) {
            case NORMAL -> first;
            case ADVANTAGE -> Math.max(first, rollDie(20, roller));
            case DISADVANTAGE -> Math.min(first, rollDie(20, roller));
        };
    }

    public static int rollDie(int sides, IntUnaryOperator roller) {
        if (sides < 2) {
            throw new IllegalArgumentException("A die needs at least two sides");
        }
        int roll = roller.applyAsInt(sides);
        if (roll < 1 || roll > sides) {
            throw new IllegalArgumentException("Roll outside 1.." + sides + ": " + roll);
        }
        return roll;
    }
}
