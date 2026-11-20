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

    private static final int INNER = 30;
    private static final String[] ICONS = {"◉", "◇", "?", "⚔", "♨", "≡", "✧", "↓"};
    private final long opened = net.minecraft.Util.getMillis();
    private int hovered = -1;
    private long changed;

    private int outer() { return Math.min(92, Math.min(width, height) / 2 - 22); }

    public ActionWheelScreen() { super(text("campaign.wheel")); }

    private int selected(double x, double y) {
        double dx = x - width / 2.0;
        double dy = y - height / 2.0;
        double radius = Math.hypot(dx, dy);
        if (radius < INNER || radius > outer() + 5) return -1;
        return Math.floorMod((int) Math.round(Math.atan2(dx, -dy) / (Math.PI / 4)), SIZE);
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        renderBackground(g);
        long now = net.minecraft.Util.getMillis();
        int selected = selected(mouseX, mouseY);
        if (hovered != selected) { hovered = selected; changed = now; }
        int outer = outer();
        var pose = g.pose();
        pose.pushPose();
        pose.translate(width / 2f, height / 2f, 0);
        float open = .76f + .24f * (float) UiAnim.easeOut(opened, 120);
        pose.scale(open, open, 1);
        sector(g, 0, INNER, 0, Math.PI * 2, 0xF0161821);
        sector(g, INNER - 3, INNER - 1, 0, Math.PI * 2, 0x885E462A);
        sector(g, INNER - 1, INNER, 0, Math.PI * 2, GOLD);
        for (int i = 0; i < SIZE; i++) {
            double angle = i * Math.PI / 4;
            double start = angle - Math.PI / 8, end = angle + Math.PI / 8;
            double radius = outer + (i == selected ? 5 * UiAnim.easeOut(changed, 110) : 0);
            sector(g, INNER, radius, start, end, 0xD5131620);
            if (i == selected) sector(g, INNER, radius, start, end, 0x55F59E0B);
            sector(g, radius - 1, radius, start, end, i == selected ? GOLD : BORDER);
            sector(g, INNER, radius, start, start + .021, BORDER);
            double mid = (INNER + radius) / 2;
            int x = (int)Math.round(mid * Math.sin(angle)), y = (int)Math.round(-mid * Math.cos(angle));
            g.drawCenteredString(font, ICONS[i], x, y - 9, i == selected ? GOLD : MUTED);
            scaledText(g, label(i), x, y + 3, (int)(mid * .72), i == selected ? GOLD : TEXT);
        }
        g.drawCenteredString(font, selected < 0 ? "✧" : ICONS[selected], 0, -17, GOLD);
        scaledText(g, selected < 0 ? getTitle() : label(selected), 0, -4, 54, TEXT);
        Component hint = ModKeybinds.wheelKey();
        if (selected >= 0) {
            if (selected < 3 && ClientDndState.sheet() != null) {
                Skill skill = switch (selected) { case 0 -> Skill.PERCEPTION; case 1 -> Skill.STEALTH; default -> Skill.INVESTIGATION; };
                int bonus = ClientDndState.sheet().skillModifier(skill);
                hint = text("wheel.check", bonus >= 0 ? "+" + bonus : Integer.toString(bonus));
            } else hint = text("wheel.hint_" + selected);
        }
        scaledText(g, hint, 0, 8, 51, GOLD);
        pose.popPose();
    }

    private void scaledText(GuiGraphics g, Component value, int x, int y, int space, int color) {
        float scale = Math.min(1f, space / (float)Math.max(1, font.width(value)));
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawCenteredString(font, value, 0, 0, color);
        g.pose().popPose();
    }

    private static void sector(GuiGraphics g, double inner, double outer, double start, double end, int color) {
        var vertices = g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        var matrix = g.pose().last().pose();
        int steps = Math.max(1, (int)Math.ceil((end - start) * 14));
        for (int i = 0; i < steps; i++) {
            double a = start + (end - start) * i / steps, b = start + (end - start) * (i + 1) / steps;
            point(vertices, matrix, inner, a, color);
            point(vertices, matrix, inner, b, color);
            point(vertices, matrix, outer, b, color);
            point(vertices, matrix, outer, a, color);
        }
        g.flush();
    }

    private static void point(com.mojang.blaze3d.vertex.VertexConsumer vertices, org.joml.Matrix4f matrix,
                              double radius, double angle, int color) {
        vertices.vertex(matrix, (float)(radius * Math.sin(angle)), (float)(-radius * Math.cos(angle)), 0)
                .color(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24).endVertex();
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
