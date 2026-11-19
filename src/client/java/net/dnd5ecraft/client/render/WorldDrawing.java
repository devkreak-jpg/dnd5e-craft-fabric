package net.dnd5ecraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.dnd5ecraft.rules.spells.AoEGeometry.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;

final class WorldDrawing {
    private WorldDrawing() {}

    static final net.minecraft.resources.ResourceLocation WHITE = net.dnd5ecraft.Dnd5eCraftMod.id("textures/entity/white_pixel.png");

    static void surface(PoseStack pose, VertexConsumer buffer, Vec3 normal, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
        vertex(pose, buffer, normal, a, 0, 0, color);
        vertex(pose, buffer, normal, b, 0, 1, color);
        vertex(pose, buffer, normal, c, 1, 1, color);
        vertex(pose, buffer, normal, d, 1, 0, color);
    }

    private static void vertex(PoseStack pose, VertexConsumer buffer, Vec3 n, Vec3 p, float u, float v, int color) {
        buffer.vertex(pose.last().pose(), (float)p.x(), (float)p.y(), (float)p.z())
                .color(color >> 16 & 255, color >> 8 & 255, color & 255, color >>> 24).uv(u, v)
                .overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(pose.last().normal(), (float)n.x(), (float)n.y(), (float)n.z()).endVertex();
    }

    static void ring(PoseStack pose, MultiBufferSource buffers, double inner, double outer, double z, int color) {
        var buffer = buffers.getBuffer(net.minecraft.client.renderer.RenderType.entityTranslucent(WHITE));
        var n = new Vec3(0, 0, 1);
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI / 24, b = (i + 1) * Math.PI / 24;
            surface(pose, buffer, n, radial(a, inner, z), radial(a, outer, z), radial(b, outer, z), radial(b, inner, z), color);
        }
    }

    private static Vec3 radial(double angle, double radius, double z) {
        return new Vec3(Math.cos(angle) * radius, Math.sin(angle) * radius, z);
    }

    static void segment(PoseStack stack, VertexConsumer buffer, Vec3 a, Vec3 b, int color) {
        Vec3 delta = b.minus(a);
        if (delta.length() < 1e-8) return;
        Vec3 direction = delta.unit();
        lineVertex(stack, buffer, a, direction, color);
        lineVertex(stack, buffer, b, direction, color);
    }

    private static void lineVertex(PoseStack stack, VertexConsumer buffer, Vec3 point, Vec3 normal, int color) {
        var pose = stack.last();
        buffer.vertex(pose.pose(), (float) point.x(), (float) point.y(), (float) point.z())
                .color((color >> 16) & 255, (color >> 8) & 255, color & 255, color >>> 24)
                .normal(pose.normal(), (float) normal.x(), (float) normal.y(), (float) normal.z()).endVertex();
    }

    static void colorVertex(PoseStack stack, VertexConsumer buffer, double x, double y, double z, int color) {
        buffer.vertex(stack.last().pose(), (float) x, (float) y, (float) z)
                .color((color >> 16) & 255, (color >> 8) & 255, color & 255, color >>> 24).endVertex();
    }

    static void quad(PoseStack pose, VertexConsumer buffer, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int argb) {
        for (Vec3 point : new Vec3[]{a, b, c, d}) colorVertex(pose, buffer, point.x(), point.y(), point.z(), argb);
    }

    static void label(PoseStack pose, MultiBufferSource buffers, Component text, int y, int color) {
        label(pose, buffers, text, y, color, 0);
    }

    static void label(PoseStack pose, MultiBufferSource buffers, Component text, int y, int color, int background) {
        var font = Minecraft.getInstance().font;
        font.drawInBatch(text, -font.width(text) / 2f, y, color, false,
                pose.last().pose(), buffers, Font.DisplayMode.NORMAL, background, LightTexture.FULL_BRIGHT);
    }
}
