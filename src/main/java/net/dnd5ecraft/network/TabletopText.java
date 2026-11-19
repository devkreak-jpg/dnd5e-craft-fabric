package net.dnd5ecraft.network;

import java.util.Locale;
import java.util.stream.Collectors;
import net.dnd5ecraft.network.TabletopCodec.Card;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.ability.TabletopRolls.Outcome;
import net.minecraft.network.chat.Component;

public final class TabletopText {
    private TabletopText() {}

    public static Component label(Check check) {
        String id = check.id().toLowerCase(Locale.ROOT);
        return switch (check.kind()) {
            case ABILITY -> text("ability." + id);
            case SKILL -> text("skill." + id);
            case SAVE -> text("tabletop.save_name", text("ability." + id));
            case DICE -> Component.literal(check.id());
            default -> text("tabletop.kind." + check.kind().name().toLowerCase(Locale.ROOT));
        };
    }

    public static Component text(String key, Object... args) { return Component.translatable("dnd-5e-craft.ui." + key, args); }

    public static String signed(int amount) { return amount >= 0 ? "+" + amount : Integer.toString(amount); }

    public static Component equation(Outcome roll) {
        String dice = roll.dice().isEmpty() ? "0" : roll.dice().stream().map(Object::toString).collect(Collectors.joining(" | "));
        String modifier = signed(roll.modifier());
        return text("tabletop.equation", dice, modifier, roll.total());
    }

    public static Component line(Card card) {
        return text(card.whisper() ? "tabletop.whisper_card" : "tabletop.card",
                card.name(), card.label(), equation(card.outcome()), result(card));
    }

    public static Component result(Card card) {
        if (card.dc() == 0) return Component.empty();
        return text("tabletop.vs_dc", card.dc(), text(card.outcome().total() >= card.dc()
                ? "tabletop.pass" : "tabletop.fail"));
    }
}
