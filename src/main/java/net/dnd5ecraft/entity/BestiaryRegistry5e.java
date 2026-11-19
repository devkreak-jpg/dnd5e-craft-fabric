package net.dnd5ecraft.entity;

import java.util.Arrays;
import java.util.List;
import net.dnd5ecraft.dialogue.DialogueTree;

import static net.dnd5ecraft.entity.BestiaryPreset.*;

public final class BestiaryRegistry5e {
    private static final List<BestiaryPreset> PRESETS = List.of(GOBLIN, KOBOLD, SKELETON, ZOMBIE, WOLF, DIRE_WOLF,
            ORC, BANDIT_CAPTAIN, BUGBEAR, GHOUL, OGRE, OWLBEAR, TROLL, VAMPIRE_SPAWN, YOUNG_RED_DRAGON);

    private static final List<BestiaryPreset> CIVILIANS = List.of(TAVERN_KEEPER, MERCHANT, TOWN_GUARD);
    private static final List<BestiaryPreset> SPAWNABLE = List.of(BestiaryPreset.values());
    private static final List<BestiaryPreset> AMBUSHERS = Arrays.stream(BestiaryPreset.values())
            .filter(preset -> !preset.civilian()).toList();

    private BestiaryRegistry5e() {}

    public static List<BestiaryPreset> presets() { return PRESETS; }
    public static List<BestiaryPreset> civilians() { return CIVILIANS; }
    public static List<BestiaryPreset> spawnable() { return SPAWNABLE; }
    public static List<BestiaryPreset> ambushers() { return AMBUSHERS; }

    public static DialogueTree starterDialogue(BestiaryPreset preset) {
        return preset.civilian() ? DialogueTree.civilian(preset.id()) : DialogueTree.defaultTree();
    }

    public static DialogueTree dialogueAfterPresetChange(BestiaryPreset previous, BestiaryPreset next, DialogueTree dialogue) {
        if (previous == next) return dialogue;
        var starter = starterDialogue(previous);
        return dialogue.start().equals(starter.start()) && dialogue.nodes().equals(starter.nodes())
                ? starterDialogue(next) : dialogue;
    }
}
