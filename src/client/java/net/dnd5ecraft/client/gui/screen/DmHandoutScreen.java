package net.dnd5ecraft.client.gui.screen;

import java.util.List;
import net.dnd5ecraft.network.Protocol;
import net.dnd5ecraft.network.Protocol.Handout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class DmHandoutScreen extends DndScreen {
    private final Screen parent;
    private final Handout card;
    private String audience = "all", title = "", body = "";
    private int firstLine;
    private List<net.minecraft.util.FormattedCharSequence> lines = List.of();

    public DmHandoutScreen(Screen parent, Handout card) {
        super(card == null ? text("online.send_handout") : text("online.handout"));
        this.parent = parent;
        this.card = card;
    }

    @Override protected void controls() {
        int x = left + 12;
        if (card == null) {
            edit("online.audience", audience, x, top + 45, innerWidth(), 16).setResponder(value -> audience = value);
            edit("online.title", title, x, top + 78, innerWidth(), Protocol.HANDOUT_TITLE_LIMIT).setResponder(value -> title = value);
            edit("online.body", body, x, top + 111, innerWidth(), Protocol.HANDOUT_BODY_LIMIT).setResponder(value -> body = value);
            button(text("online.send_handout"), x + innerWidth() - 110, bottom() - 24, 110, this::send);
            button(text("back"), x, bottom() - 24, 70, this::onClose);
        } else {
            lines = font.split(Component.literal(card.body()), innerWidth() - 28);
            firstLine = Math.min(firstLine, Math.max(0, lines.size() - visibleLines()));
            button(text("online.handout_close"), x, bottom() - 24, Math.min(innerWidth(), 180), this::onClose);
        }
    }

    private void send() {
        if (minecraft.player == null || !audience.matches("[A-Za-z0-9_]{1,16}") || title.isBlank()
                || title.contains("|") || body.isBlank()) {
            net.dnd5ecraft.client.ClientDndState.notify(text("invalid"));
            return;
        }
        if (net.dnd5ecraft.client.ClientPacketHandlers.send(net.dnd5ecraft.network.PacketPolicy.Action.HANDOUT_SEND, out -> {
            out.writeUtf(audience, 16);
            net.dnd5ecraft.network.OnlineCodec.handout(out, new Handout(title, body));
        })) onClose();
    }

    private int visibleLines() { return Math.max(1, (panelHeight - 87) / 12); }

    @Override protected void content(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left + 12;
        if (card == null) {
            caption(graphics, "online.audience", x, top + 34);
            caption(graphics, "online.title", x, top + 67);
            caption(graphics, "online.body", x, top + 100);
            return;
        }
        graphics.fill(x, top + 31, x + innerWidth(), bottom() - 31, 0xFF32231D);
        graphics.renderOutline(x, top + 31, innerWidth(), panelHeight - 62, 0xFF9B7047);
        clipped(graphics, font, Component.literal(card.title()), x + 12, top + 41, innerWidth() - 24, GOLD);
        graphics.fill(x + 12, top + 54, x + innerWidth() - 12, top + 55, BORDER);
        for (int index = firstLine; index < Math.min(lines.size(), firstLine + visibleLines()); index++)
            graphics.drawString(font, lines.get(index), x + 12, top + 61 + (index - firstLine) * 12, 0xFFF0DDBC, false);
    }

    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (card == null) return super.mouseScrolled(x, y, delta);
        firstLine = net.minecraft.util.Mth.clamp(firstLine - (int)Math.signum(delta) * 3, 0, Math.max(0, lines.size() - visibleLines()));
        return true;
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
}
