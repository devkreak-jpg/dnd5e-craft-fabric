package net.dnd5ecraft.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.WeakHashMap;
import net.dnd5ecraft.entity.CampaignCompanion;
import net.dnd5ecraft.entity.EchoAvatarEntity;
import net.dnd5ecraft.entity.EldritchCannonEntity;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.network.OnlineCodec;
import net.dnd5ecraft.network.Protocol;
import net.dnd5ecraft.network.Protocol.Handout;
import net.dnd5ecraft.network.Protocol.OnlineMember;
import net.dnd5ecraft.network.Protocol.OnlineView;
import net.dnd5ecraft.network.Protocol.ReadyView;
import net.dnd5ecraft.rules.campaign.PartyReadyCheck;
import net.dnd5ecraft.rules.equipment.CoinPurse.Denomination;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public final class OnlineParty {
    private static final WeakHashMap<MinecraftServer, Session> SESSIONS = new WeakHashMap<>();

    private static final class Session {
        PartyReadyCheck check;
        long expires;
        boolean complete;
        List<OnlineMember> roster = List.of();
    }

    private OnlineParty() {}

    private static Session session(MinecraftServer server) { return SESSIONS.computeIfAbsent(server, ignored -> new Session()); }

    public static void join(ServerPlayer player) {
        PlayerSheets.get(player).ifPresent(sheet -> ((PlayerCharacter) player).dnd5e$setSheet(sheet));
        var state = session(player.getServer());
        if (state.check != null && !state.complete && state.check.members().size() < OnlineCodec.MAX_PARTY) {
            state.check.join(player.getUUID());
            offerVanilla(player, state.check);
        }
        refresh(player.getServer());
    }

    public static void disconnect(ServerPlayer player) {
        var state = session(player.getServer());
        if (state.check != null && !state.complete) {
            state.check.leave(player.getUUID());
            completeIfReady(player.getServer(), state);
        }
    }

    public static void clear(MinecraftServer server) { SESSIONS.remove(server); }

    public static void refresh(MinecraftServer server) {
        var state = session(server);
        if (state.check != null && server.getTickCount() >= state.expires) state.check = null;
        state.roster = server.getPlayerList().getPlayers().stream().limit(OnlineCodec.MAX_PARTY).map(OnlineParty::member).toList();
    }

    private static OnlineMember member(ServerPlayer player) {
        var sheet = PlayerSheets.get(player).orElse(null);
        int hp = sheet == null ? (int)Math.ceil(player.getHealth()) : sheet.hitPoints().current();
        int max = sheet == null ? (int)Math.ceil(player.getMaxHealth()) : sheet.hitPoints().maximum();
        int ac = sheet == null ? 10 + player.getArmorValue() : TacticalCombat.armorClass(player, sheet);
        var echo = CampaignActions.companion(player, EchoAvatarEntity.class);
        var cannon = CampaignActions.companion(player, EldritchCannonEntity.class);
        return new OnlineMember(player.getUUID(), ModPackets.trim(sheet == null ? player.getGameProfile().getName() : sheet.name(), 80),
                hp, max, ac, true, id(echo), id(cannon));
    }

    private static UUID id(CampaignCompanion entity) { return entity == null ? null : entity.getUUID(); }

    public static OnlineView view(ServerPlayer player, Protocol.Combat combat) {
        var state = session(player.getServer());
        var roster = new ArrayList<>(state.roster);
        if (combat != null) for (var member : combat.initiative()) {
            if (member.offline() && roster.stream().noneMatch(other -> other.id().equals(member.id()))
                    && roster.size() < OnlineCodec.MAX_PARTY)
                roster.add(new OnlineMember(member.id(), member.name(), member.hp(), member.maxHp(), member.armor(), false, null, null));
        }
        var ready = state.check == null ? null
                : new ReadyView(state.check.id(), state.check.label(), state.check.members(), state.complete);
        return new OnlineView(roster, ready, TacticalCombat.networkZones(player).stream().limit(OnlineCodec.MAX_ZONES).toList());
    }

    public static void readyCheck(ServerPlayer dm, String label) {
        requireOperator(dm);
        var server = dm.getServer();
        var state = session(server);
        var players = server.getPlayerList().getPlayers().stream().limit(OnlineCodec.MAX_PARTY).toList();
        state.check = new PartyReadyCheck(ModPackets.trim(label.strip(), 80), players.stream().map(ServerPlayer::getUUID).toList());
        state.complete = false;
        state.expires = (long)server.getTickCount() + 20 * 60;
        players.forEach(player -> offerVanilla(player, state.check));
    }

    private static void offerVanilla(ServerPlayer player, PartyReadyCheck check) {
        if (ModPackets.supports(player)) return;
        player.sendSystemMessage(Component.translatable("dnd-5e-craft.online.ready_prompt", check.label()).append(" ")
                .append(readyLink(check.id(), true)).append(" ").append(readyLink(check.id(), false)));
    }

    private static Component readyLink(UUID id, boolean ready) {
        return Component.translatable("dnd-5e-craft.ui.online." + (ready ? "ready" : "need_time")).withStyle(style ->
                style.withColor(ready ? ChatFormatting.GREEN : ChatFormatting.GOLD)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dnd ready " + id + " " + ready)));
    }

    public static void readyResponse(ServerPlayer player, UUID id, boolean ready) {
        var state = session(player.getServer());
        if (state.check == null || state.complete || player.getServer().getTickCount() >= state.expires) return;
        if (state.check.respond(id, player.getUUID(), ready)) completeIfReady(player.getServer(), state);
    }

    private static void completeIfReady(MinecraftServer server, Session state) {
        if (!state.check.allReady()) return;
        state.complete = true;
        state.expires = (long)server.getTickCount() + 20 * 15;
        for (var player : server.getPlayerList().getPlayers()) if (player.hasPermissions(2)) {
            player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, .8f, 1.25f);
            ModPackets.notice(player, Component.translatable("dnd-5e-craft.online.all_ready"));
        }
    }

    public static void emote(ServerPlayer actor, String action) {
        String name = PlayerSheets.get(actor).map(sheet -> sheet.name()).orElse(actor.getGameProfile().getName());
        var phrase = Component.translatable("dnd-5e-craft.online.emote", name, required(action, 240))
                .withStyle(ChatFormatting.ITALIC);
        actor.serverLevel().players().stream().filter(viewer -> viewer.distanceToSqr(actor) <= 24 * 24)
                .forEach(viewer -> viewer.sendSystemMessage(phrase));
        DmDirector.playerEmote(actor, phrase);
    }

    public static void secret(ServerPlayer dm, ServerPlayer target, String body) {
        requireOperator(dm);
        target.sendSystemMessage(Component.translatable("dnd-5e-craft.online.secret", required(body, 1024))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC));
    }

    public static void pay(ServerPlayer payer, ServerPlayer recipient, long amount, String unit) {
        if (payer == recipient) throw new IllegalArgumentException("Choose another party member");
        var denomination = Denomination.valueOf(unit.toUpperCase(Locale.ROOT));
        var from = PlayerSheets.get(payer).orElseThrow(() -> new IllegalStateException("Create a character sheet first"));
        var to = PlayerSheets.get(recipient).orElseThrow(() -> new IllegalArgumentException("That player has no character sheet"));
        net.dnd5ecraft.rules.equipment.CoinPurse.Transfer transfer;
        try {
            transfer = from.coins().transfer(to.coins(), amount, denomination)
                    .orElseThrow(() -> new IllegalStateException("Insufficient coins in that denomination"));
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("The recipient cannot hold that many coins");
        }
        PlayerSheets.set(payer, from.withCoins(transfer.payer()));
        PlayerSheets.set(recipient, to.withCoins(transfer.payee()));
        payer.sendSystemMessage(Component.translatable("dnd-5e-craft.online.paid", amount, denomination.name(), to.name()));
        recipient.sendSystemMessage(Component.translatable("dnd-5e-craft.online.received", amount, denomination.name(), from.name()));
    }

    public static void handout(ServerPlayer dm, String audience, String text) {
        requireOperator(dm);
        int divider = text.indexOf('|');
        if (divider < 0) throw new IllegalArgumentException("Separate the title and body with |");
        handout(dm, audience, new Handout(text.substring(0, divider), text.substring(divider + 1)));
    }

    public static void handout(ServerPlayer dm, String audience, Handout sheet) {
        requireOperator(dm);
        var card = new Handout(required(sheet.title(), Protocol.HANDOUT_TITLE_LIMIT),
                required(sheet.body(), Protocol.HANDOUT_BODY_LIMIT));
        var players = dm.getServer().getPlayerList();
        if (audience.equalsIgnoreCase("all")) players.getPlayers().forEach(player -> ModPackets.sendHandout(player, card));
        else {
            var player = players.getPlayerByName(audience);
            if (player == null) throw new IllegalArgumentException("That player is offline");
            ModPackets.sendHandout(player, card);
        }
    }

    private static String required(String text, int limit) {
        String value = text.strip();
        if (value.isEmpty() || value.length() > limit || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Enter message text within the length limit");
        return value;
    }

    private static void requireOperator(ServerPlayer player) {
        if (!player.hasPermissions(2)) throw new IllegalStateException("Operator permissions are required");
    }
}
