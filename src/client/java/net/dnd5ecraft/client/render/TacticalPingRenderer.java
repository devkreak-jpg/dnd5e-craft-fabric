package net.dnd5ecraft.client.render;

import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.List;
import net.dnd5ecraft.rules.campaign.TacticalFeedback;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.sounds.SoundEvents;
import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class TacticalPingRenderer {
    private record Ping(net.minecraft.world.phys.Vec3 point, String sender, long start) {}
    private static final List<Ping> PINGS = new ArrayList<>();

    private TacticalPingRenderer() {}

    public static void add(net.minecraft.world.phys.Vec3 at, String sender) {
        if (PINGS.size() == 64) PINGS.remove(0);
        PINGS.add(new Ping(at, sender, Util.getMillis()));
        var local = Minecraft.getInstance().player;
        if (local != null) local.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, .65f, 1.4f);
    }

    public static void clear() { PINGS.clear(); }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            var client = Minecraft.getInstance();
            long now = Util.getMillis();
            PINGS.removeIf(p -> now - p.start >= 5000);
            if (PINGS.isEmpty() || client.player == null || client.options.hideGui || context.consumers() == null) return;
            for (var ping : PINGS) {
                var pos = ping.point.subtract(context.camera().getPosition());
                var pose = context.matrixStack();
                pose.pushPose();
                pose.translate(pos.x, pos.y + .025, pos.z);
                var buffer = context.consumers().getBuffer(RenderType.entityTranslucent(WorldDrawing.WHITE));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4, b = Math.PI * (i + 1) / 4;
                    Vec3 low = new Vec3(.09*Math.cos(a), 0, .09*Math.sin(a));
                    Vec3 next = new Vec3(.09*Math.cos(b), 0, .09*Math.sin(b));
                    Vec3 up = new Vec3(0, 2.3, 0);
                    WorldDrawing.surface(pose, buffer, new Vec3(Math.cos(a), 0, Math.sin(a)), low, low.plus(up),
                            next.plus(up), next, 0xABFFCA53);
                }
                double radius = .6 + .15 * Math.sin((now - ping.start) * .006);
                pose.pushPose();
                pose.mulPose(Axis.XP.rotationDegrees(-90));
                WorldDrawing.ring(pose, context.consumers(), radius - .055, radius, 0, 0xE8FFCE65);
                pose.popPose();
                pose.translate(0, 1.35, 0);
                pose.mulPose(context.camera().rotation());
                pose.scale(-.024f, -.024f, .024f);
                int blocks = TacticalFeedback.rulerBlocks(client.player.position().distanceTo(ping.point));
                WorldDrawing.label(pose, context.consumers(), text("ping.ruler", ping.sender, TacticalFeedback.rulerFeet(blocks), blocks),
                        0, GOLD, 0xC0101016);
                pose.popPose();
            }
        });
    }
}
