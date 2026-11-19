package net.dnd5ecraft.server;

import com.mojang.brigadier.CommandDispatcher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.ProficiencyLevel;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.character.BackgroundType;
import net.dnd5ecraft.rules.character.CharacterSheetData;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.conditions.ConditionInstance;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandAndDisarmTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void characterEditsAndGearAssignmentsRequireOperatorLevelTwo() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(DndCommands.commandTree());
        for (String command : List.of("preset finneas", "class fighter", "subclass champion", "feat resilient dex",
                "dm build_demo_map", "dm give_gear alexander", "dm give_gear minotaur Player")) {
            assertNull(dispatcher.parse("dnd " + command, source(0)).getContext().getCommand(), command);
            assertNull(dispatcher.parse("dnd " + command, source(1)).getContext().getCommand(), command);
            assertNotNull(dispatcher.parse("dnd " + command, source(2)).getContext().getCommand(), command);
        }
        assertNotNull(dispatcher.parse("dnd sheet", source(0)).getContext().getCommand());
    }

    @Test void disarmKeepsExpertiseAndResolvesAdvantageAndDisadvantageAsOneCheck() {
        var base = CharacterSheetData.create("Locksmith", RaceType.HUMAN, BackgroundType.URCHIN, ClassType.ROGUE,
                Map.of(Ability.STRENGTH, 10, Ability.DEXTERITY, 16, Ability.CONSTITUTION, 10,
                        Ability.INTELLIGENCE, 10, Ability.WISDOM, 10, Ability.CHARISMA, 10));
        var skilled = new CharacterSheetData(base.name(), base.race(), base.background(), base.personality(),
                base.startingClass(), base.classLevels(), base.subclasses(), base.abilityScores(),
                Map.of(Skill.SLEIGHT_OF_HAND, ProficiencyLevel.EXPERTISE), base.savingThrowProficiencies(), base.feats(),
                base.experiencePoints(), base.hitPoints(), base.coins(), base.attunement(), base.exhaustion(),
                base.conditions(), base.deathSaves(), base.resources());
        AtomicInteger dice = new AtomicInteger();
        var result = PlayerRules.disarm(skilled, 18, sides -> dice.incrementAndGet() == 1 ? 3 : 14);
        assertEquals(2, dice.get());
        assertEquals(14, result.naturalRoll());
        assertEquals(7, result.modifier());
        assertTrue(result.succeeds());

        var impaired = skilled.withCombatState(skilled.hitPoints(), List.of(new ConditionInstance(ConditionType.POISONED,
                java.util.Optional.empty(), ConditionInstance.INDEFINITE)), skilled.deathSaves());
        dice.set(0);
        assertEquals(3, PlayerRules.disarm(impaired, 18, sides -> dice.incrementAndGet() == 1 ? 3 : 14).naturalRoll());
        assertEquals(1, dice.get());
    }

    private static CommandSourceStack source(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission,
                "Tester", Component.literal("Tester"), null, null);
    }
}
