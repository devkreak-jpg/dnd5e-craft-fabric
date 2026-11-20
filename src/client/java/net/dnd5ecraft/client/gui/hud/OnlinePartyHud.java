package net.dnd5ecraft.client.gui.hud;

import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.client.keybind.ModKeybinds;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class OnlinePartyHud {
    private static boolean visible = true;

    private OnlinePartyHud() {}

    public static void toggle() { visible = !visible; }

    public static void respond(boolean ready) {
        var check = ClientDndState.online().ready();
        if (check == null || check.complete()) return;
        ClientPacketHandlers.send(Action.READY_RESPONSE, out -> {
            out.writeUUID(check.id());
            out.writeBoolean(ready);
        });
    }

    static void render(GuiGraphics graphics, Minecraft client, boolean combat) {
        var view = ClientDndState.online();
        var check = view.ready();
        if (check != null && !check.complete() && check.members().containsKey(client.player.getUUID())) {
            int width = Math.min(260, graphics.guiWidth() - 16);
            int x = (graphics.guiWidth() - width) / 2;
            int y = combat ? 53 : 8;
            panel(graphics, x, y, width, 30, GOLD);
            var header = text("online.ready_check");
            if (!check.label().isBlank()) header = header.copy().append(" · ").append(check.label());
            clipped(graphics, client.font, header, x + 6, y + 5, width - 12, GOLD);
            var hints = text("online.ready_keys", ModKeybinds.readyKey(), ModKeybinds.needTimeKey(),
                    name("online.status", check.members().get(client.player.getUUID())));
            clipped(graphics, client.font, hints, x + 6, y + 17, width - 12, TEXT);
        }

        if (!visible) return;
        int y = check != null && !check.complete() && combat ? 88 : combat ? 55 : 42;
        int limit = graphics.guiHeight() - 76;
        int w = Math.min(110, graphics.guiWidth() / 4);
        for (var member : view.party()) {
            if (y + 19 > limit) break;
            if (member.id().equals(client.player.getUUID())) continue;
            int color = member.online() ? EMERALD : MUTED;
            panel(graphics, 5, y, w, 19, BORDER);
            graphics.fill(8, y + 5, 11, y + 8, color);
            clipped(graphics, client.font, Component.literal(member.name()), 14, y + 3, w - 15, TEXT);
            var ac = text("online.ac", member.armor());
            int acWidth = client.font.width(ac);
            drawProgressBar(graphics, 9, y + 14, Math.max(1, w - acWidth - 14), 2,
                    net.dnd5ecraft.network.CombatHudView.healthFraction(member.hp(), member.maximum()), color);
            graphics.drawString(client.font, ac, 7 + w - acWidth - 5, y + 10, MUTED);
            y += 21;
        }
    }
}
