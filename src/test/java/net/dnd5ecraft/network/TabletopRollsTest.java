package net.dnd5ecraft.network;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.DiceRoller;
import net.dnd5ecraft.rules.ability.TabletopRolls;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.ability.TabletopRolls.Kind;
import net.dnd5ecraft.rules.equipment.DamagePipeline.DamageRoll;
import net.dnd5ecraft.rules.equipment.DamagePipeline.Dice;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TabletopRollsTest {
    @Test
    void whispersAreVisibleOnlyToTheRollerAndAuthorizedOperators() {
        assertTrue(TabletopRolls.visible(true, true, false));
        assertTrue(TabletopRolls.visible(true, false, true));
        assertFalse(TabletopRolls.visible(true, false, false));
        assertTrue(TabletopRolls.visible(false, false, false));
    }

    @Test
    void quickTrayAcceptsBoundedPoolsAndRejectsUntrustedFormulas() {
        for (int sides : TabletopRolls.DICE) {
            int highest = sides == 100 ? 1 : 8;
            for (int count = 1; count <= highest; count++) {
                assertEquals(new TabletopRolls.DicePool(count, sides), TabletopRolls.tray(count + "d" + sides));
            }
        }
        for (String invalid : List.of("9d20", "0d4", "2d100", "1d1000", "2d6kh1", "8d4+4", "1d3", "d6")) {
            assertThrows(IllegalArgumentException.class, () -> new Check(Kind.DICE, invalid), invalid);
        }
    }

    @Test
    void modifiersOverrideTheStoredModeAndInspirationUpgradesTheRoll() {
        assertSame(RollMode.ADVANTAGE, TabletopRolls.clickMode(RollMode.DISADVANTAGE, true, false));
        assertSame(RollMode.DISADVANTAGE, TabletopRolls.clickMode(RollMode.ADVANTAGE, false, true));
        assertSame(RollMode.NORMAL, TabletopRolls.clickMode(RollMode.ADVANTAGE, true, true));
        assertSame(RollMode.DISADVANTAGE, TabletopRolls.clickMode(RollMode.DISADVANTAGE, false, false));
        assertSame(RollMode.ADVANTAGE, TabletopRolls.checkMode(RollMode.DISADVANTAGE, true, false));
        assertSame(RollMode.NORMAL, TabletopRolls.checkMode(RollMode.NORMAL, true, true));
    }

    @Test
    void CriticalDoublesOnlyDamageDiceAndKeepsFlatAndPowerBonusesOnce() {
        var weapon = new DamageRoll(DamageType5e.SLASHING, new Dice(2, 6), 3);
        var roller = new DiceRoller((java.util.function.IntUnaryOperator) sides -> 4);
        assertEquals("2d6+3", TabletopRolls.damageFormula(weapon, false, 0));
        assertEquals("4d6+13", TabletopRolls.damageFormula(weapon, true, 10));
        var result = TabletopRolls.weaponDamage(roller, weapon, true, 10);
        assertTrue(result.critical());
        assertEquals(List.of(4, 4, 4, 4), result.dice());
        assertEquals(13, result.modifier());
        assertEquals(29, result.total());
        var unarmed = new DamageRoll(DamageType5e.BLUDGEONING, new Dice(0, 0), -2);
        var flat = TabletopRolls.weaponDamage(roller, unarmed, true, 0);
        assertEquals(List.of(), flat.dice());
        assertEquals(0, flat.total());
    }

    @Test
    void tabletopWireRoundTripsStructuredCardsAndExactServerPrompts() {
        var roller = new UUID(0, 1);
        var dm = new UUID(0, 2);
        var check = new Check(Kind.SKILL, "PERCEPTION");
        var prompt = new TabletopCodec.Prompt(new UUID(0, 3), "DM", check, RollMode.DISADVANTAGE, 14, true);
        var result = new TabletopCodec.Card(roller, "Player", check, Component.literal("Perception"),
                new TabletopRolls.Outcome(List.of(13, 7), 7, 5, 12, 20, RollMode.DISADVANTAGE, false), 14, dm, true);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            TabletopCodec.prompt(buffer, prompt);
            TabletopCodec.card(buffer, result);
            assertEquals(prompt, TabletopCodec.prompt(buffer));
            assertEquals(result, TabletopCodec.card(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
        assertThrows(IllegalArgumentException.class, () -> new TabletopCodec.Prompt(dm, "DM", check, RollMode.NORMAL, 31, false));
        assertThrows(IllegalArgumentException.class, () -> new TabletopCodec.Prompt(dm, "DM",
                new Check(Kind.DICE, "1d20"), RollMode.NORMAL, 10, false));
    }
}
