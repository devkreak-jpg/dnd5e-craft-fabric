package net.dnd5ecraft.item;

import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.CrowdTheme;
import net.dnd5ecraft.entity.NpcBehaviorMode;
import net.dnd5ecraft.entity.NpcSpawns;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class CrowdSpawnerStaffItem extends Item {
    public CrowdSpawnerStaffItem(Properties properties) { super(properties); }

    private static CrowdTheme theme(ItemStack staff) {
        var themes = CrowdTheme.values();
        var tag = staff.getTag();
        return themes[tag == null ? 0 : Math.floorMod(tag.getInt("CrowdTheme"), themes.length)];
    }

    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var staff = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) return InteractionResultHolder.pass(staff);
        if (player instanceof ServerPlayer operator) {
            if (!WandSelections.allowed(operator)) return InteractionResultHolder.fail(staff);
            cycle(operator, staff);
        } else if (!player.hasPermissions(2)) return InteractionResultHolder.fail(staff);
        return InteractionResultHolder.sidedSuccess(staff, level.isClientSide);
    }

    private static void cycle(ServerPlayer operator, ItemStack staff) {
        var themes = CrowdTheme.values();
        var next = themes[(theme(staff).ordinal() + 1) % themes.length];
        staff.getOrCreateTag().putInt("CrowdTheme", next.ordinal());
        WandSelections.message(operator, "dnd-5e-craft.crowd.selected", Component.translatable("dnd-5e-craft.crowd." + next.id()));
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer operator)) {
            var player = context.getPlayer();
            return player != null && player.hasPermissions(2) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
        }
        if (!WandSelections.allowed(operator)) return InteractionResult.FAIL;
        var staff = context.getItemInHand();
        if (operator.isShiftKeyDown()) {
            cycle(operator, staff);
            return InteractionResult.CONSUME;
        }
        var pos = context.getClickedPos().relative(context.getClickedFace());
        var crowd = theme(staff);
        var profile = crowd.random(new java.util.Random(operator.getRandom().nextLong()));
        var spawned = NpcSpawns.spawn(operator.serverLevel(), pos, crowd.preset(), NpcAttitude.INDIFFERENT, npc -> {
            npc.setCustomName(Component.literal(profile.name()));
            npc.setAppearance(profile.texture(), profile.scale());
            npc.setBehavior(NpcBehaviorMode.WANDER, profile.radius());
            if (profile.weapon() != null) {
                npc.setItemSlot(EquipmentSlot.MAINHAND, ModItems.weapon(profile.weapon()).getDefaultInstance());
                npc.setDropChance(EquipmentSlot.MAINHAND, 0);
            }
        });
        return spawned.isPresent() ? InteractionResult.CONSUME : InteractionResult.FAIL;
    }
}
