package net.dnd5ecraft.rules.spells;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.spells.AoEGeometry.Box;
import net.dnd5ecraft.rules.spells.AoEGeometry.Template;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class SpellRulesTest {
    private static final Vec3 ZERO = new Vec3(0, 0, 0);
    private static final Vec3 EAST = new Vec3(1, 0, 0);

    @ParameterizedTest
    @EnumSource(AoEGeometry.class)
    void resolvesEachVolumeAndRespectsObstruction(AoEGeometry geometry) {
        Vec3 inside;
        Vec3 outside;
        Template template;
        if (geometry == AoEGeometry.SINGLE) {
            var area = new Template(geometry, 5).place(ZERO, EAST, Box.point(ZERO), true);
            assertTrue(area.contains(ZERO));
            assertFalse(area.contains(EAST));
            return;
        }
        switch (geometry) {
            case CONE -> {
                template = new Template(geometry, 15);
                inside = new Vec3(2, .9, 0);
                outside = new Vec3(2, 1.1, 0);
            }
            case SPHERE -> {
                template = new Template(geometry, 10);
                inside = new Vec3(1, 1, 1);
                outside = new Vec3(1.5, 1.5, 0);
            }
            case CUBE -> {
                template = new Template(geometry, 15);
                inside = new Vec3(2, 1.4, 1.4);
                outside = new Vec3(2, 1.6, 0);
            }
            case LINE -> {
                template = new Template(geometry, 20, 5);
                inside = new Vec3(3, .49, .49);
                outside = new Vec3(3, .51, 0);
            }
            case CYLINDER -> {
                template = new Template(geometry, 10, 15);
                inside = new Vec3(1, 3, 0);
                outside = new Vec3(1, 3.1, 0);
            }
            case EMANATION -> {
                template = new Template(geometry, 5);
                inside = new Vec3(1.5, 0, 0);
                outside = new Vec3(1.6, 0, 0);
            }
            default -> throw new AssertionError(geometry);
        }
        var source = new Box(new Vec3(-.5, -.5, -.5), new Vec3(.5, .5, .5));
        var area = template.place(ZERO, EAST, source, false);
        assertTrue(area.contains(inside));
        assertFalse(area.contains(outside));
        assertFalse(area.contains(ZERO));
        assertEquals(List.of(inside), AoETargetResolver.resolve(area, List.of(inside, outside, ZERO),
                position -> position, (from, to) -> true));
        assertEquals(List.of(), AoETargetResolver.resolve(area, List.of(inside),
                position -> position, (from, to) -> false));
    }

    @Test
    void supportsVerticalAndReversedVolumes() {
        var vertical = new Template(AoEGeometry.LINE, 40, 5)
                .place(ZERO, new Vec3(0, 1, 0), Box.point(ZERO), true);
        assertTrue(vertical.contains(new Vec3(.4, 7, -.4)));
        assertFalse(vertical.contains(new Vec3(.6, 7, 0)));
        assertFalse(vertical.contains(new Vec3(0, -1, 0)));
        var reversed = new Template(AoEGeometry.CUBE, 15)
                .place(ZERO, new Vec3(0, 0, -10), Box.point(ZERO), true);
        assertTrue(reversed.contains(new Vec3(0, 0, -3)));
        assertFalse(reversed.contains(new Vec3(0, 0, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> new Template(AoEGeometry.CONE, 15).place(ZERO, ZERO, Box.point(ZERO), false));
        assertThrows(IllegalArgumentException.class, () -> new Template(AoEGeometry.LINE, 50));
    }

    @Test
    void fireCanSpreadAroundAPillarButNotThroughASealedWall() {
        Vec3 origin = new Vec3(.5, .5, .5);
        Vec3 target = new Vec3(2.5, .5, .5);
        var sphere = new Template(AoEGeometry.SPHERE, 20).place(origin, EAST, Box.point(origin), true);
        assertEquals(List.of(target), AoETargetResolver.resolveSpread(sphere, List.of(target), v -> v,
                v -> v.equals(new Vec3(1.5, .5, .5))));
        assertEquals(List.of(), AoETargetResolver.resolveSpread(sphere, List.of(target), v -> v, v -> v.x() == 1.5));
    }

    @Test
    void registryHasStableIdsSlotsSchoolsAndScaling() {
        assertEquals(47, SpellRegistry5e.all().size());
        Spell fireball = SpellRegistry5e.require("fireball");
        assertEquals(SpellSchool.EVOCATION, fireball.school());
        assertTrue(fireball.canUseSlot(3));
        assertFalse(fireball.canUseSlot(2));
        assertEquals(10, fireball.effect().diceAt(3, 5, 9));
        assertEquals(4, SpellRegistry5e.require("fire_bolt").effect().diceAt(0, 0, 17));
        assertEquals(3, SpellRegistry5e.require("eldritch_blast").targetsAt(0, 11));
        assertEquals(4, SpellRegistry5e.require("magic_missile").targetsAt(2, 3));
        assertEquals(10, SpellRegistry5e.require("detect_magic").ritualCastingMinutes());
        assertThrows(IllegalStateException.class, fireball::ritualCastingMinutes);
        assertThrows(UnsupportedOperationException.class, () -> SpellRegistry5e.all().clear());
        assertTrue(SpellRegistry5e.find("unknown").isEmpty());
    }

    @Test
    void combinesRegularCastersAndTracksPactRestSeparately() {
        assertEquals(0, SpellSlotTable.effectiveLevel(Map.of(ClassType.RANGER, 1)));
        assertEquals(2, SpellSlotTable.effectiveLevel(Map.of(ClassType.PALADIN, 3, ClassType.FIGHTER, 1)));
        assertEquals(4, SpellSlotTable.effectiveLevel(Map.of(ClassType.PALADIN, 3, ClassType.WIZARD, 3, ClassType.WARLOCK, 2)));
        assertEquals(2, SpellSlotTable.regularSlots(20)[6]);
        assertEquals(1, SpellSlotTable.regularSlots(17)[8]);
        assertEquals(new SpellSlotTable.PactSlots(1, 1), SpellSlotTable.pactSlots(1));
        assertEquals(new SpellSlotTable.PactSlots(5, 3), SpellSlotTable.pactSlots(11));
        assertEquals(new SpellSlotTable.PactSlots(5, 4), SpellSlotTable.pactSlots(17));
        var ledger = new SpellSlotTable.Ledger(Map.of(ClassType.WIZARD, 3, ClassType.WARLOCK, 3));
        assertTrue(ledger.spend(Pool.REGULAR, 2));
        assertTrue(ledger.spend(Pool.PACT, 2));
        assertFalse(ledger.spend(Pool.PACT, 1));
        ledger.shortRest();
        assertEquals(1, ledger.available(Pool.REGULAR, 2));
        assertEquals(2, ledger.available(Pool.PACT, 2));
        ledger.longRest();
        assertEquals(2, ledger.available(Pool.REGULAR, 2));
        assertThrows(IllegalArgumentException.class, () -> SpellSlotTable.pactSlots(21));
    }

    @Test
    void concentrationReplacesOnlyTheActiveCastAndChecksEachDamageEvent() {
        UUID caster = new UUID(0, 1);
        var tracker = new ConcentrationManager();
        Spell bless = SpellRegistry5e.require("bless");
        assertEquals(10, ConcentrationManager.damageDc(21));
        assertEquals(11, ConcentrationManager.damageDc(23));
        assertEquals(25, ConcentrationManager.damageDc(50));
        assertTrue(tracker.begin(caster, bless, 1).isEmpty());
        var old = tracker.begin(caster, SpellRegistry5e.require("bane"), 2).orElseThrow();
        assertFalse(tracker.end(old));
        assertTrue(tracker.onDamage(caster, 0, false, 0, RollMode.NORMAL,
                sides -> { throw new AssertionError("Zero damage must not roll"); }).isEmpty());
        assertTrue(tracker.onDamage(caster, 20, false, 1, RollMode.NORMAL, sides -> 9).isEmpty());
        AtomicInteger die = new AtomicInteger(10);
        assertEquals("bane", tracker.onDamage(caster, 22, false, 0, RollMode.DISADVANTAGE,
                sides -> die.getAndIncrement()).orElseThrow().spellId());
        assertTrue(tracker.begin(caster, bless, 3).isEmpty());
        assertTrue(tracker.onDamage(caster, 0, true, 0, RollMode.NORMAL,
                sides -> { throw new AssertionError("Incapacitation must break concentration immediately"); }).isPresent());
        assertTrue(tracker.current(caster).isEmpty());
    }
}
