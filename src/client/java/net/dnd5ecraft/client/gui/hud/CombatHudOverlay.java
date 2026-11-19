package net.dnd5ecraft.client.gui.hud;

import java.util.ArrayList;
import java.util.List;
import net.dnd5ecraft.client.ClientActions;
import net.dnd5ecraft.client.ClientCampaign;
import net.dnd5ecraft.entity.EchoAvatarEntity;
import net.dnd5ecraft.entity.EldritchCannonEntity;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.server.PlayerCharacter;
import net.dnd5ecraft.server.TacticalCombat;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.keybind.ModKeybinds;
import net.dnd5ecraft.combat.TurnEconomy.Resource;
import net.dnd5ecraft.network.CombatHudView;
import net.dnd5ecraft.network.TabletopText;
import net.dnd5ecraft.rules.ability.TabletopRolls;
import net.dnd5ecraft.rules.ability.TabletopRolls.Kind;
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
        if (client.player == null || client.level == null || client.options.hideGui) return;
        int width = g.guiWidth();
        int height = g.guiHeight();
        var combat = ClientDndState.combat();
        campaignVitals(g, client, combat);
        if (ModKeybinds.showHotkeys() && client.screen == null) {
            var keys = List.of(text("campaign.key_sheet", ModKeybinds.sheetKey()),
                    text("campaign.key_magic", ModKeybinds.spellbookKey()), text("campaign.key_roll", ModKeybinds.rollKey()),
                    text("campaign.key_wheel", ModKeybinds.wheelKey()), text("campaign.key_hide", ModKeybinds.hotkeysKey()));
            int y = height - 13 - (keys.size() - 1) * 10;
            for (var key : keys) {
                g.drawString(client.font, key, 6, y, MUTED, true);
                y += 10;
            }
        }
        if (combat != null) {
            centered(g, client.font, text("hud.round", combat.round()), width / 2, 5, GOLD);
            int mx = (int) (client.mouseHandler.xpos() * width / client.getWindow().getScreenWidth());
            int my = (int) (client.mouseHandler.ypos() * height / client.getWindow().getScreenHeight());
            initiative(g, client.font, combat, 0, 21, width, mx, my,
                    client.screen == null && !client.mouseHandler.isMouseGrabbed());
            if (combat.hasActor()) {
                resources(g, client.font, combat, width, height);
                reactions(g, client.font, combat, width, height);
            } else if (ClientDndState.state().dm()) {
                centered(g, client.font, text("hud.dm_observing"), width / 2, height - 88, MUTED);
            }
        }
        if (ClientActions.aiming()) centered(g, client.font, text("aim.hint", ClientActions.selectedTargets(),
                ClientActions.maximumTargets()), width / 2, height / 2 + 19, ARCANE);
        toast(g, client.font, width, height);
        prompt(g, client.font, width, height);
        var banner = ClientDndState.banner();
        if (banner != null) centered(g, client.font, banner.message(), width / 2, height / 3, GOLD);
    }

    private static void campaignVitals(GuiGraphics g, Minecraft client, Combat combat) {
        var sheet = ClientDndState.sheet();
        var player = client.player;
        if (sheet == null || player.isSpectator() || client.getCameraEntity() != player) return;
        int width = Math.min(210, g.guiWidth() - 14);
        int x = (g.guiWidth() - width) / 2;
        int y = g.guiHeight() - 49;
        var hp = sheet.hitPoints();
        var font = client.font;
        int flags = ((PlayerCharacter) player).dnd5e$campaignFlags();
        if ((flags & 1) != 0) {
            int alpha = 22 + (int) (12 * (1 + Math.sin(player.tickCount / 5.0)));
            int tint = alpha << 24 | 0x9A1818;
            g.fill(0, 0, g.guiWidth(), 5, tint);
            g.fill(0, g.guiHeight() - 5, g.guiWidth(), g.guiHeight(), tint);
        }
        panel(g, x, y, width, 23, GOLD);
        if (hp.current() == 0) {
            var death = sheet.deathSaves();
            for (int i = 0; i < 3; i++) {
                g.fill(x + 6 + i * 8, y + 8, x + 12 + i * 8, y + 14, i < death.successes() ? EMERALD : BORDER);
                g.fill(x + width - 28 + i * 8, y + 8, x + width - 22 + i * 8, y + 14, i < death.failures() ? CRIMSON : BORDER);
            }
            Component life = death.state() == net.dnd5ecraft.rules.conditions.DeathSaveTracker.State.DYING
                    ? text("campaign.death_hint", ModKeybinds.rollKey()) : name("life", death.state());
            centered(g, font, life, g.guiWidth() / 2, y + 7, TEXT);
        } else {
            int armorWidth = Math.min(46, width / 4);
            var actor = combat == null ? null : combat.creature(player.getUUID());
            int armor = actor == null ? TacticalCombat.armorClass(player, sheet) : actor.armor();
            int barWidth = width - armorWidth - 14;
            drawProgressBar(g, x + 5, y + 15, barWidth, 4, (double) hp.current() / hp.maximum(), CRIMSON);
            if (hp.temporary() > 0) {
                drawProgressBar(g, x + 5, y + 15, barWidth, 2, Math.min(1, hp.temporary() / (double) hp.maximum()), 0xff65d9f0);
            }
            clipped(g, font, text(hp.temporary() > 0 ? "campaign.hp_temp" : "campaign.hp", hp.current(), hp.maximum(),
                    hp.temporary()), x + 6, y + 5, barWidth, TEXT);
            centered(g, font, text("campaign.ac", armor), x + width - armorWidth / 2 - 3, y + 7, GOLD);
        }
        if (player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) || player.getAirSupply() < player.getMaxAirSupply()) {
            int bubbles = (int) Math.ceil(10.0 * player.getAirSupply() / player.getMaxAirSupply());
            for (int i = 0; i < 10; i++) {
                int bx = x + width - 59 + i * 5;
                g.fill(bx, y + 24, bx + 3, y + 26, i < bubbles ? 0xff74dbfb : BORDER);
            }
        }
        var badges = new ArrayList<Component>();
        if (ClientDndState.concentration(player.getUUID()) != null) badges.add(text("campaign.focus"));
        if ((flags & 1) != 0) badges.add(text("campaign.rage"));
        if (ClientCampaign.companion(EchoAvatarEntity.class) != null) badges.add(text("campaign.echo"));
        if (ClientCampaign.companion(EldritchCannonEntity.class) != null) {
            var label = text("campaign.fire_hint", ModKeybinds.fireCannonKey());
            var control = cannonControl(g.guiWidth(), g.guiHeight(), font);
            if (control != null) {
                boolean usable = !ClientDndState.controlsCombatant()
                        || combat != null && combat.ownsTurn() && combat.resources().contains(Resource.BONUS_ACTION);
                panel(g, control.x(), control.y(), control.width(), 14, usable ? GOLD : BORDER);
                clipped(g, font, label, control.x() + 4, control.y() + 3, control.width() - 7, usable ? GOLD : MUTED);
            } else badges.add(label);
        }
        int chaos = sheet.remaining(ClassFeature.CHAOS_POINTS);
        if (sheet.classLevels().containsKey(net.dnd5ecraft.rules.character.ClassType.HARLEQUIN)) {
            badges.add(text("campaign.chaos", chaos == 0 ? "◇" : "◆".repeat(Math.min(7, chaos))));
        }
        int badgeY = ClientCampaign.companion(EldritchCannonEntity.class) == null ? y : y - 16;
        for (var badge : badges) {
            int left = x + width + 6;
            if (left + 70 > g.guiWidth()) break;
            int badgeWidth = Math.min(g.guiWidth() - left - 4, font.width(badge) + 9);
            panel(g, left, badgeY, badgeWidth, 14, ARCANE);
            clipped(g, font, badge, left + 4, badgeY + 3, badgeWidth - 7, GOLD);
            badgeY -= 16;
        }
    }

    public record CannonControl(int x, int y, int width) {}
    public static CannonControl cannonControl(int width, int height, Font font) {
        int left = (width + Math.min(210, width - 14)) / 2 + 6;
        if (left + 55 > width) return null;
        int labelWidth = font.width(text("campaign.fire_hint", ModKeybinds.fireCannonKey())) + 9;
        return new CannonControl(left, height - 49, Math.min(width - left - 4, labelWidth));
    }

    public static void initiative(GuiGraphics g, Font font, Combat combat, int left, int y, int width,
                                  int mouseX, int mouseY, boolean interactive) {
        if (combat.initiative().isEmpty()) return;
        var ribbon = CombatHudView.initiative(combat, Math.max(1, Math.min(8, (width - 14) / 48)));
        int count = ribbon.size();
        int card = Math.max(40, Math.min(80, (width - 10) / count - 4));
        int x0 = left + (width - count * (card + 4) + 4) / 2;
        List<Component> tooltip = List.of();
        for (int i = 0; i < count; i++) {
            var actor = ribbon.get(i);
            int x = x0 + i * (card + 4);
            boolean hover = interactive && mouseX >= x && mouseX < x + card && mouseY >= y && mouseY < y + 30;
            int accent = actor.id().equals(combat.activeActor()) || hover ? GOLD : CombatHudView.allied(combat, actor) ? EMERALD : CRIMSON;
            panel(g, x, y, card, 30, accent);
            clipped(g, font, Component.literal(actor.name()), x + 4, y + 6, card - 8, TEXT);
            drawProgressBar(g, x + 4, y + 21, card - 8, 3, CombatHudView.healthFraction(actor.hp(), actor.maxHp()), accent);
            if (hover) {
                tooltip = new ArrayList<>();
                tooltip.add(Component.literal(actor.name()).withStyle(net.minecraft.ChatFormatting.GOLD));
                tooltip.add(text("tabletop.vitals", actor.hp(), actor.maxHp(), actor.temporary(), actor.armor()));
                String active = ClientDndState.concentration(actor.id());
                if (active != null) tooltip.add(text("tabletop.concentrating", text("spell." + active)));
                for (var condition : actor.conditions().stream().sorted().toList()) tooltip.add(name("condition", condition));
            }
        }
        if (!tooltip.isEmpty()) g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
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
        if (reaction == null || reaction.choices().isEmpty() || remaining <= 0) return;
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
        drawProgressBar(g, left + 1, top + tall - 3, card - 2, 2, reaction.progress(remaining), CRIMSON);
    }

    private static void toast(GuiGraphics g, Font font, int width, int height) {
        var notice = ClientDndState.notice();
        if (notice != null) {
            centered(g, font, notice.message(), width / 2, height - 108, TEXT);
        }
        var recent = ClientDndState.latestTabletop();
        long age = recent == null ? Long.MAX_VALUE : Util.getMillis() - recent.time();
        if (age < 4200) {
            var result = recent.card();
            var roll = result.outcome();
            boolean natural = roll.sides() == 20 && roll.dice().size() <= 3 && result.check().kind() != Kind.DAMAGE
                    && (result.check().kind() != Kind.DICE || result.check().id().equals("1d20"));
            boolean critical = roll.critical() || natural && roll.kept() == 20;
            boolean fumble = natural && roll.kept() == 1;
            int accent = critical ? GOLD : fumble ? CRIMSON : ARCANE;
            int w = Math.min(width - 14, 266);
            int tremor = fumble && age < 420 ? (int) (Math.sin(age / 29.0) * (420 - age) / 120) : 0;
            int x = (width - w) / 2 + tremor, y = 57 + (int) Math.max(0, (150 - age) / 22);
            panel(g, x, y, w, 43, accent);
            clipped(g, font, text("tabletop.toast_title", result.name(), result.label()), x + 7, y + 6, w - 14, TEXT);
            clipped(g, font, TabletopText.equation(roll), x + 7, y + 18, w - 14, accent);
            var detail = result.dc() > 0 ? TabletopText.result(result) : name("tabletop.mode", roll.mode());
            clipped(g, font, detail, x + 7, y + 30, w - 14, MUTED);
            return;
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

    private static void prompt(GuiGraphics g, Font font, int width, int height) {
        var prompt = ClientDndState.prompt();
        if (prompt == null || Minecraft.getInstance().screen != null) return;
        int w = Math.min(240, width - 12);
        int x = width - w - 6, y = Math.max(74, height / 2 - 54);
        panel(g, x, y, w, 42, GOLD);
        clipped(g, font, text("tabletop.prompt_title", prompt.requester()), x + 6, y + 6, w - 12, GOLD);
        clipped(g, font, text("tabletop.prompt_details", TabletopText.label(prompt.check()), prompt.dc(),
                name("tabletop.mode", prompt.mode())), x + 6, y + 17, w - 12, TEXT);
        clipped(g, font, text("tabletop.prompt_go", ModKeybinds.rollKey(),
                TabletopText.signed(TabletopRolls.modifier(ClientDndState.sheet(), prompt.check()))),
                x + 6, y + 29, w - 12, ARCANE);
    }

    private static void centered(GuiGraphics g, Font font, Component message, int center, int y, int color) {
        int half = Math.min(center - 3, font.width(message) / 2 + 7);
        if (half <= 0) return;
        g.fill(center - half, y - 3, center + half, y + 11, OBSIDIAN);
        clipped(g, font, message, center - half + 5, y, half * 2 - 10, color);
    }
}
