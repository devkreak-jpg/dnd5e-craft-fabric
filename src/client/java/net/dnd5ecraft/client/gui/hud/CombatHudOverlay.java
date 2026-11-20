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
    private static net.minecraft.client.player.LocalPlayer trackedPlayer;
    private static int trackedMax, trackedHp;
    private static long previousFrame, damageAt;
    private static double displayedHp, trailingDamageHp;

    private CombatHudOverlay() {}

    private static void animateHealth(net.minecraft.client.player.LocalPlayer player, int hp, int max) {
        long time = Util.getMillis();
        if (trackedPlayer != player || trackedMax != max || time - previousFrame > 1000) {
            trackedPlayer = player;
            trackedMax = max;
            trackedHp = hp;
            displayedHp = trailingDamageHp = hp;
            previousFrame = time;
            return;
        }
        if (hp < trackedHp) damageAt = time;
        trackedHp = hp;
        long elapsed = time - previousFrame;
        displayedHp += (hp - displayedHp) * (1 - Math.exp(-elapsed / 65.0));
        if (trailingDamageHp < displayedHp) trailingDamageHp = displayedHp;
        else if (time - damageAt > 240) trailingDamageHp += (hp - trailingDamageHp) * (1 - Math.exp(-elapsed / 220.0));
        previousFrame = time;
    }

    public static void register() { HudRenderCallback.EVENT.register((graphics, tickDelta) -> render(graphics)); }

    private static void render(GuiGraphics g) {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.options.hideGui) return;
        int width = g.guiWidth();
        int height = g.guiHeight();
        var combat = ClientDndState.combat();
        boolean reacting = combat != null && combat.reaction() != null && ClientDndState.reactionRemaining() > 0;
        var layout = CombatHudView.layout(height, reacting ? combat.reaction().choices().size() : 0);
        double flash = ClientDndState.cinema();
        if (flash > 0) {
            int edge = ((int)(99 * flash) << 24) | ((ClientDndState.goldenFlash() ? GOLD : CRIMSON) & 0xffffff);
            int band = Math.max(6, Math.min(width, height) / 10);
            g.fillGradient(0, 0, width, band, edge, edge & 0xffffff);
            g.fillGradient(0, height - band, width, height, edge & 0xffffff, edge);
            for (int x = 0; x < band; x++) {
                int color = ((int)((edge >>> 24) * (1 - x / (float)band)) << 24) | (edge & 0xffffff);
                g.fill(x, band, x + 1, height - band, color);
                g.fill(width-x-1, band, width-x, height-band, color);
            }
        }
        campaignVitals(g, client, combat, layout);
        CombatInspector.render(g, client, combat);
        OnlinePartyHud.render(g, client, combat != null);
        if (ModKeybinds.showHotkeys() && client.screen == null) {
            var keys = List.of(text("campaign.key_sheet", ModKeybinds.sheetKey()),
                    text("campaign.key_magic", ModKeybinds.spellbookKey()), text("campaign.key_roll", ModKeybinds.rollKey()),
                    text("campaign.key_wheel", ModKeybinds.wheelKey()), text("campaign.key_hide", ModKeybinds.hotkeysKey()));
            int y = height - 13 - (keys.size() - 1) * 10;
            for (var key : keys) {
                clipped(g, client.font, key, 6, y, (width - Math.min(210, width - 14)) / 2 - 10, MUTED);
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
                resources(g, client.font, combat, width, layout);
                reactions(g, client.font, combat, width, layout);
            } else if (ClientDndState.state().dm()) {
                centered(g, client.font, text("hud.dm_observing"), width / 2, height - 88, MUTED);
            }
        }
        if (ClientDndState.restToast() != null) {
            centered(g, client.font, ClientDndState.restToast().message(), width / 2, height / 3, GOLD);
            return;
        }
        if (reacting) return;
        int feedbackBottom = combat != null && combat.hasActor() ? layout.resourcesTop() - 5 : layout.oxygenTop() - 5;
        var banner = ClientDndState.banner();
        if (ClientActions.aiming()) {
            centered(g, client.font, text("aim.hint", ClientActions.selectedTargets(), ClientActions.maximumTargets()),
                    width / 2, Math.max(54, Math.min(height / 2 + 19, feedbackBottom - 13)), ARCANE);
        } else if (banner != null) {
            centered(g, client.font, banner.message(), width / 2, Math.max(54, Math.min(height / 3, feedbackBottom - 13)), GOLD);
        } else if (ClientDndState.prompt() != null && client.screen == null) {
            if (feedbackBottom - 53 >= 42) prompt(g, client.font, width, Math.min(Math.max(54, height / 2 - 54), feedbackBottom - 42));
            else centered(g, client.font, text("tabletop.prompt_title", ClientDndState.prompt().requester()), width / 2,
                    Math.max(54, feedbackBottom - 13), GOLD);
        } else {
            toast(g, client.font, width, height, feedbackBottom);
        }
    }

    private static void campaignVitals(GuiGraphics g, Minecraft client, Combat combat, CombatHudView.Layout layout) {
        var sheet = ClientDndState.sheet();
        var player = client.player;
        if (sheet == null || player.isSpectator() || client.getCameraEntity() != player) return;
        int width = Math.min(210, g.guiWidth() - 14);
        int x = (g.guiWidth() - width) / 2;
        int y = layout.vitalsTop();
        var hp = sheet.hitPoints();
        animateHealth(player, hp.current(), hp.maximum());
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
            int filled = (int) Math.round(barWidth * displayedHp / hp.maximum());
            int color = hp.current() * 4 <= hp.maximum()
                    ? UiAnim.color(CRIMSON, 0xffffb77c, .22 + .22 * Math.sin(Util.getMillis() / 160.0)) : CRIMSON;
            drawProgressBar(g, x + 5, y + 15, barWidth, 4, trailingDamageHp / hp.maximum(), 0xfff3dba0);
            g.fill(x + 5, y + 15, x + 5 + filled, y + 19, color);
            if (hp.temporary() > 0) {
                int temporary = (int) Math.round(barWidth * Math.min(1, hp.temporary() / (double) hp.maximum()));
                g.fill(x + 5, y + 15, x + 5 + temporary, y + 17, 0xff65d9f0);
            }
            clipped(g, font, text(hp.temporary() > 0 ? "campaign.hp_temp" : "campaign.hp", hp.current(), hp.maximum(),
                    hp.temporary()), x + 6, y + 5, barWidth, TEXT);
            centered(g, font, text("campaign.ac", armor), x + width - armorWidth / 2 - 3, y + 7, GOLD);
        }
        if (player.getAirSupply() < player.getMaxAirSupply()) {
            int oxygen = layout.oxygenTop();
            panel(g, x, oxygen, width, 7, ARCANE);
            drawProgressBar(g, x + 2, oxygen + 2, width - 4, 3, (double) player.getAirSupply() / player.getMaxAirSupply(), 0xff65d9f0);
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
        return new CannonControl(left, CombatHudView.layout(height, 0).vitalsTop(), Math.min(width - left - 4, labelWidth));
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
            if (actor.offline()) {
                g.fill(x + 4, y + 20, x + 7, y + 23, MUTED);
                clipped(g, font, text("online.offline"), x + 10, y + 18, card - 13, MUTED);
            } else drawProgressBar(g, x + 4, y + 21, card - 8, 3, CombatHudView.healthFraction(actor.hp(), actor.maxHp()), accent);
            clipped(g, font, Component.literal(actor.name()), x + 4, y + 6, card - 8, actor.offline() ? MUTED : TEXT);
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

    private static void resources(GuiGraphics g, Font font, Combat combat, int width, CombatHudView.Layout layout) {
        int available = Math.min(width - 12, 435);
        int left = (width - available) / 2;
        int top = layout.resourcesTop();
        panel(g, left, top, available, layout.resourcesHeight(), combat.ownsTurn() ? GOLD : BORDER);
        int step = available / Resource.values().length;
        for (Resource resource : Resource.values()) {
            var label = resource == Resource.MOVEMENT ? text("hud.move", combat.movement()) : name("resource", resource);
            int start = left + resource.ordinal() * step;
            clipped(g, font, label, start + 5, top + 7, step - 8, combat.resources().contains(resource) ? TEXT : MUTED);
        }
        if (layout.resourcesHeight() < 30) return;
        var hints = text("hud.controls", ModKeybinds.dashKey(), ModKeybinds.disengageKey(), ModKeybinds.standKey(), ModKeybinds.endKey());
        clipped(g, font, hints, left + 5, top + 20, available - 10, MUTED);
    }

    private static void reactions(GuiGraphics g, Font font, Combat combat, int width, CombatHudView.Layout layout) {
        var reaction = combat.reaction();
        long remaining = ClientDndState.reactionRemaining();
        if (reaction == null || reaction.choices().isEmpty() || remaining <= 0) return;
        int card = Math.min(width - 18, 290);
        int left = (width - card) / 2;
        int top = layout.reactionsTop();
        int tall = layout.reactionsBottom() - top;
        if (tall < 23) return;
        int pulse = UiAnim.color(CRIMSON, 0xffffbc72, .3 + .25 * Math.sin(Util.getMillis() / 170.0));
        panel(g, left, top, card, tall, pulse);
        if (layout.compactReactions()) {
            clipped(g, font, text("hud.open_reaction", ModKeybinds.rollKey(), reaction.choices().size()),
                    left + 7, top + 8, card - 14, GOLD);
        } else {
            clipped(g, font, text("hud.reaction"), left + 7, top + 6, card - 14, GOLD);
            for (int i = 0; i < reaction.choices().size(); i++) {
                var choice = reaction.choices().get(i);
                clipped(g, font, text("hud.reaction_key", ModKeybinds.reactionKey(choice), name("reaction", choice)),
                        left + 7, top + 16 + i * 12, card - 14, TEXT);
            }
        }
        drawProgressBar(g, left + 1, top + tall - 3, card - 2, 2, reaction.progress(remaining), pulse);
    }

    private static void toast(GuiGraphics g, Font font, int width, int height, int bottom) {
        var notice = ClientDndState.notice();
        if (notice != null && bottom >= 68) {
            centered(g, font, notice.message(), width / 2, Math.min(height - 108, bottom - 13), TEXT);
            return;
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
            if (y + 43 > bottom) return;
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
        if (bottom >= 77 && Util.getMillis() - latest.started() > 550) {
            int result = latest.roll().result();
            String label = result == 20 ? "hud.roll_critical" : result == 1 ? "hud.roll_fumble" : "hud.roll";
            centered(g, font, text(label, latest.roll().name(), result), width / 2, 64,
                    result == 20 ? EMERALD : result == 1 ? CRIMSON : GOLD);
        }
    }

    private static void prompt(GuiGraphics g, Font font, int width, int y) {
        var prompt = ClientDndState.prompt();
        if (prompt == null || Minecraft.getInstance().screen != null) return;
        int w = Math.min(240, width - 12);
        int x = width - w - 6;
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
