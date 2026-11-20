package net.dnd5ecraft.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.dnd5ecraft.rules.campaign.PartyReadyCheck;
import net.dnd5ecraft.rules.campaign.PartyReadyCheck.Status;
import net.dnd5ecraft.rules.equipment.CoinPurse;
import net.dnd5ecraft.rules.equipment.CoinPurse.Denomination;
import net.dnd5ecraft.rules.spells.AoEGeometry;
import net.dnd5ecraft.rules.spells.AoEGeometry.Box;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OnlinePartyTest {
    @Test
    void transfersExactDenominationAndRejectsInsufficientFundsBeforeAnyChange() {
        var sender = new CoinPurse(40, 16, 9, 3, 1);
        var recipient = new CoinPurse(0, 0, 1, 2, 0);
        var transfer = sender.transfer(recipient, 5, Denomination.EP).orElseThrow();
        assertEquals(new CoinPurse(40, 16, 4, 3, 1), transfer.payer());
        assertEquals(new CoinPurse(0, 0, 6, 2, 0), transfer.payee());
        assertTrue(sender.transfer(recipient, 4, Denomination.GP).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> sender.transfer(recipient, 0, Denomination.CP));
        assertThrows(ArithmeticException.class,
                () -> sender.transfer(new CoinPurse(Long.MAX_VALUE, 0, 0, 0, 0), 1, Denomination.CP));
    }

    @Test
    void readyCheckTracksIndividualStatusAndIgnoresStaleOrUnauthorizedResponses() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var check = new PartyReadyCheck("Start the fight", List.of(first, second));
        assertFalse(check.respond(UUID.randomUUID(), first, true));
        assertFalse(check.respond(check.id(), UUID.randomUUID(), true));
        assertTrue(check.respond(check.id(), first, true));
        assertTrue(check.respond(check.id(), second, false));
        assertEquals(Map.of(first, Status.READY, second, Status.NEED_TIME), check.members());
        assertFalse(check.allReady());
        check.leave(second);
        assertTrue(check.allReady());
        check.join(second);
        assertFalse(check.allReady());
        assertTrue(check.respond(check.id(), second, true));
        assertTrue(check.allReady());
    }

    @Test
    void snapshotRoundTripsOfflineRosterCompanionsReadyStatusAndActiveGeometry() {
        var id = UUID.randomUUID();
        var ready = new Protocol.ReadyView(UUID.randomUUID(), "New chapter", Map.of(id, Status.READY), true);
        var position = new Vec3(3.5, -64, 9.5);
        var area = new AoEGeometry.Template(AoEGeometry.SPHERE, 15).place(position, null, new Box(position, position), true);
        var online = new Protocol.OnlineView(List.of(new Protocol.OnlineMember(id, "Mage", 10, 12, 14, false,
                UUID.randomUUID(), null)), ready, List.of(new Protocol.SpellZone("fog_cloud", area)));
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            OnlineCodec.write(buf, online);
            assertEquals(online, OnlineCodec.read(buf));
            assertFalse(buf.isReadable());
        } finally { buf.release(); }
    }
}
