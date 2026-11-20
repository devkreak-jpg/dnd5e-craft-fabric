package net.dnd5ecraft.item;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BagOfHoldingTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void sparseInventoryRoundTripsItsExactSlots() {
        var inventory = new SimpleContainer(27);
        inventory.setItem(4, new ItemStack(Items.APPLE, 3));
        inventory.setItem(26, new ItemStack(Items.COMPASS));
        var saved = BagOfHoldingItem.saveSlots(inventory);
        assertEquals(4, saved.getCompound(0).getInt("Slot"));
        assertEquals(26, saved.getCompound(1).getInt("Slot"));
        var reopened = new SimpleContainer(27);
        BagOfHoldingItem.loadSlots(reopened, saved);
        assertTrue(reopened.getItem(0).isEmpty());
        assertTrue(reopened.getItem(4).is(Items.APPLE));
        assertEquals(3, reopened.getItem(4).getCount());
        assertTrue(reopened.getItem(26).is(Items.COMPASS));
    }

    @Test void portableStorageCannotBeInserted() {
        assertFalse(BagOfHoldingItem.canStore(BagOfHoldingItem.class, null));
        assertFalse(BagOfHoldingItem.canStore(new ItemStack(Items.SHULKER_BOX)));
        assertFalse(BagOfHoldingItem.canStore(new ItemStack(Items.RED_SHULKER_BOX)));
        assertTrue(BagOfHoldingItem.canStore(new ItemStack(Items.APPLE)));
    }
}
