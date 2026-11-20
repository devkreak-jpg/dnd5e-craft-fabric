package net.dnd5ecraft.network;

import java.util.LinkedHashMap;
import java.util.UUID;
import net.dnd5ecraft.network.Protocol.Handout;
import net.dnd5ecraft.network.Protocol.OnlineMember;
import net.dnd5ecraft.network.Protocol.OnlineView;
import net.dnd5ecraft.network.Protocol.ReadyView;
import net.dnd5ecraft.network.Protocol.SpellZone;
import net.dnd5ecraft.rules.campaign.PartyReadyCheck.Status;
import net.dnd5ecraft.rules.spells.AoEGeometry;
import net.dnd5ecraft.rules.spells.AoEGeometry.Box;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.minecraft.network.FriendlyByteBuf;

import static net.dnd5ecraft.network.PacketCodec.*;

public final class OnlineCodec {
    public static final int MAX_PARTY = 512, MAX_ZONES = 128;

    private OnlineCodec() {}

    public static void write(FriendlyByteBuf out, OnlineView view) {
        writeList(out, view.party(), (buf, member) -> {
            buf.writeUUID(member.id());
            buf.writeUtf(member.name(), 80);
            buf.writeVarInt(member.hp());
            buf.writeVarInt(member.maximum());
            buf.writeVarInt(member.armor());
            buf.writeBoolean(member.online());
            optionalId(buf, member.echo());
            optionalId(buf, member.cannon());
        });
        var ready = view.ready();
        out.writeBoolean(ready != null);
        if (ready != null) {
            out.writeUUID(ready.id());
            out.writeUtf(ready.label(), 80);
            out.writeBoolean(ready.complete());
            out.writeVarInt(ready.members().size());
            ready.members().forEach((id, status) -> { out.writeUUID(id); writeEnum(out, status); });
        }
        writeList(out, view.zones(), (buf, zone) -> {
            var area = zone.area();
            buf.writeUtf(zone.spell(), 64);
            writeEnum(buf, area.template().shape());
            buf.writeDouble(area.template().size());
            buf.writeDouble(area.template().secondary());
            vector(buf, area.origin());
            buf.writeBoolean(area.facing() != null);
            if (area.facing() != null) vector(buf, area.facing());
            vector(buf, area.source().min());
            vector(buf, area.source().max());
            buf.writeBoolean(area.includeOrigin());
        });
    }

    public static OnlineView read(FriendlyByteBuf in) {
        var party = readList(in, MAX_PARTY, buf -> new OnlineMember(buf.readUUID(), buf.readUtf(80), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), optionalId(buf), optionalId(buf)));
        ReadyView ready = null;
        if (in.readBoolean()) {
            UUID id = in.readUUID();
            String label = in.readUtf(80);
            boolean complete = in.readBoolean();
            int size = PacketPolicy.count(in.readVarInt(), MAX_PARTY);
            var members = new LinkedHashMap<UUID, Status>();
            for (int i = 0; i < size; i++) members.put(in.readUUID(), readEnum(in, Status.class));
            ready = new ReadyView(id, label, members, complete);
        }
        var zones = readList(in, MAX_ZONES, buf -> {
            String spell = buf.readUtf(64);
            var template = new AoEGeometry.Template(readEnum(buf, AoEGeometry.class), buf.readDouble(), buf.readDouble());
            var origin = vector(buf);
            var facing = buf.readBoolean() ? vector(buf) : null;
            var bounds = new Box(vector(buf), vector(buf));
            return new SpellZone(spell, template.place(origin, facing, bounds, buf.readBoolean()));
        });
        return new OnlineView(party, ready, zones);
    }

    public static void handout(FriendlyByteBuf buf, Handout handout) {
        buf.writeUtf(handout.title(), Protocol.HANDOUT_TITLE_LIMIT);
        buf.writeUtf(handout.body(), Protocol.HANDOUT_BODY_LIMIT);
    }

    public static Handout handout(FriendlyByteBuf buf) {
        return new Handout(buf.readUtf(Protocol.HANDOUT_TITLE_LIMIT), buf.readUtf(Protocol.HANDOUT_BODY_LIMIT));
    }

    private static void optionalId(FriendlyByteBuf buf, UUID id) {
        buf.writeBoolean(id != null);
        if (id != null) buf.writeUUID(id);
    }

    private static UUID optionalId(FriendlyByteBuf buf) { return buf.readBoolean() ? buf.readUUID() : null; }

    private static void vector(FriendlyByteBuf buf, Vec3 vector) {
        buf.writeDouble(vector.x());
        buf.writeDouble(vector.y());
        buf.writeDouble(vector.z());
    }

    private static Vec3 vector(FriendlyByteBuf buf) { return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()); }
}
