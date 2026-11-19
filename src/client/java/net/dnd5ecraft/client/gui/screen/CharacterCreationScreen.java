package net.dnd5ecraft.client.gui.screen;

import java.util.Arrays;
import java.util.List;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.network.PacketCodec;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.Protocol.AbilityMethod;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.character.BackgroundType;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.PointBuyCalculator;
import net.dnd5ecraft.rules.character.RaceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class CharacterCreationScreen extends DndScreen {
    private String character = "", ideal = "", bond = "", flaw = "";
    private RaceType race = RaceType.HUMAN;
    private ClassType startingClass = ClassType.FIGHTER;
    private BackgroundType background = BackgroundType.SOLDIER;
    private AbilityMethod method = AbilityMethod.POINT_BUY;
    private final int[] scores = {8, 8, 8, 8, 8, 8};
    private int page;
    private int selected = -1;
    private EditBox characterInput, idealInput, bondInput, flawInput;

    public CharacterCreationScreen() { super(text("create.title")); }

    @Override protected void controls() {
        int x = left + 12;
        int col = (innerWidth() - 10) / 2;
        if (page == 0) {
            if (character.isEmpty() && minecraft != null && minecraft.player != null) character = minecraft.player.getGameProfile().getName();
            characterInput = edit("field.name", character, x, top + 42, innerWidth(), 32);
            characterInput.setResponder(value -> character = value);
            cycle("race", race, RaceType.values(), x, top + 67, col, value -> {
                remember(); race = value; rebuild();
            });
            cycle("class", startingClass, ClassType.values(), x + col + 10, top + 67, col, value -> {
                remember(); startingClass = value; rebuild();
            });
            cycle("background", background, BackgroundType.values(), x, top + 91, innerWidth(), value -> {
                remember(); background = value; rebuild();
            });
            idealInput = edit("field.ideal", ideal, x, top + 126, col, 80);
            bondInput = edit("field.bond", bond, x + col + 10, top + 126, col, 80);
            flawInput = edit("field.flaw", flaw, x, top + 160, innerWidth(), 80);
            idealInput.setResponder(value -> ideal = value);
            bondInput.setResponder(value -> bond = value);
            flawInput.setResponder(value -> flaw = value);
            button(text("next"), x + innerWidth() - 88, bottom() - 25, 88, () -> {
                remember(); if (!character.isBlank()) { page = 1; rebuild(); }
            });
        } else {
            cycle("method", method, AbilityMethod.values(), x, top + 29, col, value -> {
                method = value; selected = -1; loadScores(); rebuild();
            });
            if (method == AbilityMethod.ROLLED) button(text("create.roll"), x + col + 10, top + 29, col,
                    () -> ClientPacketHandlers.send(Action.DRAFT_ROLL));
            for (Ability ability : Ability.values()) {
                int i = ability.ordinal();
                int px = x + (i % 2) * (col + 10);
                int py = top + 65 + (i / 2) * 30;
                if (method == AbilityMethod.POINT_BUY) {
                    button(Component.literal("−"), px, py, 18, () -> adjust(i, -1)).active = scores[i] > 8;
                    button(Component.literal("+"), px + col - 18, py, 18, () -> adjust(i, 1)).active
                            = scores[i] < 15 && remaining() >= PointBuyCalculator.cost(scores[i] + 1) - PointBuyCalculator.cost(scores[i]);
                } else {
                    button(Component.literal((selected == i ? "◆ " : "") + scores[i]), px, py, col, () -> swap(i))
                            .active = method != AbilityMethod.ROLLED || ClientDndState.draft().size() == 6;
                }
            }
            button(text("back"), x, bottom() - 25, 80, () -> { page = 0; rebuild(); });
            button(text("create.confirm"), x + innerWidth() - 110, bottom() - 25, 110, this::submit).active
                    = method != AbilityMethod.ROLLED || ClientDndState.draft().size() == 6;
        }
    }

    private void remember() {
        character = characterInput.getValue().strip();
        ideal = idealInput.getValue();
        bond = bondInput.getValue();
        flaw = flawInput.getValue();
    }

    private void adjust(int index, int amount) { scores[index] += amount; rebuild(); }

    private void swap(int index) {
        if (selected == -1) selected = index;
        else {
            int previous = scores[selected];
            scores[selected] = scores[index];
            scores[index] = previous;
            selected = -1;
        }
        rebuild();
    }

    private void loadScores() {
        if (method == AbilityMethod.POINT_BUY) Arrays.fill(scores, 8);
        else if (method == AbilityMethod.STANDARD_ARRAY) receiveRoll(PointBuyCalculator.STANDARD_ARRAY);
        else if (ClientDndState.draft().size() == 6) receiveRoll(ClientDndState.draft());
        else Arrays.fill(scores, 0);
    }

    public void receiveRoll(List<Integer> values) {
        if (page != 1 || values.size() != 6 || method == AbilityMethod.POINT_BUY) return;
        for (int i = 0; i < 6; i++) scores[i] = values.get(i);
        selected = -1;
        rebuild();
    }

    private int remaining() { return 27 - Arrays.stream(scores).map(PointBuyCalculator::cost).sum(); }

    private void submit() {
        ClientPacketHandlers.send(Action.CREATE, out -> {
            out.writeUtf(character, 32);
            PacketCodec.writeEnum(out, race);
            PacketCodec.writeEnum(out, background);
            PacketCodec.writeEnum(out, startingClass);
            PacketCodec.writeEnum(out, method);
            for (int score : scores) out.writeVarInt(score);
            out.writeUtf(ideal, 80);
            out.writeUtf(bond, 80);
            out.writeUtf(flaw, 80);
        });
    }

    @Override protected void content(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 12;
        int col = (innerWidth() - 10) / 2;
        if (page == 0) {
            caption(g, "field.name", x, top + 31);
            caption(g, "field.ideal", x, top + 115);
            caption(g, "field.bond", x + col + 10, top + 115);
            caption(g, "field.flaw", x, top + 149);
        } else {
            for (Ability ability : Ability.values()) {
                int i = ability.ordinal();
                int px = x + (i % 2) * (col + 10);
                int py = top + 65 + (i / 2) * 30;
                g.drawString(font, name("ability", ability), px, py - 10, MUTED, false);
                if (method == AbilityMethod.POINT_BUY) g.drawCenteredString(font, "" + scores[i], px + col / 2, py + 5, TEXT);
            }
            var detail = method == AbilityMethod.POINT_BUY ? text("create.points", remaining()) : text("create.swap");
            g.drawString(font, detail, x, top + 158, MUTED, false);
        }
    }
}
