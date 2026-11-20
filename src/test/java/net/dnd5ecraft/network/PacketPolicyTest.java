package net.dnd5ecraft.network;

import java.util.List;
import java.util.UUID;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.PacketPolicy.TurnToken;
import net.dnd5ecraft.network.Protocol.AbilityMethod;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.character.BackgroundType;
import net.dnd5ecraft.rules.character.CharacterSheetData.Personality;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.RaceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketPolicyTest {
    @Test
    void alteredActorsRoundsOrEncountersNeverGrantAuthorityEvenToAnOperator() {
        UUID encounter = new UUID(0, 1), actor = new UUID(0, 2), stranger = new UUID(0, 3);
        var valid = new TurnToken(encounter, actor, 2);
        for (Action action : Action.values()) {
            if (!action.turn()) continue;
            PacketPolicy.require(action, false, valid, valid);
            assertThrows(IllegalStateException.class, () -> PacketPolicy.require(action, true, valid, null));
            assertThrows(IllegalStateException.class, () -> PacketPolicy.require(action, true, new TurnToken(encounter, stranger, 2), valid));
            assertThrows(IllegalStateException.class, () -> PacketPolicy.require(action, false, new TurnToken(encounter, actor, 1), valid));
            assertThrows(IllegalStateException.class, () -> PacketPolicy.require(action, false, new TurnToken(stranger, actor, 2), valid));
        }
    }

    @Test
    void operatorsAreRequiredForEveryWorldEditingAction() {
        for (Action action : List.of(Action.DM_MODE, Action.START_COMBAT, Action.STOP_COMBAT, Action.POSSESS, Action.RELEASE,
                Action.PILOT, Action.NPC_INSPECT, Action.NPC_SAVE, Action.NPC_SPAWN, Action.ZONE_INSPECT, Action.ZONE_SAVE, Action.PARTY)) {
            assertThrows(IllegalStateException.class, () -> PacketPolicy.require(action, false, null, null));
            PacketPolicy.require(action, true, null, null);
        }
        PacketPolicy.require(Action.REACT, false, null, null);
    }

    @Test
    void packetLimitsRejectOversizedFieldsAndThrottleBursts() {
        assertEquals(16, PacketPolicy.count(16, 16));
        assertThrows(IllegalArgumentException.class, () -> PacketPolicy.count(17, 16));
        assertThrows(IllegalArgumentException.class, () -> PacketPolicy.count(-1, 16));
        var limit = new PacketPolicy.RateLimit();
        for (int i = 0; i < 20; i++) assertTrue(limit.take(100));
        assertFalse(limit.take(99_000_000));
        assertTrue(limit.take(100_000_100));
        assertFalse(limit.take(100_000_101));
        assertTrue(limit.take(200_000_100));
    }

    @Test
    void creationUsesValidatedPointBuyAndServerOwnedRolls() {
        var valid = draft(AbilityMethod.POINT_BUY, List.of(15, 15, 15, 8, 8, 8));
        assertEquals(15, valid.create(List.of()).score(Ability.STRENGTH));
        assertThrows(IllegalArgumentException.class, () -> draft(AbilityMethod.POINT_BUY,
                List.of(15, 15, 15, 9, 8, 8)).create(List.of()));
        var rolled = draft(AbilityMethod.ROLLED, List.of(16, 17, 12, 11, 10, 9));
        assertThrows(IllegalArgumentException.class, () -> rolled.create(List.of()));
        assertThrows(IllegalArgumentException.class, () -> rolled.create(List.of(16, 17, 12, 11, 10, 8)));
        assertEquals(17, rolled.create(List.of(11, 17, 9, 16, 10, 12)).score(Ability.DEXTERITY));
        assertThrows(IllegalArgumentException.class, () -> draft(AbilityMethod.STANDARD_ARRAY,
                List.of(15, 15, 13, 12, 10, 8)).create(List.of()));
    }

    @Test
    void roundTripsAdvancedZoneSettings() {
        var position = new net.dnd5ecraft.combat.GridMovementTracker.Cell(1, 4, -3);
        var spec = new net.dnd5ecraft.entity.TriggerZoneSpec(position, position,
                net.dnd5ecraft.entity.TriggerZoneSpec.Mode.AMBUSH_CUTSCENE, 120, true, 15,
                net.dnd5ecraft.entity.BestiaryPreset.BUGBEAR, 3, 12, 14, 15, 16, "A trap in the keep");
        var packet = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            PacketCodec.zoneSpec(packet, spec);
            assertEquals(spec, PacketCodec.zoneSpec(packet));
            assertFalse(packet.isReadable());
        } finally {
            packet.release();
        }
    }

    private static CharacterDraft draft(AbilityMethod method, List<Integer> scores) {
        return new CharacterDraft("Ada", RaceType.HUMAN, BackgroundType.SOLDIER, ClassType.FIGHTER, method, scores, Personality.EMPTY);
    }
}
