package net.dnd5ecraft.client.gui.screen;

import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.client.TabletopActions;
import net.dnd5ecraft.client.keybind.ModKeybinds;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.ability.TabletopRolls.Kind;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class ActionWheelScreen extends Screen {
    private static final int SIZE = 8;

    public ActionWheelScreen() { super(text("campaign.wheel")); }

    private int selected(double x, double y) {
        double dx = x - width / 2.0;
        double dy = y - height / 2.0;
        if (Math.hypot(dx, dy) < 20) return -1;
        return Math.floorMod((int) Math.round(Math.atan2(dx, -dy) / (Math.PI / 4)), SIZE);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);
        int selected = selected(mouseX, mouseY);
        int rx = Math.min(131, Math.max(45, (width - 104) / 2));
        int ry = Math.min(84, Math.max(36, (height - 32) / 2));
        int card = Math.min(106, width / 3 - 5);
        for (int i = 0; i < SIZE; i++) {
            double angle = i * Math.PI / 4;
            int x = width / 2 + (int) (rx * Math.sin(angle)) - card / 2;
            int y = height / 2 - (int) (ry * Math.cos(angle)) - 10;
            panel(g, x, y, card, 20, i == selected ? GOLD : BORDER);
            clipped(g, font, label(i), x + 5, y + 6, card - 10, i == selected ? GOLD : TEXT);
        }
        g.drawCenteredString(font, getTitle(), width / 2, height / 2 - 4, GOLD);
    }

    private Component label(int index) {
        return switch (index) {
            case 0 -> name("skill", Skill.PERCEPTION);
            case 1 -> name("skill", Skill.STEALTH);
            case 2 -> name("skill", Skill.INVESTIGATION);
            case 3 -> text("campaign.initiative");
            case 4 -> text("sheet.short_rest");
            case 5 -> text("sheet.title");
            case 6 -> text("spellbook.title");
            default -> text(Screen.hasShiftDown() ? "campaign.kneel" : "campaign.sit");
        };
    }

    private void choose(int index) {
        minecraft.setScreen(null);
        if (minecraft.player == null) return;
        switch (index) {
            case 0 -> roll(Skill.PERCEPTION);
            case 1 -> roll(Skill.STEALTH);
            case 2 -> roll(Skill.INVESTIGATION);
            case 3 -> TabletopActions.roll(new Check(Kind.INITIATIVE, ""), false);
            case 4 -> {
                if (ClientDndState.tabletop()) ClientPacketHandlers.send(Action.TABLETOP_REST);
                else ClientPacketHandlers.send(Action.REST, out -> out.writeBoolean(false));
            }
            case 5 -> ClientPacketHandlers.openSheet();
            case 6 -> minecraft.setScreen(new SpellbookScreen());
            case 7 -> ClientPacketHandlers.send(Action.CAMPAIGN_POSE, out -> out.writeBoolean(Screen.hasShiftDown()));
            default -> { }
        }
    }

    private static void roll(Skill skill) { TabletopActions.roll(new Check(Kind.SKILL, skill.name()), false); }

    @Override public boolean keyReleased(int key, int scancode, int modifiers) {
        if (!ModKeybinds.matchesWheel(key, scancode)) return super.keyReleased(key, scancode, modifiers);
        var window = minecraft.getWindow();
        choose(selected(minecraft.mouseHandler.xpos() * width / window.getScreenWidth(),
                minecraft.mouseHandler.ypos() * height / window.getScreenHeight()));
        return true;
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) return super.mouseClicked(x, y, button);
        choose(selected(x, y));
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }
}
