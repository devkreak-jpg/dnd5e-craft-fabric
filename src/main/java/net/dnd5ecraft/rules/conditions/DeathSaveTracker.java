package net.dnd5ecraft.rules.conditions;

import java.util.Objects;
import net.dnd5ecraft.rules.equipment.DamagePipeline;
import net.dnd5ecraft.rules.equipment.DamagePipeline.HitPoints;

public record DeathSaveTracker(State state, int successes, int failures) {
    public enum State { CONSCIOUS, DYING, STABLE, DEAD }
    public record Outcome(DeathSaveTracker tracker, int hitPointsRestored) {}

    public static final DeathSaveTracker CONSCIOUS = new DeathSaveTracker(State.CONSCIOUS, 0, 0);
    public static final DeathSaveTracker DYING = new DeathSaveTracker(State.DYING, 0, 0);
    public static final DeathSaveTracker STABLE = new DeathSaveTracker(State.STABLE, 0, 0);
    public static final DeathSaveTracker DEAD = new DeathSaveTracker(State.DEAD, 0, 0);

    public DeathSaveTracker {
        Objects.requireNonNull(state);
        if (successes < 0 || successes > 2 || failures < 0 || failures > 2
                || (state != State.DYING && (successes != 0 || failures != 0))) {
            throw new IllegalArgumentException("Invalid death save counts");
        }
    }

    public Outcome roll(int naturalD20, int modifier) {
        if (naturalD20 < 1 || naturalD20 > 20) {
            throw new IllegalArgumentException("A death save requires a d20 roll");
        }
        if (state != State.DYING) {
            throw new IllegalStateException("Only a dying creature rolls death saves");
        }
        if (naturalD20 == 20) {
            return new Outcome(CONSCIOUS, 1);
        }
        if (naturalD20 == 1) {
            return new Outcome(addFailures(2), 0);
        }
        if ((long) naturalD20 + modifier >= 10) {
            return new Outcome(successes == 2 ? STABLE : new DeathSaveTracker(state, successes + 1, failures), 0);
        }
        return new Outcome(addFailures(1), 0);
    }

    public DeathSaveTracker afterDamage(HitPoints before, DamagePipeline.Result damage, boolean critical) {
        if (damage.enduranceUsed()) return CONSCIOUS;
        int relevantDamage = before.current() == 0 ? damage.damageTaken()
                : Math.addExact(damage.hitPointsLost(), damage.excessDamage());
        return afterDamage(before.current(), relevantDamage, before.maximum(), critical);
    }

    private DeathSaveTracker afterDamage(int previousHitPoints, int relevantDamage,
            int maximumHitPoints, boolean critical) {
        if (previousHitPoints < 0 || relevantDamage < 0 || maximumHitPoints < previousHitPoints
                || maximumHitPoints < 1) {
            throw new IllegalArgumentException("Invalid damage or hit points");
        }
        if (state == State.DEAD || relevantDamage == 0
                || relevantDamage < previousHitPoints) {
            return this;
        }
        if (relevantDamage - previousHitPoints >= maximumHitPoints) {
            return DEAD;
        }
        if (previousHitPoints > 0) {
            return DYING;
        }
        return (state == State.DYING ? this : DYING).addFailures(critical ? 2 : 1);
    }

    public DeathSaveTracker medicine(int total) { return total >= 10 ? stabilize() : this; }

    public DeathSaveTracker stabilize() {
        return state == State.DYING ? STABLE : this;
    }

    public Outcome recoverAfterHours(int hoursElapsed, int requiredHours) {
        if (hoursElapsed < 0 || requiredHours < 1 || requiredHours > 4) {
            throw new IllegalArgumentException("Stable recovery takes a rolled 1d4 hours");
        }
        return state == State.STABLE && hoursElapsed >= requiredHours
                ? new Outcome(CONSCIOUS, 1) : new Outcome(this, 0);
    }

    public DeathSaveTracker afterHealing(int currentHitPoints) {
        if (currentHitPoints < 0) {
            throw new IllegalArgumentException("Hit points cannot be negative");
        }
        return currentHitPoints > 0 && state != State.DEAD ? CONSCIOUS : this;
    }

    private DeathSaveTracker addFailures(int count) {
        return failures + count >= 3 ? DEAD : new DeathSaveTracker(State.DYING, successes, failures + count);
    }
}
