package net.dnd5ecraft.network;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class CampaignHudLayoutTest {
    @ParameterizedTest
    @CsvSource({"480,270", "320,180"})
    void combatBandsAndVitalsFitAtSmallGuiSizes(int width, int height) {
        for (int choices : new int[]{1, 3, 7}) {
            var layout = CombatHudView.layout(height, choices);
            assertEquals(height - 54, layout.vitalsTop());
            assertTrue(layout.vitalsTop() + 23 <= height - 31);
            assertTrue(layout.oxygenTop() + 7 < layout.vitalsTop());
            assertTrue(layout.resourcesTop() + layout.resourcesHeight() < layout.oxygenTop());
            assertEquals(layout.resourcesTop() - 6, layout.reactionsBottom());
            assertTrue(layout.reactionsTop() >= 53);
            assertTrue(layout.reactionsBottom() - layout.reactionsTop() >= 23, width + "×" + height);
        }
    }
}
