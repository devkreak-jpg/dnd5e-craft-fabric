package net.dnd5ecraft.dialogue;

import java.util.Objects;
import java.util.Optional;
import net.dnd5ecraft.rules.ability.Skill;

public record DialogueChoice(String id, String label, Optional<Check> check, Outcome success, Outcome failure) {
    public record Check(Skill skill, int difficulty) {
        public Check {
            Objects.requireNonNull(skill);
            NpcAttitude.INDIFFERENT.adjustDifficulty(difficulty);
        }
    }

    public record Outcome(Optional<String> next, Optional<NpcAttitude> attitude) {
        public Outcome {
            Objects.requireNonNull(next);
            Objects.requireNonNull(attitude);
            next.ifPresent(DialogueChoice::requireId);
        }

        public static Outcome goTo(String id) { return new Outcome(Optional.of(id), Optional.empty()); }
        public static Outcome end() { return new Outcome(Optional.empty(), Optional.empty()); }
    }

    public DialogueChoice {
        requireId(id);
        Objects.requireNonNull(label);
        Objects.requireNonNull(check);
        Objects.requireNonNull(success);
        Objects.requireNonNull(failure);
        if (label.isBlank() || label.length() > 256 || (check.isEmpty() && !success.equals(failure))) {
            throw new IllegalArgumentException("Invalid dialogue choice: " + id);
        }
    }

    public static DialogueChoice direct(String id, String label, Outcome outcome) {
        return new DialogueChoice(id, label, Optional.empty(), outcome, outcome);
    }

    static void requireId(String value) {
        if (value == null || !value.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid dialogue ID");
    }
}
