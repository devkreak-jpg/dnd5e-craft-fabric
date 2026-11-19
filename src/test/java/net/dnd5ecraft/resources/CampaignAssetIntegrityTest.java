package net.dnd5ecraft.resources;

import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.dnd5ecraft.entity.CrowdTheme;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CampaignAssetIntegrityTest {
    private static final String ROOT = "/assets/dnd-5e-craft/";

    @Test
    void registeredItemModelsReferenceVisibleRgbaSprites() throws Exception {
        var items = new ArrayList<>(List.of("dm_master_wand", "npc_spawner_wand", "dungeon_brush", "trigger_zone_wand",
                "character_folio", "physical_d20", "healing_draught", "bag_of_holding", "crowd_spawner_staff", "echo_sigil",
                "eldritch_cannon_controller", "harlequin_deck_wand", "repeating_light_crossbow", "scale_mail",
                "sentinel_shield", "splint_armor", "thieves_tools", "worldbreaker_wraps"));
        for (var weapon : Weapon.values()) items.add(weapon.name().toLowerCase(Locale.ROOT));
        for (String item : items) {
            try (var reader = new InputStreamReader(asset("models/item/" + item + ".json"), StandardCharsets.UTF_8)) {
                var model = JsonParser.parseReader(reader).getAsJsonObject();
                assertTrue(model.has("parent") || model.has("elements"), item);
                var texture = model.getAsJsonObject("textures").get("layer0").getAsString();
                assertEquals("dnd-5e-craft:item/" + item, texture);
                var image = png("textures/" + texture.substring(texture.indexOf(':') + 1) + ".png");
                if (items.indexOf(item) >= 7) {
                    assertTrue(List.of(16, 32).contains(image.getWidth()), item);
                    assertEquals(image.getWidth(), image.getHeight(), item);
                    assertTrue(model.has("display") && model.getAsJsonObject("display").size() >= 4, item);
                }
            }
        }
    }

    @Test
    void campaignEntityAndEveryCrowdSkinCanBeDecoded() throws Exception {
        for (var name : List.of("campaign_npc", "echo_avatar", "eldritch_cannon", "cosmetic_linen")) {
            png("textures/entity/" + name + ".png");
        }
        for (var theme : CrowdTheme.values()) for (int i = 0; i < 3; i++) {
            var skin = png("textures/entity/crowd/" + theme.id() + "_" + i + ".png");
            assertEquals(64, skin.getWidth());
            assertEquals(64, skin.getHeight());
        }
    }

    private static InputStream asset(String path) {
        var input = CampaignAssetIntegrityTest.class.getResourceAsStream(ROOT + path);
        assertNotNull(input, path);
        return input;
    }

    private static java.awt.image.BufferedImage png(String path) throws Exception {
        try (var input = asset(path)) {
            var image = ImageIO.read(input);
            assertNotNull(image, path);
            assertTrue(image.getColorModel().hasAlpha(), path);
            boolean visible = false;
            for (int y = 0; y < image.getHeight() && !visible; y++) {
                for (int x = 0; x < image.getWidth() && !visible; x++) visible = image.getRGB(x, y) >>> 24 != 0;
            }
            assertTrue(visible, path);
            return image;
        }
    }
}
