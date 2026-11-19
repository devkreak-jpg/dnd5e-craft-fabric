package net.dnd5ecraft.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.dnd5ecraft.dialogue.DialogueNode;
import net.dnd5ecraft.dialogue.DialogueTree;
import net.dnd5ecraft.dialogue.DialogueText;
import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.network.ModPackets;
import net.dnd5ecraft.network.Protocol;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class DialogueSessions {
    private static final int RANGE_SQUARED = 36;
    private static final int TIMEOUT_TICKS = 600;
    private static final Map<MinecraftServer, Map<UUID, Session>> SESSIONS = new WeakHashMap<>();

    private record Session(UUID token, UUID npc, DialogueTree tree, String node, long expires) {}

    private DialogueSessions() {}

    public static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var sessions = SESSIONS.get(server);
            if (sessions != null) sessions.remove(handler.player.getUUID());
        });
    }

    public static boolean open(ServerPlayer player, DndNpcEntity npc) {
        if (!available(player, npc)) return false;
        DialogueTree tree = npc.dialogue();
        enter(player, npc, tree, tree.start());
        return true;
    }

    public static boolean choose(ServerPlayer player, String id) {
        var session = active(player).get(player.getUUID());
        return session != null && choose(player, session.token, id);
    }

    public static boolean choose(ServerPlayer player, UUID token, String id) {
        var sessions = active(player);
        Session session = sessions.get(player.getUUID());
        if (session == null || !session.token.equals(token)) return false;
        if (player.serverLevel().getGameTime() >= session.expires
                || !(player.serverLevel().getEntity(session.npc) instanceof DndNpcEntity npc)
                || !available(player, npc) || npc.dialogue() != session.tree) {
            sessions.remove(player.getUUID());
            ModPackets.endDialogue(player, token);
            return false;
        }
        var character = PlayerSheets.get(player);
        RollMode mode = RollMode.from(false, character.map(sheet -> sheet.exhaustion().abilityCheckDisadvantage())
                .orElse(false));
        DialogueTree.Selection selection = session.tree.choose(session.node, id, npc.attitudeFor(player.getUUID()),
                skill -> character.map(sheet -> sheet.skillModifier(skill))
                        .orElseGet(() -> Ability.modifier(10)), mode, sides -> 1 + player.getRandom().nextInt(sides));
        selection.check().ifPresent(check -> ModPackets.notice(player, Component.translatable("dnd-5e-craft.dialogue.check",
                check.naturalRoll(), check.total(), check.difficulty())));
        selection.outcome().attitude().ifPresent(attitude -> npc.setAttitudeFor(player.getUUID(), attitude));
        sessions.remove(player.getUUID());
        if (selection.outcome().next().isPresent()) enter(player, npc, session.tree, selection.outcome().next().orElseThrow());
        else ModPackets.endDialogue(player, token);
        return true;
    }

    public static void close(ServerPlayer player, UUID token) {
        var sessions = active(player);
        Session session = sessions.get(player.getUUID());
        if (session != null && session.token.equals(token)) sessions.remove(player.getUUID());
    }

    private static boolean available(ServerPlayer player, DndNpcEntity npc) {
        return player.isAlive() && !player.isSpectator() && npc.isAlive() && player.level() == npc.level()
                && player.distanceToSqr(npc) <= RANGE_SQUARED && player.hasLineOfSight(npc)
                && npc.deathSaves().state() == net.dnd5ecraft.rules.conditions.DeathSaveTracker.State.CONSCIOUS
                && !TacticalCombat.isLocked(player) && !TacticalCombat.isLocked(npc);
    }

    private static Map<UUID, Session> active(ServerPlayer player) {
        return SESSIONS.computeIfAbsent(player.getServer(), ignored -> new HashMap<>());
    }

    private static void enter(ServerPlayer player, DndNpcEntity npc, DialogueTree tree, String nodeId) {
        DialogueNode node = tree.node(nodeId);
        var sessions = active(player);
        UUID token = UUID.randomUUID();
        if (node.choices().isEmpty()) sessions.remove(player.getUUID());
        else sessions.put(player.getUUID(), new Session(token, npc.getUUID(), tree, nodeId,
                player.serverLevel().getGameTime() + TIMEOUT_TICKS));
        if (ModPackets.supports(player)) {
            var attitude = npc.attitudeFor(player.getUUID());
            ModPackets.sendDialogue(player, new Protocol.Dialogue(token, npc.getDisplayName().getString(), node.text(), attitude,
                    node.choices().stream().map(choice -> ModPackets.option(choice, attitude)).toList()), npc.getName());
            return;
        }
        player.sendSystemMessage(Component.empty().append(npc.getDisplayName()).append(": ").append(DialogueText.component(node.text())));
        for (var choice : node.choices()) {
            player.sendSystemMessage(Component.literal("[").append(DialogueText.component(choice.label())).append("]").withStyle(style ->
                    style.withColor(ChatFormatting.GOLD).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/dnd choose " + choice.id()))));
        }
    }
}
