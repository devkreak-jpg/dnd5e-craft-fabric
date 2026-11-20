package net.dnd5ecraft.dialogue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record DialogueNode(String id, String text, List<DialogueChoice> choices) {
    public DialogueNode {
        DialogueChoice.requireId(id);
        choices = List.copyOf(choices);
        if (text.isBlank() || text.length() > 1024 || choices.size() > 12) {
            throw new IllegalArgumentException("Invalid dialogue node: " + id);
        }
        Set<String> ids = new HashSet<>();
        if (choices.stream().anyMatch(choice -> !ids.add(choice.id()))) {
            throw new IllegalArgumentException("Duplicate choice in " + id);
        }
    }
}
