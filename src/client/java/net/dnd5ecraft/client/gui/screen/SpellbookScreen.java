package net.dnd5ecraft.client.gui.screen;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.dnd5ecraft.client.ClientActions;
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

    @Override protected void controls() {
        inCombat = ClientDndState.combat() != null;
        availableSpells = spellIds();
        cast = ritual = null;
        int x = left + 12;
        listWidth = Math.max(85, innerWidth() / 2 - 5);
        int right = x + listWidth + 10;
        int details = innerWidth() - listWidth - 10;
        search = edit("spellbook.search", query, x, top + 29, listWidth, 48);
        var buttons = new java.util.ArrayList<Button>();
        for (int i = 0; i < rowsCount(); i++) {
            int index = i;
            buttons.add(button(text("spellbook.none"), x, top + 53 + 20 * i, listWidth, () -> select(index)));
        }
        rows = List.copyOf(buttons);
        prev = button(text("previous"), x, bottom() - 25, listWidth / 2 - 2, () -> { page--; refreshList(); });
        next = button(text("next"), x + listWidth / 2 + 2, bottom() - 25, listWidth / 2 - 2, () -> { page++; refreshList(); });
        search.setResponder(input -> { query = input; page = 0; refreshList(); });
        if (selected != null) {
            var combat = ClientDndState.combat();
            if (combat != null && selected.level() > 0) {
                button(text("spellbook.slot", slot, name("pool", pool)), right, bottom() - 83, details, this::cycleSlot);
            }
            if (!choices().isEmpty()) {
                button(text("spellbook.choice", choiceLabel()), right, bottom() - 62, details, () -> { choice++; rebuild(); });
            }
            if (combat != null && selected.castingTime() != Spell.CastingTime.REACTION && selected.castingTime() != Spell.CastingTime.TEN_MINUTES) {
                cast = button(text("spellbook.cast"), right, bottom() - 25, details, this::aim);
            } else if (combat == null && selected.ritual()) {
                ritual = button(text("spellbook.ritual"), right, bottom() - 25, details,
                        () -> ClientPacketHandlers.send(Action.RITUAL_START, out -> out.writeUtf(selected.id(), 64)));
            }
        }
        refreshList();
        updateActions();
    }

    @Override public void tick() {
        var currentSpells = spellIds();
        if (inCombat != (ClientDndState.combat() != null) || !availableSpells.equals(currentSpells)) {
            if (selected != null && !currentSpells.contains(selected.id())) selected = null;
            rebuild();
        } else updateActions();
    }

    private void updateActions() {
        if (cast != null) {
            var combat = ClientDndState.combat();
            cast.active = combat != null && combat.ownsTurn()
                    && (selected.level() == 0 || combat.slots().available(slot, pool == Pool.PACT) > 0);
        }
        if (ritual != null) {
            var sheet = ClientDndState.sheet();
            ritual.active = sheet != null && SpellAccess.rituals(sheet, SpellAccess.available(sheet)).contains(selected.id());
        }
    }

    private void refreshList() {
        var available = spells();
        page = Math.min(Math.max(0, page), Math.max(0, (available.size() - 1) / rowsCount()));
        for (int i = 0; i < rows.size(); i++) {
            int index = page * rowsCount() + i;
            var button = rows.get(i);
            button.visible = index < available.size();
            if (button.visible) button.setMessage(text("spell." + available.get(index).id()));
        }
        prev.active = page > 0;
        next.active = (page + 1) * rowsCount() < available.size();
    }

    private int rowsCount() { return Math.max(2, (panelHeight - 95) / 20); }

    private Set<String> spellIds() {
        var combat = ClientDndState.combat();
        if (combat != null) return Set.copyOf(combat.spells());
        var sheet = ClientDndState.sheet();
        return sheet == null ? Set.of() : SpellAccess.available(sheet).keySet();
    }

    private List<Spell> spells() {
        String filter = query.toLowerCase(Locale.ROOT).strip();
        return availableSpells.stream().map(SpellRegistry5e::require)
                .filter(spell -> spell.id().contains(filter) || text("spell." + spell.id()).getString().toLowerCase(Locale.ROOT).contains(filter))
                .sorted(java.util.Comparator.comparingInt(Spell::level).thenComparing(Spell::id)).toList();
    }

    private void select(int row) {
        var spells = spells();
        int position = page * rowsCount() + row;
        if (position >= spells.size()) return;
        selected = spells.get(position);
        slot = selected.level();
        pool = Pool.REGULAR;
        choice = 0;
        var combat = ClientDndState.combat();
        if (combat != null && slot > 0 && combat.slots().available(slot, false) == 0) cycleSlot();
        else rebuild();
    }

    private void cycleSlot() {
        var combat = ClientDndState.combat();
        if (combat == null) return;
        for (int i = 0; i < 18; i++) {
            int next = (slot + (pool == Pool.PACT ? 9 : 0)) % 18;
            pool = next >= 9 ? Pool.PACT : Pool.REGULAR;
            slot = next % 9 + 1;
            if (slot >= selected.level() && combat.slots().available(slot, pool == Pool.PACT) > 0) break;
        }
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
        Enum<?> value = choices().get(choice % choices().size());
        return name(value instanceof DamageType5e ? "damage" : "condition", value);
    }

    private void aim() {
        var options = choices();
        ClientActions.startSpell(selected, slot, pool, options.isEmpty() ? null : options.get(choice % options.size()).name());
        minecraft.setScreen(null);
    }

    @Override protected void content(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 22 + listWidth;
        int w = innerWidth() - listWidth - 10;
        if (selected == null) {
            clipped(g, font, text(spells().isEmpty() ? "spellbook.none" : "spellbook.select"), x, top + 34, w, MUTED);
            return;
        }
        clipped(g, font, text("spell." + selected.id()), x, top + 32, w, GOLD);
        clipped(g, font, text("spellbook.level", selected.level(), name("school", selected.school())), x, top + 48, w, TEXT);
        clipped(g, font, name("time", selected.castingTime()), x, top + 62, w, MUTED);
        clipped(g, font, text("spellbook.range", selected.rangeFeet()), x, top + 76, w, TEXT);
        if (selected.concentration()) clipped(g, font, text("spellbook.concentration"), x, top + 90, w, ARCANE);
        if (selected.area().isPresent()) clipped(g, font, name("area", selected.area().orElseThrow().shape()), x, top + 104, w, ARCANE);
    }
}
