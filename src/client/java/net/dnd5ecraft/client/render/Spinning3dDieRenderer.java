package net.dnd5ecraft.client.render;

import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.dnd5ecraft.client.ClientDndState;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import org.joml.Quaternionf;
import org.joml.Vector3f;


public final class Spinning3dDieRenderer {
    private static final Vector3f LIGHT = new Vector3f(-.4f, .75f, .65f).normalize();

    private Spinning3dDieRenderer() {}

    public static void register() { WorldRenderEvents.AFTER_ENTITIES.register(Spinning3dDieRenderer::render); }

    private static void render(WorldRenderContext context) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.options.hideGui || context.matrixStack() == null || context.consumers() == null) return;
        long now = Util.getMillis();
        var camera = context.camera().getPosition();
        for (var die : ClientDndState.dice()) {
            var roll = die.roll();
            if (roll.result() < 1 || roll.result() > Icosahedron.FACES.size()) continue;
            var pos = roll.position();
            if (camera.distanceToSqr(pos.x(), pos.y(), pos.z()) > 48 * 48) continue;
            double t = Math.min(1, (now - die.started()) / 920.0);
            double phase = 1 - Math.pow(1 - t, 3);
            var pose = context.matrixStack();
            pose.pushPose();
            pose.translate(pos.x() - camera.x, pos.y() - camera.y + .7 * Math.sin(t * Math.PI), pos.z() - camera.z);
            pose.mulPose(context.camera().rotation());
            // The camera looks down local +Z. Orient the selected outward normal toward the viewer.
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180));
            if ((roll.result() == 20 || roll.result() == 1) && t > .75) {
                double pulse = .50 + .045 * Math.sin((now - die.started()) * .009);
                WorldDrawing.ring(pose, context.consumers(), pulse - .025, pulse, -.26,
                        roll.result() == 20 ? 0xA8FFCB59 : 0xA8EE3652);
            }
            pose.scale(.38f, .38f, .38f);
            var facing = new Vector3f(0, 0, 1);
            var resultNormal = vector(Icosahedron.FACES.get(roll.result() - 1).normal());
            var rotation = new Quaternionf().rotationTo(resultNormal, facing)
                    .rotateY((float) Math.toRadians((phase - 1) * (720 + Math.floorMod(roll.seed(), 540))))
                    .rotateX((float) Math.toRadians((phase - 1) * 840));
            pose.mulPose(rotation);
            var faces = context.consumers().getBuffer(RenderType.entitySolid(WorldDrawing.WHITE));
            for (int i = 0; i < Icosahedron.FACES.size(); i++) {
                var f = Icosahedron.FACES.get(i);
                var a = Icosahedron.POINTS.get(f.a());
                var b = Icosahedron.POINTS.get(f.b());
                var c = Icosahedron.POINTS.get(f.c());
                var center = a.plus(b).plus(c).scale(1.0 / 3);
                var n = f.normal();
                float shade = .55f + .45f * Math.max(0, rotation.transform(vector(n)).dot(LIGHT));
                int gold = shade(0xEFBD65, shade);
                int color = shade(t >= 1 && i == roll.result() - 1 ? 0xD69A38 : 0x297887, shade);
                var ia = a.scale(.91).plus(center.scale(.09));
                var ib = b.scale(.91).plus(center.scale(.09));
                var ic = c.scale(.91).plus(center.scale(.09));
                WorldDrawing.surface(pose, faces, n, a, b, ib, ia, gold);
                WorldDrawing.surface(pose, faces, n, b, c, ic, ib, gold);
                WorldDrawing.surface(pose, faces, n, c, a, ia, ic, gold);
                WorldDrawing.surface(pose, faces, n, ia, ib, ic, ic, color);
            }
            for (int i = 0; i < Icosahedron.FACES.size(); i++) {
                var face = Icosahedron.FACES.get(i);
                var n = vector(face.normal());
                if (rotation.transform(new Vector3f(n)).z <= .08f) continue;
                var center = Icosahedron.POINTS.get(face.a()).plus(Icosahedron.POINTS.get(face.b()))
                        .plus(Icosahedron.POINTS.get(face.c())).scale(1.0 / 3);
                pose.pushPose();
                pose.translate(center.x(), center.y(), center.z());
                pose.mulPose(new Quaternionf().rotationTo(facing, n));
                pose.translate(0, 0, .004f);
                pose.scale(.036f, -.036f, .036f);
                String number = Integer.toString(i + 1);
                client.font.drawInBatch(number, -client.font.width(number) / 2f, -4, 0xFFFFF2D7, false,
                        pose.last().pose(), context.consumers(), Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
                pose.popPose();
            }
            pose.popPose();
        }
    }

    private static Vector3f vector(Vec3 point) {
        return new Vector3f((float)point.x(), (float)point.y(), (float)point.z());
    }

    private static int shade(int rgb, float factor) {
        return 0xff000000 | Math.round((rgb >> 16 & 255) * factor) << 16
                | Math.round((rgb >> 8 & 255) * factor) << 8 | Math.round((rgb & 255) * factor);
    }
}
