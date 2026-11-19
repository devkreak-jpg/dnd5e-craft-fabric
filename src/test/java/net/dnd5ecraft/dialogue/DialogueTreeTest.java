package net.dnd5ecraft.dialogue;

import java.util.List;
import java.util.Optional;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.Skill;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DialogueTreeTest {
    @Test
    void relationshipsChangeTheDifficultyAndSuccessfulBranchesCanChangeAttitude() {
        DialogueTree tree = DialogueTree.defaultTree();
        var friendly = tree.choose(tree.start(), "parley", NpcAttitude.FRIENDLY, ability -> {
            assertEquals(Skill.PERSUASION, ability);
            return 1;
        }, RollMode.NORMAL, sides -> 10);
        assertEquals(8, friendly.check().orElseThrow().difficulty());
        assertEquals(Optional.of("welcome"), friendly.outcome().next());
        assertEquals(Optional.of(NpcAttitude.FRIENDLY), friendly.outcome().attitude());

        var hostile = tree.choose(tree.start(), "parley", NpcAttitude.HOSTILE, skill -> 1, RollMode.NORMAL, sides -> 10);
        assertEquals(18, hostile.check().orElseThrow().difficulty());
        assertEquals(Optional.of("refusal"), hostile.outcome().next());
        assertTrue(hostile.outcome().attitude().isEmpty());
        assertEquals(0, NpcAttitude.FRIENDLY.adjustDifficulty(3));
        assertEquals(30, NpcAttitude.HOSTILE.adjustDifficulty(28));
    }

    @Test
    void directOptionsDontRollAndGraphValidationRejectsStaleOrAmbiguousChoices() {
        DialogueTree tree = DialogueTree.defaultTree();
        var ended = tree.choose(tree.start(), "leave", NpcAttitude.INDIFFERENT,
                skill -> { throw new AssertionError("Direct option must not check skills"); },
                RollMode.NORMAL, sides -> { throw new AssertionError("Direct option must not roll"); });
        assertTrue(ended.check().isEmpty());
        assertTrue(ended.outcome().next().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> tree.choose("welcome", "leave", NpcAttitude.FRIENDLY,
                skill -> 0, RollMode.NORMAL, sides -> 1));
        var exit = DialogueChoice.direct("leave", "Leave", DialogueChoice.Outcome.goTo("absent"));
        assertThrows(IllegalArgumentException.class, () -> new DialogueNode("start", "Hello", List.of(exit, exit)));
        assertThrows(IllegalArgumentException.class, () -> new DialogueTree("start",
                List.of(new DialogueNode("start", "Hello", List.of(exit)))));
    }

    @Test
    void dialogueNbtRoundTripPreservesPresentationAndBranches() {
        DialogueTree tree = DialogueTree.defaultTree();
        DialogueTree decoded = DialogueNbt.read(DialogueNbt.write(tree));
        assertEquals(tree.start(), decoded.start());
        assertEquals(tree.nodes(), decoded.nodes());
    }
}
