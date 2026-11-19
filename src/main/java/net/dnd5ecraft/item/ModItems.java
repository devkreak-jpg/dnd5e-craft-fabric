package net.dnd5ecraft.item;

import java.util.List;
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
    private static final List<Item> ITEMS = List.of(DM_MASTER_WAND, NPC_SPAWNER_WAND, DUNGEON_BRUSH, TRIGGER_ZONE_WAND,
            CHARACTER_FOLIO, PHYSICAL_D20, HEALING_DRAUGHT);

    private ModItems() {}

    private static Item.Properties single() { return new Item.Properties().stacksTo(1); }

    private static Item item(String name, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, Dnd5eCraftMod.id(name), item);
    }

    public static void register() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Dnd5eCraftMod.id("tools"), FabricItemGroup.builder()
                .title(Component.translatable("itemGroup.dnd-5e-craft.tools"))
                .icon(() -> new ItemStack(PHYSICAL_D20))
                .displayItems((parameters, output) -> ITEMS.forEach(output::accept))
                .build());
    }
}
