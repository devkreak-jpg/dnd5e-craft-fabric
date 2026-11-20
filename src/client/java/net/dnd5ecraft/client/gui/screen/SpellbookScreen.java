package net.dnd5ecraft.client.gui.screen;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.dnd5ecraft.client.ClientActions;
import net.dnd5ecraft.client.ClientCampaign;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.spells.Spell;
import net.dnd5ecraft.rules.spells.SpellAccess;
import net.dnd5ecraft.rules.spells.SpellRegistry5e;
import net.dnd5ecraft.rules.spells.SpellSlotTable.Pool;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class SpellbookScreen extends DndScreen {
    private String query = "";
    private int page;
    private int filterLevel = -1;
    private boolean concentrationOnly;
    private boolean ritualOnly;
    private Spell selected;
    private int slot;
    private Pool pool = Pool.REGULAR;
    private int choice;
    private EditBox search;
    private int listWidth;
    private List<Button> rows = List.of();
    private Button prev, next, cast, ritual;
    private boolean inCombat;
    private Set<String> availableSpells = Set.of();

    public SpellbookScreen() { super(text("spellbook.title")); }

    @Override protected int maximumHeight() { return 380; }

    @Override protected void controls() {
        inCombat = ClientDndState.controlsCombatant();
        availableSpells = spellIds();
        cast = ritual = null;
        int x = left + 12;
        listWidth = Math.max(85, innerWidth() / 2 - 5);
        int right = x + listWidth + 10;
        int details = innerWidth() - listWidth - 10;
        search = edit("spellbook.search", query, x, top + 27, innerWidth(), 48);
        search.setHint(text("spellbook.search"));
        int filterWidth = (innerWidth() - 16) / 9;
        for (int level = -1; level <= 5; level++) {
            int filter = level;
            var label = level < 0 ? text("tabletop.spell.all") : level == 0 ? text("tabletop.spell.cantrip")
                    : net.minecraft.network.chat.Component.literal(Integer.toString(level));
            pill(label, x + (level + 1) * (filterWidth + 2), top + 50, filterWidth, 17, filterLevel == level,
                    () -> { filterLevel = filter; page = 0; rebuild(); });
        }
        pill(text("tabletop.spell.concentration_short"), x + 7 * (filterWidth + 2), top + 50, filterWidth, 17,
                concentrationOnly, () -> { concentrationOnly = !concentrationOnly; page = 0; rebuild(); })
                .setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("spellbook.concentration")));
        pill(text("tabletop.spell.ritual_short"), x + 8 * (filterWidth + 2), top + 50, filterWidth, 17,
                ritualOnly, () -> { ritualOnly = !ritualOnly; page = 0; rebuild(); })
                .setTooltip(net.minecraft.client.gui.components.Tooltip.create(text("spellbook.ritual")));
        var buttons = new java.util.ArrayList<Button>();
        for (int i = 0; i < rowsCount(); i++) {
            int index = i;
            buttons.add(pill(text("spellbook.none"), x, top + 73 + 20 * i, listWidth, 18, false, () -> select(index)));
        }
        rows = List.copyOf(buttons);
        prev = button(text("previous"), x, bottom() - 25, listWidth / 2 - 2, () -> { page--; refreshList(); });
        next = button(text("next"), x + listWidth / 2 + 2, bottom() - 25, listWidth / 2 - 2, () -> { page++; refreshList(); });
        search.setResponder(input -> { query = input; page = 0; refreshList(); });
        if (selected != null) {
            var combat = ClientDndState.controlsCombatant() ? ClientDndState.combat() : null;
            if (combat != null && selected.level() > 0) {
                button(text("spellbook.slot", slot, name("pool", pool)), right, bottom() - 83, details, this::cycleSlot);
            }
            if (!choices().isEmpty()) {
                button(text("spellbook.choice", choiceLabel()), right, bottom() - 62, details, () -> { choice++; rebuild(); });
            }
            if (campaignCast() || combat != null && selected.castingTime() != Spell.CastingTime.REACTION && selected.castingTime() != Spell.CastingTime.TEN_MINUTES) {
                cast = button(text("spellbook.cast"), right, bottom() - 25, details, this::aim);
            }
            if (selected.id().equals("mending")) {
                button(text("campaign.mending_self"), right, bottom() - 49, details, () -> {
                    ClientCampaign.spell("mending", null, null, "");
                    minecraft.setScreen(null);
                }).active = combat == null || combat.ownsTurn();
            }
            if (selected.ritual()) {
                ritual = pill(text("spellbook.ritual"), right, bottom() - (cast == null ? 25 : 43), details, 17, false,
                        () -> ClientPacketHandlers.send(Action.RITUAL_START, out -> out.writeUtf(selected.id(), 64)));
            }
        }
        refreshList();
        updateActions();
    }

    @Override protected void tickInWorld() {
        var currentSpells = spellIds();
        if (inCombat != (ClientDndState.controlsCombatant()) || !availableSpells.equals(currentSpells)) {
            if (selected != null && !currentSpells.contains(selected.id())) selected = null;
            rebuild();
        } else updateActions();
    }

    private void updateActions() {
        if (cast != null) {
            var combat = ClientDndState.controlsCombatant() ? ClientDndState.combat() : null;
            var sheet = ClientDndState.sheet();
            boolean racialFog = selected.id().equals("fog_cloud") && sheet != null
                    && sheet.remaining(net.dnd5ecraft.rules.character.ClassFeature.TRITON_FOG) > 0;
            cast.active = campaignCast() && combat == null || combat != null && combat.ownsTurn()
                    && (racialFog || selected.level() == 0 || combat.slots().available(slot, pool == Pool.PACT) > 0);
        }
        if (ritual != null) {
            var sheet = ClientDndState.sheet();
            ritual.active = !ClientDndState.controlsCombatant() && sheet != null && SpellAccess.rituals(sheet, SpellAccess.available(sheet)).contains(selected.id());
        }
    }

    private void refreshList() {
        var available = spells();
        page = Math.min(Math.max(0, page), Math.max(0, (available.size() - 1) / rowsCount()));
        for (int i = 0; i < rows.size(); i++) {
            int index = page * rowsCount() + i;
            var button = rows.get(i);
            button.visible = index < available.size();
            if (button.visible) {
                var spell = available.get(index);
                button.setMessage(text("spell." + spell.id()));
                ((net.dnd5ecraft.client.gui.theme.TabletopButton) button).trailing(net.minecraft.network.chat.Component.literal(
                        (spell.concentration() ? "C " : "") + (spell.ritual() ? "R " : "") + (spell.area().isPresent() ? "◇ " : "")
                                + spell.level()));
                button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(name("time", spell.castingTime())));
            }
        }
        prev.active = page > 0;
        next.active = (page + 1) * rowsCount() < available.size();
    }

    private int rowsCount() { return Math.max(2, (panelHeight - 108) / 20); }

    private Set<String> spellIds() {
        var combat = ClientDndState.controlsCombatant() ? ClientDndState.combat() : null;
        if (combat != null) return Set.copyOf(combat.spells());
        var sheet = ClientDndState.sheet();
        return sheet == null ? Set.of() : SpellAccess.available(sheet).keySet();
    }

    private List<Spell> spells() {
        String filter = query.toLowerCase(Locale.ROOT).strip();
        return availableSpells.stream().flatMap(id -> SpellRegistry5e.find(id).stream())
                .filter(spell -> (filterLevel < 0 || spell.level() == filterLevel)
                        && (!concentrationOnly || spell.concentration()) && (!ritualOnly || spell.ritual()))
                .filter(spell -> spell.id().contains(filter) || text("spell." + spell.id()).getString().toLowerCase(Locale.ROOT).contains(filter))
                .sorted(java.util.Comparator.comparingInt(Spell::level).thenComparing(Spell::id)).toList();
    }

    private void select(int row) {
        var spells = spells();
        int position = page * rowsCount() + row;
        if (position < 0 || position >= spells.size()) return;
        selected = spells.get(position);
        slot = selected.level();
        pool = Pool.REGULAR;
        choice = 0;
        var combat = ClientDndState.controlsCombatant() ? ClientDndState.combat() : null;
        if (combat != null && slot > 0 && combat.slots().available(slot, false) == 0) cycleSlot();
        else rebuild();
    }

    private void cycleSlot() {
        var combat = ClientDndState.controlsCombatant() ? ClientDndState.combat() : null;
        if (combat == null) return;
        int originalSlot = slot;
        Pool originalPool = pool;
        for (int i = 0; i < 18; i++) {
            int next = (slot + (pool == Pool.PACT ? 9 : 0)) % 18;
            pool = next >= 9 ? Pool.PACT : Pool.REGULAR;
            slot = next % 9 + 1;
            if (slot >= selected.level() && combat.slots().available(slot, pool == Pool.PACT) > 0) { rebuild(); return; }
        }
        slot = originalSlot;
        pool = originalPool;
        rebuild();
    }

    private List<Enum<?>> choices() {
        return switch (selected.effect().kind()) {
            case BLINDNESS -> List.of(ConditionType.BLINDED, ConditionType.DEAFENED);
            case RESTORE -> List.of(ConditionType.BLINDED, ConditionType.DEAFENED, ConditionType.PARALYZED, ConditionType.POISONED);
            case RESISTANCE -> List.of(DamageType5e.ACID, DamageType5e.COLD, DamageType5e.FIRE, DamageType5e.LIGHTNING, DamageType5e.THUNDER);
            default -> List.of();
        };
    }

    private net.minecraft.network.chat.Component choiceLabel() {
        var options = choices();
        if (options.isEmpty()) return text("spellbook.none");
        Enum<?> value = options.get(Math.floorMod(choice, options.size()));
        return name(value instanceof DamageType5e ? "damage" : "condition", value);
    }

    private boolean campaignCast() {
        return selected.effect().kind() == Spell.EffectKind.UTILITY || selected.id().equals("fog_cloud");
    }

    private void aim() {
        var id = selected.id();
        var sheet = ClientDndState.sheet();
        if (selected.effect().kind() == Spell.EffectKind.UTILITY || id.equals("fog_cloud")
                && (!inCombat || sheet != null && sheet.remaining(net.dnd5ecraft.rules.character.ClassFeature.TRITON_FOG) > 0)) {
            switch (id) {
                case "message" -> minecraft.setScreen(new CampaignMessageScreen(this));
                case "mending" -> ClientCampaign.creature(2.5, target -> ClientCampaign.spell(id, target, null, ""));
                case "fog_cloud" -> ClientCampaign.point(point -> ClientCampaign.spell(id, null, point, ""));
                default -> {
                    ClientCampaign.spell(id, null, null, "");
                    minecraft.setScreen(null);
                }
            }
            return;
        }
        var options = choices();
        ClientActions.startSpell(selected, slot, pool, options.isEmpty() ? null : options.get(Math.floorMod(choice, options.size())).name());
        minecraft.setScreen(null);
    }

    @Override protected void content(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 22 + listWidth;
        int w = innerWidth() - listWidth - 10;
        if (selected == null) {
            clipped(g, font, text(spells().isEmpty() ? "spellbook.none" : "spellbook.select"), x, top + 76, w, MUTED);
            return;
        }
        int detailY = top + 74;
        panel(g, x - 3, detailY - 1, w + 3, 55, BORDER);
        clipped(g, font, text("spell." + selected.id()), x + 3, detailY + 3, w - 6, GOLD);
        clipped(g, font, text("spellbook.level", selected.level(), name("school", selected.school())), x + 3, detailY + 16, w - 6, TEXT);
        clipped(g, font, name("time", selected.castingTime()), x + 3, detailY + 29, w - 6, MUTED);
        var badges = net.minecraft.network.chat.Component.literal("");
        if (selected.concentration()) badges.append("C  ");
        if (selected.ritual()) badges.append("R  ");
        if (selected.area().isPresent()) badges.append("◇ ").append(name("area", selected.area().orElseThrow().shape()));
        if (badges.getString().isBlank()) badges.append(text("spellbook.range", selected.rangeFeet()));
        clipped(g, font, badges, x + 3, detailY + 42, w - 6, ARCANE);
    }
}
