package net.dnd5ecraft.dialogue;

import net.minecraft.network.chat.Component;

public final class DialogueText {
    public static final String NPC_NAME_PREFIX = "entity.dnd-5e-craft.npc.";

    private DialogueText() {}

    public static Component title(String text) {
        return text.startsWith(NPC_NAME_PREFIX) ? Component.translatable(text) : Component.literal(text);
    }

    public static Component component(String text) {
        return text.startsWith(DialogueTree.BUILTIN_PREFIX) ? Component.translatable(text) : Component.literal(text);
    }
}
