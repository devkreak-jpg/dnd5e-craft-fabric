package net.dnd5ecraft.client;

import java.util.UUID;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.TabletopCodec;
import net.dnd5ecraft.rules.ability.TabletopRolls;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.ability.TabletopRolls.Kind;
import net.dnd5ecraft.rules.ability.TabletopRolls.Options;
import net.minecraft.client.gui.screens.Screen;

public final class TabletopActions {
    private TabletopActions() {}

    public static Options options(boolean modifiers) {
        var mode = TabletopRolls.clickMode(ClientDndState.rollMode(), modifiers && Screen.hasShiftDown(), modifiers && Screen.hasAltDown());
        return new Options(mode, ClientDndState.whisper(), ClientDndState.inspiration(), ClientDndState.smell());
    }

    public static void roll(Check check, boolean power) {
        if (!ClientDndState.tabletop()) {
            if (check.kind() == Kind.SKILL || check.kind() == Kind.SAVE) {
                ClientPacketHandlers.send(Action.REQUEST_ROLL, out -> {
                    out.writeBoolean(check.kind() == Kind.SAVE);
                    out.writeUtf(check.id(), 32);
                });
            }
            return;
        }
        var selected = options(true);
        boolean eligible = switch (check.kind()) {
            case HIT_DIE, DAMAGE -> false;
            case DICE -> check.id().equals("1d20");
            default -> true;
        };
        var roll = new Options(selected.mode(), selected.whisper(), eligible && selected.inspiration(), selected.smell());
        if (ClientPacketHandlers.send(Action.TABLETOP_ROLL, packet -> {
            TabletopCodec.check(packet, check);
            TabletopCodec.options(packet, roll);
            packet.writeBoolean(power);
        }) && roll.inspiration()) ClientDndState.inspiration(false);
    }

    public static void dialogue(UUID token, String id, boolean skill) {
        if (ClientDndState.tabletop() && skill) {
            var selected = options(true);
            if (ClientPacketHandlers.send(Action.TABLETOP_CHOOSE, packet -> {
                packet.writeUUID(token);
                packet.writeUtf(id, 64);
                TabletopCodec.options(packet, selected);
            }) && selected.inspiration()) ClientDndState.inspiration(false);
        } else ClientPacketHandlers.send(Action.CHOOSE, packet -> {
            packet.writeUUID(token);
            packet.writeUtf(id, 64);
        });
    }

    public static boolean answerPrompt() {
        var prompt = ClientDndState.prompt();
        return prompt != null && ClientPacketHandlers.send(Action.DM_ROLL_ANSWER, out -> out.writeUUID(prompt.id()));
    }
}
