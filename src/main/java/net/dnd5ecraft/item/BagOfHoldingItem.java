package net.dnd5ecraft.item;

import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class BagOfHoldingItem extends Item {
    public BagOfHoldingItem(Properties properties) { super(properties); }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack bag = player.getItemInHand(hand);
        if (player instanceof ServerPlayer server) {
            var inventory = new SimpleContainer(27) {
                @Override public boolean canPlaceItem(int slot, ItemStack stack) {
                    return !(stack.getItem() instanceof BagOfHoldingItem)
                            && !(stack.getItem() instanceof net.minecraft.world.item.BlockItem block
                            && block.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock);
                }

                @Override public boolean stillValid(Player holder) { return holder.getItemInHand(hand) == bag; }
            };
            inventory.fromTag(bag.getOrCreateTag().getList("Inventory", Tag.TAG_COMPOUND));
            inventory.addListener(changed -> bag.getOrCreateTag().put("Inventory", inventory.createTag()));
            server.openMenu(new SimpleMenuProvider((id, playerInventory, holder) -> ChestMenu.threeRows(id, playerInventory, inventory),
                    Component.translatable(getDescriptionId())));
        }
        return InteractionResultHolder.sidedSuccess(bag, level.isClientSide);
    }
}
