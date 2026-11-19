package net.dnd5ecraft.network;

import java.util.UUID;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.ability.TabletopRolls.Kind;
import net.dnd5ecraft.rules.ability.TabletopRolls.Options;
import net.dnd5ecraft.rules.ability.TabletopRolls.Outcome;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.network.PacketCodec.*;

public final class TabletopCodec {
    public record Card(UUID roller, String name, Check check, Component label, Outcome outcome, int dc,
                       UUID requestedBy, boolean whisper) {}
    public record Prompt(UUID id, String requester, Check check, RollMode mode, int dc, boolean secret) {
        public Prompt {
            if (!check.requestable() || dc < 5 || dc > 30) throw new IllegalArgumentException("Invalid roll request");
        }
    }

    private TabletopCodec() {}

    public static Check check(FriendlyByteBuf buf) { return new Check(readEnum(buf, Kind.class), buf.readUtf(32)); }
    public static void check(FriendlyByteBuf buf, Check check) {
        writeEnum(buf, check.kind());
        buf.writeUtf(check.id(), 32);
    }

    public static Options options(FriendlyByteBuf buf) {
        return new Options(readEnum(buf, RollMode.class), buf.readBoolean(), buf.readBoolean());
    }
    public static void options(FriendlyByteBuf buf, Options target) {
        writeEnum(buf, target.mode());
        buf.writeBoolean(target.whisper());
        buf.writeBoolean(target.inspiration());
    }

    public static Card card(FriendlyByteBuf buf) {
        var roller = buf.readUUID();
        var name = buf.readUtf(40);
        var check = check(buf);
        var label = buf.readComponent();
        var dice = readList(buf, 16, FriendlyByteBuf::readVarInt);
        var outcome = new Outcome(dice, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                readEnum(buf, RollMode.class), buf.readBoolean());
        int dc = PacketPolicy.count(buf.readVarInt(), 30);
        UUID requestedBy = buf.readBoolean() ? buf.readUUID() : null;
        return new Card(roller, name, check, label, outcome, dc, requestedBy, buf.readBoolean());
    }

    public static void card(FriendlyByteBuf buf, Card card) {
        buf.writeUUID(card.roller());
        buf.writeUtf(card.name(), 40);
        check(buf, card.check());
        buf.writeComponent(card.label());
        var roll = card.outcome();
        writeList(buf, roll.dice(), FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(roll.kept());
        buf.writeVarInt(roll.modifier());
        buf.writeVarInt(roll.total());
        buf.writeVarInt(roll.sides());
        writeEnum(buf, roll.mode());
        buf.writeBoolean(roll.critical());
        buf.writeVarInt(card.dc());
        buf.writeBoolean(card.requestedBy() != null);
        if (card.requestedBy() != null) buf.writeUUID(card.requestedBy());
        buf.writeBoolean(card.whisper());
    }

    public static Prompt prompt(FriendlyByteBuf buf) {
        return new Prompt(buf.readUUID(), buf.readUtf(40), check(buf), readEnum(buf, RollMode.class),
                buf.readVarInt(), buf.readBoolean());
    }

    public static void prompt(FriendlyByteBuf buf, Prompt prompt) {
        buf.writeUUID(prompt.id());
        buf.writeUtf(prompt.requester(), 40);
        check(buf, prompt.check());
        writeEnum(buf, prompt.mode());
        buf.writeVarInt(prompt.dc());
        buf.writeBoolean(prompt.secret());
    }
}
