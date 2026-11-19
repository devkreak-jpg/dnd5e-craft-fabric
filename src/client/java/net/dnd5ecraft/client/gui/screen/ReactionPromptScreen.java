package net.dnd5ecraft.client.gui.screen;

import java.util.UUID;
import net.dnd5ecraft.client.ClientActions;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.keybind.ModKeybinds;
import net.dnd5ecraft.network.Protocol.Reaction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class ReactionPromptScreen extends DndScreen {
    private final Screen previous;
    private final UUID encounter;
    private final Reaction reaction;

    public ReactionPromptScreen(Screen previous, UUID encounter, Reaction reaction) {
        super(text("hud.reaction"));
        this.previous = previous;
        this.encounter = encounter;
        this.reaction = reaction;
    }

    public Screen previous() { return previous; }

    public boolean matches(UUID id, long window) { return encounter.equals(id) && reaction.id() == window; }

    @Override protected void controls() {
        int x = left + 12;
        for (int i = 0; i < reaction.choices().size(); i++) {
            var kind = reaction.choices().get(i);
            button(name("reaction", kind), x, top + 32 + 22 * i, innerWidth(), () -> ClientActions.react(kind, hasShiftDown()));
        }
        button(text("hud.decline"), x, bottom() - 25, 100, this::onClose);
    }

    @Override public void tick() {
        var combat = ClientDndState.combat();
        if (ClientDndState.reactionRemaining() == 0 || combat == null || combat.reaction() == null
                || !matches(combat.id(), combat.reaction().id())) minecraft.setScreen(previous);
    }

    @Override protected void content(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(left + 12, bottom() - 48, left + 12 + (int) (innerWidth() * ClientDndState.reactionRemaining() / 3500),
                bottom() - 45, CRIMSON);
    }

    @Override public boolean keyPressed(int key, int scancode, int modifiers) {
        if (ModKeybinds.matchesRoll(key, scancode) && !reaction.choices().isEmpty()) {
            ClientActions.react(reaction.choices().get(0), hasShiftDown());
            return true;
        }
        return super.keyPressed(key, scancode, modifiers);
    }

    @Override public void onClose() {
        ClientActions.react(null, false);
        minecraft.setScreen(previous);
    }
}
