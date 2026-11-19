package net.dnd5ecraft.network;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.combat.ReactionWindowManager;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.network.Protocol.Combat;
import net.dnd5ecraft.network.Protocol.Creature;
import net.dnd5ecraft.network.Protocol.Reaction;
import net.dnd5ecraft.network.Protocol.State;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class HardeningTest {
    @Test
    void emptyAndObserverInitiativeCanRenderWithoutAControlledActor() {
        var empty = combat(null, List.of(), null);
        assertEquals(List.of(), CombatHudView.initiative(empty, 4));
        assertFalse(empty.hasActor());
        assertFalse(empty.ownsTurn());
        assertEquals(0, empty.slots().available(1, false));

        var ally = creature(1, 0);
        var enemy = creature(2, 1);
        var observer = combat(enemy.id(), List.of(ally, enemy), null);
        assertNull(observer.creature(observer.controlledActor()));
        assertFalse(observer.ownsTurn());
        assertEquals(List.of(enemy), CombatHudView.initiative(observer, 1));
        assertEquals(List.of(ally, enemy), CombatHudView.initiative(observer, 4));
        assertTrue(CombatHudView.allied(observer, ally));
        assertFalse(CombatHudView.allied(observer, enemy));
        assertEquals(0.0, CombatHudView.healthFraction(1, 0));
        assertEquals(1.0, CombatHudView.healthFraction(12, 10));
        assertEquals(0.0, CombatHudView.healthFraction(-1, 10));

        var stale = new Combat(observer.id(), enemy.id(), enemy.id(), 1, false, List.of(ally),
                Set.of(), 0, Map.of(), Map.of(), List.of(), List.of(), Protocol.Slots.EMPTY, null);
        assertFalse(stale.ownsTurn());
        assertEquals(List.of(ally), CombatHudView.initiative(stale, 1));
        assertEquals(new State(true, true, 0, empty), roundTrip(new State(true, true, 0, empty), true));
    }

    @Test
    void timedV2StateUsesTheServerDurationAndLegacyLayoutUsesTheDefault() {
        var actor = creature(1, 0);
        var windows = new ReactionWindowManager(8_000);
        var window = windows.open(List.of(new ReactionWindowManager.Offer(actor.id(), Kind.SHIELD)), 200);
        assertEquals(8_200, window.deadlineMillis());
        assertEquals(8_000, window.totalDurationMs());
        var reaction = new Reaction(window.id(), 2_000, List.of(Kind.SHIELD), window.totalDurationMs());
        assertEquals(0.25, reaction.progress(reaction.millisecondsLeft()));
        assertEquals(1.0, reaction.progress(20_000));
        assertEquals(0.0, reaction.progress(-1));
        assertEquals(3_500, new Reaction(1, 20_000, List.of(), 0).millisecondsLeft());

        var state = new State(true, true, 0, combat(actor.id(), List.of(actor), reaction));
        assertEquals(state, roundTrip(state, true));
        var legacy = roundTrip(state, false);
        assertEquals(new Reaction(window.id(), 2_000, List.of(Kind.SHIELD)), legacy.combat().reaction());
    }

    @Test
    void replacementPlayersRetainPendingSessionStateUntilDisconnect() {
        record Player(UUID id, String dimension) {}
        record Session(List<Integer> pendingRolls, String ritual, UUID encounter) {}
        var store = new PlayerSessionStore<Session>();
        UUID id = new UUID(0, 1);
        var before = new Player(id, "overworld");
        var afterRespawn = new Player(id, "overworld");
        var afterDimensionChange = new Player(id, "the_nether");
        var session = store.getOrCreate(before.id(), () -> new Session(new ArrayList<>(), "detect_magic", new UUID(0, 2)));
        session.pendingRolls().add(17);
        assertNotSame(before, afterRespawn);
        assertSame(session, store.getOrCreate(afterRespawn.id(), () -> fail("respawn discarded the session")));
        var transferred = store.getOrCreate(afterDimensionChange.id(), () -> fail("transfer discarded the session"));
        assertSame(session, transferred);
        assertEquals(List.of(17), transferred.pendingRolls());
        assertEquals("detect_magic", transferred.ritual());
        assertEquals(new UUID(0, 2), transferred.encounter());

        UUID other = new UUID(0, 3);
        var otherSession = store.getOrCreate(other, () -> new Session(List.of(6), null, null));
        store.remove(id);
        assertNotSame(session, store.getOrCreate(id, () -> new Session(List.of(), null, null)));
        assertSame(otherSession, store.getOrCreate(other, () -> fail("disconnect removed an unrelated session")));
    }

    private static Combat combat(UUID active, List<Creature> roster, Reaction reaction) {
        return new Combat(new UUID(0, 3), reaction == null ? null : active, active, 1, false, roster, Set.of(), 0,
                Map.of(), Map.of(), List.of(), List.of(), Protocol.Slots.EMPTY, reaction);
    }

    private static Creature creature(long id, int team) {
        return new Creature(new UUID(0, id), "Creature " + id, team, 20 - (int) id, new Cell((int) id, 0, 0),
                10, 10, 0, 12, 5, DeathSaveTracker.State.CONSCIOUS, Set.of());
    }

    private static State roundTrip(State state, boolean timed) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PacketCodec.state(buffer, state, timed);
            State decoded = PacketCodec.state(buffer, timed);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
