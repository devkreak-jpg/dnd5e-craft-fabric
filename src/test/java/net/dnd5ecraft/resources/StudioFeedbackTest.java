package net.dnd5ecraft.resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import javax.imageio.ImageIO;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.campaign.TacticalFeedback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class StudioFeedbackTest {
    @ParameterizedTest
    @CsvSource({"15,8,NORMAL,.70", "15,8,ADVANTAGE,.91", "15,8,DISADVANTAGE,.49",
            "80,-9,NORMAL,.05", "0,99,NORMAL,.95", "80,-9,ADVANTAGE,.0975", "0,99,DISADVANTAGE,.9025"})
    void exactD20Probability(int armor, int bonus, RollMode mode, double expected) {
        assertEquals(expected, TacticalFeedback.hitChance(armor, bonus, mode), 1e-12);
    }

    @Test void rulerAndMonotonicRateLimit() throws Exception {
        assertEquals(5, TacticalFeedback.rulerBlocks(5.24));
        assertEquals(25, TacticalFeedback.rulerFeet(5));
        var file = Path.of("src/main/resources/assets/dnd-5e-craft/lang/en_us.json");
        var format = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                .get("dnd-5e-craft.ui.ping.ruler").getAsString();
        assertEquals("[Scout] 25 ft (5 bl.)", format.formatted("Scout", TacticalFeedback.rulerFeet(5), 5));
        var gate = new TacticalFeedback.PingLimiter();
        assertTrue(gate.take(-1));
        assertFalse(gate.take(999_999_998L));
        assertTrue(gate.take(999_999_999L));
        assertFalse(gate.take(999_999_999L));
        assertTrue(new TacticalFeedback.PingLimiter().take(0));
    }

    @Test void eightDistinctShadedTilesAndShaderSafeWorldMaterials() throws Exception {
        Path atlas = Path.of("src/main/resources/assets/dnd-5e-craft/textures/entity/racial_cosmetics_atlas.png");
        assertEquals(6, Files.readAllBytes(atlas)[25]); // PNG IHDR color type 6 is RGBA
        var image = ImageIO.read(atlas.toFile());
        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
        var tileHashes = new HashSet<Integer>();
        for (int i = 0; i < 8; i++) {
            int[] pixels = image.getRGB((i%4)*16, (i/4)*16, 16, 16, null, 0, 16);
            assertTrue(java.util.Arrays.stream(pixels).distinct().count() > 25);
            assertTrue(tileHashes.add(java.util.Arrays.hashCode(pixels)));
        }
        Path render = Path.of("src/client/java/net/dnd5ecraft/client/render");
        for (String name : new String[]{"Spinning3dDieRenderer", "FloatingCombatBarRenderer"})
            assertFalse(Files.readString(render.resolve(name + ".java")).contains("RenderType.debugQuads()"));
    }
}
