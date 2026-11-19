package net.dnd5ecraft.rules.campaign;

import net.dnd5ecraft.rules.equipment.ExhaustionLevel;

public final class CampaignRules {
    public static final float TRITON_SWIM_MULTIPLIER = 1.35f;
    private CampaignRules() {}

    public static double walkMultiplier(int feet, boolean heavyArmorPenalty, ExhaustionLevel exhaustion, boolean immobilized) {
        if (immobilized || feet <= 0 || exhaustion.level() >= 5) return 0;
        double base = feet == 25 ? .85 : feet / 30.0;
        return Math.max(0, base - (heavyArmorPenalty ? .33 : 0)) * (exhaustion.level() >= 2 ? .5 : 1);
    }

    public static boolean smellSkill(net.dnd5ecraft.rules.ability.Skill skill) {
        return switch (skill) {
            case PERCEPTION, SURVIVAL, INVESTIGATION -> true;
            default -> false;
        };
    }

    public static int chaosLimit(int level) { return Math.min(7, Math.max(3, level + 2)); }

    public static int chaosChange(boolean allied, boolean helpful, boolean failedRequiredSave) {
        return allied == helpful ? -1 : failedRequiredSave ? 1 : 0;
    }

    public static int saveDc(int proficiency, int abilityModifier) { return 8 + proficiency + abilityModifier; }
    public static int cannonHealth(int artificerLevel) { return 5 * artificerLevel; }
    public static int echoArmor(int proficiency) { return 14 + proficiency; }
    public static int echoSwapCostFeet() { return 15; }
    public static boolean echoInRange(double blocks) { return blocks <= 6; }

    public static int worldbreakerDie(int level) {
        return level >= 14 ? 12 : level >= 10 ? 10 : level >= 6 ? 8 : 6;
    }
}
