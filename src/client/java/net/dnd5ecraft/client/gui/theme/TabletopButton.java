package net.dnd5ecraft.client.gui.theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class TabletopButton extends Button {
    private static final int HOVER = 0xFF353040;
    private static final int SELECTED = 0xFF49351C;
    private final boolean selected;
    private Component trailing;
    private Runnable rightClick;
    private int accent = GOLD;

    public TabletopButton(int x, int y, int width, int height, Component label, boolean selected, OnPress press) {
        super(x, y, Math.max(10, width), height, label, press, DEFAULT_NARRATION);
        this.selected = selected;
    }

    public TabletopButton trailing(Component value) { trailing = value; return this; }
    public TabletopButton accent(int value) { accent = value; return this; }
    public TabletopButton rightClick(Runnable action) { rightClick = action; return this; }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (button != 1 || rightClick == null) return super.mouseClicked(x, y, button);
        if (!active || !visible || !isMouseOver(x, y)) return false;
        playDownSound(Minecraft.getInstance().getSoundManager());
        rightClick.run();
        return true;
    }

    @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean hover = active && isHoveredOrFocused();
        int border = active && (selected || hover) ? accent : BORDER;
        g.fill(x + 1, y, x + w - 1, y + h, border);
        g.fill(x, y + 2, x + w, y + h - 2, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, selected ? SELECTED : hover ? HOVER : PANEL);
        var font = Minecraft.getInstance().font;
        int ty = y + (h - 8) / 2;
        if (trailing == null) {
            int tx = x + Math.max(4, (w - font.width(getMessage())) / 2);
            clipped(g, font, getMessage(), tx, ty, x + w - tx - 4, active ? TEXT : MUTED);
        } else {
            int trailingWidth = font.width(trailing);
            clipped(g, font, getMessage(), x + 5, ty, w - trailingWidth - 14, active ? TEXT : MUTED);
            clipped(g, font, trailing, x + w - trailingWidth - 5, ty, trailingWidth, active ? accent : MUTED);
        }
    }
}
