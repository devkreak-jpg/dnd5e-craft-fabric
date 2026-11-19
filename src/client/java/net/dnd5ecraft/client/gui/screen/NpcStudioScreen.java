package net.dnd5ecraft.client.gui.screen;

import java.util.ArrayList;
import java.util.List;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.network.PacketCodec;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.Protocol.DialogueOption;
import net.dnd5ecraft.network.Protocol.Npc;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class NpcStudioScreen extends DndScreen {
    private final Npc original;
    private final List<DialogueOption> options;
    private String displayName;
    private String greeting;
    private BestiaryPreset preset;
    private NpcAttitude attitude;
    private boolean lair;
    private int option = -1;

    public NpcStudioScreen(Npc npc) {
        super(text("npc.title"));
        original = npc;
        displayName = npc.name();
        greeting = npc.greeting();
        preset = npc.preset();
        attitude = npc.attitude();
        lair = npc.lair();
        options = new ArrayList<>(npc.opening());
    }

    @Override protected void controls() {
        int x = left + 12;
        int w = innerWidth();
        int half = (w - 8) / 2;
        if (option == -1) {
            edit("field.name", displayName, x, top + 43, w, 80).setResponder(value -> displayName = value);
            button(Component.translatable("entity.dnd-5e-craft.npc." + preset.id()), x, top + 69, half, () -> {
                var presets = BestiaryPreset.values();
                preset = presets[(preset.ordinal() + 1) % presets.length];
                if (!preset.features().contains(BestiaryPreset.Feature.LAIR_ACTION)) lair = false;
                rebuild();
            });
            cycle("attitude", attitude, NpcAttitude.values(), x + half + 8, top + 69, half, value -> { attitude = value; rebuild(); });
            button(text("npc.lair", text(lair ? "on" : "off")), x, top + 95, half, () -> { lair = !lair; rebuild(); }).active
                    = preset.features().contains(BestiaryPreset.Feature.LAIR_ACTION);
            button(text("npc.options"), x + half + 8, top + 95, half, () -> { option = 0; rebuild(); }).active = !options.isEmpty();
            edit("npc.greeting", greeting, x, top + 129, w, 1024).setResponder(value -> greeting = value);
        } else {
            var current = options.get(option);
            edit("npc.option_label", current.label(), x, top + 43, w, 256).setResponder(label -> {
                var latest = options.get(option);
                options.set(option, new DialogueOption(latest.id(), label, latest.skill(), latest.dc()));
            });
            if (current.skill() != null) {
                button(name("skill", current.skill()), x, top + 70, half, () -> {
                    var latest = options.get(option);
                    var skills = net.dnd5ecraft.rules.ability.Skill.values();
                    options.set(option, new DialogueOption(latest.id(), latest.label(),
                            skills[(latest.skill().ordinal() + 1) % skills.length], latest.dc()));
                    rebuild();
                });
                EditBox dc = edit("npc.dc", Integer.toString(current.dc()), x + half + 8, top + 70, half, 2);
                dc.setFilter(value -> value.matches("[0-9]{0,2}"));
                dc.setResponder(value -> {
                    var latest = options.get(option);
                    int amount = value.isEmpty() ? 0 : Math.min(30, Integer.parseInt(value));
                    options.set(option, new DialogueOption(latest.id(), latest.label(), latest.skill(), amount));
                });
            }
            button(text("back"), x, top + 97, half, () -> { option = -1; rebuild(); });
            button(text("next"), x + half + 8, top + 97, half, () -> { option = (option + 1) % options.size(); rebuild(); });
        }
        button(text("close"), x, bottom() - 25, 70, this::onClose);
        button(text("save"), x + w - 84, bottom() - 25, 84, this::save).active = ClientDndState.state().operator();
    }

    private void save() {
        if (displayName.isBlank() || greeting.isBlank() || options.stream().anyMatch(choice -> choice.label().isBlank())) {
            ClientDndState.notify(text("invalid"));
            return;
        }
        ClientPacketHandlers.send(Action.NPC_SAVE, out -> {
            out.writeUUID(original.id());
            out.writeLong(original.revision());
            out.writeUtf(displayName, 80);
            PacketCodec.writeEnum(out, preset);
            PacketCodec.writeEnum(out, attitude);
            out.writeBoolean(lair);
            out.writeUtf(greeting, 1024);
            PacketCodec.writeList(out, options, PacketCodec::dialogueOption);
        });
    }

    @Override protected void content(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 12;
        if (option == -1) {
            caption(g, "field.name", x, top + 32);
            caption(g, "npc.greeting", x, top + 118);
            clipped(g, font, text("npc.stats", preset.challenge(), preset.armor(), preset.maxHp(), preset.speedFeet()),
                    x, top + 156, innerWidth(), MUTED);
        } else {
            caption(g, "npc.option_label", x, top + 32);
            if (options.get(option).skill() != null) caption(g, "npc.dc", x + (innerWidth() + 8) / 2, top + 59);
        }
    }
}
