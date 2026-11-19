package net.dnd5ecraft.server;

import java.util.function.Consumer;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.entity.BestiaryRegistry5e;
import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.entity.ModEntities;
import net.dnd5ecraft.entity.NpcBehaviorMode;
import net.dnd5ecraft.entity.NpcSkinCatalog;
import net.dnd5ecraft.entity.NpcSpawns;
import net.dnd5ecraft.entity.TriggerZoneBlockEntity;
import net.dnd5ecraft.entity.TriggerZoneSpec;
import net.dnd5ecraft.item.ModItems;
import net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;

public final class DemoMapBuilder {
    static final String DEMO_NPC = "dnd5e_demo_npc";
    private static final int SIZE = 38;
    private static final int CLEARANCE = 12;

    private final ServerLevel level;
    private final BlockPos origin;

    private DemoMapBuilder(ServerLevel level, BlockPos origin) {
        this.level = level;
        this.origin = origin;
    }

    public static void build(ServerPlayer operator) {
        if (!operator.hasPermissions(2)) throw new IllegalStateException("Operator permissions are required");
        var level = operator.serverLevel();
        if (TacticalCombat.hasEncounter(level)) throw new IllegalStateException("End the tactical encounter before rebuilding the demo");
        var site = level.getDataStorage().computeIfAbsent(Site::load, Site::new, "dnd5e_demo_site");
        var origin = site.origin != null ? site.origin : operator.blockPosition().offset(-SIZE / 2, 0, 5);
        var demo = new DemoMapBuilder(level, origin);
        demo.prepare(operator);
        site.origin = origin;
        site.setDirty();
        demo.tavern();
        demo.square();
        demo.crypt();
        operator.sendSystemMessage(Component.translatable("dnd-5e-craft.demo.built", origin.getX(), origin.getY(), origin.getZ()));
    }

    private static final class Site extends SavedData {
        private BlockPos origin;

        private static Site load(CompoundTag tag) {
            var site = new Site();
            if (tag.contains("Origin")) site.origin = BlockPos.of(tag.getLong("Origin"));
            return site;
        }

        @Override public CompoundTag save(CompoundTag tag) {
            if (origin != null) tag.putLong("Origin", origin.asLong());
            return tag;
        }
    }

    private void prepare(ServerPlayer operator) {
        var far = at(SIZE - 1, CLEARANCE, SIZE - 1);
        if (level.isOutsideBuildHeight(origin.below(3)) || level.isOutsideBuildHeight(far)
                || !level.getWorldBorder().isWithinBounds(origin) || !level.getWorldBorder().isWithinBounds(far))
            throw new IllegalArgumentException("Demo site is outside the world");
        var footprint = new AABB(origin.below(3), far.offset(1, 1, 1));
        if (!level.getEntitiesOfClass(ServerPlayer.class, footprint, player -> player != operator && !player.isSpectator()).isEmpty())
            throw new IllegalStateException("Move the other players out of the demo site before building");
        for (int x = origin.getX() >> 4; x <= far.getX() >> 4; x++)
            for (int z = origin.getZ() >> 4; z <= far.getZ() >> 4; z++) level.getChunk(x, z);
        if (footprint.contains(operator.position())) operator.teleportTo(origin.getX() + SIZE / 2.0,
                origin.getY(), origin.getZ() - 1.5);
        level.getEntitiesOfClass(DndNpcEntity.class, footprint.inflate(24), npc -> npc.getTags().contains(DEMO_NPC))
                .forEach(DndNpcEntity::discard);
        for (BlockPos pos : BlockPos.betweenClosed(origin, far)) {
            if (level.getBlockEntity(pos) instanceof Clearable container) container.clearContent();
            if (!level.isEmptyBlock(pos)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        fill(0, -3, 0, SIZE - 1, -2, SIZE - 1, Blocks.STONE);
        fill(0, -1, 0, SIZE - 1, -1, SIZE - 1, Blocks.GRASS_BLOCK);
    }

    private BlockPos at(int x, int y, int z) { return origin.offset(x, y, z); }

    private void put(int x, int y, int z, Block block) { put(x, y, z, block.defaultBlockState()); }

    private void put(int x, int y, int z, BlockState state) {
        level.setBlock(at(x, y, z), state, Block.UPDATE_CLIENTS);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
        var state = block.defaultBlockState();
        for (BlockPos pos : BlockPos.betweenClosed(at(x1, y1, z1), at(x2, y2, z2)))
            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    private void tavern() {
        fill(2, -2, 3, 16, -2, 15, Blocks.COBBLESTONE);
        fill(2, -1, 3, 16, -1, 15, Blocks.SPRUCE_PLANKS);
        fill(2, 0, 3, 16, 2, 3, Blocks.OAK_PLANKS);
        fill(2, 0, 15, 16, 2, 15, Blocks.OAK_PLANKS);
        fill(2, 0, 3, 2, 2, 15, Blocks.OAK_PLANKS);
        fill(16, 0, 3, 16, 2, 15, Blocks.OAK_PLANKS);
        for (int x : new int[]{2, 9, 16}) {
            for (int z : new int[]{3, 15}) fill(x, 0, z, x, 3, z, Blocks.SPRUCE_LOG);
            if (x != 9) for (int z : new int[]{7, 11}) {
                fill(x, 0, z, x, 1, z, Blocks.GLASS_PANE);
                put(x, 2, z, Blocks.SPRUCE_LOG);
            }
        }
        for (int ramp = 0; ramp < 6; ramp++) {
            for (int z = 2; z <= 16; z++) {
                put(1 + ramp, ramp + 3, z, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
                put(17 - ramp, ramp + 3, z, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
            }
            for (int z : new int[]{3, 15}) fill(2 + ramp, 3 + ramp, z, 16 - ramp, 3 + ramp, z, Blocks.OAK_PLANKS);
        }
        fill(7, 9, 2, 11, 9, 16, Blocks.DARK_OAK_PLANKS);
        for (int z : new int[]{5, 10, 14}) fill(3, 3, z, 15, 3, z, Blocks.SPRUCE_LOG);
        fill(8, 0, 3, 10, 1, 3, Blocks.AIR);
        fill(8, 0, 4, 10, 0, 8, Blocks.RED_CARPET);
        fill(4, 0, 12, 8, 0, 12, Blocks.SPRUCE_PLANKS);
        put(4, 0, 14, Blocks.BARREL);
        put(5, 0, 14, Blocks.BARREL);
        for (int z : new int[]{6, 9, 12}) {
            put(13, 0, z, Blocks.SPRUCE_FENCE);
            put(13, 1, z, Blocks.SPRUCE_PRESSURE_PLATE);
            put(12, 0, z, Blocks.SPRUCE_STAIRS);
            put(14, 0, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
        }
        for (int z : new int[]{5, 10, 14}) put(11, 2, z, Blocks.LANTERN.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true));
        fill(9, 0, 14, 11, 9, 14, Blocks.COBBLESTONE);
        put(10, 0, 14, Blocks.CAMPFIRE);
        fill(10, 1, 14, 10, 9, 14, Blocks.AIR);
        put(10, 10, 14, Blocks.COBBLESTONE_WALL);
        spawn(6, 0, 13, BestiaryPreset.TAVERN_KEEPER, NpcSkinCatalog.TAVERN_KEEPER_BORIS, NpcAttitude.INDIFFERENT, npc -> {
            npc.setBehavior(NpcBehaviorMode.STATIONARY, 4);
            npc.setDialogue(BestiaryRegistry5e.borisDialogue());
        });
        spawn(5, 0, 8, BestiaryPreset.MERCHANT, NpcSkinCatalog.MERCHANT_ELARA, NpcAttitude.INDIFFERENT,
                npc -> npc.setBehavior(NpcBehaviorMode.STATIONARY, 4));
        for (var skin : new NpcSkinCatalog[]{NpcSkinCatalog.BARD_MINSTREL, NpcSkinCatalog.PEASANT_FEMALE}) {
            spawn(skin == NpcSkinCatalog.BARD_MINSTREL ? 14 : 7, 0, 10, BestiaryPreset.MERCHANT, skin,
                    NpcAttitude.INDIFFERENT, npc -> npc.setBehavior(NpcBehaviorMode.WANDER, 4));
        }
    }

    private void square() {
        fill(8, -1, 0, 10, -1, 3, Blocks.COBBLESTONE);
        fill(17, -1, 4, 35, -1, 16, Blocks.COBBLESTONE);
        fill(9, -1, 16, 26, -1, 23, Blocks.GRAVEL);
        for (int x : new int[]{19, 26, 33}) {
            for (int z : new int[]{5, 15}) {
                fill(x, 0, z, x, 1, z, Blocks.STONE_BRICK_WALL);
                put(x, 2, z, Blocks.LANTERN);
            }
        }
        put(17, 0, 13, Blocks.BARREL);
        put(18, 0, 13, Blocks.OAK_LOG);
        put(18, 1, 13, Blocks.OAK_TRAPDOOR);
        guard(23, 8, NpcSkinCatalog.TOWN_GUARD_SPEARMAN, Weapon.SPEAR);
        guard(29, 12, NpcSkinCatalog.TOWN_GUARD_CAPTAIN, Weapon.HALBERD);
    }

    private void guard(int x, int z, NpcSkinCatalog appearance, Weapon weapon) {
        spawn(x, 0, z, BestiaryPreset.TOWN_GUARD, appearance, NpcAttitude.FRIENDLY, npc -> {
            equip(npc, weapon);
            npc.setBehavior(NpcBehaviorMode.PATROL, 8);
            for (BlockPos point : new BlockPos[]{at(22, 0, 7), at(31, 0, 7), at(31, 0, 13), at(22, 0, 13)})
                npc.addWaypoint(point);
        });
    }

    private void crypt() {
        fill(6, -1, 22, 32, -1, 36, Blocks.MOSSY_STONE_BRICKS);
        for (int x = 6; x <= 32; x++) {
            put(x, 0, 22, Blocks.MOSSY_STONE_BRICKS);
            fill(x, 0, 36, x, 1 + x % 3, 36, Blocks.MOSSY_STONE_BRICKS);
        }
        for (int z = 22; z < 36; z++) {
            fill(6, 0, z, 6, 1 + z % 3, z, Blocks.STONE_BRICKS);
            fill(32, 0, z, 32, 1 + (z + 1) % 3, z, Blocks.MOSSY_STONE_BRICKS);
        }
        fill(17, 0, 22, 21, 2, 22, Blocks.AIR);
        for (int x : new int[]{16, 22}) fill(x, 0, 22, x, 3, 22, Blocks.CHISELED_STONE_BRICKS);
        fill(16, 3, 22, 22, 3, 22, Blocks.STONE_BRICKS);
        for (int x : new int[]{11, 25}) {
            for (int z : new int[]{25, 30}) {
                fill(x, 0, z, x, 2, z, Blocks.STONE_BRICKS);
                put(x, 3, z, Blocks.COBBLESTONE_WALL);
            }
            fill(x - 1, 0, 27, x + 1, 0, 27, Blocks.STONE_BRICK_SLAB);
            put(x, 0, 32, Blocks.COBWEB);
        }
        fill(14, -1, 29, 16, -1, 32, Blocks.WATER);
        fill(17, 0, 32, 22, 0, 35, Blocks.POLISHED_ANDESITE);
        fill(17, 0, 31, 22, 0, 31, Blocks.STONE_BRICK_SLAB);
        for (int x : new int[]{17, 22}) {
            put(x, 1, 35, Blocks.SOUL_LANTERN);
            put(x, 0, 24, Blocks.SOUL_LANTERN);
        }
        var marker = at(19, 0, 22);
        level.setBlockAndUpdate(marker, ModEntities.TRIGGER_ZONE.defaultBlockState());
        if (level.getBlockEntity(marker) instanceof TriggerZoneBlockEntity trap)
            trap.configurePoisonTrap(new TriggerZoneSpec(cell(at(17, 0, 22)), cell(at(21, 1, 22)),
                    TriggerZoneSpec.Mode.TRAP, 100, false, 12, BestiaryPreset.GOBLIN, 2, 12, 12, 13, 13, ""));
        spawn(11, 0, 28, BestiaryPreset.GOBLIN_ARCHER, NpcSkinCatalog.GOBLIN_ARCHER, NpcAttitude.HOSTILE,
                npc -> equip(npc, Weapon.SHORTBOW));
        spawn(25, 0, 28, BestiaryPreset.GOBLIN_ARCHER, NpcSkinCatalog.GOBLIN_ARCHER, NpcAttitude.HOSTILE,
                npc -> equip(npc, Weapon.SHORTBOW));
        spawn(19, 0, 27, BestiaryPreset.SKELETON, NpcSkinCatalog.SKELETON_WARRIOR, NpcAttitude.HOSTILE,
                npc -> equip(npc, Weapon.SHORTSWORD));
        spawn(24, 0, 34, BestiaryPreset.CULTIST_ACOLYTE, NpcSkinCatalog.CULTIST_ACOLYTE, NpcAttitude.HOSTILE,
                npc -> equip(npc, Weapon.SICKLE));
        spawn(19, 1, 33, BestiaryPreset.CAPTAIN_MORGAN, NpcSkinCatalog.BANDIT_CAPTAIN_BOSS, NpcAttitude.HOSTILE,
                npc -> equip(npc, Weapon.SCIMITAR));
        put(20, 1, 35, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        if (level.getBlockEntity(at(20, 1, 35)) instanceof ChestBlockEntity chest) {
            chest.setCustomName(Component.translatable("dnd-5e-craft.demo.loot"));
            chest.setItem(0, new ItemStack(Items.GOLD_NUGGET, 48).setHoverName(
                    Component.translatable("dnd-5e-craft.demo.coins")));
            chest.setItem(1, new ItemStack(ModItems.HEALING_DRAUGHT, 4));
            chest.setItem(2, ModItems.weapon(Weapon.GREATSWORD).getDefaultInstance());
            chest.setItem(3, ModItems.weapon(Weapon.SCIMITAR).getDefaultInstance());
            chest.setItem(4, ModItems.REPEATING_LIGHT_CROSSBOW.getDefaultInstance());
            chest.setChanged();
        }
    }

    private static Cell cell(BlockPos pos) { return new Cell(pos.getX(), pos.getY(), pos.getZ()); }

    private static void equip(DndNpcEntity npc, Weapon weapon) {
        npc.setItemSlot(EquipmentSlot.MAINHAND, ModItems.weapon(weapon).getDefaultInstance());
        npc.setDropChance(EquipmentSlot.MAINHAND, 0);
    }

    private void spawn(int x, int y, int z, BestiaryPreset preset, NpcSkinCatalog skin, NpcAttitude attitude,
            Consumer<DndNpcEntity> configure) {
        NpcSpawns.spawn(level, at(x, y, z), preset, attitude, npc -> {
            npc.addTag(DEMO_NPC);
            npc.setPersistenceRequired();
            npc.setCustomName(Component.translatable("dnd-5e-craft.ui.npc.skin." + skin.id()));
            if (preset == BestiaryPreset.CAPTAIN_MORGAN)
                npc.setCustomName(Component.translatable("entity.dnd-5e-craft.npc." + preset.id()));
            npc.setAppearance(skin.texture().toString(), preset.scale());
            npc.setBehavior(NpcBehaviorMode.STATIONARY, 4);
            configure.accept(npc);
        }).orElseThrow(() -> new IllegalStateException("Demo NPC could not spawn at " + at(x, y, z).toShortString()));
    }
}
