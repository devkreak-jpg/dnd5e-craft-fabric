package net.dnd5ecraft.client.render;

import com.mojang.math.Axis;
import net.dnd5ecraft.client.ClientDndState;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class Spinning3dDieRenderer {
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
            pose.pushPose();
            pose.scale(.38f, .38f, .38f);
            var normal = Icosahedron.FACES.get(roll.result() - 1).normal();
            pose.mulPose(new Quaternionf().rotationTo(new Vector3f((float) normal.x(), (float) normal.y(), (float) normal.z()),
                    new Vector3f(0, 0, 1)));
            pose.mulPose(Axis.YP.rotationDegrees((float) ((phase - 1) * (720 + Math.floorMod(roll.seed(), 540)))));
            pose.mulPose(Axis.XP.rotationDegrees((float) ((phase - 1) * 840)));
            var faces = context.consumers().getBuffer(RenderType.debugQuads());
            for (int i = 0; i < Icosahedron.FACES.size(); i++) {
                var f = Icosahedron.FACES.get(i);
                var a = Icosahedron.POINTS.get(f.a());
                var b = Icosahedron.POINTS.get(f.b());
                var c = Icosahedron.POINTS.get(f.c());
                int color = t >= 1 && i == roll.result() - 1 ? GOLD : (i % 2 == 0 ? 0xFF216F73 : 0xFF224D63);
                WorldDrawing.quad(pose, faces, a, b, c, c, color);
            }
            var outline = context.consumers().getBuffer(RenderType.lines());
            for (var f : Icosahedron.FACES) {
                WorldDrawing.segment(pose, outline, Icosahedron.POINTS.get(f.a()), Icosahedron.POINTS.get(f.b()), 0xFF9DB3BE);
                WorldDrawing.segment(pose, outline, Icosahedron.POINTS.get(f.b()), Icosahedron.POINTS.get(f.c()), 0xFF9DB3BE);
                WorldDrawing.segment(pose, outline, Icosahedron.POINTS.get(f.c()), Icosahedron.POINTS.get(f.a()), 0xFF9DB3BE);
            }
            pose.popPose();
            if (t > .97) {
                pose.translate(0, -.47, 0);
                pose.scale(.032f, -.032f, .032f);
                WorldDrawing.label(pose, context.consumers(), Component.literal(Integer.toString(roll.result())), 0, GOLD);
            }
            pose.popPose();
        }
    }
}
