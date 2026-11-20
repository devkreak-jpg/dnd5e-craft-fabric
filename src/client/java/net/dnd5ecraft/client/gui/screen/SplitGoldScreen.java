package net.dnd5ecraft.client.gui.screen;

import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.network.PacketPolicy.Action;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.text;

final class SplitGoldScreen extends DndScreen {
    private final DmMasterConsoleScreen parent;
    private String amount = "";

    SplitGoldScreen(DmMasterConsoleScreen parent) {
        super(text("journal.split"));
        this.parent = parent;
    }

    @Override protected int maximumHeight() { return 105; }

    @Override protected void controls() {
        var field = edit("journal.gold_amount", amount, left + 12, top + 34, innerWidth(), 5);
        field.setFilter(value -> value.chars().allMatch(ch -> ch >= '0' && ch <= '9'));
        field.setHint(text("journal.gold_amount"));
        field.setResponder(value -> amount = value);
        button(text("back"), left + 12, bottom() - 25, 65, this::onClose);
        button(text("journal.split"), left + panelWidth - 150, bottom() - 25, 138, () -> {
            int gold = amount.isEmpty() ? 0 : Integer.parseInt(amount);
            if (gold < 1 || gold > 10_000) {
                ClientDndState.notify(text("journal.gold_amount"));
                return;
            }
            if (ClientPacketHandlers.send(Action.SPLIT_GOLD, out -> out.writeVarInt(gold))) onClose();
        }).active = ClientDndState.state().operator();
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
