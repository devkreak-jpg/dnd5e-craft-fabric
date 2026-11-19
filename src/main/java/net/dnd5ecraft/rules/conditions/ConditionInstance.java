package net.dnd5ecraft.rules.conditions;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ConditionInstance(ConditionType type, Optional<UUID> source, int roundsRemaining) {
    public static final int INDEFINITE = -1;

    public ConditionInstance {
        Objects.requireNonNull(type);
        Objects.requireNonNull(source);
        if (roundsRemaining != INDEFINITE && roundsRemaining < 1) {
            throw new IllegalArgumentException("A condition must last at least one round or be indefinite");
        }
        if (type == ConditionType.EXHAUSTION) {
            throw new IllegalArgumentException("Record exhaustion as a level");
        }
    }

    public Optional<ConditionInstance> advanceRound() {
        return switch (roundsRemaining) {
            case INDEFINITE -> Optional.of(this);
            case 1 -> Optional.empty();
            default -> Optional.of(new ConditionInstance(type, source, roundsRemaining - 1));
        };
    }

    public boolean endsAfterGrapplerChanges(boolean grapplerCanAct, boolean withinGrapplerReach) {
        return type == ConditionType.GRAPPLED && (!grapplerCanAct || !withinGrapplerReach);
    }

    public boolean comesFrom(UUID entity) {
        return source.filter(entity::equals).isPresent();
    }
}
