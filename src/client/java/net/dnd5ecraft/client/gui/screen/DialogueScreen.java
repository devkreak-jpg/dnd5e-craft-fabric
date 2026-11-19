package net.dnd5ecraft.client.gui.screen;

import java.util.UUID;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.dialogue.DialogueText;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.Protocol.Dialogue;
import net.minecraft.client.gui.GuiGraphics;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class DialogueScreen extends DndScreen {
    private final Dialogue dialogue;
    private int page;
    private int scroll;

    public DialogueScreen(Dialogue dialogue) {
        super(DialogueText.title(dialogue.title()));
        this.dialogue = dialogue;
    }

    public UUID token() { return dialogue.token(); }
    private int visibleOptions() { return Math.max(1, (panelHeight - 122) / 22); }

    @Override protected void controls() {
        int x = left + 12;
        int maxPage = Math.max(0, (dialogue.options().size() - 1) / visibleOptions());
        page = Math.max(0, Math.min(page, maxPage));
        for (int i = 0; i < visibleOptions(); i++) {
            int index = page * visibleOptions() + i;
            if (index >= dialogue.options().size()) break;
            var option = dialogue.options().get(index);
            var label = option.skill() == null ? DialogueText.component(option.label())
                    : text("dialogue.checked", DialogueText.component(option.label()), name("skill", option.skill()), option.dc());
            button(label, x, top + 79 + i * 22, innerWidth(), () -> ClientPacketHandlers.send(Action.CHOOSE, buf -> {
                buf.writeUUID(token());
                buf.writeUtf(option.id(), 64);
            }));
        }
        button(text("close"), x, bottom() - 25, 70, this::onClose);
        if (dialogue.options().size() > visibleOptions()) {
            button(text("previous"), x + innerWidth() - 112, bottom() - 25, 54, () -> { page--; rebuild(); }).active = page > 0;
            button(text("next"), x + innerWidth() - 54, bottom() - 25, 54, () -> { page++; rebuild(); }).active
                    = (page + 1) * visibleOptions() < dialogue.options().size();
        }
    }

    @Override protected void content(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 12;
        g.drawString(font, name("attitude", dialogue.attitude()), x, top + 27, ARCANE, false);
        var lines = font.split(DialogueText.component(dialogue.text()), innerWidth());
        for (int i = 0; i < 3 && i + scroll < lines.size(); i++) {
            g.drawString(font, lines.get(i + scroll), x, top + 42 + 10 * i, TEXT, false);
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double delta) {
        int last = Math.max(0, font.split(DialogueText.component(dialogue.text()), innerWidth()).size() - 3);
        scroll = Math.min(last, Math.max(0, scroll - Integer.signum((int) Math.signum(delta))));
        return true;
    }

    @Override public void onClose() {
        if (worldReady() && !dialogue.options().isEmpty()) {
            ClientPacketHandlers.send(Action.CLOSE_DIALOGUE, buf -> buf.writeUUID(token()));
        }
        super.onClose();
    }
}
