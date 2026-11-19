package net.dnd5ecraft.client.render;

import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import static net.dnd5ecraft.client.gui.theme.DndUiTheme.text;

public final class ShortRestEffects {
    private static final Map<Integer, Long> START = new HashMap<>();
    private ShortRestEffects() {}

    public static void start(int id, int restored) {
        var client = Minecraft.getInstance();
        var level = client.level;
        var entity = level == null ? null : level.getEntity(id);
        if (entity == null) return;
        if (START.put(id, level.getGameTime()) == null)
            level.playLocalSound(entity.getX(), entity.getY(), entity.getZ(), SoundEvents.CAMPFIRE_CRACKLE,
                    SoundSource.PLAYERS, .65f, .92f, false);
        if (entity == client.player) {
            var message = text("rest.campfire", restored).copy().withStyle(net.minecraft.ChatFormatting.GOLD);
            net.dnd5ecraft.client.ClientDndState.restToast(message);
        }
    }

    public static void clear() { START.clear(); }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.isPaused()) return;
            var level = client.level;
            START.entrySet().removeIf(entry -> {
                var entity = level.getEntity(entry.getKey());
                long age = level.getGameTime() - entry.getValue();
                if (entity == null || age >= 70) return true;
                for (int i = 0; i < 2; i++) {
                    double a = age * .19 + i * Math.PI;
                    double x = entity.getX() + .66*Math.cos(a), z = entity.getZ() + .66*Math.sin(a);
                    level.addParticle(ParticleTypes.FLAME, x, entity.getY()+.12, z, 0, .025, 0);
                    if (age % 4 == 0) level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, entity.getY()+.25, z,
                            -.012*Math.sin(a), .037, .012*Math.cos(a));
                    if (age % 13 == 0) level.addParticle(ParticleTypes.LAVA, x, entity.getY()+.14, z, 0, .03, 0);
                }
                return false;
            });
        });
    }
}
