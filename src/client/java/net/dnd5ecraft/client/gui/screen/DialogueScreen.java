package net.dnd5ecraft.client.gui.screen;

import java.util.UUID;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.client.TabletopActions;
import net.dnd5ecraft.dialogue.DialogueText;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.Protocol.Dialogue;
import net.dnd5ecraft.network.TabletopCodec.Card;
import net.dnd5ecraft.network.TabletopText;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.TabletopRolls;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.ability.TabletopRolls.Kind;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class DialogueScreen extends DndScreen {
    private final Dialogue dialogue;
    private int page;
    private int scroll;
    private Card result;
    private long until;
    private boolean branchQueued;
    private Dialogue next;
    private RollMode previewMode;
    private net.dnd5ecraft.rules.character.CharacterSheetData previewSheet;

    public DialogueScreen(Dialogue dialogue) {
        super(DialogueText.title(dialogue.title()));
        this.dialogue = dialogue;
    }

    public UUID token() { return dialogue.token(); }

    public void showRoll(Card roll) {
        result = roll;
        until = Util.getMillis() + 1800;
        rebuild();
    }

    public boolean queueBranch(Dialogue branch) {
        if (result == null) return false;
        branchQueued = true;
        next = branch;
        return true;
    }

    private int visibleOptions() { return Math.max(1, (panelHeight - 133) / 22); }

    private RollMode mode() {
        var options = TabletopActions.options(true);
        return TabletopRolls.checkMode(options.mode(), options.inspiration(), TabletopRolls.disadvantaged(ClientDndState.sheet(), Kind.SKILL));
    }

    @Override protected void controls() {
        previewSheet = ClientDndState.sheet();
        previewMode = mode();
        int x = left + 12;
        int maxPage = Math.max(0, (dialogue.options().size() - 1) / visibleOptions());
        page = Math.max(0, Math.min(page, maxPage));
        for (int i = 0; i < visibleOptions(); i++) {
            int index = page * visibleOptions() + i;
            if (index >= dialogue.options().size()) break;
            var choice = dialogue.options().get(index);
            var button = pill(DialogueText.component(choice.label()), x, top + 79 + i * 22, innerWidth(), 18,
                    false, () -> TabletopActions.dialogue(token(), choice.id(), choice.skill() != null));
            button.active = result == null;
            if (choice.skill() != null) {
                int bonus = TabletopRolls.modifier(previewSheet, new Check(Kind.SKILL, choice.skill().name()));
                int needed = choice.dc() - bonus;
                button.accent(previewMode == RollMode.ADVANTAGE || needed <= 9 ? EMERALD : needed <= 15 ? GOLD : CRIMSON);
                button.trailing(text("tabletop.dialogue_preview", name("skill", choice.skill()), TabletopText.signed(bonus), choice.dc(),
                        name("tabletop.mode", previewMode)));
                button.setTooltip(Tooltip.create(text("tabletop.roll_hint")));
            }
        }
        button(text("close"), x, bottom() - 25, 70, this::onClose);
        if (maxPage > 0) {
            button(text("previous"), x + innerWidth() - 112, bottom() - 25, 54, () -> { page--; rebuild(); }).active = page > 0;
            button(text("next"), x + innerWidth() - 54, bottom() - 25, 54, () -> { page++; rebuild(); }).active = page < maxPage;
        }
    }

    @Override protected void tickInWorld() {
        if (result != null && branchQueued && Util.getMillis() >= until) {
            minecraft.setScreen(next == null ? null : new DialogueScreen(next));
        } else if (previewMode != mode() || !java.util.Objects.equals(previewSheet, ClientDndState.sheet())) rebuild();
    }

    @Override protected void content(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 12;
        g.drawString(font, name("attitude", dialogue.attitude()), x, top + 27, ARCANE, false);
        var lines = font.split(DialogueText.component(dialogue.text()), innerWidth());
        for (int i = 0; i < 3 && i + scroll < lines.size(); i++) {
            g.drawString(font, lines.get(i + scroll), x, top + 42 + 10 * i, TEXT, false);
        }
    }

    @Override protected void foreground(GuiGraphics g, int mouseX, int mouseY) {
        if (result == null || !worldReady()) return;
        int x = left + 12, w = innerWidth(), y = bottom() - 62;
        int color = result.outcome().total() >= result.dc() ? EMERALD : CRIMSON;
        panel(g, x, y, w, 29, color);
        clipped(g, font, text("tabletop.dialogue_banner", result.label(), TabletopText.result(result)), x + 6, y + 5, w - 12, color);
        clipped(g, font, TabletopText.equation(result.outcome()), x + 6, y + 17, w - 12, TEXT);
    }

    @Override public boolean mouseScrolled(double x, double y, double delta) {
        int last = Math.max(0, font.split(DialogueText.component(dialogue.text()), innerWidth()).size() - 3);
        scroll = Math.min(last, Math.max(0, scroll - (int) Math.signum(delta)));
        return true;
    }

    @Override public void onClose() {
        if (worldReady() && !dialogue.options().isEmpty()) {
            ClientPacketHandlers.send(Action.CLOSE_DIALOGUE, buf -> buf.writeUUID(next == null ? token() : next.token()));
        }
        super.onClose();
    }
}
