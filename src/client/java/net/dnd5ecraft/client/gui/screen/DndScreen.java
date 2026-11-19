package net.dnd5ecraft.client.gui.screen;

import java.util.function.Consumer;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.gui.theme.DndUiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

abstract class DndScreen extends Screen {
    protected int left, top, panelWidth, panelHeight;

    protected DndScreen(Component title) { super(title); }

    @Override
    protected void init() {
        panelWidth = Math.min(560, width - 16);
        panelHeight = Math.min(315, height - 12);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        controls();
    }

    protected abstract void controls();

    protected final int bottom() { return top + panelHeight; }
    protected final int innerWidth() { return panelWidth - 24; }

    protected final Button button(Component label, int x, int y, int w, Runnable task) {
        return addRenderableWidget(Button.builder(label, ignored -> task.run()).bounds(x, y, Math.max(12, w), 18).build());
    }

    protected final EditBox edit(String label, String value, int x, int y, int w, int limit) {
        var input = new EditBox(font, x, y, Math.max(24, w), 18, text(label));
        input.setMaxLength(limit);
        input.setValue(value);
        addRenderableWidget(input);
        return input;
    }

    protected final <E extends Enum<E>> Button cycle(String family, E current, E[] values, int x, int y, int w,
            Consumer<E> selected) {
        return button(name(family, current), x, y, w, () -> selected.accept(values[(current.ordinal() + 1) % values.length]));
    }

    protected final void rebuild() {
        clearWidgets();
        controls();
    }

    protected final void caption(GuiGraphics g, String key, int x, int y) {
        g.drawString(font, text(key), x, y, MUTED, false);
    }

    protected void content(GuiGraphics g, int mouseX, int mouseY) {}

    @Override
    public final void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        panel(g, left, top, panelWidth, panelHeight, GOLD);
        DndUiTheme.clipped(g, font, title, left + 12, top + 12, innerWidth(), GOLD);
        content(g, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
        var notice = ClientDndState.notice();
        if (notice != null) {
            int y = bottom() - 37;
            g.fill(left + 11, y - 2, left + panelWidth - 11, y + 11, OBSIDIAN);
            DndUiTheme.clipped(g, font, notice.message(), left + 13, y, innerWidth() - 2, TEXT);
        }
    }

    @Override public boolean isPauseScreen() { return false; }
}
