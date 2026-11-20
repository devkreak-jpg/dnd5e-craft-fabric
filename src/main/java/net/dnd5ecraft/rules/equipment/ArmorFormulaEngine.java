package net.dnd5ecraft.rules.equipment;

import net.dnd5ecraft.rules.ability.Ability;

public final class ArmorFormulaEngine {
    private ArmorFormulaEngine() {}

    public enum Formula {
        UNARMORED(ArmorCategory.UNARMORED, 10, false),
        MAGE_ARMOR(ArmorCategory.UNARMORED, 13, false),
        BARBARIAN(ArmorCategory.UNARMORED, 10, false),
        MONK(ArmorCategory.UNARMORED, 10, false),
        PADDED(ArmorCategory.LIGHT, 11, true),
        LEATHER(ArmorCategory.LIGHT, 11, false),
        STUDDED_LEATHER(ArmorCategory.LIGHT, 12, false),
        HIDE(ArmorCategory.MEDIUM, 12, false),
        CHAIN_SHIRT(ArmorCategory.MEDIUM, 13, false),
        SCALE_MAIL(ArmorCategory.MEDIUM, 14, true),
        BREASTPLATE(ArmorCategory.MEDIUM, 14, false),
        HALF_PLATE(ArmorCategory.MEDIUM, 15, true),
        RING_MAIL(ArmorCategory.HEAVY, 14, true),
        CHAIN_MAIL(ArmorCategory.HEAVY, 16, true, 13),
        SPLINT(ArmorCategory.HEAVY, 17, true, 15),
        PLATE(ArmorCategory.HEAVY, 18, true, 15);

        private final ArmorCategory category;
        private final int baseAc;
        private final boolean stealthDisadvantage;
        private final int strengthRequired;

        Formula(ArmorCategory category, int baseAc, boolean stealthDisadvantage) {
            this(category, baseAc, stealthDisadvantage, 0);
        }

        Formula(ArmorCategory category, int baseAc, boolean stealthDisadvantage, int strengthRequired) {
            this.category = category;
            this.baseAc = baseAc;
            this.stealthDisadvantage = stealthDisadvantage;
            this.strengthRequired = strengthRequired;
        }

        public ArmorCategory category() { return category; }
        public boolean stealthDisadvantage() { return stealthDisadvantage; }

        public int speedPenaltyFeet(int strengthScore, boolean ignoresArmorPenalty) {
            return !ignoresArmorPenalty && strengthScore < strengthRequired ? 10 : 0;
        }
    }

    public static int armorClass(Formula formula, int dexterityScore, int constitutionScore,
            int wisdomScore, boolean shield) {
        return armorClass(formula, dexterityScore, constitutionScore, wisdomScore, shield, false);
    }

    public static int armorClass(Formula formula, int dexterityScore, int constitutionScore,
            int wisdomScore, boolean shield, boolean mediumArmorMaster) {
        int dexterity = Ability.modifier(dexterityScore);
        int ac = switch (formula) {
            case BARBARIAN -> formula.baseAc + dexterity + Ability.modifier(constitutionScore);
            case MONK -> shield ? Formula.UNARMORED.baseAc + dexterity
                    : formula.baseAc + dexterity + Ability.modifier(wisdomScore);
            default -> switch (formula.category) {
                case HEAVY -> formula.baseAc;
                case MEDIUM -> formula.baseAc + Math.min(dexterity, mediumArmorMaster ? 3 : 2);
                case LIGHT, UNARMORED -> formula.baseAc + dexterity;
                case SHIELD -> throw new IllegalArgumentException("Shield is an AC bonus");
            };
        };
        return ac + (shield ? 2 : 0);
    }

    public static int carryingCapacityPounds(int strengthScore) {
        Ability.modifier(strengthScore);
        return strengthScore * 15;
    }

    public static int variantEncumbranceSpeedPenalty(int strengthScore, double carriedPounds) {
        Ability.modifier(strengthScore);
        if (!Double.isFinite(carriedPounds) || carriedPounds < 0) {
            throw new IllegalArgumentException("Invalid carried weight");
        }
        if (carriedPounds > strengthScore * 10) {
            return 20;
        }
        return carriedPounds > strengthScore * 5 ? 10 : 0;
    }
}
