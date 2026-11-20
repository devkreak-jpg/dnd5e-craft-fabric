package net.dnd5ecraft.client.gui.screen;

import java.util.UUID;
import net.dnd5ecraft.client.ClientCampaign;
import net.minecraft.client.gui.screens.Screen;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.text;

final class CampaignMessageScreen extends DndScreen {
    private final Screen parent;
    private UUID target;
    private String phrase = "";

    CampaignMessageScreen(Screen parent) {
        super(text("spell.message"));
        this.parent = parent;
    }

    @Override protected int maximumHeight() { return 122; }

    @Override protected void controls() {
        int x = left + 12;
        var players = minecraft.level.players().stream()
                .filter(player -> player != minecraft.player && minecraft.player.distanceToSqr(player) <= 576).toList();
        int index = java.util.stream.IntStream.range(0, players.size())
                .filter(i -> players.get(i).getUUID().equals(target)).findFirst().orElse(-1);
        var recipient = index < 0 ? null : players.get(index);
        button(recipient == null ? text("campaign.recipient") : recipient.getDisplayName(), x, top + 29, innerWidth(), () -> {
            target = players.get((index + 1) % players.size()).getUUID();
            rebuild();
        }).active = !players.isEmpty();
        var words = edit("campaign.message_text", phrase, x, top + 52, innerWidth(), 160);
        words.setHint(text("campaign.message_text"));
        button(text("back"), x, bottom() - 25, 70, this::onClose);
        var cast = button(text("spellbook.cast"), x + innerWidth() - 90, bottom() - 25, 90, () -> {
            ClientCampaign.spell("message", target, null, phrase);
            minecraft.setScreen(null);
        });
        cast.active = recipient != null && !phrase.isBlank();
        words.setResponder(value -> { phrase = value; cast.active = recipient != null && !value.isBlank(); });
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
}
