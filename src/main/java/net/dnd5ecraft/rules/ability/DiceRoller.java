package net.dnd5ecraft.rules.ability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;

public final class DiceRoller {
    private static final Pattern EXPRESSION =
            Pattern.compile("(\\d{0,3})d(\\d{1,5})(?:kh(\\d{1,3}))?([+-]\\d{1,7})?");
    private final IntUnaryOperator source;

    public record RollResult(List<Integer> rolls, List<Integer> kept, int modifier, int total) {
        public RollResult {
            rolls = List.copyOf(rolls);
            kept = List.copyOf(kept);
        }
    }

    public DiceRoller(RandomGenerator random) {
        this((IntUnaryOperator) sides -> random.nextInt(sides) + 1);
    }

    public DiceRoller(long seed) { this(new Random(seed)); }

    public DiceRoller(IntUnaryOperator source) { this.source = Objects.requireNonNull(source); }

    public RollResult roll(String expression) { return roll(expression, false, false); }

    public RollResult roll(String expression, boolean halflingLucky, boolean greatWeaponFighting) {
        if (expression == null || expression.length() > 64) throw new IllegalArgumentException("Invalid dice expression");
        String input = expression.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        if (input.equals("advantage")) return d20(RollMode.ADVANTAGE, 0, halflingLucky);
        if (input.equals("disadvantage")) return d20(RollMode.DISADVANTAGE, 0, halflingLucky);
        var matcher = EXPRESSION.matcher(input);
        if (!matcher.matches()) throw new IllegalArgumentException("Use NdS, NdS+M, NdSkhK, Advantage or Disadvantage");
        int count = matcher.group(1).isEmpty() ? 1 : Integer.parseInt(matcher.group(1));
        int sides = Integer.parseInt(matcher.group(2));
        int keep = matcher.group(3) == null ? count : Integer.parseInt(matcher.group(3));
        int flat = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
        if (count < 1 || count > 100 || sides < 2 || sides > 10000 || keep < 1 || keep > count) {
            throw new IllegalArgumentException("Dice count, sides or keep count out of range");
        }
        List<Integer> rolls = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int value = AbilityCheckEngine.rollDie(sides, source);
            if ((halflingLucky && sides == 20 && value == 1) || (greatWeaponFighting && value <= 2)) {
                value = AbilityCheckEngine.rollDie(sides, source);
            }
            rolls.add(value);
        }
        var kept = keep == count ? rolls : rolls.stream().sorted(Comparator.reverseOrder()).limit(keep).toList();
        return new RollResult(rolls, kept, flat, Math.addExact(flat, kept.stream().mapToInt(Integer::intValue).sum()));
    }

    public RollResult d20(RollMode mode, int modifier, boolean lucky) {
        int first = naturalD20(source, lucky);
        List<Integer> rolls = mode == RollMode.NORMAL ? List.of(first) : List.of(first, naturalD20(source, lucky));
        int kept = switch (mode) {
            case NORMAL -> first;
            case ADVANTAGE -> Math.max(first, rolls.get(1));
            case DISADVANTAGE -> Math.min(first, rolls.get(1));
        };
        return new RollResult(rolls, List.of(kept), modifier, Math.addExact(kept, modifier));
    }

    public static int naturalD20(IntUnaryOperator source, boolean lucky) {
        int first = AbilityCheckEngine.rollDie(20, source);
        return first == 1 && lucky ? AbilityCheckEngine.rollDie(20, source) : first;
    }
}
