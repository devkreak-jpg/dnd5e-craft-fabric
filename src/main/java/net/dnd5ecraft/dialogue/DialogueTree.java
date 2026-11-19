package net.dnd5ecraft.dialogue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.CheckResult;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.Skill;

public final class DialogueTree {
    public static final String BUILTIN_PREFIX = "dnd-5e-craft.dialogue.builtin.";
    public record Selection(DialogueChoice.Outcome outcome, Optional<CheckResult> check) {}

    private final String start;
    private final Map<String, DialogueNode> nodes;

    public DialogueTree(String start, List<DialogueNode> nodes) {
        DialogueChoice.requireId(start);
        if (nodes.isEmpty() || nodes.size() > 128) throw new IllegalArgumentException("Invalid number of dialogue nodes");
        Map<String, DialogueNode> indexed = new LinkedHashMap<>();
        for (DialogueNode node : nodes) {
            if (indexed.putIfAbsent(node.id(), node) != null) throw new IllegalArgumentException("Duplicate node: " + node.id());
        }
        if (!indexed.containsKey(start)) throw new IllegalArgumentException("Missing dialogue start");
        for (DialogueNode node : indexed.values()) {
            for (DialogueChoice choice : node.choices()) {
                requireDestination(indexed, choice.success());
                requireDestination(indexed, choice.failure());
            }
        }
        this.start = start;
        this.nodes = java.util.Collections.unmodifiableMap(indexed);
    }

    private static void requireDestination(Map<String, DialogueNode> nodes, DialogueChoice.Outcome outcome) {
        outcome.next().ifPresent(next -> {
            if (!nodes.containsKey(next)) throw new IllegalArgumentException("Missing dialogue node: " + next);
        });
    }

    public String start() { return start; }
    public List<DialogueNode> nodes() { return List.copyOf(nodes.values()); }

    public DialogueNode node(String id) {
        DialogueNode node = nodes.get(id);
        if (node == null) throw new IllegalArgumentException("Unknown dialogue node: " + id);
        return node;
    }

    public Selection choose(String nodeId, String choiceId, NpcAttitude attitude,
            ToIntFunction<Skill> modifier, RollMode mode, IntUnaryOperator roller) {
        DialogueChoice choice = node(nodeId).choices().stream().filter(option -> option.id().equals(choiceId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Choice is unavailable at this node"));
        if (choice.check().isEmpty()) return new Selection(choice.success(), Optional.empty());
        DialogueChoice.Check check = choice.check().orElseThrow();
        CheckResult result = AbilityCheckEngine.check(modifier.applyAsInt(check.skill()),
                attitude.adjustDifficulty(check.difficulty()), mode, roller);
        return new Selection(result.succeeds() ? choice.success() : choice.failure(), Optional.of(result));
    }

    public static DialogueTree defaultTree() {
        var success = new DialogueChoice.Outcome(Optional.of("welcome"), Optional.of(NpcAttitude.FRIENDLY));
        return new DialogueTree("meeting", List.of(
                new DialogueNode("meeting", BUILTIN_PREFIX + "stranger.greeting", List.of(
                        new DialogueChoice("parley", BUILTIN_PREFIX + "stranger.parley", Optional.of(new DialogueChoice.Check(Skill.PERSUASION, 13)),
                                success, DialogueChoice.Outcome.goTo("refusal")),
                        DialogueChoice.direct("leave", BUILTIN_PREFIX + "leave", DialogueChoice.Outcome.end()))),
                new DialogueNode("welcome", BUILTIN_PREFIX + "stranger.welcome", List.of()),
                new DialogueNode("refusal", BUILTIN_PREFIX + "stranger.refusal", List.of())));
    }

    /** Role-specific starter dialogue. Both choices lead to valid, distinct branches. */
    public static DialogueTree civilian(String role) {
        DialogueChoice.requireId(role);
        String key = BUILTIN_PREFIX + role + ".";
        return new DialogueTree("meeting", List.of(
                new DialogueNode("meeting", key + "greeting", List.of(
                        DialogueChoice.direct("service", key + "service", DialogueChoice.Outcome.goTo("offer")),
                        DialogueChoice.direct("news", key + "news", DialogueChoice.Outcome.goTo("rumor")),
                        DialogueChoice.direct("leave", BUILTIN_PREFIX + "leave", DialogueChoice.Outcome.end()))),
                new DialogueNode("offer", key + "offer", List.of(
                        DialogueChoice.direct("back", BUILTIN_PREFIX + "back", DialogueChoice.Outcome.goTo("meeting")))),
                new DialogueNode("rumor", key + "rumor", List.of(
                        DialogueChoice.direct("back", BUILTIN_PREFIX + "back", DialogueChoice.Outcome.goTo("meeting"))))));
    }
}
