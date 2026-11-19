package net.dnd5ecraft.client.gui.hud;

import java.util.List;
import net.dnd5ecraft.client.ClientActions;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.keybind.ModKeybinds;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.network.Protocol.Combat;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class CombatHudOverlay {
    private CombatHudOverlay() {}

    public static void register() { HudRenderCallback.EVENT.register((graphics, tickDelta) -> render(graphics)); }

    private static void render(GuiGraphics g) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) return;
        int width = g.guiWidth();
        int height = g.guiHeight();
        var combat = ClientDndState.combat();
        if (combat != null) {
            initiative(g, client.font, combat, width);
            resources(g, client.font, combat, width, height);
            reactions(g, client.font, combat, width, height);
        }
        if (ClientActions.aiming()) centered(g, client.font, text("aim.hint", ClientActions.selectedTargets(),
                ClientActions.maximumTargets()), width / 2, height / 2 + 19, ARCANE);
        toast(g, client.font, width, height);
        var banner = ClientDndState.banner();
        if (banner != null) centered(g, client.font, banner.message(), width / 2, height / 3, GOLD);
    }

    private static void initiative(GuiGraphics g, Font font, Combat combat, int width) {
        int max = Math.max(1, Math.min(8, (width - 14) / 48));
        int count = Math.min(max, combat.initiative().size());
        int card = Math.max(40, Math.min(80, (width - 10) / count - 4));
        int active = 0;
        for (int i = 0; i < combat.initiative().size(); i++) {
            if (combat.initiative().get(i).id().equals(combat.activeActor())) active = i;
        }
        int start = Math.min(Math.max(0, active - count / 2), combat.initiative().size() - count);
        int x0 = (width - count * (card + 4) + 4) / 2;
        centered(g, font, text("hud.round", combat.round()), width / 2, 5, GOLD);
        var self = combat.creature(combat.controlledActor());
        for (int i = 0; i < count; i++) {
            var actor = combat.initiative().get(start + i);
            int x = x0 + i * (card + 4);
            int accent = actor.id().equals(combat.activeActor()) ? GOLD : actor.team() == self.team() ? EMERALD : CRIMSON;
            panel(g, x, 21, card, 30, accent);
            clipped(g, font, Component.literal(actor.name()), x + 4, 27, card - 8, TEXT);
            drawProgressBar(g, x + 4, 42, card - 8, 3, actor.hp() / (double) Math.max(1, actor.maxHp()), accent);
        }
    }

    private static void resources(GuiGraphics g, Font font, Combat combat, int width, int height) {
        int available = Math.min(width - 12, 435);
        int left = (width - available) / 2;
        int top = height - 88;
        panel(g, left, top, available, 33, combat.ownsTurn() ? GOLD : BORDER);
        int step = available / Resource.values().length;
        for (Resource resource : Resource.values()) {
            var label = resource == Resource.MOVEMENT ? text("hud.move", combat.movement()) : name("resource", resource);
            int start = left + resource.ordinal() * step;
            clipped(g, font, label, start + 5, top + 7, step - 8, combat.resources().contains(resource) ? TEXT : MUTED);
        }
        var hints = text("hud.controls", ModKeybinds.dashKey(), ModKeybinds.disengageKey(), ModKeybinds.standKey(), ModKeybinds.endKey());
        clipped(g, font, hints, left + 5, top + 20, available - 10, MUTED);
    }

    private static void reactions(GuiGraphics g, Font font, Combat combat, int width, int height) {
        var reaction = combat.reaction();
        long remaining = ClientDndState.reactionRemaining();
        if (reaction == null || remaining <= 0) return;
        int card = Math.min(width - 18, 290);
        int left = (width - card) / 2;
        int top = height / 2 + 20;
        var choices = reaction.choices();
        int tall = 27 + (choices.size() + 1) * 12;
        panel(g, left, top, card, tall, CRIMSON);
        clipped(g, font, text("hud.reaction"), left + 7, top + 7, card - 14, GOLD);
        for (int i = 0; i < choices.size(); i++) {
            var choice = choices.get(i);
            clipped(g, font, text("hud.reaction_key", ModKeybinds.reactionKey(choice), name("reaction", choice)),
                    left + 7, top + 19 + i * 12, card - 14, TEXT);
        }
        clipped(g, font, text("hud.reaction_key", ModKeybinds.reactionKey(null), text("hud.decline")),
                left + 7, top + 19 + choices.size() * 12, card - 14, MUTED);
        drawProgressBar(g, left + 1, top + tall - 3, card - 2, 2, remaining / 3500.0, CRIMSON);
    }

    private static void toast(GuiGraphics g, Font font, int width, int height) {
        var notice = ClientDndState.notice();
        if (notice != null) {
            centered(g, font, notice.message(), width / 2, height - 108, TEXT);
        }
        List<ClientDndState.Die> dice = ClientDndState.dice();
        if (dice.isEmpty()) return;
        var latest = dice.get(dice.size() - 1);
        if (Util.getMillis() - latest.started() > 550) {
            int result = latest.roll().result();
            String label = result == 20 ? "hud.roll_critical" : result == 1 ? "hud.roll_fumble" : "hud.roll";
            centered(g, font, text(label, latest.roll().name(), result), width / 2, 64,
                    result == 20 ? EMERALD : result == 1 ? CRIMSON : GOLD);
        }
    }

    private static void centered(GuiGraphics g, Font font, Component message, int center, int y, int color) {
        int half = Math.min(center - 3, font.width(message) / 2 + 7);
        if (half <= 0) return;
        g.fill(center - half, y - 3, center + half, y + 11, OBSIDIAN);
        clipped(g, font, message, center - half + 5, y, half * 2 - 10, color);
    }
}
