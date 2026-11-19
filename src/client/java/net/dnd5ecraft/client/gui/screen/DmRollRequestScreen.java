package net.dnd5ecraft.client.gui.screen;

import java.util.List;
import java.util.UUID;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.network.PacketCodec;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.Protocol.PartyMember;
import net.dnd5ecraft.network.TabletopCodec;
import net.dnd5ecraft.network.TabletopText;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.ability.TabletopRolls.Kind;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

final class DmRollRequestScreen extends DndScreen {
    private static final Kind[] KINDS = {Kind.ABILITY, Kind.SKILL, Kind.SAVE};
    private final DmMasterConsoleScreen parent;
    private List<PartyMember> party = List.of();
    private UUID target;
    private Kind kind = Kind.SKILL;
    private Ability ability = Ability.DEXTERITY;
    private Skill skill = Skill.PERCEPTION;
    private RollMode mode = RollMode.NORMAL;
    private String dc = "14";
    private boolean secret;

    DmRollRequestScreen(DmMasterConsoleScreen parent) {
        super(text("tabletop.dm_title"));
        this.parent = parent;
    }

    @Override protected int maximumHeight() { return 365; }

    @Override protected void controls() {
        party = ClientDndState.party();
        int x = left + 12, w = innerWidth(), half = (w - 4) / 2, third = (w - 8) / 3;
        int index = java.util.stream.IntStream.range(0, party.size()).filter(i -> party.get(i).id().equals(target)).findFirst().orElse(-1);
        if (index < 0) target = null;
        pill(text("tabletop.dm_target", index < 0 ? text("tabletop.dm_all") : party.get(index).name()), x, top + 29, half,
                18, false, () -> { target = index + 1 >= party.size() ? null : party.get(index + 1).id(); rebuild(); }).active = !party.isEmpty();
        pill(text("tabletop.dm_secret"), x + half + 4, top + 29, half, 18, secret, () -> { secret = !secret; rebuild(); });
        for (int i = 0; i < KINDS.length; i++) {
            Kind type = KINDS[i];
            pill(name("tabletop.request", type), x + i * (third + 4), top + 52, third, 17, type == kind, () -> { kind = type; rebuild(); });
        }
        int maxRows = (panelHeight - 156) / 19;
        int cols = Math.min(6, Math.max(3, (18 + Math.max(1, maxRows) - 1) / Math.max(1, maxRows)));
        int col = (w - 4 * (cols - 1)) / cols;
        Enum<?>[] choices = kind == Kind.SKILL ? Skill.values() : Ability.values();
        for (int i = 0; i < choices.length; i++) {
            var choice = choices[i];
            pill(name(kind == Kind.SKILL ? "skill" : "ability", choice), x + (i % cols) * (col + 4), top + 75 + (i / cols) * 19,
                    col, 17, choice == (kind == Kind.SKILL ? skill : ability), () -> {
                        if (choice instanceof Skill selected) skill = selected; else ability = (Ability) choice;
                        rebuild();
                    }).setTooltip(net.minecraft.client.gui.components.Tooltip.create(name(kind == Kind.SKILL ? "skill" : "ability", choice)));
        }
        int y = bottom() - 61;
        var difficulty = edit("tabletop.dc", dc, x + 25, y, 34, 2);
        difficulty.setFilter(value -> value.matches("[0-9]{0,2}"));
        difficulty.setResponder(value -> dc = value);
        int modeWidth = (w - 73) / 3;
        for (RollMode option : RollMode.values()) {
            pill(name("tabletop.mode", option), x + 65 + option.ordinal() * (modeWidth + 4), y, modeWidth, 17,
                    mode == option, () -> { mode = option; rebuild(); });
        }
        pill(text("back"), x, bottom() - 23, half, 17, false, this::onClose);
        pill(text("tabletop.dm_send"), x + half + 4, bottom() - 23, half, 17, false, this::send)
                .active = ClientDndState.state().operator() && ClientDndState.tabletop();
    }

    private void send() {
        int difficulty = dc.isBlank() ? 0 : Integer.parseInt(dc);
        if (difficulty < 5 || difficulty > 30) {
            ClientDndState.notify(text("tabletop.invalid_dc"));
            return;
        }
        ClientPacketHandlers.send(Action.DM_ROLL_REQUEST, out -> {
            out.writeBoolean(target != null);
            if (target != null) out.writeUUID(target);
            TabletopCodec.check(out, new Check(kind, kind == Kind.SKILL ? skill.name() : ability.name()));
            PacketCodec.writeEnum(out, mode);
            out.writeVarInt(difficulty);
            out.writeBoolean(secret);
        });
    }

    @Override protected void tickInWorld() {
        if (!ClientDndState.state().operator()) onClose();
        else if (!party.equals(ClientDndState.party())) rebuild();
    }

    @Override protected void content(GuiGraphics graphics, int mouseX, int mouseY) {
        clipped(graphics, font, text("tabletop.dc"), left + 12, bottom() - 55, 23, GOLD);
    }

    @Override public void onClose() { if (worldReady()) minecraft.setScreen(parent); }
}
