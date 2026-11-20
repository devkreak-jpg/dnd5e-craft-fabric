package net.dnd5ecraft.entity;

import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;
import net.dnd5ecraft.entity.BestiaryPreset.Feature;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.equipment.DamageType5e;

public final class BestiaryTurnState {
    public record Snapshot(boolean breathReady, boolean regenerationSuppressed, int legendaryResistances,
                           int legendaryActions, boolean lairEnabled, boolean bloodied) {
        public Snapshot(boolean breathReady, boolean regenerationSuppressed, int legendaryResistances,
                int legendaryActions, boolean lairEnabled) {
            this(breathReady, regenerationSuppressed, legendaryResistances, legendaryActions, lairEnabled, false);
        }
    }

    private final BestiaryPreset preset;
    private boolean breathReady;
    private boolean regenerationSuppressed;
    private int legendaryResistances;
    private int legendaryActions;
    private boolean lairEnabled;
    private boolean bloodied;

    public BestiaryTurnState(BestiaryPreset preset) {
        this(preset, new Snapshot(preset.features().contains(Feature.BREATH_RECHARGE), false,
                preset.legendaryLimit(), preset.legendaryLimit(), false));
    }

    public BestiaryTurnState(BestiaryPreset preset, Snapshot saved) {
        this.preset = Objects.requireNonNull(preset);
        int cap = preset.legendaryLimit();
        if (saved.legendaryResistances < 0 || saved.legendaryResistances > cap
                || saved.legendaryActions < 0 || saved.legendaryActions > cap
                || (saved.breathReady && !has(Feature.BREATH_RECHARGE))
                || (saved.regenerationSuppressed && !has(Feature.REGENERATION))
                || (saved.lairEnabled && !has(Feature.LAIR_ACTION))) {
            throw new IllegalArgumentException("Invalid saved bestiary resources");
        }
        breathReady = saved.breathReady;
        regenerationSuppressed = saved.regenerationSuppressed;
        legendaryResistances = saved.legendaryResistances;
        legendaryActions = saved.legendaryActions;
        lairEnabled = saved.lairEnabled;
        bloodied = saved.bloodied;
    }

    public BestiaryPreset preset() { return preset; }
    public boolean has(Feature feature) { return preset.features().contains(feature); }
    public boolean canUseBreath() { return breathReady; }
    public boolean isInLair() { return lairEnabled; }
    public int legendaryActions() { return legendaryActions; }

    public void setLairEnabled(boolean enabled) {
        if (enabled && !has(Feature.LAIR_ACTION)) throw new IllegalArgumentException("Preset has no lair action");
        lairEnabled = enabled;
    }

    public int beginTurn(int currentHp, boolean environmentBlocksRegeneration, IntUnaryOperator roller) {
        if (preset != BestiaryPreset.CAPTAIN_MORGAN) renewLegendaryActions();
        if (preset != BestiaryPreset.DEATH_KNIGHT && has(Feature.BREATH_RECHARGE) && !breathReady) {
            breathReady = AbilityCheckEngine.rollDie(6, roller) >= 5;
        }
        boolean regenerates = !regenerationSuppressed && !environmentBlocksRegeneration
                && (currentHp > 0 || preset == BestiaryPreset.TROLL);
        regenerationSuppressed = false;
        return regenerates ? preset.regeneration() : 0;
    }

    public void renewLegendaryActions() { legendaryActions = preset.legendaryLimit(); }

    public boolean enterBloodied(int hp) {
        if (preset != BestiaryPreset.CAPTAIN_MORGAN || bloodied || hp <= 0 || (long) hp * 2 > preset.maxHp()) return false;
        bloodied = true;
        breathReady = true;
        return true;
    }

    public void onDamage(Map<DamageType5e, Integer> damage) {
        if (preset.suppressesRegeneration().stream().anyMatch(type -> damage.getOrDefault(type, 0) > 0)) {
            regenerationSuppressed = true;
        }
    }

    public boolean spendBreath() {
        if (!breathReady) return false;
        breathReady = false;
        return true;
    }

    public boolean useLegendaryResistance() {
        if (legendaryResistances == 0) return false;
        legendaryResistances--;
        return true;
    }

    public boolean spendLegendaryAction() {
        if (legendaryActions == 0) return false;
        legendaryActions--;
        return true;
    }

    public void recoverDailyResources() {
        legendaryResistances = preset.legendaryLimit();
        if (preset == BestiaryPreset.DEATH_KNIGHT) breathReady = true;
    }

    public Snapshot snapshot() {
        return new Snapshot(breathReady, regenerationSuppressed, legendaryResistances, legendaryActions, lairEnabled, bloodied);
    }
}
