package net.dnd5ecraft.client.gui.screen;

import java.util.List;
import java.util.UUID;
import net.dnd5ecraft.client.ClientCampaign;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.item.ModItems;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.server.DmDirector.Tool;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class DmDirectorScreen extends DndScreen {
    private enum Panel { SOUND, WEATHER, PARTY, NPC }
    private final Screen parent;
    private Panel panel = Panel.SOUND;
    private UUID target, selected;
    private int loot = 7, radius = 8, page;
    private String gold = "10";

    public DmDirectorScreen(Screen parent) {
        super(text("campaign.director"));
        this.parent = parent;
    }

    @Override protected int maximumHeight() { return 264; }

    @Override protected void tickInWorld() {
        if (!ClientDndState.state().operator()) onClose();
        else if (!java.util.Objects.equals(selected, ClientCampaign.directedNpc())) rebuild();
    }

    @Override protected void controls() {
        selected = ClientCampaign.directedNpc();
        int x = left + 12;
        int tab = (innerWidth() - 9) / 4;
        for (Panel choice : Panel.values()) {
            pill(name("director.panel", choice), x + choice.ordinal() * (tab + 3), top + 28, tab, 17, choice == panel,
                    () -> { panel = choice; page = 0; rebuild(); });
        }
        int col = (innerWidth() - 8) / 3;
        int y = top + 55;
        switch (panel) {
            case SOUND -> tools(x, y, col, List.of(Tool.DRAGON_ROAR, Tool.SWORD_CLASH, Tool.DOOR_SLAM, Tool.THUNDERCLAP,
                    Tool.EERIE_WHISPER, Tool.TAVERN_AMBIENCE, Tool.COMBAT_HORN));
            case WEATHER -> tools(x, y, col, List.of(Tool.DAWN, Tool.NOON, Tool.DUSK, Tool.MIDNIGHT, Tool.CLEAR, Tool.RAIN,
                    Tool.THUNDERSTORM, Tool.FOG, Tool.DARKNESS, Tool.SHAKE));
            case PARTY -> party(x, y, col);
            case NPC -> npc(x, y, col);
        }
        button(text("back"), x, bottom() - 24, 72, this::onClose);
        button(text("director.build_demo"), left + panelWidth - 160, bottom() - 24, 148, () -> {
            if (minecraft.player != null) minecraft.player.connection.sendCommand("dnd dm build_demo_map");
            onClose();
        }).active = ClientDndState.state().operator();
    }

    private void tools(int x, int y, int col, List<Tool> actions) {
        int perPage = Math.max(1, (panelHeight - 111) / 21) * 3;
        for (int i = 0; i < perPage && page * perPage + i < actions.size(); i++) {
            var tool = actions.get(page * perPage + i);
            button(name("director.tool", tool), x + (i % 3) * (col + 4), y + (i / 3) * 21, col, () -> run(tool, null, 0));
        }
        if (actions.size() > perPage) {
            button(text("previous"), x + 78, bottom() - 45, 70, () -> { page--; rebuild(); }).active = page > 0;
            button(text("next"), x + 153, bottom() - 45, 70, () -> { page++; rebuild(); }).active = (page + 1) * perPage < actions.size();
        }
    }

    private void party(int x, int y, int col) {
        var members = ClientDndState.party();
        int index = java.util.stream.IntStream.range(0, members.size())
                .filter(i -> members.get(i).id().equals(target)).findFirst().orElse(-1);
        if (index < 0) target = null;
        button(index < 0 ? text("tabletop.dm_all") : Component.literal(members.get(index).name()), x, y, innerWidth(), () -> {
            target = index + 1 == members.size() ? null : members.get(index + 1).id();
            rebuild();
        }).active = !members.isEmpty();
        edit("director.tool.coins", gold, x, y + 22, col, 4).setResponder(value -> gold = value);
        button(name("director.tool", Tool.COINS), x + col + 4, y + 22, col, () -> {
            try {
                int amount = Integer.parseInt(gold);
                if (amount < 1 || amount > 1000) throw new NumberFormatException();
                run(Tool.COINS, target, amount);
            } catch (NumberFormatException invalid) { ClientDndState.notify(text("director.gold_amount")); }
        });
        button(name("director.tool", Tool.TELEPORT), x + (col + 4) * 2, y + 22, col,
                () -> run(Tool.TELEPORT, target, 0)).active = target != null;
        button(ModItems.all().get(loot).getDescription(), x, y + 44, col * 2 + 4, () -> {
            loot = loot + 1 == ModItems.all().size() ? 7 : loot + 1;
            rebuild();
        });
        button(name("director.tool", Tool.LOOT), x + (col + 4) * 2, y + 44, col, () -> run(Tool.LOOT, target, loot));
        button(name("director.tool", Tool.SUMMON_PARTY), x, y + 66, col, () -> run(Tool.SUMMON_PARTY, target, 0));
        button(name("director.tool", Tool.FREEZE), x + col + 4, y + 66, col, () -> run(Tool.FREEZE, null, 0));
        button(text("tabletop.dm_title"), x + (col + 4) * 2, y + 66, col,
                () -> minecraft.setScreen(new DmRollRequestScreen(this))).active = ClientDndState.tabletop();
    }

    private void npc(int x, int y, int col) {
        button(name("director.tool", Tool.SELECT_NPC), x, y, col, () ->
                ClientCampaign.creature(32, id -> run(Tool.SELECT_NPC, id, 0)));
        button(name("director.tool", Tool.SPEAK), x + col + 4, y, col, () -> run(Tool.SPEAK, null, 0)).active = selected != null;
        button(text("dm.possess"), x + (col + 4) * 2, y, col, () -> {
            if (ClientPacketHandlers.send(Action.POSSESS, out -> out.writeUUID(selected))) minecraft.setScreen(null);
        }).active = selected != null && ClientDndState.state().dm();
        var behaviors = List.of(Tool.STATIONARY, Tool.WANDER, Tool.PATROL, Tool.DM_COMMANDED);
        for (int i = 0; i < behaviors.size(); i++) {
            var behavior = behaviors.get(i);
            button(name("director.tool", behavior), x + i % 3 * (col + 4), y + 22 + i / 3 * 22, col,
                    () -> run(behavior, null, radius)).active = selected != null;
        }
        button(text("director.radius", radius), x + col + 4, y + 44, col, () -> {
            radius = radius == 16 ? 4 : radius + 2;
            rebuild();
        }).active = selected != null;
        button(name("director.tool", Tool.NPC_EMOTE), x + (col + 4) * 2, y + 44, col,
                () -> run(Tool.NPC_EMOTE, null, 0)).active = selected != null;
        button(name("director.tool", Tool.NPC_ATTACK), x, y + 66, col, () ->
                ClientCampaign.creature(48, id -> run(Tool.NPC_ATTACK, id, 0))).active = selected != null;
        button(name("director.tool", Tool.NPC_END), x + col + 4, y + 66, col,
                () -> run(Tool.NPC_END, null, 0)).active = selected != null;
        button(name("campaign.action", net.dnd5ecraft.server.CampaignActions.Action.DIRECTOR_SCENE_CHANGE),
                x + (col + 4) * 2, y + 66, col, () -> ClientCampaign.scenePair(true));
    }

    private static void run(Tool tool, UUID target, int value) { ClientCampaign.director(tool, target, value); }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
