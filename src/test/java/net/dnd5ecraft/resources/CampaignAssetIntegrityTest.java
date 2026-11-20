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
import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.entity.NpcSkinCatalog;
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
    void weaponGripsShieldGeometryAndChestplateSpritesAreComplete() throws Exception {
        var weapons = new ArrayList<String>();
        for (var weapon : Weapon.values()) weapons.add(weapon.name().toLowerCase(Locale.ROOT));
        weapons.add("repeating_light_crossbow");
        for (var weapon : weapons) {
            var model = model(weapon);
            assertEquals("minecraft:item/handheld", model.get("parent").getAsString(), weapon);
            for (String hand : List.of("righthand", "lefthand")) {
                var first = model.getAsJsonObject("display").getAsJsonObject("firstperson_" + hand);
                for (var scale : first.getAsJsonArray("scale")) assertTrue(scale.getAsDouble() <= .85, weapon);
            }
        }
        var shield = model("sentinel_shield");
        assertTrue(shield.getAsJsonArray("elements").size() >= 10);
        var override = shield.getAsJsonArray("overrides").get(0).getAsJsonObject();
        assertEquals(1, override.getAsJsonObject("predicate").get("blocking").getAsInt());
        assertEquals("dnd-5e-craft:item/sentinel_shield_blocking", override.get("model").getAsString());
        assertNotEquals(shield.getAsJsonObject("display").get("firstperson_lefthand"),
                model("sentinel_shield_blocking").getAsJsonObject("display").get("firstperson_lefthand"));
        for (String armor : List.of("studded_leather_armor", "breastplate", "half_plate", "plate_armor")) {
            assertEquals("dnd-5e-craft:item/" + armor, model(armor).getAsJsonObject("textures").get("layer0").getAsString());
            var image = png("textures/item/" + armor + ".png");
            assertEquals(32, image.getWidth(), armor);
            assertEquals(32, image.getHeight(), armor);
        }
    }

    @Test
    void masterworkSpritesAndWornArmorAreRgba() throws Exception {
        var armor = List.of("studded_leather_armor", "scale_mail", "breastplate", "half_plate", "splint_armor", "plate_armor");
        var items = new ArrayList<>(armor);
        items.addAll(List.of("sentinel_shield", "repeating_light_crossbow", "worldbreaker_wraps", "harlequin_deck_wand",
                "echo_sigil", "eldritch_cannon_controller", "crowd_spawner_staff", "dm_master_wand", "bag_of_holding"));
        assertEquals(37, Weapon.values().length);
        for (var weapon : Weapon.values()) items.add(weapon.name().toLowerCase(Locale.ROOT));
        for (var item : items) dimensions("textures/item/" + item + ".png", 32, 32);
        for (var type : armor) for (int layer = 1; layer <= 2; layer++)
            dimensions("textures/models/armor/" + type + "_layer_" + layer + ".png", 64, 32);
    }

    @Test
    void allNpcSkinsAreUniqueLayeredAndAcceptedByAppearanceValidation() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        assertTrue(NpcSkinCatalog.ALL.size() >= 64);
        var images = new java.util.HashSet<String>();
        var sha256 = java.security.MessageDigest.getInstance("SHA-256");
        for (var skin : NpcSkinCatalog.ALL) {
            var path = skin.texture().getPath();
            var image = dimensions(path, 64, 64);
            assertEquals(skin.texture(), DndNpcEntity.validatedSkin(skin.texture().toString()));
            var pixels = java.nio.ByteBuffer.allocate(64 * 64 * Integer.BYTES);
            for (int pixel : image.getRGB(0, 0, 64, 64, null, 0, 64)) pixels.putInt(pixel);
            assertTrue(images.add(java.util.HexFormat.of().formatHex(sha256.digest(pixels.array()))), path);
            assertTrue(java.util.Arrays.stream(image.getRGB(16, 36, 24, 12, null, 0, 24)).anyMatch(pixel -> pixel >>> 24 != 0), path);
            assertEquals(0, image.getRGB(63, 31) >>> 24, path);
        }
        assertNotNull(DndNpcEntity.validatedSkin("dnd-5e-craft:textures/entity/crowd/guards_0.png"));
        assertThrows(IllegalArgumentException.class, () -> DndNpcEntity.validatedSkin("foreign:textures/entity/skins/a.png"));
        assertThrows(IllegalArgumentException.class, () -> DndNpcEntity.validatedSkin("dnd-5e-craft:textures/entity/skins/../a.png"));
    }

    private static java.awt.image.BufferedImage dimensions(String path, int width, int height) throws Exception {
        try (var input = asset(path)) {
            var header = input.readNBytes(26);
            assertEquals(26, header.length, path);
            assertEquals(6, header[25], path);
        }
        var image = png(path);
        assertEquals(width, image.getWidth(), path);
        assertEquals(height, image.getHeight(), path);
        return image;
    }

    private static com.google.gson.JsonObject model(String item) throws Exception {
        try (var reader = new InputStreamReader(asset("models/item/" + item + ".json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
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
