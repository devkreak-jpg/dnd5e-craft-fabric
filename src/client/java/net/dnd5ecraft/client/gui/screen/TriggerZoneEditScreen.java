package net.dnd5ecraft.client.gui.screen;

import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.combat.GridMovementTracker.Cell;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.entity.TriggerZoneSpec;
import net.dnd5ecraft.entity.TriggerZoneSpec.Mode;
import net.dnd5ecraft.network.PacketCodec;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.dnd5ecraft.network.Protocol.Zone;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import static net.dnd5ecraft.client.gui.theme.DndUiTheme.*;

public final class TriggerZoneEditScreen extends DndScreen {
    private final Zone original;
    private final String[] minimum, maximum;
    private String cooldown, count, damage;
    private final String[] difficulties;
    private String chapter;
    private boolean armed, once, advanced;
    private Mode mode;
    private BestiaryPreset ambusher;

    public TriggerZoneEditScreen(Zone zone) {
        super(text("zone.title"));
        original = zone;
        var spec = zone.spec();
        minimum = coordinates(spec.min());
        maximum = coordinates(spec.max());
        cooldown = Integer.toString(spec.cooldownTicks());
        count = Integer.toString(spec.ambushers());
        damage = Integer.toString(spec.damage());
        armed = zone.armed();
        once = spec.oneShot();
        mode = spec.mode();
        ambusher = spec.ambusher();
        difficulties = new String[]{Integer.toString(spec.perceptionDc()), Integer.toString(spec.investigationDc()),
                Integer.toString(spec.disarmDc()), Integer.toString(spec.saveDc())};
        chapter = spec.title();
    }

    private static String[] coordinates(Cell cell) {
        return new String[]{Integer.toString(cell.x()), Integer.toString(cell.y()), Integer.toString(cell.z())};
    }

    @Override protected void controls() {
        int x = left + 12;
        int col = (innerWidth() - 12) / 3;
        if (advanced) {
            if (mode == Mode.TRAP) {
                for (int i = 0; i < difficulties.length; i++) {
                    int index = i;
                    numeric(dcLabel(i), difficulties[i], x + i % 2 * (col + 6), top + 63 + i / 2 * 34, col, false)
                            .setResponder(value -> difficulties[index] = value);
                }
            } else {
                edit("zone.chapter", chapter, x, top + 63, innerWidth(), 100).setResponder(value -> chapter = value);
            }
            button(text("previous"), x, bottom() - 25, 70, () -> { advanced = false; rebuild(); });
            button(text("save"), x + innerWidth() - 85, bottom() - 25, 85, this::save).active = ClientDndState.state().operator();
            return;
        }
        cycle("zone.mode", mode, Mode.values(), x, top + 28, col, value -> { mode = value; rebuild(); });
        button(text(armed ? "zone.armed" : "zone.disarmed"), x + col + 6, top + 28, col, () -> { armed = !armed; rebuild(); });
        button(text(once ? "zone.once" : "zone.repeat"), x + 2 * (col + 6), top + 28, col, () -> { once = !once; rebuild(); });
        for (int i = 0; i < 3; i++) {
            int index = i;
            int px = x + i * (col + 6);
            numeric("zone.min", minimum[i], px, top + 63, col, true).setResponder(value -> minimum[index] = value);
            numeric("zone.max", maximum[i], px, top + 97, col, true).setResponder(value -> maximum[index] = value);
        }
        numeric("zone.cooldown", cooldown, x, top + 131, col, false).setResponder(value -> cooldown = value);
        if (mode == Mode.TRAP) {
            numeric("zone.damage", damage, x + col + 6, top + 131, col, false).setResponder(value -> damage = value);
        } else {
            numeric("zone.count", count, x + col + 6, top + 131, col, false).setResponder(value -> count = value);
            button(Component.translatable("entity.dnd-5e-craft.npc." + ambusher.id()), x + 2 * (col + 6), top + 131, col, () -> {
                var all = BestiaryPreset.values();
                ambusher = all[(ambusher.ordinal() + 1) % all.length];
                rebuild();
            });
        }
        button(text("close"), x, bottom() - 25, 70, this::onClose);
        button(text("zone.details"), x + 75, bottom() - 25, 72, () -> { advanced = true; rebuild(); }).active = mode != Mode.AMBUSH;
        button(text("save"), x + innerWidth() - 85, bottom() - 25, 85, this::save).active = ClientDndState.state().operator();
    }

    private static String dcLabel(int i) {
        return switch (i) {
            case 0 -> "zone.perception";
            case 1 -> "zone.investigation";
            case 2 -> "zone.disarm";
            default -> "zone.save_dc";
        };
    }

    private EditBox numeric(String label, String value, int x, int y, int w, boolean signed) {
        EditBox field = edit(label, value, x, y, w, 9);
        field.setFilter(input -> input.matches(signed ? "-?[0-9]{0,8}" : "[0-9]{0,8}"));
        return field;
    }

    private static Cell cell(String[] values) {
        return new Cell(Integer.parseInt(values[0]), Integer.parseInt(values[1]), Integer.parseInt(values[2]));
    }

    private void save() {
        TriggerZoneSpec spec;
        try {
            spec = new TriggerZoneSpec(cell(minimum), cell(maximum), mode, Integer.parseInt(cooldown), once,
                    Integer.parseInt(damage), ambusher, Integer.parseInt(count),
                    Integer.parseInt(difficulties[0]), Integer.parseInt(difficulties[1]), Integer.parseInt(difficulties[2]),
                    Integer.parseInt(difficulties[3]), chapter);
            if (!spec.contains(original.marker())) throw new IllegalArgumentException("Marker outside zone");
        } catch (IllegalArgumentException invalid) {
            ClientDndState.notify(text("zone.invalid"));
            return;
        }
        ClientPacketHandlers.send(Action.ZONE_SAVE, out -> {
            PacketCodec.cell(out, original.marker());
            out.writeLong(original.revision());
            PacketCodec.zoneSpec(out, spec);
            out.writeBoolean(armed);
        });
    }

    @Override protected void content(GuiGraphics g, int mouseX, int mouseY) {
        int x = left + 12;
        if (advanced) {
            if (mode == Mode.TRAP) {
                int col = (innerWidth() - 12) / 3;
                for (int i = 0; i < difficulties.length; i++) {
                    caption(g, dcLabel(i), x + i % 2 * (col + 6), top + 52 + i / 2 * 34);
                }
            } else caption(g, "zone.chapter", x, top + 52);
            return;
        }
        caption(g, "zone.min", x, top + 52);
        caption(g, "zone.max", x, top + 86);
        caption(g, "zone.cooldown", x, top + 120);
        caption(g, mode == Mode.TRAP ? "zone.damage" : "zone.count", x + (innerWidth() - 12) / 3 + 6, top + 120);
    }
}
