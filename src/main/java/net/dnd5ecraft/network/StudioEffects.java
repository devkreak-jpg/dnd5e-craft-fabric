package net.dnd5ecraft.network;

import java.util.function.Consumer;
import net.dnd5ecraft.network.Protocol.Update;
import net.dnd5ecraft.rules.campaign.TacticalFeedback;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class StudioEffects {
    private StudioEffects() {}

    public static HitResult sight(Entity viewer, Vec3 from, Vec3 direction, double range) {
        var to = from.add(direction.scale(range));
        var block = viewer.level().clip(new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, viewer));
        var entity = ProjectileUtil.getEntityHitResult(viewer, from, to, new net.minecraft.world.phys.AABB(from, to).inflate(1),
                candidate -> candidate.isPickable() && !candidate.isSpectator(), from.distanceToSqr(block.getLocation()));
        return entity == null ? block : entity;
    }

    // Third-person cameras can be offset. Verify the selected surface from the controlled entity's eyes.
    public static HitResult verify(Entity viewer, Vec3 point, int entityId, int range) {
        var from = viewer.getEyePosition(1);
        var delta = point.subtract(from);
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance > range + .01 || distance < .001) return null;
        var hit = sight(viewer, from, delta.scale(1 / distance), distance + .06);
        if (hit instanceof net.minecraft.world.phys.EntityHitResult entity)
            return entity.getEntity().getId() == entityId ? hit : null;
        return entityId == -1 && hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceToSqr(point) < .04 ? hit : null;
    }

    private static void near(ServerLevel level, Vec3 point, Update kind, Consumer<FriendlyByteBuf> write) {
        for (var recipient : level.players()) if (recipient.distanceToSqr(point) <= TacticalFeedback.AUDIENCE * TacticalFeedback.AUDIENCE)
            ModPackets.send(recipient, ModPackets.STUDIO, kind, write);
    }

    public static void ping(ServerPlayer player, Vec3 point, int entityId) {
        if (player.getCamera().level() != player.serverLevel()) return;
        var hit = verify(player.getCamera(), point, entityId, TacticalFeedback.PING_RANGE);
        if (hit == null) return;
        var p = hit.getLocation();
        near(player.serverLevel(), p, Update.S2C_TACTICAL_PING, out -> {
            out.writeDouble(p.x); out.writeDouble(p.y); out.writeDouble(p.z);
            out.writeUtf(player.getGameProfile().getName(), 80);
        });
    }

    public static void rest(ServerPlayer player, int restored) {
        near(player.serverLevel(), player.position(), Update.S2C_SHORT_REST_FX, out -> {
            out.writeVarInt(player.getId());
            out.writeVarInt(restored);
        });
    }

    public static void natural(ServerPlayer roller, int roll) {
        if (roll == 1 || roll == 20)
            ModPackets.send(roller, ModPackets.STUDIO, Update.S2C_NATURAL, out -> out.writeBoolean(roll == 20));
    }

    public static void damage(Entity target, int amount, DamageType5e type, boolean critical) {
        if (amount < 0) return;
        String symbol = switch (type) {
            case FIRE -> "🔥"; case LIGHTNING -> "⚡"; case NECROTIC -> "💀"; case RADIANT -> "✨";
            case COLD -> "❄"; case PSYCHIC -> "🧠"; case POISON, ACID -> "☠"; case FORCE, THUNDER -> "✦";
            default -> "⚔";
        };
        int color = switch (type) {
            case FIRE -> 0xff8c42; case LIGHTNING -> 0x58e8ff; case NECROTIC -> 0xb585f6;
            case RADIANT -> 0xffdb65; case COLD -> 0xb6eeff; case PSYCHIC -> 0xf686d7;
            case POISON, ACID -> 0x93dc64; case FORCE, THUNDER -> 0xa7a9ff; default -> 0xe4e8ed;
        };
        floating(target, "-" + amount + " " + symbol, color, critical, false);
    }

    public static void healed(Entity target, int amount) {
        if (amount > 0) floating(target, "+" + amount + " 💚", 0x52efa2, false, false);
    }

    public static void spell(Entity caster, String id) {
        floating(caster, "spell/" + id, 0x9dd9ff, false, false);
    }

    public static void status(Entity target, String text) { floating(target, text, 0xb8c1cb, false, true); }

    private static void floating(Entity target, String text, int color, boolean critical, boolean miss) {
        if (!(target.level() instanceof ServerLevel level)) return;
        var p = target.position().add(0, target.getBbHeight() + .1, 0);
        near(level, p, Update.S2C_COMBAT_FLOAT_TEXT, out -> {
            out.writeDouble(p.x); out.writeDouble(p.y); out.writeDouble(p.z);
            out.writeUtf(text, 64); out.writeInt(color); out.writeBoolean(critical); out.writeBoolean(miss);
        });
    }
}
