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
        font.drawInBatch(text, -font.width(text) / 2f, y, color, true,
                pose.last().pose(), buffers, Font.DisplayMode.NORMAL, background, LightTexture.FULL_BRIGHT);
    }
}
