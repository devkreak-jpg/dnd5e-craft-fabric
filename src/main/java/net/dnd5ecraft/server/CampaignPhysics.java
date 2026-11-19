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

    private CampaignPhysics() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerList().getPlayers().forEach(CampaignPhysics::update));
    }

    private static void update(ServerPlayer player) {
        var sheet = ((PlayerCharacter) player).dnd5e$sheet().orElse(null);
        var movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
        var health = player.getAttribute(Attributes.MAX_HEALTH);
        if (sheet == null) {
            movement.removeModifier(WALK);
            health.removeModifier(HIT_POINTS);
            return;
        }
        double max = Math.max(1, sheet.hitPoints().maximum());
        set(health, HIT_POINTS, "D&D maximum hit points", max - health.getBaseValue(), AttributeModifier.Operation.ADDITION);
        if (player.isAlive()) {
            float expected = Math.min(player.getMaxHealth(), Math.max(1, sheet.hitPoints().current()));
            if (player.getHealth() != expected) player.setHealth(expected);
        }
        if (!TacticalCombat.isLocked(player)) ((PlayerCharacter) player).dnd5e$campaignFlags(0);
        boolean triton = sheet.race() == RaceType.TRITON;
        if (triton) player.setAirSupply(player.getMaxAirSupply());
        boolean heavy = TacticalCombat.armorFormula(player, sheet).speedPenaltyFeet(sheet.score(Ability.STRENGTH), sheet.race() == RaceType.DWARF) > 0;
        boolean stopped = sheet.hitPoints().current() == 0 || sheet.conditions().stream().anyMatch(condition ->
                condition.type() == ConditionType.RESTRAINED || condition.type() == ConditionType.GRAPPLED);
        double ratio = CampaignRules.walkMultiplier(sheet.race().walkingFeet(), heavy, sheet.exhaustion(), stopped);
        if (TacticalCombat.isLocked(player) || player.isSpectator()) ratio = 1;
        set(movement, WALK, "D&D movement speed", ratio - 1, AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    private static void set(AttributeInstance attribute, UUID id, String name, double value, AttributeModifier.Operation operation) {
        var current = attribute.getModifier(id);
        if (current != null && current.getAmount() == value) return;
        attribute.removeModifier(id);
        if (value != 0) attribute.addTransientModifier(new AttributeModifier(id, name, value, operation));
    }
}
