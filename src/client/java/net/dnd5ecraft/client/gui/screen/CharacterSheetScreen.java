package net.dnd5ecraft.client.gui.screen;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.client.TabletopActions;
import net.dnd5ecraft.client.gui.theme.TabletopButton;
import net.dnd5ecraft.combat.CombatManager.Maneuver;
import net.dnd5ecraft.combat.PlayerWeaponProfiles;
import net.dnd5ecraft.network.PacketCodec;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.Protocol.Combat;
import net.dnd5ecraft.network.TabletopText;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.AbilityCheckEngine.RollMode;
import net.dnd5ecraft.rules.ability.ProficiencyLevel;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.ability.TabletopRolls;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.ability.TabletopRolls.Kind;
import net.dnd5ecraft.rules.ability.TabletopRolls.Weapon;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassFeature;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker.State;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;
import static net.dnd5ecraft.network.TabletopText.signed;

public final class CharacterSheetScreen extends DndScreen {
    private enum Tab { OVERVIEW, SKILLS, ACTIONS, INVENTORY }
    private enum ActionGroup { WEAPONS, MANEUVERS, FEATURES }

    private static final List<ClassFeature> FEATURES = List.of(ClassFeature.RAGE, ClassFeature.SECOND_WIND,
            ClassFeature.ACTION_SURGE, ClassFeature.KI_POINTS, ClassFeature.LAY_ON_HANDS, ClassFeature.BARDIC_INSPIRATION,
            ClassFeature.SORCERY_POINTS, ClassFeature.CHANNEL_DIVINITY);
    private static final List<Maneuver> QUICK = List.of(Maneuver.DODGE, Maneuver.HELP, Maneuver.HIDE, Maneuver.SHOVE, Maneuver.GRAPPLE);
    private static final String[] ABBREVIATIONS = {"STR", "DEX", "CON", "INT", "WIS", "CHA"};

    private Tab tab = Tab.OVERVIEW;
    private int page;
    private boolean compact;
    private ActionGroup actionGroup = ActionGroup.WEAPONS;
    private int diceSides = 20;
    private int diceCount = 1;
    private UUID target;
    private final EnumSet<Weapon> power = EnumSet.noneOf(Weapon.class);
    private CharacterSheetData presented;
    private Combat combatSnapshot;
    private boolean tabletop, activeRest, inspired;
    private ItemStack main = ItemStack.EMPTY, offhand = ItemStack.EMPTY;
    private int lastHitFlags;

    public CharacterSheetScreen() { super(text("sheet.title")); }

    @Override protected int maximumHeight() { return 405; }
    private int bodyTop() { return top + 72; }
    private boolean shortLayout() { return panelHeight < 303; }
    private int bodyBottom() { return bottom() - 92; }

    @Override protected void controls() {
        presented = ClientDndState.sheet();
        combatSnapshot = ClientDndState.combat();
        tabletop = ClientDndState.tabletop();
        activeRest = ClientDndState.shortRest();
        inspired = ClientDndState.inspiration();
        if (minecraft.player != null) {
            main = minecraft.player.getMainHandItem().copy();
            offhand = minecraft.player.getOffhandItem().copy();
        }
        lastHitFlags = lastHitFlags();
        header();
        footer();
        if (presented == null) return;
        switch (tab) {
            case OVERVIEW -> { if (compact) skills(true); else overview(); }
            case SKILLS -> skills(false);
            case ACTIONS -> actions();
            case INVENTORY -> pages(inventory().size(), rowCount());
        }
    }

    private void header() {
        int x = left + 12;
        int segment = (innerWidth() - 16) / 5;
        for (RollMode mode : RollMode.values()) {
            var button = pill(name("tabletop.mode", mode), x + mode.ordinal() * (segment + 4), top + 26,
                    segment, 17, ClientDndState.rollMode() == mode, () -> { ClientDndState.rollMode(mode); rebuild(); });
            button.active = tabletop;
        }
        pill(text("tabletop.whisper"), x + 3 * (segment + 4), top + 26, segment, 17, ClientDndState.whisper(),
                () -> { ClientDndState.whisper(!ClientDndState.whisper()); rebuild(); }).active = tabletop;
        if (presented != null && presented.resources().inspiration()) {
            var toggle = pill(text("tabletop.inspiration"), x + 4 * (segment + 4), top + 26, segment, 17, inspired,
                    () -> { ClientDndState.inspiration(!inspired); rebuild(); });
            toggle.active = tabletop;
            toggle.setTooltip(Tooltip.create(text("tabletop.inspiration_hint")));
        }
        int width = (innerWidth() - 12) / 4;
        for (Tab choice : Tab.values()) {
            pill(name("sheet.tab", choice), x + choice.ordinal() * (width + 4), top + 48, width, 18, tab == choice, () -> {
                tab = choice; page = 0; rebuild();
            });
        }
    }

    private void overview() {
        int x = left + 12, w = innerWidth(), y = bodyTop();
        int third = (w - 8) / 3;
        int columns = shortLayout() ? 6 : 3;
        int cardWidth = (w - (columns - 1) * 4) / columns;
        int cardY = y + (shortLayout() ? 22 : 31);
        for (Ability ability : Ability.values()) {
            int index = ability.ordinal();
            int start = x + (index % columns) * (cardWidth + 4);
            int top = cardY + (index / columns) * 36;
            int score = presented.score(ability);
            var check = new Check(Kind.ABILITY, ability.name());
            rollButton(abilityTag(ability).copy().append(" " + score), check, start, top, cardWidth, 18, false, false)
                    .trailing(Component.literal(signed(Ability.modifier(score))).withStyle(net.minecraft.ChatFormatting.BOLD));
            String dot = presented.savingThrowProficiencies().contains(ability) ? "●" : "○";
            rollButton(shortLayout() ? Component.literal(dot + " " + signed(presented.savingThrowModifier(ability)))
                    : text("tabletop.save_strip", dot, signed(presented.savingThrowModifier(ability))),
                    new Check(Kind.SAVE, ability.name()), start, top + 18, cardWidth, shortLayout() ? 13 : 15, false, false);
        }
        int below = cardY + (shortLayout() ? 33 : 74);
        var initiative = new Check(Kind.INITIATIVE, "");
        rollButton(text("tabletop.initiative", signed(TabletopRolls.modifier(presented, initiative))), initiative,
                x, below, third, 17, false, false);
        if (presented.deathSaves().state() == State.DYING) {
            var death = rollButton(text("tabletop.death_save"), new Check(Kind.DEATH_SAVE, ""), x + third + 4,
                    below, third, 17, false, false);
            death.active &= !ClientDndState.controlsCombatant() || combatSnapshot.activeActor() != null
                    && combatSnapshot.activeActor().equals(minecraft.player.getUUID()) && !combatSnapshot.paused();
        } else {
            int dice = presented.resources().hitDice().values().stream().mapToInt(Integer::intValue).sum();
            var rest = rollButton(text("tabletop.hit_dice", dice), new Check(Kind.HIT_DIE, ""), x + third + 4, below,
                    third, 17, activeRest, false);
            rest.active &= canRest() && dice != 0 && presented.hitPoints().current() < presented.hitPoints().maximum();
        }
        pill(text("tabletop.compact"), x + 2 * (third + 4), below, third, 17, false, () -> { compact = true; page = 0; rebuild(); });
    }

    private void skills(boolean condensed) {
        int x = left + 12;
        int columns = condensed && innerWidth() >= 300 ? 2 : 1;
        int column = (innerWidth() - (columns - 1) * 5) / columns;
        int startY = bodyTop() + (condensed ? 20 : 0);
        if (condensed) pill(text("back"), x, bodyTop(), 75, 16, false, () -> { compact = false; page = 0; rebuild(); });
        int rows = Math.max(1, (bodyBottom() - startY) / 19);
        int perPage = rows * columns;
        pages(Skill.values().length, perPage);
        for (int i = 0; i < perPage && page * perPage + i < Skill.values().length; i++) {
            Skill skill = Skill.values()[page * perPage + i];
            ProficiencyLevel proficiency = presented.skillProficiencies().getOrDefault(skill, ProficiencyLevel.NONE);
            if (proficiency == ProficiencyLevel.NONE && presented.classLevels().getOrDefault(ClassType.BARD, 0) >= 2) proficiency = ProficiencyLevel.HALF;
            String marker = switch (proficiency) {
                case NONE -> "○";
                case HALF -> "◐";
                case PROFICIENT -> "●";
                case EXPERTISE -> "★";
            };
            var row = rollButton(Component.literal(marker + " ").append(name("skill", skill)),
                    new Check(Kind.SKILL, skill.name()), x + i / rows * (column + 5), startY + i % rows * 19,
                    column, 17, false, false);
            row.trailing(Component.literal(abilityTag(skill.ability()).getString() + "   ")
                    .append(Component.literal(signed(presented.skillModifier(skill))).withStyle(net.minecraft.ChatFormatting.BOLD)));
            row.accent(proficiency == ProficiencyLevel.NONE ? MUTED : GOLD);
        }
    }

    private void actions() {
        if (minecraft.player == null) return;
        int x = left + 12, w = innerWidth();
        boolean compactActions = shortLayout();
        if (compactActions) {
            int third = (w - 8) / 3;
            for (var group : ActionGroup.values()) {
                pill(name("tabletop.group", group), x + group.ordinal() * (third + 4), bodyTop(), third, 15,
                        group == actionGroup, () -> { actionGroup = group; page = 0; rebuild(); });
            }
        }
        int y = actionStart();
        if (!compactActions || actionGroup == ActionGroup.WEAPONS) {
            weaponButtons(x, y, w, compactActions ? 18 : 21);
            if (compactActions) return;
        }
        boolean turn = ownTurn();
        var targets = combatSnapshot == null ? List.<net.dnd5ecraft.network.Protocol.Creature>of() : combatSnapshot.initiative().stream()
                .filter(actor -> actor.life() != State.DEAD && !actor.id().equals(minecraft.player.getUUID())).toList();
        int index = java.util.stream.IntStream.range(0, targets.size()).filter(i -> targets.get(i).id().equals(target)).findFirst().orElse(-1);
        if (index < 0) target = null;
        int targetY = y + (compactActions ? 0 : 64);
        pill(text("tabletop.action_target", index < 0 ? text("tabletop.no_target") : targets.get(index).name()), x, targetY, w,
                17, false, () -> { target = targets.get((index + 1) % targets.size()).id(); rebuild(); }).active = !targets.isEmpty();
        if (!compactActions || actionGroup == ActionGroup.MANEUVERS) {
            quickActions(x, targetY + (compactActions ? 18 : 20), w, turn);
            if (compactActions) return;
        }
        int featureY = targetY + (compactActions ? 18 : 60);
        var features = FEATURES.stream().filter(feature -> feature.capacity(presented.classLevels(),
                Ability.modifier(presented.score(Ability.CHARISMA)), presented.race()) > 0).toList();
        int featureRows = Math.max(1, (bodyBottom() - featureY) / 19);
        int perPage = featureRows * 2;
        pages(features.size(), perPage);
        int half = (w - 4) / 2;
        for (int i = 0; i < perPage && page * perPage + i < features.size(); i++) {
            var feature = features.get(page * perPage + i);
            int remaining = presented.remaining(feature);
            String pips = remaining == Integer.MAX_VALUE ? "∞" : remaining <= 5 ? "●".repeat(remaining) : "×" + remaining;
            if (pips.isEmpty()) pips = "○";
            pill(name("tabletop.feature", feature), x + (i % 2) * (half + 4), featureY + i / 2 * 19, half, 17,
                    false, () -> feature(feature)).trailing(Component.literal(pips)).active = turn
                    && remaining >= (feature == ClassFeature.SORCERY_POINTS ? 2 : 1)
                    && (feature != ClassFeature.BARDIC_INSPIRATION || target != null);
        }
    }

    private int actionStart() { return bodyTop() + (shortLayout() ? 17 : 0); }

    private void quickActions(int x, int y, int w, boolean turn) {
        int quickWidth = (w - 12) / 4;
        pill(text("tabletop.action.dash"), x, y, quickWidth, 17, false, () -> ClientPacketHandlers.send(Action.DASH)).active = turn;
        pill(text("tabletop.action.disengage"), x + quickWidth + 4, y, quickWidth, 17, false,
                () -> ClientPacketHandlers.send(Action.DISENGAGE)).active = turn;
        for (int i = 0; i < QUICK.size(); i++) {
            var action = QUICK.get(i);
            int position = i + 2;
            boolean needsTarget = action == Maneuver.HELP || action == Maneuver.SHOVE || action == Maneuver.GRAPPLE;
            pill(name("tabletop.action", action), x + (position % 4) * (quickWidth + 4), y + (position / 4) * 18,
                    quickWidth, 17, false, () -> maneuver(action, needsTarget ? target : null, 0)).active = turn && (!needsTarget || target != null);
        }
    }

    private void weaponButtons(int x, int y, int w, int spacing) {
        int labelWidth = w * 3 / 10, buttonWidth = (w - labelWidth - 8) / 2;
        for (Weapon weapon : Weapon.values()) {
            int wy = y + weapon.ordinal() * spacing;
            if (!PlayerWeaponProfiles.available(minecraft.player, weapon)) continue;
            var profile = PlayerWeaponProfiles.tabletop(minecraft.player, presented, weapon);
            boolean canPower = PlayerWeaponProfiles.canPower(presented, profile);
            boolean powered = canPower && power.contains(weapon);
            boolean crit = ClientDndState.critical(weapon);
            if (canPower) pill(text("tabletop.power", PlayerWeaponProfiles.label(minecraft.player, weapon)), x, wy,
                    labelWidth - 4, 18, powered, () -> { if (!power.add(weapon)) power.remove(weapon); rebuild(); })
                    .setTooltip(Tooltip.create(text("tabletop.power_hint")));
            rollButton(text("tabletop.hit", signed(profile.attackBonus() - (powered ? 5 : 0))), new Check(Kind.HIT, weapon.name()),
                    x + labelWidth, wy, buttonWidth, 18, false, powered);
            rollButton(text(crit ? "tabletop.crit_damage" : "tabletop.damage",
                    TabletopRolls.damageFormula(profile.damage().get(0), crit, ClientDndState.powerDamage(weapon, powered) ? 10 : 0)), new Check(Kind.DAMAGE, weapon.name()),
                    x + labelWidth + buttonWidth + 4, wy, buttonWidth, 18, crit, powered);
        }
    }

    private void feature(ClassFeature feature) {
        switch (feature) {
            case RAGE -> maneuver(Maneuver.RAGE, null, 0);
            case SECOND_WIND -> maneuver(Maneuver.SECOND_WIND, null, 0);
            case ACTION_SURGE -> ClientPacketHandlers.send(Action.SURGE);
            case KI_POINTS -> maneuver(Maneuver.PATIENT_DEFENSE, null, 0);
            case LAY_ON_HANDS -> maneuver(Maneuver.LAY_ON_HANDS, target == null ? minecraft.player.getUUID() : target, 1);
            case BARDIC_INSPIRATION -> maneuver(Maneuver.INSPIRE, target, 0);
            case SORCERY_POINTS -> maneuver(Maneuver.QUICKEN_SPELL, null, 0);
            case CHANNEL_DIVINITY -> maneuver(Maneuver.TURN_UNDEAD, null, 0);
            default -> throw new IllegalArgumentException("Unavailable feature");
        }
    }

    private static void maneuver(Maneuver maneuver, UUID target, int option) {
        ClientPacketHandlers.send(Action.MANEUVER, out -> {
            PacketCodec.writeEnum(out, maneuver);
            out.writeBoolean(target != null);
            if (target != null) out.writeUUID(target);
            out.writeVarInt(option);
            out.writeBoolean(false);
        });
    }

    private void footer() {
        int x = left + 12, w = innerWidth();
        if (tab == Tab.ACTIONS && presented != null) {
            pill(text("campaign.actions"), x + w - Math.min(125, w / 3), top + 7, Math.min(125, w / 3), 16,
                    false, () -> minecraft.setScreen(new CampaignActionsScreen(this)));
        } else if ((tab == Tab.OVERVIEW || tab == Tab.SKILLS) && presented != null
                && presented.race() == net.dnd5ecraft.rules.character.RaceType.LOXODON) {
            int toggleWidth = Math.min(126, w / 3);
            var smell = pill(text("campaign.keen_smell"), x + w - toggleWidth, top + 7, toggleWidth, 16, ClientDndState.smell(),
                    () -> { ClientDndState.smell(!ClientDndState.smell()); rebuild(); });
            smell.setTooltip(Tooltip.create(text("campaign.keen_smell_hint")));
        } else if (tab == Tab.INVENTORY && minecraft.player instanceof net.dnd5ecraft.server.PlayerCharacter character) {
            int toggleWidth = Math.min(175, w / 2);
            pill(text("campaign.cosmetics"), x + w - toggleWidth, top + 7, toggleWidth, 16,
                    character.dnd5e$showsCosmetics(), () -> ClientPacketHandlers.send(Action.CAMPAIGN_COSMETICS));
        }
        int dieWidth = (w - 24) / 7;
        for (int i = 0; i < TabletopRolls.DICE.size(); i++) {
            int sides = TabletopRolls.DICE.get(i);
            int count = diceSides == sides ? diceCount : 1;
            var die = pill(Component.literal((count == 1 ? "" : Integer.toString(count)) + "d" + sides), x + i * (dieWidth + 4),
                    bottom() - 87, dieWidth, 17, count > 1, () -> {
                        TabletopActions.roll(new Check(Kind.DICE, count + "d" + sides), false);
                        diceCount = 1; rebuild();
                    }).rightClick(() -> {
                        if (diceSides != sides) { diceSides = sides; diceCount = 1; }
                        diceCount = sides == 100 ? 1 : Math.min(8, diceCount + 1);
                        rebuild();
                    });
            die.active = tabletop;
            die.setTooltip(Tooltip.create(text("tabletop.dice_hint")));
        }
        int button = (w - 62) / 3;
        int y = bottom() - 23;
        pill(text("spellbook.title"), x, y, button, 17, false, () -> minecraft.setScreen(new SpellbookScreen()));
        pill(text("sheet.short_rest"), x + button + 4, y, button, 17, activeRest, () -> {
            if (tabletop) ClientPacketHandlers.send(Action.TABLETOP_REST);
            else ClientPacketHandlers.send(Action.REST, out -> out.writeBoolean(false));
        }).active = canRest() && !activeRest;
        pill(text("sheet.long_rest"), x + (button + 4) * 2, y, button, 17, false,
                () -> ClientPacketHandlers.send(Action.REST, out -> out.writeBoolean(true))).active = canRest();
    }

    private void pages(int count, int perPage) {
        page = Math.min(Math.max(0, page), Math.max(0, (count - 1) / perPage));
        if (count <= perPage) return;
        int x = left + 12 + innerWidth() - 46, y = bottom() - 23;
        pill(Component.literal("‹"), x, y, 20, 17, false, () -> { page--; rebuild(); }).active = page > 0;
        pill(Component.literal("›"), x + 24, y, 20, 17, false, () -> { page++; rebuild(); }).active = (page + 1) * perPage < count;
    }

    private TabletopButton rollButton(Component label, Check check, int x, int y, int w, int h, boolean selected, boolean power) {
        var button = pill(label, x, y, w, h, selected, () -> { TabletopActions.roll(check, power); rebuild(); });
        button.active = tabletop || check.kind() == Kind.SAVE || check.kind() == Kind.SKILL;
        button.setTooltip(Tooltip.create(text("tabletop.roll_hint")));
        return button;
    }

    private boolean canRest() { return presented != null && presented.deathSaves().state() == State.CONSCIOUS
            && !ClientDndState.controlsCombatant() && minecraft.player != null && !minecraft.player.isSpectator(); }
    private boolean ownTurn() { return combatSnapshot != null && combatSnapshot.ownsTurn()
            && presented.deathSaves().state() == State.CONSCIOUS
            && combatSnapshot.controlledActor().equals(minecraft.player.getUUID()); }

    private static Component abilityTag(Ability ability) {
        return text("tabletop.ability_tag." + ABBREVIATIONS[ability.ordinal()].toLowerCase(java.util.Locale.ROOT));
    }

    private static int lastHitFlags() {
        int mask = 0;
        for (var weapon : Weapon.values()) {
            int index = weapon.ordinal() * 3;
            if (ClientDndState.critical(weapon)) mask |= 1 << index;
            if (ClientDndState.powerDamage(weapon, false)) mask |= 2 << index;
            if (ClientDndState.powerDamage(weapon, true)) mask |= 4 << index;
        }
        return mask;
    }

    private int rowCount() { return Math.max(1, (bodyBottom() - bodyTop()) / 13); }

    private List<Component> inventory() {
        var result = new ArrayList<Component>();
        var coins = presented.coins();
        result.add(text("sheet.coins", coins.copper(), coins.silver(), coins.electrum(), coins.gold(), coins.platinum()));
        result.add(text("sheet.attuned", presented.attunement().items().size()));
        presented.attunement().items().values().stream().sorted().forEach(item -> result.add(Component.literal(item)));
        result.add(text("sheet.background", name("background", presented.background())));
        presented.classLevels().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            result.add(text("sheet.level", name("class", entry.getKey()), entry.getValue()));
            var subclass = presented.subclasses().get(entry.getKey());
            if (subclass != null) result.add(name("subclass", subclass));
        });
        presented.feats().stream().sorted().forEach(feat -> result.add(name("feat", feat)));
        presented.conditions().forEach(condition -> result.add(name("condition", condition.type())));
        var traits = presented.personality();
        String[] keys = {"field.personality", "field.ideal", "field.bond", "field.flaw"};
        String[] values = {traits.description(), traits.ideal(), traits.bond(), traits.flaw()};
        for (int i = 0; i < keys.length; i++) if (!values[i].isBlank()) result.add(text("sheet.trait", text(keys[i]), values[i]));
        return result;
    }

    @Override protected void tickInWorld() {
        if (!java.util.Objects.equals(presented, ClientDndState.sheet()) || !java.util.Objects.equals(combatSnapshot, ClientDndState.combat())
                || tabletop != ClientDndState.tabletop() || activeRest != ClientDndState.shortRest() || inspired != ClientDndState.inspiration()
                || lastHitFlags != lastHitFlags()
                || !ItemStack.isSameItemSameTags(main, minecraft.player.getMainHandItem())
                || !ItemStack.isSameItemSameTags(offhand, minecraft.player.getOffhandItem())) rebuild();
    }

    @Override protected void content(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 12, y = bodyTop(), w = innerWidth();
        if (presented == null) clipped(g, font, text("sheet.empty"), x, y, w, MUTED);
        else if (tab == Tab.OVERVIEW && !compact) {
            var hp = presented.hitPoints();
            clipped(g, font, text("sheet.identity", presented.name(), name("race", presented.race()), presented.level()), x, y, w, GOLD);
            var deathPips = text("tabletop.death_pips", pips(presented.deathSaves().successes()), pips(presented.deathSaves().failures()));
            clipped(g, font, hp.current() == 0 && shortLayout() ? deathPips
                    : text("tabletop.vitals", hp.current(), hp.maximum(), hp.temporary(), ClientDndState.sheetArmor()), x, y + 12, w, TEXT);
            if (!shortLayout()) {
                drawProgressBar(g, x, y + 24, w, 3, net.dnd5ecraft.network.CombatHudView.healthFraction(hp.current(), hp.maximum()), EMERALD);
                if (hp.current() == 0) clipped(g, font, deathPips, x, y + 124, w, CRIMSON);
            }
        } else if (tab == Tab.INVENTORY) {
            var rows = inventory().stream().skip((long) page * rowCount()).limit(rowCount()).toList();
            for (int i = 0; i < rows.size(); i++) clipped(g, font, rows.get(i), x, y + i * 13, w, TEXT);
        } else if (tab == Tab.ACTIONS && minecraft.player != null && (!shortLayout() || actionGroup == ActionGroup.WEAPONS)) {
            y = actionStart();
            int spacing = shortLayout() ? 18 : 21;
            for (Weapon weapon : Weapon.values()) {
                if (!PlayerWeaponProfiles.available(minecraft.player, weapon)) {
                    clipped(g, font, name("tabletop.weapon", weapon), x + 4, y + weapon.ordinal() * spacing + 5, w / 3, MUTED);
                } else if (!PlayerWeaponProfiles.canPower(presented, PlayerWeaponProfiles.tabletop(minecraft.player, presented, weapon))) {
                    clipped(g, font, PlayerWeaponProfiles.label(minecraft.player, weapon), x + 4, y + weapon.ordinal() * spacing + 5, w * 3 / 10 - 6, TEXT);
                }
            }
        }
        var player = minecraft.player;
        var feed = ClientDndState.tabletopResults().stream().filter(result -> result.card().roller().equals(player.getUUID())).toList();
        int first = Math.max(0, feed.size() - 3);
        if (feed.isEmpty()) clipped(g, font, text("tabletop.recent"), x, bottom() - 58, w, MUTED);
        for (int i = first; i < feed.size(); i++) {
            clipped(g, font, TabletopText.line(feed.get(i).card()), x, bottom() - 69 + 10 * (i - first), w, i == feed.size() - 1
                            && net.minecraft.Util.getMillis() - feed.get(i).time() < 700 ? GOLD : MUTED);
        }
    }

    private static String pips(int count) { return "●".repeat(count) + "○".repeat(3 - count); }
}
