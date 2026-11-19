package net.dnd5ecraft.client.gui.screen;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.dnd5ecraft.client.ClientActions;
import net.dnd5ecraft.rules.spells.AoEGeometry;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.client.gui.hud.CombatHudOverlay;
import net.dnd5ecraft.network.TabletopText;
import net.dnd5ecraft.combat.EncounterDifficultyCalculator;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.entity.BestiaryRegistry5e;
import net.dnd5ecraft.network.PacketCodec;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class DmMasterConsoleScreen extends DndScreen {
    private final UUID npc;
    private final Cell marker;
    private final Cell spawn;
    private BestiaryPreset preset = BestiaryPreset.GOBLIN;
    private String levels = "1,1,1,1";
    private String monsters = "1/4,1/4";
    private EncounterDifficultyCalculator.Result difficulty;
    private AoEGeometry shape = AoEGeometry.CONE;
    private boolean lastDm;
    private boolean lastTabletop;
    private boolean showParty;
    private int partyPage;
    private List<net.dnd5ecraft.network.Protocol.PartyMember> lastParty = List.of();

    public DmMasterConsoleScreen(UUID npc, Cell marker, Cell spawn) {
        super(text("dm.title"));
        this.npc = npc;
        this.marker = marker;
        this.spawn = spawn;
    }

    @Override protected void controls() {
        int x = left + 12;
        if (showParty) {
            partyPage = Math.min(partyPage, Math.max(0, (ClientDndState.party().size() - 1) / partyRows()));
            int y = bottom() - 25;
            button(text("close"), x, y, 60, () -> { showParty = false; rebuild(); });
            button(text("previous"), x + 66, y, 64, () -> { partyPage--; rebuild(); }).active = partyPage > 0;
            button(text("next"), x + 136, y, 64, () -> { partyPage++; rebuild(); })
                    .active = (partyPage + 1) * partyRows() < ClientDndState.party().size();
            return;
        }
        int col = (innerWidth() - 12) / 3;
        int second = x + col + 6, third = second + col + 6;
        boolean op = ClientDndState.state().operator();
        lastDm = ClientDndState.state().dm();
        lastTabletop = ClientDndState.tabletop();
        button(text("dm.start"), x, top + 28, col, () -> ClientPacketHandlers.send(Action.START_COMBAT)).active = op;
        button(text("dm.stop"), second, top + 28, col, () -> ClientPacketHandlers.send(Action.STOP_COMBAT)).active = op;
        button(text("dm.toggle"), third, top + 28, col, () -> ClientPacketHandlers.send(Action.DM_MODE)).active = op;
        button(text("dm.npc"), x, top + 51, col, () -> ClientPacketHandlers.send(Action.NPC_INSPECT, out -> out.writeUUID(npc))).active = op && npc != null;
        button(text("dm.zone"), second, top + 51, col, () -> ClientPacketHandlers.send(Action.ZONE_INSPECT, out -> PacketCodec.cell(out, marker)))
                .active = op && marker != null;
        var possess = button(text("dm.possess"), third, top + 51, col, () -> {
            if (ClientPacketHandlers.send(Action.POSSESS, out -> out.writeUUID(npc))) onClose();
        });
        possess.active = op && npc != null && ClientDndState.state().dm();
        button(Component.translatable("entity.dnd-5e-craft.npc." + preset.id()), x, top + 74, col, () -> {
            var presets = BestiaryRegistry5e.spawnable(); preset = presets.get((presets.indexOf(preset) + 1) % presets.size()); rebuild();
        }).active = op;
        button(text("dm.spawn"), second, top + 74, col, () -> ClientPacketHandlers.send(Action.NPC_SPAWN, out -> {
            PacketCodec.cell(out, spawn);
            PacketCodec.writeEnum(out, preset);
            PacketCodec.writeEnum(out, NpcAttitude.INDIFFERENT);
        })).active = op && spawn != null;
        button(text("dm.release"), third, top + 74, col, () -> ClientPacketHandlers.send(Action.RELEASE)).active = op && ClientDndState.state().dm();
        edit("dm.levels", levels, x, top + 115, col, 64).setResponder(value -> levels = value);
        edit("dm.ratings", monsters, second, top + 115, col, 128).setResponder(value -> monsters = value);
        button(text("dm.assess"), third, top + 115, col, this::assess);
        pill(text("tabletop.dm_title"), x, top + 138, col * 2 + 6, 18, false, () -> {
            if (ClientPacketHandlers.send(Action.PARTY)) minecraft.setScreen(new DmRollRequestScreen(this));
        }).active = op && ClientDndState.tabletop();
        button(text("dm.party"), third, top + 138, col, () -> {
            if (ClientPacketHandlers.send(Action.PARTY)) { showParty = true; partyPage = 0; rebuild(); }
        }).active = op;
        button(text("campaign.director"), x, top + 160, col, () -> {
            ClientPacketHandlers.send(Action.PARTY);
            minecraft.setScreen(new DmDirectorScreen(this));
        }).active = op;
        button(text("close"), x, bottom() - 25, col, this::onClose);
        cycle("area", shape, AoEGeometry.values(), second, bottom() - 25, col, value -> { shape = value; rebuild(); });
        button(text("dm.preview"), third, bottom() - 25, col, () -> {
            ClientActions.preview(shape);
            onClose();
        }).active = op;
    }

    @Override protected void tickInWorld() {
        if (showParty && !lastParty.equals(ClientDndState.party())) {
            lastParty = ClientDndState.party();
            rebuild();
        } else if (!showParty && (lastDm != ClientDndState.state().dm() || lastTabletop != ClientDndState.tabletop())) rebuild();
    }

    private void assess() {
        try {
            List<Integer> party = Arrays.stream(levels.split(",")).map(String::strip).map(Integer::parseInt).toList();
            var enemies = Arrays.stream(monsters.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                    .map(EncounterDifficultyCalculator.Monster::new).toList();
            difficulty = EncounterDifficultyCalculator.assess(party, enemies);
        } catch (IllegalArgumentException invalid) {
            difficulty = null;
            ClientDndState.notify(text("dm.invalid"));
        }
    }

    private int partyRows() { return Math.max(1, (panelHeight - 82) / 15); }

    @Override protected void content(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 12;
        if (showParty) {
            caption(g, "dm.party", x, top + 32);
            var shown = ClientDndState.party().stream().skip((long) partyPage * partyRows()).limit(partyRows()).toList();
            for (int i = 0; i < shown.size(); i++) {
                var player = shown.get(i);
                clipped(g, font, text("dm.party_member", player.name(), player.level(), player.hp(), player.maxHp(),
                        player.temporary(), player.armor()), x, top + 48 + i * 15, innerWidth(), TEXT);
            }
            return;
        }
        caption(g, "dm.levels", x, top + 103);
        caption(g, "dm.ratings", x + (innerWidth() - 12) / 3 + 6, top + 103);
        if (difficulty != null) {
            clipped(g, font, text("dm.rating", name("difficulty", difficulty.difficulty()), difficulty.baseXp(), difficulty.adjustedXp()),
                    x, top + 163, innerWidth(), GOLD);
            var t = difficulty.thresholds();
            clipped(g, font, text("dm.thresholds", t.easy(), t.medium(), t.hard(), t.deadly()), x, top + 178, innerWidth(), MUTED);
        }
        var results = ClientDndState.tabletopResults().stream()
                .filter(roll -> minecraft.player.getUUID().equals(roll.card().requestedBy())).toList();
        int from = Math.max(0, results.size() - 3);
        int feedY = top + 195;
        for (int i = from; i < results.size() && feedY + 10 < bottom() - 57; i++, feedY += 10) {
            clipped(g, font, TabletopText.line(results.get(i).card()), x, feedY, innerWidth(), TEXT);
        }
    }

    @Override protected void foreground(GuiGraphics graphics, int mouseX, int mouseY) {
        var combat = ClientDndState.combat();
        if (!showParty && combat != null && panelHeight >= 280) {
            CombatHudOverlay.initiative(graphics, font, combat, left + 12, bottom() - 64,
                    innerWidth(), mouseX, mouseY, true);
        }
    }
}
