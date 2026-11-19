package net.dnd5ecraft.rules.conditions;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Defenses;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.equipment.ExhaustionLevel;

import static net.dnd5ecraft.rules.conditions.ConditionType.*;

public record ConditionEffectApplier(Set<ConditionType> conditions, ExhaustionLevel exhaustion) {
    public enum Sense { NONE, SIGHT, HEARING }
    public record RollEffect(RollMode mode, boolean automaticFailure) {}
    public record AttackEffect(RollMode mode, boolean permitted, boolean criticalOnHit) {}

    public ConditionEffectApplier {
        conditions = Set.copyOf(conditions);
        Objects.requireNonNull(exhaustion);
    }

    public boolean has(ConditionType condition) {
        return condition == EXHAUSTION ? exhaustion != ExhaustionLevel.NONE : conditions.contains(condition);
    }

    public boolean canAct() {
        return !has(INCAPACITATED) && !has(PARALYZED) && !has(PETRIFIED)
                && !has(STUNNED) && !has(UNCONSCIOUS) && !exhaustion.dead();
    }

    public boolean canReact() {
        return canAct();
    }

    public boolean canSpeak() {
        return !has(PARALYZED) && !has(PETRIFIED) && !has(UNCONSCIOUS) && !exhaustion.dead();
    }

    public boolean speechIsFaltering() {
        return canSpeak() && has(STUNNED);
    }

    public boolean awareOfSurroundings() {
        return !has(UNCONSCIOUS) && !has(PETRIFIED) && !exhaustion.dead();
    }

    public boolean dropsHeldItems() {
        return has(UNCONSCIOUS);
    }

    public boolean fallsProne() {
        return has(UNCONSCIOUS);
    }

    public int weightMultiplier() {
        return has(PETRIFIED) ? 10 : 1;
    }

    public int speedFeet(int baseSpeed) {
        int effectiveSpeed = exhaustion.speedFeet(baseSpeed);
        return has(GRAPPLED) || has(RESTRAINED) || has(PARALYZED) || has(PETRIFIED)
                || has(STUNNED) || has(UNCONSCIOUS) ? 0 : effectiveSpeed;
    }

    public boolean mustCrawl() {
        return has(PRONE) || fallsProne();
    }

    public int standingCostFeet(int speedFeet) {
        int effectiveSpeed = speedFeet(speedFeet);
        if (!mustCrawl()) {
            return 0;
        }
        if (effectiveSpeed == 0) {
            throw new IllegalStateException("Cannot stand at zero speed");
        }
        return effectiveSpeed / 2;
    }

    public int crawlCostPerFoot() {
        return mustCrawl() ? 2 : 1;
    }

    public boolean canApproachFrightSource() {
        return !has(FRIGHTENED);
    }

    public RollEffect abilityCheck(Sense requiredSense, boolean frightSourceInSight,
            boolean advantage, boolean disadvantage) {
        boolean autoFail = (has(BLINDED) && requiredSense == Sense.SIGHT)
                || (has(DEAFENED) && requiredSense == Sense.HEARING);
        return new RollEffect(
                RollMode.from(advantage, disadvantage || exhaustion.abilityCheckDisadvantage()
                        || has(POISONED) || (has(FRIGHTENED) && frightSourceInSight)),
                autoFail);
    }

    public boolean grantsSocialAdvantageToCharmer(boolean interactingWithCharmer) {
        return has(CHARMED) && interactingWithCharmer;
    }

    public boolean canTargetWithHarmfulEffect(boolean targetsCharmer) {
        return !(has(CHARMED) && targetsCharmer);
    }

    public RollEffect savingThrow(Ability ability, boolean advantage, boolean disadvantage) {
        boolean physical = ability == Ability.STRENGTH || ability == Ability.DEXTERITY;
        boolean autoFail = physical && (has(PARALYZED) || has(PETRIFIED) || has(STUNNED) || has(UNCONSCIOUS));
        return new RollEffect(RollMode.from(advantage, disadvantage || exhaustion.savingThrowDisadvantage()
                || (ability == Ability.DEXTERITY && has(RESTRAINED))), autoFail);
    }

    public AttackEffect attackAgainst(ConditionEffectApplier defender, int distanceFeet,
            boolean canSeeDefender, boolean defenderCanSeeAttacker, boolean targetsCharmer,
            boolean frightSourceInSight, boolean advantage, boolean disadvantage) {
        if (distanceFeet < 0) {
            throw new IllegalArgumentException("Distance cannot be negative");
        }
        boolean close = distanceFeet <= 5;
        boolean defenderProne = defender.mustCrawl();
        boolean ignoredDefense = defender.has(PARALYZED) || defender.has(PETRIFIED)
                || defender.has(STUNNED) || defender.has(UNCONSCIOUS);
        RollMode mode = RollMode.from(
                advantage || !defenderCanSeeAttacker || defender.has(BLINDED)
                        || defender.has(RESTRAINED) || ignoredDefense || (defenderProne && close),
                disadvantage || !canSeeDefender || has(BLINDED) || mustCrawl() || has(RESTRAINED)
                        || has(POISONED) || exhaustion.attackDisadvantage()
                        || (has(FRIGHTENED) && frightSourceInSight) || (defenderProne && !close));
        return new AttackEffect(mode, canAct() && canTargetWithHarmfulEffect(targetsCharmer),
                close && (defender.has(PARALYZED) || defender.has(UNCONSCIOUS)));
    }

    public boolean visiblyPresent(boolean seesInvisible) {
        return !has(INVISIBLE) || seesInvisible;
    }

    public boolean canAcquirePoisonOrDisease() {
        return !has(PETRIFIED);
    }

    public Defenses adjustDefenses(Defenses base) {
        return has(PETRIFIED)
                ? new Defenses(base.immunities(), EnumSet.allOf(DamageType5e.class), base.vulnerabilities())
                : base;
    }
}
