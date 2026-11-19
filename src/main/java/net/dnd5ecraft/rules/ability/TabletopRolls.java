package net.dnd5ecraft.rules.ability;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.equipment.DamagePipeline.DamageRoll;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.FeatType;
import net.dnd5ecraft.rules.conditions.ConditionType;

public final class TabletopRolls {
    public static final List<Integer> DICE = List.of(4, 6, 8, 10, 12, 20, 100);
    private static final Pattern TRAY = Pattern.compile("([1-8])d(4|6|8|10|12|20|100)");

    public enum Kind { ABILITY, SKILL, SAVE, INITIATIVE, DEATH_SAVE, HIT_DIE, HIT, DAMAGE, DICE }
    public enum Weapon { MAIN_HAND, OFF_HAND, UNARMED }

    public record Check(Kind kind, String id) {
        public Check {
            switch (kind) {
                case ABILITY, SAVE -> Ability.valueOf(id);
                case SKILL -> Skill.valueOf(id);
                case HIT, DAMAGE -> Weapon.valueOf(id);
                case DICE -> tray(id);
                default -> {
                    if (!id.isEmpty()) throw new IllegalArgumentException("Unexpected roll choice");
                }
            }
        }

        public boolean requestable() { return kind == Kind.ABILITY || kind == Kind.SKILL || kind == Kind.SAVE; }
    }

    public record Options(RollMode mode, boolean whisper, boolean inspiration) {}
    public record DicePool(int count, int sides) {}
    public record Outcome(List<Integer> dice, int kept, int modifier, int total, int sides, RollMode mode, boolean critical) {
        public Outcome { dice = List.copyOf(dice); }
    }

    private TabletopRolls() {}

    public static DicePool tray(String expression) {
        var match = TRAY.matcher(expression);
        if (!match.matches()) throw new IllegalArgumentException("Invalid tray dice");
        int count = Integer.parseInt(match.group(1));
        int sides = Integer.parseInt(match.group(2));
        if (sides == 100 && count != 1) throw new IllegalArgumentException("Roll one percentile die at a time");
        return new DicePool(count, sides);
    }

    public static RollMode clickMode(RollMode selected, boolean shift, boolean alt) {
        return shift || alt ? RollMode.from(shift, alt) : Objects.requireNonNull(selected);
    }

    public static RollMode checkMode(RollMode requested, boolean inspiration, boolean disadvantage) {
        return RollMode.from(inspiration || requested == RollMode.ADVANTAGE,
                disadvantage || !inspiration && requested == RollMode.DISADVANTAGE);
    }

    public static boolean visible(boolean privateRoll, boolean roller, boolean operator) {
        return !privateRoll || roller || operator;
    }

    public static Outcome d20(DiceRoller roller, RollMode mode, int modifier, boolean lucky) {
        var rolled = roller.d20(mode, modifier, lucky);
        return new Outcome(rolled.rolls(), rolled.kept().get(0), modifier, rolled.total(), 20, mode, false);
    }

    public static boolean disadvantaged(CharacterSheetData sheet, Kind kind) {
        if (sheet == null) return false;
        boolean impaired = sheet.conditions().stream().anyMatch(active -> active.type() == ConditionType.POISONED
                || active.type() == ConditionType.FRIGHTENED);
        return switch (kind) {
            case SAVE, DEATH_SAVE -> sheet.exhaustion().savingThrowDisadvantage();
            case HIT -> impaired || sheet.exhaustion().attackDisadvantage();
            case DICE -> false;
            default -> impaired || sheet.exhaustion().abilityCheckDisadvantage();
        };
    }

    public static int modifier(CharacterSheetData sheet, Check check) {
        if (sheet == null) return 0;
        return switch (check.kind()) {
            case ABILITY -> Ability.modifier(sheet.score(Ability.valueOf(check.id())));
            case SKILL -> sheet.skillModifier(Skill.valueOf(check.id()));
            case SAVE -> sheet.savingThrowModifier(Ability.valueOf(check.id()));
            case INITIATIVE -> Ability.modifier(sheet.score(Ability.DEXTERITY))
                    + AbilityCheckEngine.jackOfAllTrades(sheet.classLevels().getOrDefault(ClassType.BARD, 0), sheet.level(), ProficiencyLevel.NONE)
                    + (sheet.feats().contains(FeatType.ALERT) ? 5 : 0);
            default -> 0;
        };
    }

    public static int damageDice(int dice, boolean critical) { return critical ? Math.multiplyExact(dice, 2) : dice; }

    public static String damageFormula(DamageRoll damage, boolean critical, int extra) {
        int count = damageDice(damage.dice().count(), critical);
        int flat = damage.modifier() + extra;
        return count == 0 ? Integer.toString(Math.max(0, flat))
                : count + "d" + damage.dice().sides() + (flat == 0 ? "" : flat > 0 ? "+" + flat : Integer.toString(flat));
    }

    public static Outcome weaponDamage(DiceRoller roller, DamageRoll damage, boolean critical, int extra) {
        int count = damageDice(damage.dice().count(), critical);
        int flat = damage.modifier() + extra;
        if (count == 0) return new Outcome(List.of(), 0, flat, Math.max(0, flat), 0, RollMode.NORMAL, critical);
        var roll = roller.roll(damageFormula(damage, critical, extra));
        return new Outcome(roll.rolls(), roll.rolls().get(0), flat, Math.max(0, roll.total()), damage.dice().sides(), RollMode.NORMAL, critical);
    }
}
