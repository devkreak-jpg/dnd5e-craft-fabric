package net.dnd5ecraft.entity;

import net.dnd5ecraft.Dnd5eCraftMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModEntities {
    public static final EntityType<DndNpcEntity> DND_NPC = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            Dnd5eCraftMod.id("npc"), FabricEntityTypeBuilder.create(MobCategory.CREATURE, DndNpcEntity::new)
                    .dimensions(EntityDimensions.scalable(.6f, 1.8f)).trackRangeChunks(10).build());

    public static final Block TRIGGER_ZONE = Registry.register(BuiltInRegistries.BLOCK,
            Dnd5eCraftMod.id("trigger_zone"), new TriggerZoneBlock(BlockBehaviour.Properties.of()
                    .noCollission().noOcclusion().strength(.2f).noLootTable()));

    public static final BlockEntityType<TriggerZoneBlockEntity> TRIGGER_ZONE_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, Dnd5eCraftMod.id("trigger_zone"),
            BlockEntityType.Builder.of(TriggerZoneBlockEntity::new, TRIGGER_ZONE).build(null));

    private ModEntities() {}

    public static void register() {
        FabricDefaultAttributeRegistry.register(DND_NPC, DndNpcEntity.createNpcAttributes());
    }
}
