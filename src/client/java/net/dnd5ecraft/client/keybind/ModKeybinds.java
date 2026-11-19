package net.dnd5ecraft.client.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.EnumMap;
import net.dnd5ecraft.client.ClientActions;
import net.dnd5ecraft.client.ClientDndState;
import net.dnd5ecraft.client.ClientPacketHandlers;
import net.dnd5ecraft.client.TabletopActions;
import net.dnd5ecraft.client.gui.screen.SpellbookScreen;
import net.dnd5ecraft.client.gui.screen.ActionWheelScreen;
import net.dnd5ecraft.client.gui.screen.CampaignActionsScreen;
import net.dnd5ecraft.client.gui.screen.ReactionPromptScreen;
import net.dnd5ecraft.rules.ability.TabletopRolls.Check;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker.State;
import net.dnd5ecraft.combat.ReactionWindowManager.Kind;
import net.dnd5ecraft.network.PacketPolicy.Action;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class ModKeybinds {
    public static KeyMapping PING;
    private static KeyMapping sheet, spellbook, roll, end, dm, console, dash, disengage, stand, interact, decline, cancel, attackProfile, wheel, hotkeys, fireCannon;
    private static boolean showHotkeys = true;
    private static final EnumMap<Kind, KeyMapping> REACTIONS = new EnumMap<>(Kind.class);
    private static CameraType previousCamera;

    private ModKeybinds() {}

    public static void register() {
        sheet = key("sheet", GLFW.GLFW_KEY_C);
        spellbook = key("spellbook", GLFW.GLFW_KEY_K);
        roll = key("roll", GLFW.GLFW_KEY_R);
        end = key("end", GLFW.GLFW_KEY_SPACE);
        dm = key("dm", GLFW.GLFW_KEY_M);
        console = key("console", GLFW.GLFW_KEY_N);
        PING = key("ping", GLFW.GLFW_KEY_O);
        wheel = key("action_wheel", GLFW.GLFW_KEY_V);
        hotkeys = key("hotkeys", GLFW.GLFW_KEY_F12);
        fireCannon = key("fire_cannon", GLFW.GLFW_KEY_P);
        dash = key("dash", GLFW.GLFW_KEY_G);
        disengage = key("disengage", GLFW.GLFW_KEY_X);
        stand = key("stand", GLFW.GLFW_KEY_B);
        interact = key("interact", GLFW.GLFW_KEY_I);
        attackProfile = key("attack_profile", GLFW.GLFW_KEY_H);
        cancel = key("cancel", GLFW.GLFW_KEY_Z);
        decline = key("decline", GLFW.GLFW_KEY_F10);
        for (Kind kind : Kind.values()) {
            int keyCode = switch (kind) {
                case CUTTING_WORDS -> GLFW.GLFW_KEY_J;
                case DEFLECT_MISSILES -> GLFW.GLFW_KEY_Y;
                case READIED_ATTACK -> GLFW.GLFW_KEY_U;
                case IMPROMPTU_ADD, IMPROMPTU_SUBTRACT, PROMPTERS_EDIT, FINAL_ACT_20, FINAL_ACT_1, IMPROVISATION,
                        ABSORB_ELEMENTS -> InputConstants.UNKNOWN.getValue();
                default -> GLFW.GLFW_KEY_F6 + kind.ordinal();
            };
            REACTIONS.put(kind, key(kind.name().toLowerCase(java.util.Locale.ROOT), keyCode));
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientDndState.tick();
            ClientActions.tick();
            if (client.player == null || client.level == null) {
                if (previousCamera != null) client.options.setCameraType(previousCamera);
                previousCamera = null;
                return;
            }
            boolean tactical = ClientDndState.controlsCombatant();
            if (tactical && previousCamera == null && !ClientDndState.state().dm()) {
                previousCamera = client.options.getCameraType();
                if (previousCamera == CameraType.FIRST_PERSON) client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            } else if ((!tactical || ClientDndState.state().dm()) && previousCamera != null) {
                client.options.setCameraType(previousCamera);
                previousCamera = null;
            }
            for (var entry : REACTIONS.entrySet()) if (entry.getValue().consumeClick()) ClientActions.react(entry.getKey(), Screen.hasShiftDown());
            if (decline.consumeClick()) ClientActions.react(null, false);
            if (roll.consumeClick()) {
                var combat = ClientDndState.combat();
                if (combat != null && combat.reaction() != null && !combat.reaction().choices().isEmpty()
                        && ClientDndState.reactionRemaining() > 0) {
                    if (client.screen == null) client.setScreen(new ReactionPromptScreen(null, combat.id(), combat.reaction()));
                } else if (client.screen == null && !TabletopActions.answerPrompt()) {
                    var character = ClientDndState.sheet();
                    if (character != null && character.deathSaves().state() == State.DYING) {
                        TabletopActions.roll(new Check(net.dnd5ecraft.rules.ability.TabletopRolls.Kind.DEATH_SAVE, ""), false);
                    } else ClientPacketHandlers.send(Action.ROLL);
                }
            }
            if (client.screen != null) return;
            if (wheel.consumeClick() && wheel.isDown()) client.setScreen(new ActionWheelScreen());
            if (hotkeys.consumeClick()) showHotkeys = !showHotkeys;
            if (fireCannon.consumeClick()) {
                if (Screen.hasShiftDown() && net.dnd5ecraft.client.ClientCampaign.companion(
                        net.dnd5ecraft.entity.EldritchCannonEntity.class) != null) {
                    client.setScreen(new net.dnd5ecraft.client.gui.screen.CannonHudScreen());
                } else CampaignActionsScreen.fireCannon();
            }
            if (sheet.consumeClick()) ClientPacketHandlers.openSheet();
            if (spellbook.consumeClick()) { ClientActions.cancelAim(); client.setScreen(new SpellbookScreen()); }
            if (dm.consumeClick()) ClientPacketHandlers.send(Action.DM_MODE);
            if (console.consumeClick()) {
                if (Screen.hasShiftDown() && ClientDndState.state().operator()) {
                    ClientPacketHandlers.send(Action.PARTY);
                    client.setScreen(new net.dnd5ecraft.client.gui.screen.DmDirectorScreen(null));
                } else ClientActions.console();
            }
            if (PING.consumeClick()) ClientActions.ping();
            if (cancel.consumeClick()) {
                ClientActions.cancelAim();
                net.dnd5ecraft.client.ClientCampaign.cancelPick();
            }
            if (attackProfile.consumeClick()) ClientActions.cycleAttack();
            if (end.consumeClick() && tactical) ClientPacketHandlers.send(Action.END_TURN);
            if (dash.consumeClick() && tactical) ClientPacketHandlers.send(Action.DASH);
            if (disengage.consumeClick() && tactical) ClientPacketHandlers.send(Action.DISENGAGE);
            if (stand.consumeClick() && tactical) ClientPacketHandlers.send(Action.STAND);
            if (interact.consumeClick() && tactical) ClientActions.interact();
        });
    }

    public static boolean sharedToolbarKeys(Minecraft client, int key, int scancode, int action) {
        if (client.player == null || client.screen != null || action != GLFW.GLFW_PRESS) return false;
        if (sheet != null && sheet.matches(key, scancode) && client.options.keySaveHotbarActivator.matches(key, scancode)) {
            ClientPacketHandlers.openSheet();
            return true;
        }
        if (disengage != null && disengage.matches(key, scancode) && ClientDndState.controlsCombatant()
                && client.options.keyLoadHotbarActivator.matches(key, scancode)) {
            ClientPacketHandlers.send(Action.DISENGAGE);
            return true;
        }
        return false;
    }

    public static boolean sharedJump(Minecraft client, int key, int scancode, int action) {
        if (end == null || !end.matches(key, scancode) || !client.options.keyJump.matches(key, scancode)) return false;
        if (client.screen != null || client.player == null) {
            if (action == GLFW.GLFW_RELEASE) client.options.keyJump.setDown(false);
            return false;
        }
        if (!ClientDndState.controlsCombatant()) {
            client.options.keyJump.setDown(action != GLFW.GLFW_RELEASE);
        } else {
            client.options.keyJump.setDown(false);
            if (action == GLFW.GLFW_PRESS) ClientPacketHandlers.send(Action.END_TURN);
        }
        return true;
    }

    public static net.minecraft.network.chat.Component rollKey() { return roll.getTranslatedKeyMessage(); }

    public static boolean matchesRoll(int key, int scancode) {
        return roll != null && roll.matches(key, scancode);
    }

    public static Component reactionKey(Kind kind) { return (kind == null ? decline : REACTIONS.get(kind)).getTranslatedKeyMessage(); }

    public static boolean matchesWheel(int key, int scancode) { return wheel.matches(key, scancode); }
    public static boolean showHotkeys() { return showHotkeys; }
    public static Component sheetKey() { return sheet.getTranslatedKeyMessage(); }
    public static Component spellbookKey() { return spellbook.getTranslatedKeyMessage(); }
    public static Component wheelKey() { return wheel.getTranslatedKeyMessage(); }
    public static Component hotkeysKey() { return hotkeys.getTranslatedKeyMessage(); }
    public static Component fireCannonKey() { return fireCannon.getTranslatedKeyMessage(); }

    public static Component endKey() { return end.getTranslatedKeyMessage(); }
    public static Component dashKey() { return dash.getTranslatedKeyMessage(); }
    public static Component disengageKey() { return disengage.getTranslatedKeyMessage(); }
    public static Component standKey() { return stand.getTranslatedKeyMessage(); }

    private static KeyMapping key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping("key.dnd-5e-craft." + name,
                InputConstants.Type.KEYSYM, code, "key.categories.dnd-5e-craft"));
    }
}
