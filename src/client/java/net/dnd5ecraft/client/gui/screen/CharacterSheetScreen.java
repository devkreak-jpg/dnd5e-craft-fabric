package net.dnd5ecraft.client.gui.screen;

import java.util.ArrayList;
import java.util.List;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.ProficiencyLevel;
import net.dnd5ecraft.rules.ability.Skill;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class CharacterSheetScreen extends DndScreen {
    private enum Tab { OVERVIEW, SKILLS, INVENTORY }

    private record Row(Component label, String roll, boolean save) {
        Row(Component label) { this(label, null, false); }
    }

    private Tab tab = Tab.OVERVIEW;
    private int page;
    private int displayedRows;
    private boolean restingAvailable;

    public CharacterSheetScreen() { super(text("sheet.title")); }

    @Override protected void controls() {
        int x = left + 12;
        int col = (innerWidth() - 12) / 3;
        for (Tab choice : Tab.values()) {
            button(name("sheet.tab", choice), x + (col + 6) * choice.ordinal(), top + 28, col, () -> {
                tab = choice;
                page = 0;
                rebuild();
            }).active = tab != choice;
        }
        int y = bottom() - 25;
        int main = (innerWidth() - 58) / 3 - 4;
        restingAvailable = canRest();
        button(text("spellbook.title"), x, y, main, () -> minecraft.setScreen(new SpellbookScreen()));
        button(text("sheet.short_rest"), x + main + 4, y, main, () -> rest(false)).active = restingAvailable;
        button(text("sheet.long_rest"), x + (main + 4) * 2, y, main, () -> rest(true)).active = restingAvailable;
        var all = lines();
        displayedRows = all.size();
        page = Math.max(0, Math.min(page, (all.size() - 1) / rows()));
        button(Component.literal("‹"), x + innerWidth() - 52, y, 24, () -> { page--; rebuild(); }).active = page > 0;
        button(Component.literal("›"), x + innerWidth() - 24, y, 24, () -> { page++; rebuild(); })
                .active = (page + 1) * rows() < displayedRows;

        var visible = visible(all);
        for (int i = 0; i < visible.size(); i++) {
            var row = visible.get(i);
            if (row.roll() == null) continue;
            var action = button(text(row.save() ? "sheet.roll_save" : "sheet.roll_skill"),
                    x + innerWidth() - 76, top + 53 + 21 * i, 76, () -> ClientPacketHandlers.send(Action.REQUEST_ROLL, out -> {
                        out.writeBoolean(row.save());
                        out.writeUtf(row.roll(), 32);
                    }));
            action.setTooltip(net.minecraft.client.gui.components.Tooltip.create(row.label()));
        }
    }

    @Override protected void tickInWorld() {
        if (displayedRows != lines().size() || canRest() != restingAvailable) rebuild();
    }

    private boolean canRest() {
        return ClientDndState.sheet() != null && !ClientDndState.controlsCombatant()
                && ClientDndState.sheet().deathSaves().state() == net.dnd5ecraft.rules.conditions.DeathSaveTracker.State.CONSCIOUS;
    }

    private static void rest(boolean longRest) {
        ClientPacketHandlers.send(Action.REST, out -> out.writeBoolean(longRest));
    }

    private int rows() { return Math.max(1, (panelHeight - 99) / 21); }

    private List<Row> visible(List<Row> all) {
        return all.stream().skip((long) page * rows()).limit(rows()).toList();
    }

    private List<Row> lines() {
        var current = ClientDndState.sheet();
        if (current == null) return List.of(new Row(text("sheet.empty")));
        List<Row> output = new ArrayList<>();
        switch (tab) {
            case OVERVIEW -> {
                var combat = ClientDndState.combat();
                var actor = combat == null || minecraft == null || minecraft.player == null ? null
                        : combat.creature(minecraft.player.getUUID());
                int armor = actor == null ? ClientDndState.sheetArmor() : actor.armor();
                int hitDice = current.resources().hitDice().values().stream().mapToInt(Integer::intValue).sum();
                output.add(new Row(text("sheet.identity", current.name(), name("race", current.race()), current.level())));
                output.add(new Row(text("sheet.hp", current.hitPoints().current(), current.hitPoints().maximum(), current.hitPoints().temporary())));
                output.add(new Row(text("sheet.defenses", armor, hitDice, current.level(), current.exhaustion().level())));
                output.add(new Row(text("sheet.death_saves", current.deathSaves().successes(), current.deathSaves().failures(),
                        name("life", current.deathSaves().state()))));
                for (Ability ability : Ability.values()) output.add(new Row(text("sheet.ability", name("ability", ability),
                        current.score(ability), signed(Ability.modifier(current.score(ability))), signed(current.savingThrowModifier(ability))),
                        ability.name(), true));
                output.add(new Row(text("sheet.background", name("background", current.background()))));
                current.classLevels().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
                    var type = entry.getKey();
                    output.add(new Row(text("sheet.level", name("class", type), entry.getValue())));
                    if (current.subclasses().containsKey(type)) output.add(new Row(name("subclass", current.subclasses().get(type))));
                });
                current.feats().stream().sorted().forEach(feat -> output.add(new Row(name("feat", feat))));
                for (var condition : current.conditions()) output.add(new Row(name("condition", condition.type())));
                if (!current.personality().description().isBlank()) {
                    output.add(new Row(text("sheet.trait", text("field.personality"), current.personality().description())));
                }
                if (!current.personality().ideal().isBlank()) output.add(new Row(text("sheet.trait", text("field.ideal"), current.personality().ideal())));
                if (!current.personality().bond().isBlank()) output.add(new Row(text("sheet.trait", text("field.bond"), current.personality().bond())));
                if (!current.personality().flaw().isBlank()) output.add(new Row(text("sheet.trait", text("field.flaw"), current.personality().flaw())));
            }
            case SKILLS -> {
                for (Skill skill : Skill.values()) output.add(new Row(text("sheet.skill", name("skill", skill),
                        signed(current.skillModifier(skill)), name("proficiency",
                                current.skillProficiencies().getOrDefault(skill, ProficiencyLevel.NONE))), skill.name(), false));
            }
            case INVENTORY -> {
                var coins = current.coins();
                output.add(new Row(text("sheet.coins", coins.copper(), coins.silver(), coins.electrum(), coins.gold(), coins.platinum())));
                output.add(new Row(text("sheet.attuned", current.attunement().items().size())));
                current.attunement().items().values().stream().sorted().forEach(item -> output.add(new Row(Component.literal(item))));
            }
        }
        return output;
    }

    private static String signed(int number) { return number >= 0 ? "+" + number : Integer.toString(number); }

    @Override protected void content(GuiGraphics g, int mouseX, int mouseY) {
        var visible = visible(lines());
        for (int i = 0; i < visible.size(); i++) {
            var row = visible.get(i);
            clipped(g, font, row.label(), left + 12, top + 58 + 21 * i, innerWidth() - (row.roll() == null ? 0 : 80), TEXT);
        }
    }
}
