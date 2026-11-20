package net.dnd5ecraft.client.gui.theme;

import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class DndUiTheme {
    public static final int OBSIDIAN = 0xFF0A0B0E;
    public static final int SLATE = 0xFF12141C;
    public static final int PANEL = 0xFF202431;
    public static final int BORDER = 0xFF3C414E;
    public static final int TEXT = 0xFFF5EDE3;
    public static final int MUTED = 0xFFB5B6C0;
    public static final int GOLD = 0xFFF59E0B;
    public static final int CRIMSON = 0xFFEF4444;
    public static final int EMERALD = 0xFF10B981;
    public static final int ARCANE = 0xFF38BDF8;
    public static final int SPACING = 8;

    private DndUiTheme() {}

    public static final class UiAnim {
        private UiAnim() {}

        public static double easeOut(long started, int duration) {
            double elapsed = Math.max(0, Math.min(1, (net.minecraft.Util.getMillis() - started) / (double) duration));
            return 1 - Math.pow(1 - elapsed, 3);
        }

        public static int color(int from, int to, double fraction) {
            int result = 0;
            for (int shift = 0; shift <= 24; shift += 8) {
                int base = from >>> shift & 255;
                int end = to >>> shift & 255;
                result |= (int) Math.round(base + (end - base) * fraction) << shift;
            }
            return result;
        }
    }

    public static Component text(String key, Object... args) { return Component.translatable("dnd-5e-craft.ui." + key, args); }

    public static Component name(String family, Enum<?> element) {
        return text(family + "." + element.name().toLowerCase(Locale.ROOT));
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h, int line) {
        g.fill(x, y, x + w, y + h, SLATE);
        if (line == GOLD) drawGoldBorder(g, x, y, w, h);
        else g.renderOutline(x, y, w, h, BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + 3, line);
    }

    public static void drawGoldBorder(GuiGraphics g, int x, int y, int w, int h) {
        g.renderOutline(x, y, w, h, GOLD);
    }

    public static void drawProgressBar(GuiGraphics g, int x, int y, int w, int h, double ratio, int color) {
        g.fill(x, y, x + w, y + h, BORDER);
        int completed = (int) Math.round(w * Math.max(0, Math.min(1, ratio)));
        if (completed > 0) g.fill(x, y, x + completed, y + h, color);
    }

    public static void clipped(GuiGraphics g, Font font, Component value, int x, int y, int width, int color) {
        if (width <= 0) return;
        if (font.width(value) <= width) {
            g.drawString(font, value, x, y, color, false);
            return;
        }
        int available = width - font.width("…");
        if (available < 0) return;
        String prefix = font.plainSubstrByWidth(value.getString(), available);
        g.drawString(font, Component.literal(prefix + "…").withStyle(value.getStyle()), x, y, color, false);
    }
}
