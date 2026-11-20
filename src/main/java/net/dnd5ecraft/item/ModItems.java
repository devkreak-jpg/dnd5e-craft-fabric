package net.dnd5ecraft.item;

import java.util.List;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry;
import net.dnd5ecraft.rules.equipment.WeaponProperty;
import net.dnd5ecraft.Dnd5eCraftMod;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ModItems {
    public static final Item DM_MASTER_WAND = item("dm_master_wand", new DmMasterWandItem(single()));
    public static final Item NPC_SPAWNER_WAND = item("npc_spawner_wand", new NpcSpawnerWandItem(single()));
    public static final Item DUNGEON_BRUSH = item("dungeon_brush", new DungeonBrushItem(single()));
    public static final Item TRIGGER_ZONE_WAND = item("trigger_zone_wand", new TriggerZoneWandItem(single()));
    public static final Item CHARACTER_FOLIO = item("character_folio", new CharacterFolioItem(single()));
    public static final Item PHYSICAL_D20 = item("physical_d20", new PhysicalD20Item(single()));
    public static final Item HEALING_DRAUGHT = item("healing_draught", new HealingDraughtItem(new Item.Properties().stacksTo(16)));
    public static final Item REPEATING_LIGHT_CROSSBOW = item("repeating_light_crossbow", new DndWeaponItem(repeating(), 1, single().rarity(net.minecraft.world.item.Rarity.RARE)));
    public static final Item SENTINEL_SHIELD = item("sentinel_shield", new net.minecraft.world.item.ShieldItem(single().durability(400)));
    public static final Item BAG_OF_HOLDING = item("bag_of_holding", new BagOfHoldingItem(single()));
    public static final Item THIEVES_TOOLS = item("thieves_tools", new ThievesToolsItem(single()));
    public static final Item SCALE_MAIL = item("scale_mail", new net.minecraft.world.item.ArmorItem(net.minecraft.world.item.ArmorMaterials.CHAIN,
            net.minecraft.world.item.ArmorItem.Type.CHESTPLATE, single()));
    public static final Item SPLINT_ARMOR = item("splint_armor", new net.minecraft.world.item.ArmorItem(net.minecraft.world.item.ArmorMaterials.IRON,
            net.minecraft.world.item.ArmorItem.Type.CHESTPLATE, single()));
    public static final Item STUDDED_LEATHER_ARMOR = chestplate("studded_leather_armor", net.minecraft.world.item.ArmorMaterials.LEATHER);
    public static final Item BREASTPLATE = chestplate("breastplate", net.minecraft.world.item.ArmorMaterials.GOLD);
    public static final Item HALF_PLATE = chestplate("half_plate", net.minecraft.world.item.ArmorMaterials.IRON);
    public static final Item PLATE_ARMOR = chestplate("plate_armor", net.minecraft.world.item.ArmorMaterials.DIAMOND);
    public static final Item WORLDBREAKER_WRAPS = item("worldbreaker_wraps", new Item(single()));
    public static final Item HARLEQUIN_DECK_WAND = item("harlequin_deck_wand", new CampaignFocusItem(single(), CampaignFocusItem.Focus.CHAOS));
    public static final Item ECHO_SIGIL = item("echo_sigil", new CampaignFocusItem(single(), CampaignFocusItem.Focus.ECHO));
    public static final Item ELDRITCH_CANNON_CONTROLLER = item("eldritch_cannon_controller", new CampaignFocusItem(single(), CampaignFocusItem.Focus.CANNON));
    public static final Item CROWD_SPAWNER_STAFF = item("crowd_spawner_staff", new CrowdSpawnerStaffItem(single()));
    private static final Map<Weapon, Item> WEAPONS = weapons();
    private static final List<Item> TOOLS = List.of(DM_MASTER_WAND, NPC_SPAWNER_WAND, DUNGEON_BRUSH, TRIGGER_ZONE_WAND,
            CHARACTER_FOLIO, PHYSICAL_D20, HEALING_DRAUGHT, REPEATING_LIGHT_CROSSBOW, SENTINEL_SHIELD, BAG_OF_HOLDING,
            THIEVES_TOOLS, SCALE_MAIL, SPLINT_ARMOR, WORLDBREAKER_WRAPS, HARLEQUIN_DECK_WAND, ECHO_SIGIL,
            ELDRITCH_CANNON_CONTROLLER, CROWD_SPAWNER_STAFF, STUDDED_LEATHER_ARMOR, BREASTPLATE, HALF_PLATE, PLATE_ARMOR);

    private static WeaponStatsRegistry.Stats repeating() {
        var base = Weapon.LIGHT_CROSSBOW.stats();
        var properties = java.util.EnumSet.copyOf(base.properties());
        properties.remove(WeaponProperty.LOADING);
        properties.remove(WeaponProperty.AMMUNITION);
        properties.add(WeaponProperty.MAGICAL);
        return new WeaponStatsRegistry.Stats(base.damage(), base.twoHandedDamage(), base.flatDamage(), base.damageType(),
                base.normalRangeFeet(), base.longRangeFeet(), properties);
    }

    private static Map<Weapon, Item> weapons() {
        var items = new EnumMap<Weapon, Item>(Weapon.class);
        for (var weapon : Weapon.values()) items.put(weapon, item(weapon.name().toLowerCase(Locale.ROOT),
                new DndWeaponItem(weapon.stats(), 0, single())));
        return Map.copyOf(items);
    }

    public static Item weapon(Weapon weapon) { return WEAPONS.get(weapon); }
    public static List<Item> all() {
        var items = new ArrayList<>(TOOLS);
        for (var weapon : Weapon.values()) items.add(weapon(weapon));
        return List.copyOf(items);
    }

    private ModItems() {}

    private static Item.Properties single() { return new Item.Properties().stacksTo(1); }

    private static Item chestplate(String name, net.minecraft.world.item.ArmorMaterials material) {
        return item(name, new net.minecraft.world.item.ArmorItem(material, net.minecraft.world.item.ArmorItem.Type.CHESTPLATE, single()));
    }

    private static Item item(String name, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, Dnd5eCraftMod.id(name), item);
    }

    public static void register() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Dnd5eCraftMod.id("tools"), FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.dnd-5e-craft.tools"))
                .icon(() -> new ItemStack(PHYSICAL_D20))
                .displayItems((parameters, output) -> all().forEach(output::accept))
                .build());
    }
}
