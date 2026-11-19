package net.dnd5ecraft.client.gui.hud;

import java.util.UUID;
import net.dnd5ecraft.client.ClientActions;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.combat.CombatManager.AttackPreview;
import net.dnd5ecraft.combat.CoverRaycaster.Cover;
import net.dnd5ecraft.entity.DndNpcEntity;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.Protocol.Combat;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.campaign.TacticalFeedback;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class CombatInspector {
    private static UUID requested, encounter;
    private static int profile, round;
    private static AttackPreview preview;
    private static long nextQuery, expires;

    private CombatInspector() {}

    public static void receive(UUID id, int index, AttackPreview next) {
        if (id.equals(requested) && profile == index) { preview = next; expires = Util.getMillis() + 700; }
    }

    public static void clear() { requested = encounter = null; preview = null; nextQuery = 0; }

    static boolean canInspect(Combat combat, UUID target, boolean eligibleOutsider) {
        var actor = combat.creature(combat.controlledActor());
        if (actor == null) return false;
        var entry = combat.creature(target);
        return entry == null ? eligibleOutsider : entry.team() != actor.team();
    }

    static void render(GuiGraphics g, Minecraft client, Combat combat) {
        if (combat == null || !combat.ownsTurn() || client.screen != null
                || !client.player.getUUID().equals(combat.controlledActor()) || combat.attacks().isEmpty()) {
            clear();
            return;
        }
        var ray = ClientActions.crosshair(32);
        if (!(ray instanceof net.minecraft.world.phys.EntityHitResult hit) || !(hit.getEntity() instanceof LivingEntity target) || !target.isAlive() || target.isInvisibleTo(client.player)) return;
        if (combat.reaction() != null
                || !canInspect(combat, target.getUUID(), target instanceof DndNpcEntity || target instanceof Enemy)) return;
        int index = ClientActions.attackProfile();
        long now = Util.getMillis();
        if (!combat.id().equals(encounter) || round != combat.round() || !target.getUUID().equals(requested) || profile != index) {
            encounter = combat.id(); round = combat.round(); requested = target.getUUID(); profile = index; preview = null;
        }
        if (now >= nextQuery) {
            nextQuery = now + 220;
            var pos = hit.getLocation();
            ClientPacketHandlers.send(Action.INSPECT_ATTACK, out -> {
                out.writeUUID(target.getUUID()); out.writeVarInt(index);
                out.writeDouble(pos.x); out.writeDouble(pos.y); out.writeDouble(pos.z);
            });
        }
        if (preview == null || now > expires) return;
        var row = text("inspect.vitals", target.getDisplayName(), preview.hp(), preview.maxHp(), preview.armor()).copy();
        if (preview.cover() != Cover.NONE) row.append(" | ").append(name("inspect.cover", preview.cover()));
        int accent = GOLD;
        if (!preview.inRange()) row.append(" | ").append(text("inspect.range", preview.distance()));
        else if (!preview.cover().blocksTargeting()) {
            if (preview.mode() != RollMode.NORMAL) {
                int modeColor = preview.mode() == RollMode.ADVANTAGE ? EMERALD : CRIMSON;
                row.append(" | ").append(name("inspect.mode", preview.mode()).copy().withStyle(s -> s.withColor(modeColor & 0xffffff)));
            }
            int percent = (int)Math.round(100 * TacticalFeedback.hitChance(preview.armor() + preview.cover().bonus(),
                    preview.bonus(), preview.mode()));
            accent = percent >= 70 ? EMERALD : percent >= 45 ? GOLD : CRIMSON;
            int rgb = accent & 0xffffff;
            row.append(" | ").append(text("inspect.hit", percent).copy().withStyle(s -> s.withColor(rgb)));
        }
        var font = client.font;
        float scale = Math.min(1, (g.guiWidth() - 16f) / (font.width(row) + 14));
        g.pose().pushPose();
        g.pose().translate(g.guiWidth()/2f, g.guiHeight()/2f + 16, 0);
        g.pose().scale(scale, scale, 1);
        int w = font.width(row) + 12;
        g.fill(-w/2, 0, w/2 + 1, 15, 0xe613141a);
        g.renderOutline(-w/2, 0, w + 1, 15, 0xffa47d46);
        g.fill(-w/2 + 1, 2, -w/2 + 3, 13, accent);
        g.drawCenteredString(font, row, 0, 4, TEXT);
        g.pose().popPose();
    }
}
