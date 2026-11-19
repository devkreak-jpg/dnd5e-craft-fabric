package net.dnd5ecraft.dialogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.dnd5ecraft.rules.ability.Skill;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class DialogueNbt {
    private DialogueNbt() {}

    public static CompoundTag write(DialogueTree tree) {
        CompoundTag root = new CompoundTag();
        root.putString("Start", tree.start());
        ListTag nodes = new ListTag();
        for (DialogueNode node : tree.nodes()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", node.id());
            entry.putString("Text", node.text());
            ListTag choices = new ListTag();
            for (DialogueChoice choice : node.choices()) {
                CompoundTag data = new CompoundTag();
                data.putString("Id", choice.id());
                data.putString("Label", choice.label());
                choice.check().ifPresent(check -> {
                    data.putString("Skill", check.skill().name());
                    data.putInt("DC", check.difficulty());
                });
                data.put("Success", writeOutcome(choice.success()));
                data.put("Failure", writeOutcome(choice.failure()));
                choices.add(data);
            }
            entry.put("Choices", choices);
            nodes.add(entry);
        }
        root.put("Nodes", nodes);
        return root;
    }

    public static DialogueTree read(CompoundTag root) {
        ListTag nodes = root.getList("Nodes", Tag.TAG_COMPOUND);
        if (nodes.size() > 128) throw new IllegalArgumentException("Too many dialogue nodes");
        List<DialogueNode> result = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag entry = nodes.getCompound(i);
            ListTag choices = entry.getList("Choices", Tag.TAG_COMPOUND);
            if (choices.size() > 12) throw new IllegalArgumentException("Too many dialogue choices");
            List<DialogueChoice> options = new ArrayList<>();
            for (int j = 0; j < choices.size(); j++) {
                CompoundTag data = choices.getCompound(j);
                Optional<DialogueChoice.Check> check = data.contains("Skill", Tag.TAG_STRING)
                        ? Optional.of(new DialogueChoice.Check(Skill.valueOf(data.getString("Skill")), data.getInt("DC")))
                        : Optional.empty();
                options.add(new DialogueChoice(data.getString("Id"), data.getString("Label"), check,
                        readOutcome(data.getCompound("Success")), readOutcome(data.getCompound("Failure"))));
            }
            result.add(new DialogueNode(entry.getString("Id"), entry.getString("Text"), options));
        }
        return new DialogueTree(root.getString("Start"), result);
    }

    private static CompoundTag writeOutcome(DialogueChoice.Outcome outcome) {
        CompoundTag tag = new CompoundTag();
        outcome.next().ifPresent(next -> tag.putString("Next", next));
        outcome.attitude().ifPresent(attitude -> tag.putString("Attitude", attitude.name()));
        return tag;
    }

    private static DialogueChoice.Outcome readOutcome(CompoundTag tag) {
        return new DialogueChoice.Outcome(
                tag.contains("Next", Tag.TAG_STRING) ? Optional.of(tag.getString("Next")) : Optional.empty(),
                tag.contains("Attitude", Tag.TAG_STRING)
                        ? Optional.of(NpcAttitude.valueOf(tag.getString("Attitude"))) : Optional.empty());
    }
}
