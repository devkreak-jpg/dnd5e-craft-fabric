package net.dnd5ecraft.combat;

import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;

public final class LightingVisionEvaluator {
    public enum Light { BRIGHT, DIM, DARK, MAGICAL_DARKNESS }
    public record Vision(boolean visible, RollMode sightCheckMode, boolean colorVisible) {}

    private LightingVisionEvaluator() {}

    public static Vision evaluate(Light light, int distanceFeet, int darkvisionFeet, boolean clearSight, boolean blinded) {
        return evaluate(light, distanceFeet, darkvisionFeet, 0, clearSight, blinded);
    }

    public static Vision evaluate(Light light, int distanceFeet, int darkvisionFeet, int devilsSightFeet,
            boolean clearSight, boolean blinded) {
        if (distanceFeet < 0 || darkvisionFeet < 0 || devilsSightFeet < 0) throw new IllegalArgumentException("Distance cannot be negative");
        if (!clearSight || blinded) return unseen();
        if ((light == Light.DARK || light == Light.MAGICAL_DARKNESS) && devilsSightFeet > 0 && distanceFeet <= devilsSightFeet) {
            return new Vision(true, RollMode.NORMAL, true);
        }
        boolean darkvision = darkvisionFeet > 0 && distanceFeet <= darkvisionFeet;
        return switch (light) {
            case BRIGHT -> new Vision(true, RollMode.NORMAL, true);
            case DIM -> new Vision(true, darkvision ? RollMode.NORMAL : RollMode.DISADVANTAGE, true);
            case DARK -> darkvision ? new Vision(true, RollMode.DISADVANTAGE, false) : unseen();
            case MAGICAL_DARKNESS -> unseen();
        };
    }

    private static Vision unseen() { return new Vision(false, RollMode.DISADVANTAGE, false); }
}
