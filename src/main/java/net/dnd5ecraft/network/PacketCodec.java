package net.dnd5ecraft.network;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.entity.TriggerZoneSpec;
import net.dnd5ecraft.network.PacketPolicy.TurnToken;
import net.dnd5ecraft.network.Protocol.*;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.minecraft.network.FriendlyByteBuf;

public final class PacketCodec {
    private PacketCodec() {}

    public static <E extends Enum<E>> E readEnum(FriendlyByteBuf buf, Class<E> type) {
        var values = type.getEnumConstants();
        return values[PacketPolicy.count(buf.readVarInt(), values.length - 1)];
    }

    public static void writeEnum(FriendlyByteBuf buf, Enum<?> value) { buf.writeVarInt(value.ordinal()); }

    public static <T> List<T> readList(FriendlyByteBuf buf, int max, Function<FriendlyByteBuf, T> read) {
        int count = PacketPolicy.count(buf.readVarInt(), max);
        var items = new ArrayList<T>(count);
        for (int index = 0; index < count; index++) items.add(read.apply(buf));
        return List.copyOf(items);
    }

    public static <T> void writeList(FriendlyByteBuf buf, List<T> list, BiConsumer<FriendlyByteBuf, T> write) {
        buf.writeVarInt(list.size());
        list.forEach(value -> write.accept(buf, value));
    }

    public static Cell cell(FriendlyByteBuf buf) {
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        if (Math.abs((long) x) > 30_000_000 || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2048) {
            throw new IllegalArgumentException("Invalid grid position");
        }
        return new Cell(x, y, z);
    }

    public static void cell(FriendlyByteBuf buf, Cell cell) {
        buf.writeInt(cell.x());
        buf.writeInt(cell.y());
        buf.writeInt(cell.z());
    }

    public static Vec3 point(FriendlyByteBuf buf) {
        Vec3 result = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        if (Math.abs(result.x()) > 30_000_000 || Math.abs(result.z()) > 30_000_000 || Math.abs(result.y()) > 2048) {
            throw new IllegalArgumentException("Invalid aim position");
        }
        return result;
    }

    public static void point(FriendlyByteBuf buf, Vec3 point) {
        buf.writeDouble(point.x());
        buf.writeDouble(point.y());
        buf.writeDouble(point.z());
    }

    public static TurnToken turn(FriendlyByteBuf buf) {
        return new TurnToken(buf.readUUID(), buf.readUUID(), buf.readVarInt());
    }

    public static void turn(FriendlyByteBuf buf, TurnToken turn) {
        buf.writeUUID(turn.encounter());
        buf.writeUUID(turn.actor());
        buf.writeVarInt(turn.round());
    }

    public static State state(FriendlyByteBuf buf) { return state(buf, false); }

    public static State state(FriendlyByteBuf buf, boolean timed) {
        boolean operator = buf.readBoolean();
        boolean dm = buf.readBoolean();
        int darkvision = PacketPolicy.count(buf.readVarInt(), 240);
        return new State(operator, dm, darkvision, buf.readBoolean() ? combat(buf, timed) : null);
    }

    public static void state(FriendlyByteBuf buf, State state) { state(buf, state, false); }

    public static void state(FriendlyByteBuf buf, State state, boolean timed) {
        buf.writeBoolean(state.operator());
        buf.writeBoolean(state.dm());
        buf.writeVarInt(state.darkvision());
        buf.writeBoolean(state.combat() != null);
        if (state.combat() != null) combat(buf, state.combat(), timed);
    }

    private static Combat combat(FriendlyByteBuf buf, boolean timed) {
        UUID id = buf.readUUID();
        UUID actor = buf.readUUID();
        UUID active = buf.readUUID();
        int round = buf.readVarInt();
        boolean paused = buf.readBoolean();
        List<Creature> creatures = readList(buf, Protocol.MAX_COMBATANTS, PacketCodec::creature);
        Set<Resource> resources = fromMask(Resource.class, buf.readVarInt());
        int movement = buf.readVarInt();
        int length = PacketPolicy.count(buf.readVarInt(), Protocol.MAX_CELLS);
        Map<Cell, Integer> cells = new LinkedHashMap<>();
        Map<Cell, List<UUID>> threats = new LinkedHashMap<>();
        for (int i = 0; i < length; i++) {
            Cell cell = cell(buf);
            cells.put(cell, buf.readVarInt());
            var danger = readList(buf, Protocol.MAX_COMBATANTS, FriendlyByteBuf::readUUID);
            if (!danger.isEmpty()) threats.put(cell, danger);
        }
        var attacks = readList(buf, 16, value -> value.readUtf(80));
        var spells = readList(buf, 80, value -> value.readUtf(64));
        Slots slots = new Slots(readSlots(buf), readSlots(buf));
        Reaction reaction = null;
        if (buf.readBoolean()) {
            long window = buf.readVarLong();
            int remaining = buf.readVarInt();
            var choices = readList(buf, Kind.values().length, value -> readEnum(value, Kind.class));
            reaction = timed ? new Reaction(window, remaining, choices, buf.readVarInt()) : new Reaction(window, remaining, choices);
        }
        return new Combat(id, actor, active, round, paused, creatures, resources, movement, cells, threats, attacks, spells, slots, reaction);
    }

    private static void combat(FriendlyByteBuf buf, Combat state, boolean timed) {
        buf.writeUUID(state.id());
        buf.writeUUID(state.controlledActor() == null ? Protocol.NO_ACTOR : state.controlledActor());
        buf.writeUUID(state.activeActor() == null ? Protocol.NO_ACTOR : state.activeActor());
        buf.writeVarInt(state.round());
        buf.writeBoolean(state.paused());
        writeList(buf, state.initiative(), PacketCodec::creature);
        buf.writeVarInt(mask(state.resources()));
        buf.writeVarInt(state.movement());
        buf.writeVarInt(state.reachable().size());
        state.reachable().forEach((cell, cost) -> {
            cell(buf, cell);
            buf.writeVarInt(cost);
            writeList(buf, state.threats().getOrDefault(cell, List.of()), FriendlyByteBuf::writeUUID);
        });
        writeList(buf, state.attacks(), (output, value) -> output.writeUtf(value, 80));
        writeList(buf, state.spells(), (output, value) -> output.writeUtf(value, 64));
        state.slots().regular().forEach(buf::writeVarInt);
        state.slots().pact().forEach(buf::writeVarInt);
        var reaction = state.reaction();
        buf.writeBoolean(reaction != null);
        if (reaction != null) {
            buf.writeVarLong(reaction.id());
            buf.writeVarInt(reaction.millisecondsLeft());
            writeList(buf, reaction.choices(), PacketCodec::writeEnum);
            if (timed) buf.writeVarInt(reaction.totalDurationMs());
        }
    }

    private static Creature creature(FriendlyByteBuf buf) {
        return new Creature(buf.readUUID(), buf.readUtf(80), buf.readVarInt(), buf.readVarInt(), cell(buf),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                readEnum(buf, DeathSaveTracker.State.class), fromMask(ConditionType.class, buf.readVarInt()), buf.readBoolean());
    }

    private static void creature(FriendlyByteBuf buf, Creature value) {
        buf.writeUUID(value.id());
        buf.writeUtf(value.name(), 80);
        buf.writeVarInt(value.team());
        buf.writeVarInt(value.initiative());
        cell(buf, value.cell());
        buf.writeVarInt(value.hp());
        buf.writeVarInt(value.maxHp());
        buf.writeVarInt(value.temporary());
        buf.writeVarInt(value.armor());
        buf.writeVarInt(value.reach());
        writeEnum(buf, value.life());
        buf.writeVarInt(mask(value.conditions()));
        buf.writeBoolean(value.offline());
    }

    private static List<Integer> readSlots(FriendlyByteBuf buf) {
        List<Integer> values = new ArrayList<>(9);
        for (int level = 0; level < 9; level++) values.add(PacketPolicy.count(buf.readVarInt(), 20));
        return values;
    }

    private static int mask(Set<? extends Enum<?>> values) {
        int result = 0;
        for (Enum<?> value : values) result |= 1 << value.ordinal();
        return result;
    }

    private static <E extends Enum<E>> Set<E> fromMask(Class<E> type, int mask) {
        EnumSet<E> values = EnumSet.noneOf(type);
        for (E value : type.getEnumConstants()) if ((mask & (1 << value.ordinal())) != 0) values.add(value);
        return values;
    }

    public static void dialogueOption(FriendlyByteBuf buf, DialogueOption value) {
        buf.writeUtf(value.id(), 64);
        buf.writeUtf(value.label(), 256);
        buf.writeBoolean(value.skill() != null);
        if (value.skill() != null) {
            writeEnum(buf, value.skill());
            buf.writeVarInt(value.dc());
        }
    }

    public static DialogueOption dialogueOption(FriendlyByteBuf buf) {
        String id = buf.readUtf(64);
        String text = buf.readUtf(256);
        return buf.readBoolean() ? new DialogueOption(id, text, readEnum(buf, Skill.class), buf.readVarInt())
                : new DialogueOption(id, text, null, 0);
    }

    public static void roll(FriendlyByteBuf buf, Roll roll) {
        buf.writeUUID(roll.player());
        buf.writeUtf(roll.name(), 80);
        point(buf, roll.position());
        buf.writeVarInt(roll.result());
        buf.writeLong(roll.seed());
    }

    public static Roll roll(FriendlyByteBuf buf) {
        UUID player = buf.readUUID();
        String name = buf.readUtf(80);
        Vec3 position = point(buf);
        int result = PacketPolicy.count(buf.readVarInt(), 20);
        if (result == 0) throw new IllegalArgumentException("Invalid d20 result");
        return new Roll(player, name, position, result, buf.readLong());
    }

    public static void zoneSpec(FriendlyByteBuf buf, TriggerZoneSpec spec) {
        cell(buf, spec.min());
        cell(buf, spec.max());
        writeEnum(buf, spec.mode());
        buf.writeVarInt(spec.cooldownTicks());
        buf.writeBoolean(spec.oneShot());
        buf.writeVarInt(spec.damage());
        writeEnum(buf, spec.ambusher());
        buf.writeVarInt(spec.ambushers());
        buf.writeVarInt(spec.perceptionDc());
        buf.writeVarInt(spec.investigationDc());
        buf.writeVarInt(spec.disarmDc());
        buf.writeVarInt(spec.saveDc());
        buf.writeUtf(spec.title(), 100);
    }

    public static TriggerZoneSpec zoneSpec(FriendlyByteBuf buf) {
        return new TriggerZoneSpec(cell(buf), cell(buf), readEnum(buf, TriggerZoneSpec.Mode.class),
                buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), readEnum(buf, BestiaryPreset.class), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(100));
    }
}
