package net.dnd5ecraft.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ShulkerBoxBlock;

public final class BagOfHoldingItem extends Item {
    private static final int SIZE = 27;

    public BagOfHoldingItem(Properties properties) { super(properties.stacksTo(1)); }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack bag = player.getItemInHand(hand);
        if (player instanceof ServerPlayer server) {
            var inventory = new SimpleContainer(SIZE) {
                @Override public boolean canPlaceItem(int slot, ItemStack stack) { return canStore(stack); }
                @Override public boolean stillValid(Player holder) { return holder.getItemInHand(hand) == bag; }
            };
            var tag = bag.getTag();
            if (tag != null) loadSlots(inventory, tag.getList("Inventory", Tag.TAG_COMPOUND));
            inventory.addListener(changed -> bag.getOrCreateTag().put("Inventory", saveSlots(inventory)));
            server.openMenu(new SimpleMenuProvider((id, playerInventory, holder) -> new BagMenu(id, playerInventory, inventory),
                    Component.translatable(getDescriptionId())));
        }
        return InteractionResultHolder.sidedSuccess(bag, level.isClientSide);
    }

    static boolean canStore(ItemStack stack) {
        var item = stack.getItem();
        return canStore(item.getClass(), item instanceof BlockItem block ? block.getBlock() : null);
    }

    static boolean canStore(Class<? extends Item> itemType, net.minecraft.world.level.block.Block placedBlock) {
        return itemType != BagOfHoldingItem.class && !(placedBlock instanceof ShulkerBoxBlock);
    }

    static void loadSlots(SimpleContainer inventory, ListTag items) {
        for (int index = 0; index < items.size(); index++) {
            var tag = items.getCompound(index);
            // Old bags stored a compact list with no slot metadata.
            int slot = tag.contains("Slot", Tag.TAG_ANY_NUMERIC) ? tag.getInt("Slot") : index;
            if (slot >= 0 && slot < SIZE) inventory.setItem(slot, ItemStack.of(tag));
        }
    }

    static ListTag saveSlots(SimpleContainer inventory) {
        var items = new ListTag();
        for (int slot = 0; slot < SIZE; slot++) {
            var stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            var tag = stack.save(new CompoundTag());
            tag.putByte("Slot", (byte) slot);
            items.add(tag);
        }
        return items;
    }

    static final class BagMenu extends AbstractContainerMenu {
        private final SimpleContainer bag;

        BagMenu(int id, Inventory playerInventory, SimpleContainer bag) {
            super(MenuType.GENERIC_9x3, id);
            this.bag = bag;
            for (int i = 0; i < SIZE; i++) {
                addSlot(new Slot(bag, i, 8 + i % 9 * 18, 18 + i / 9 * 18) {
                    @Override public boolean mayPlace(ItemStack stack) { return bag.canPlaceItem(getContainerSlot(), stack); }
                });
            }
            for (int i = 0; i < SIZE; i++) addSlot(new Slot(playerInventory, 9 + i, 8 + i % 9 * 18, 85 + i / 9 * 18));
            for (int i = 0; i < 9; i++) addSlot(new Slot(playerInventory, i, 8 + i * 18, 143));
        }

        @Override public boolean stillValid(Player player) { return bag.stillValid(player); }

        @Override public ItemStack quickMoveStack(Player player, int slotIndex) {
            var slot = getSlot(slotIndex);
            if (!slot.hasItem()) return ItemStack.EMPTY;
            var stack = slot.getItem();
            var original = stack.copy();
            boolean moved = slotIndex < SIZE ? moveItemStackTo(stack, SIZE, slots.size(), true)
                    : canStore(stack) && moveItemStackTo(stack, 0, SIZE, false);
            if (!moved) return ItemStack.EMPTY;
            if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
            else slot.setChanged();
            return original;
        }
    }
}
