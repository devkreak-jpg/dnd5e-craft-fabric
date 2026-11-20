package net.dnd5ecraft.client.gui.screen;

import java.util.Arrays;
import java.util.List;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.network.PacketCodec;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.campaign.CampaignHero;
import net.dnd5ecraft.rules.character.BackgroundType;
import net.dnd5ecraft.rules.character.RaceType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class CampaignPresetScreen extends DndScreen {
    private final Screen parent;
    private CampaignHero editing;
    private RaceType race;
    private BackgroundType background;
    private int page;
    private String character, hp;
    private String[] scores;
    private final String[] traits = new String[4];

    public CampaignPresetScreen(Screen parent) {
        super(text("campaign.presets"));
        this.parent = parent;
    }

    @Override protected void controls() {
        int x = left + 12;
        if (editing == null) {
            int editWidth = Math.min(86, innerWidth() / 3);
            for (var hero : CampaignHero.values()) {
                int y = top + 30 + hero.ordinal() * 24;
                button(text("campaign.hero." + hero.id()), x, y, innerWidth() - editWidth - 4, () -> {
                    reset(hero);
                    submit();
                });
                button(text("campaign.edit"), x + innerWidth() - editWidth, y, editWidth, () -> {
                    reset(hero);
                    rebuild();
                });
            }
            button(text("back"), x, bottom() - 25, 78, this::onClose);
            return;
        }

        int half = (innerWidth() - 6) / 2;
        if (page == 0) {
            edit("field.name", character, x, top + 42, innerWidth(), 32).setResponder(value -> character = value);
            cycle("race", race, RaceType.values(), x, top + 64, half, value -> { race = value; rebuild(); });
            cycle("background", background, BackgroundType.values(), x + half + 6, top + 64, half,
                    value -> { background = value; rebuild(); });
            for (int i = 0; i < scores.length; i++) {
                int index = i;
                int col = (innerWidth() - 10) / 3;
                var field = edit("ability." + Ability.values()[i].name().toLowerCase(java.util.Locale.ROOT), scores[i],
                        x + i % 3 * (col + 5), top + 100 + (i / 3) * 32, col, 2);
                numbers(field);
                field.setResponder(value -> scores[index] = value);
            }
        } else {
            var health = edit("campaign.maximum_hp", hp, x, top + 42, half, 3);
            numbers(health);
            health.setResponder(value -> hp = value);
            for (int i = 0; i < traits.length; i++) {
                int index = i;
                edit(traitKey(i), traits[i], x + (i % 2) * (half + 6), top + 76 + (i / 2) * 33,
                        half, i == 3 ? 256 : 80).setResponder(value -> traits[index] = value);
            }
        }
        button(text("back"), x, bottom() - 25, 78, () -> {
            if (page == 0) editing = null;
            else page--;
            rebuild();
        });
        button(text(page == 0 ? "next" : "create.confirm"), x + innerWidth() - 88, bottom() - 25, 88, () -> {
            if (page == 0) { page++; rebuild(); }
            else submit();
        });
    }

    @Override protected void content(GuiGraphics graphics, int mouseX, int mouseY) {
        if (editing == null) return;
        int x = left + 12;
        if (page == 0) {
            caption(graphics, "field.name", x, top + 31);
            int col = (innerWidth() - 10) / 3;
            for (Ability ability : Ability.values()) {
                int i = ability.ordinal();
                graphics.drawString(font, name("ability", ability), x + i % 3 * (col + 5), top + 89 + i / 3 * 32, MUTED, false);
            }
        } else {
            caption(graphics, "campaign.maximum_hp", x, top + 31);
            int half = (innerWidth() - 6) / 2;
            for (int i = 0; i < traits.length; i++) caption(graphics, traitKey(i), x + i % 2 * (half + 6), top + 65 + i / 2 * 33);
        }
    }

    private static String traitKey(int index) {
        return List.of("field.ideal", "field.bond", "field.flaw", "campaign.notes").get(index);
    }

    private static void numbers(EditBox box) {
        box.setFilter(value -> value.chars().allMatch(c -> c >= '0' && c <= '9'));
    }

    private void reset(CampaignHero hero) {
        editing = hero;
        race = hero.race();
        background = hero.background();
        character = hero.defaultName();
        scores = hero.scores().stream().map(String::valueOf).toArray(String[]::new);
        hp = Integer.toString(hero.hp());
        Arrays.fill(traits, "");
        page = 0;
    }

    private void submit() {
        try {
            var values = Arrays.stream(scores).map(Integer::parseInt).toList();
            int health = Integer.parseInt(hp);
            if (character.isBlank() || health < 1 || health > 100 || values.stream().anyMatch(score -> score < 1 || score > 30)) {
                throw new IllegalArgumentException("Invalid character");
            }
            ClientPacketHandlers.send(Action.CAMPAIGN_PRESET, out -> {
                PacketCodec.writeEnum(out, editing);
                out.writeUtf(character, 32);
                PacketCodec.writeEnum(out, race);
                PacketCodec.writeEnum(out, background);
                PacketCodec.writeList(out, values, net.minecraft.network.FriendlyByteBuf::writeVarInt);
                out.writeVarInt(health);
                for (int i = 0; i < traits.length; i++) out.writeUtf(traits[i], i == 3 ? 256 : 80);
            });
        } catch (IllegalArgumentException invalid) {
            net.dnd5ecraft.client.ClientDndState.notify(text("campaign.invalid_preset"));
        }
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
