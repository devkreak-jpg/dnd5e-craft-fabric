package net.dnd5ecraft.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import static net.dnd5ecraft.client.gui.theme.DndUiTheme.text;

public final class FloatingCombatTextRenderer {
    private record Number(Vec3 at, double drift, double lift, Component label, int color, boolean critical, long start) {}
    private static final List<Number> NUMBERS = new ArrayList<>();
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new BufferBuilder(8192));

    private FloatingCombatTextRenderer() {}

    public static void add(Vec3 at, String value, int color, boolean critical, boolean status) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        if (NUMBERS.size() == 128) NUMBERS.remove(0);
        Component label;
        if (value.startsWith("spell/") && net.dnd5ecraft.rules.spells.SpellRegistry5e.find(value.substring(6)).isPresent())
            label = text("spell." + value.substring(6));
        else if (status) label = switch (value) {
            case "saved" -> text("float.saved");
            case "failed" -> text("float.failed");
            default -> text("float.miss");
        };
        else {
            int split = value.indexOf(' ');
            var amount = Component.literal(split < 0 ? value : value.substring(0, split + 1));
            if (split >= 0) amount.append(Component.literal(value.substring(split + 1)).withStyle(style ->
                    style.withFont(net.dnd5ecraft.Dnd5eCraftMod.id("combat_symbols"))));
            label = critical ? text("float.critical", amount) : amount;
        }
        long now = Util.getMillis();
        int lane = (int)NUMBERS.stream().filter(n -> now - n.start < 350 && n.at.distanceToSqr(at) < 1).count();
        double drift = ((lane % 2 == 0 ? 1 : -1) * (.22 + level.random.nextDouble() * .18));
        NUMBERS.add(new Number(at, drift, .21 * (lane % 5), label, color, critical, now));
    }

    public static void clear() { NUMBERS.clear(); }

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            long now = Util.getMillis();
            NUMBERS.removeIf(n -> now - n.start >= 1150);
            if (NUMBERS.isEmpty() || Minecraft.getInstance().options.hideGui) return;
            var pose = context.matrixStack();
            var camera = context.camera();
            for (var n : NUMBERS) {
                double t = (now - n.start) / 1150.0;
                var pos = n.at.subtract(camera.getPosition());
                pose.pushPose();
                pose.translate(pos.x, pos.y + n.lift + 1.7*t - .84*t*t, pos.z);
                pose.mulPose(camera.rotation());
                pose.translate(n.drift * (.4 + t), 0, 0);
                float bounce = n.critical ? (float)(1.45 + .32 * Math.exp(-8*t) * Math.sin(28*t)) : 1f;
                float size = .027f * bounce;
                pose.scale(-size, -size, size);
                int alpha = (int)(255 * Math.min(1, (1-t)/.4));
                if (alpha > 3) WorldDrawing.label(pose, BUFFERS, n.label, 0, (alpha << 24) | (n.color & 0xffffff));
                pose.popPose();
            }
            BUFFERS.endBatch();
        });
    }
}
