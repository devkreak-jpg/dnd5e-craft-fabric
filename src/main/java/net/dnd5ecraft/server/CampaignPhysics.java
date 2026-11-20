package net.dnd5ecraft.server;

import java.util.UUID;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.campaign.CampaignRules;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

public final class CampaignPhysics {
    private static final UUID WALK = UUID.fromString("f82118ea-86c8-4c0b-8a3e-b542345ec98d");
    private static final UUID HIT_POINTS = UUID.fromString("5336e157-e2a4-4c12-91ad-a6d56f2331cb");

    private record EchoMovement(long until, double costFraction) {}
    private static final java.util.Map<ServerPlayer, EchoMovement> ECHO_MOVEMENT = new java.util.WeakHashMap<>();

    private CampaignPhysics() {}

    static void chargeEchoMovement(ServerPlayer player, double fraction) {
        ECHO_MOVEMENT.put(player, new EchoMovement(player.getServer().overworld().getGameTime() + 120, fraction));
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerList().getPlayers().forEach(CampaignPhysics::update));
    }

    private static void update(ServerPlayer player) {
        var sheet = ((PlayerCharacter) player).dnd5e$sheet().orElse(null);
        var movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
        var health = player.getAttribute(Attributes.MAX_HEALTH);
        if (sheet == null) {
            if (movement.getModifier(WALK) != null) movement.removeModifier(WALK);
            if (health.getModifier(HIT_POINTS) != null) health.removeModifier(HIT_POINTS);
            return;
        }
        double max = Math.max(1, sheet.hitPoints().maximum());
        set(health, HIT_POINTS, "D&D maximum hit points", max - health.getBaseValue(), AttributeModifier.Operation.ADDITION);
        if (player.isAlive()) {
            float expected = Math.min(player.getMaxHealth(), Math.max(1, sheet.hitPoints().current()));
            if (Math.abs(player.getHealth() - expected) > 1e-4) player.setHealth(expected);
        }
        boolean triton = sheet.race() == RaceType.TRITON;
        if (triton) player.setAirSupply(player.getMaxAirSupply());
        boolean heavy = TacticalCombat.armorFormula(player, sheet).speedPenaltyFeet(sheet.score(Ability.STRENGTH), sheet.race() == RaceType.DWARF) > 0;
        boolean stopped = sheet.hitPoints().current() == 0 || sheet.conditions().stream().anyMatch(condition ->
                condition.type() == ConditionType.RESTRAINED || condition.type() == ConditionType.GRAPPLED);
        double ratio = CampaignRules.walkMultiplier(sheet.race().walkingFeet(), heavy, sheet.exhaustion(), stopped);
        var echo = ECHO_MOVEMENT.get(player);
        if (echo != null) {
            if (player.getServer().overworld().getGameTime() >= echo.until()) ECHO_MOVEMENT.remove(player);
            else ratio *= 1 - echo.costFraction();
        }
        if (TacticalCombat.isLocked(player) || player.isSpectator()) ratio = 1;
        set(movement, WALK, "D&D movement speed", ratio - 1, AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    private static void set(AttributeInstance attribute, UUID id, String name, double value, AttributeModifier.Operation operation) {
        var current = attribute.getModifier(id);
        double previous = current == null ? 0 : current.getAmount();
        if (Math.abs(previous - value) <= 1e-4) return;
        if (current != null) attribute.removeModifier(id);
        if (Math.abs(value) > 1e-4) attribute.addTransientModifier(new AttributeModifier(id, name, value, operation));
    }
}
