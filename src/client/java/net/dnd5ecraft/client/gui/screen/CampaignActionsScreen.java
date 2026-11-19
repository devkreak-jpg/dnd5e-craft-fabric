package net.dnd5ecraft.client.gui.screen;

import java.util.ArrayList;
import java.util.List;
import net.dnd5ecraft.client.ClientCampaign;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.combat.CombatManager.Maneuver;
import net.dnd5ecraft.entity.EchoAvatarEntity;
import net.dnd5ecraft.entity.EldritchCannonEntity;
import net.dnd5ecraft.network.PacketCodec;
import net.dnd5ecraft.network.PacketPolicy;
import net.dnd5ecraft.rules.campaign.CampaignHero;
import net.dnd5ecraft.rules.campaign.CampaignRules;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.server.CampaignActions.Action;
import net.minecraft.client.gui.screens.Screen;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class CampaignActionsScreen extends DndScreen {
    private record Entry(net.minecraft.network.chat.Component title, Runnable run, boolean enabled) {}
    private final Screen parent;
    private int quip = 1, page;

    public CampaignActionsScreen(Screen parent) {
        super(text("campaign.actions"));
        this.parent = parent;
    }

    @Override protected int maximumHeight() { return 245; }

    @Override protected void controls() {
        var sheet = ClientDndState.sheet();
        if (sheet == null) return;
        int x = left + 12, col = (innerWidth() - 5) / 2;
        boolean fighting = ClientDndState.controlsCombatant();
        boolean turn = fighting && ClientDndState.combat().ownsTurn();
        int flags = ((net.dnd5ecraft.server.PlayerCharacter) minecraft.player).dnd5e$campaignFlags();
        List<Entry> entries = new ArrayList<>();
        if (CampaignHero.FINNEAS.matches(sheet)) {
            var cannon = ClientCampaign.companion(EldritchCannonEntity.class);
            if (cannon == null) {
                add(entries, Action.CANNON_SUMMON, () -> point(Action.CANNON_SUMMON), !fighting || turn);
            } else {
                add(entries, Action.CANNON_FIRE, CampaignActionsScreen::fireCannon, !fighting || turn);
                entries.add(new Entry(name("cannon", cannon.mode()), () -> act(Action.CANNON_MODE), !fighting || turn));
                add(entries, Action.CANNON_STANCE, () -> act(Action.CANNON_STANCE), !fighting || turn);
            }
        }
        if (CampaignHero.ALEXANDER.matches(sheet)) {
            entries.add(new Entry(name("tabletop.feature", ClassFeature.RAGE), () -> ClientPacketHandlers.send(PacketPolicy.Action.MANEUVER, out -> {
                PacketCodec.writeEnum(out, Maneuver.RAGE);
                out.writeBoolean(false);
                out.writeVarInt(0);
                out.writeBoolean(false);
            }), turn && sheet.remaining(ClassFeature.RAGE) > 0));
            add(entries, Action.RECKLESS_ATTACK, () -> act(Action.RECKLESS_ATTACK), turn);
            add(entries, Action.FOLLOW_UP_FIST, () -> act(Action.FOLLOW_UP_FIST), turn && (flags & 2) != 0);
            add(entries, Action.BURYING_HANDS, () -> act(Action.BURYING_HANDS), turn && (flags & 4) != 0);
        }
        if (CampaignHero.HARLEQUIN.matches(sheet)) {
            int limit = CampaignRules.chaosLimit(sheet.classLevels().get(net.dnd5ecraft.rules.character.ClassType.HARLEQUIN));
            entries.add(new Entry(text("campaign.quip_dice", quip), () -> { quip = quip % limit + 1; rebuild(); }, true));
            entries.add(new Entry(text("campaign.quip_bolster"), () -> creature(Action.CHAOS_QUIP, 12, quip), turn));
            entries.add(new Entry(text("campaign.quip_sting"), () -> creature(Action.CHAOS_QUIP, 12, -quip), turn));
            add(entries, Action.SCENE_CHANGE, () -> ClientCampaign.scenePair(false), turn);
            add(entries, Action.AUDIENCE_LAUGHTER, () -> act(Action.AUDIENCE_LAUGHTER), fighting);
            add(entries, Action.MISE_EN_SCENE, () -> point(Action.MISE_EN_SCENE), turn);
            add(entries, Action.CAST_OF_THE_DIE, () -> act(Action.CAST_OF_THE_DIE), !fighting && sheet.remaining(ClassFeature.CAST_OF_DIE) > 0);
        }
        if (CampaignHero.MINOTAUR.matches(sheet)) {
            if (ClientCampaign.companion(EchoAvatarEntity.class) == null) {
                add(entries, Action.ECHO_MANIFEST, () -> point(Action.ECHO_MANIFEST), !fighting || turn);
            } else {
                add(entries, Action.ECHO_MOVE, () -> point(Action.ECHO_MOVE), !fighting || turn);
                add(entries, Action.ECHO_SWAP, () -> act(Action.ECHO_SWAP), !fighting || turn);
                add(entries, Action.ECHO_ATTACK, () -> creature(Action.ECHO_ATTACK, 9, 0), turn);
                add(entries, Action.UNLEASH_INCARNATION, () -> creature(Action.UNLEASH_INCARNATION, 9, 0), turn);
            }
        }
        if (sheet.race() == net.dnd5ecraft.rules.character.RaceType.MINOTAUR) {
            add(entries, Action.GORING_RUSH, () -> creature(Action.GORING_RUSH, 3, 0), turn);
            add(entries, Action.HAMMERING_HORNS, () -> act(Action.HAMMERING_HORNS), turn && (flags & 8) != 0);
        }
        int pageSize = 2 * Math.max(1, (panelHeight - 68) / 22);
        for (int i = 0; i < pageSize && page * pageSize + i < entries.size(); i++) {
            var entry = entries.get(page * pageSize + i);
            button(entry.title(), x + i % 2 * (col + 5), top + 32 + i / 2 * 22, col, entry.run()).active = entry.enabled();
        }
        button(text("back"), x, bottom() - 25, 70, this::onClose);
        if (entries.size() > pageSize) {
            button(text("previous"), x + 75, bottom() - 25, 70, () -> { page--; rebuild(); }).active = page > 0;
            button(text("next"), x + 150, bottom() - 25, 70, () -> { page++; rebuild(); }).active = (page + 1) * pageSize < entries.size();
        }
    }

    private static void add(List<Entry> entries, Action action, Runnable run, boolean enabled) {
        entries.add(new Entry(name("campaign.action", action), run, enabled));
    }

    private static void act(Action action) { ClientCampaign.act(action, null, null, 0); }
    private static void point(Action action) { ClientCampaign.point(point -> ClientCampaign.act(action, null, point, 0)); }
    private static void creature(Action action, double range, int option) {
        ClientCampaign.creature(range, id -> ClientCampaign.act(action, id, null, option));
    }

    public static void fireCannon() {
        var cannon = ClientCampaign.companion(EldritchCannonEntity.class);
        if (cannon == null) return;
        if (cannon.mode() == EldritchCannonEntity.Mode.FORCE_BALLISTA) creature(Action.CANNON_FIRE, 28, 0);
        else act(Action.CANNON_FIRE);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
