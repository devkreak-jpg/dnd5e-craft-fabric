package net.dnd5ecraft.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.dnd5ecraft.combat.ReactionWindowManager;
import net.dnd5ecraft.combat.TurnEconomy;
import net.dnd5ecraft.dialogue.DialogueTree;
import net.dnd5ecraft.dialogue.NpcAttitude;
import net.dnd5ecraft.entity.BestiaryPreset;
import net.dnd5ecraft.entity.BestiaryRegistry5e;
import net.dnd5ecraft.entity.TriggerZoneSpec;
import net.dnd5ecraft.network.Protocol;
import net.dnd5ecraft.rules.ability.Ability;
import net.dnd5ecraft.rules.ability.ProficiencyLevel;
import net.dnd5ecraft.rules.ability.Skill;
import net.dnd5ecraft.rules.character.BackgroundType;
import net.dnd5ecraft.rules.character.ClassType;
import net.dnd5ecraft.rules.character.FeatType;
import net.dnd5ecraft.rules.character.RaceType;
import net.dnd5ecraft.rules.character.SubclassType;
import net.dnd5ecraft.rules.conditions.ConditionType;
import net.dnd5ecraft.rules.conditions.DeathSaveTracker;
import net.dnd5ecraft.rules.equipment.DamageType5e;
import net.dnd5ecraft.rules.spells.AoEGeometry;
import net.dnd5ecraft.rules.spells.Spell;
import net.dnd5ecraft.rules.spells.SpellRegistry5e;
import net.dnd5ecraft.rules.spells.SpellSchool;
import net.dnd5ecraft.rules.spells.SpellSlotTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class LocalizationTest {
    private static final String UI = "dnd-5e-craft.ui.";
    private static final Pattern FORMAT = Pattern.compile("%(?:\\d+\\$)?s|%%");

    @Test
    void languagesHaveTheSameKeysAndSubstitutions() throws Exception {
        var english = translations("en_us");
        var russian = translations("ru_ru");
        assertEquals(english.keySet(), russian.keySet());
        for (String key : english.keySet()) {
            String en = english.get(key).getAsString();
            String ru = russian.get(key).getAsString();
            assertFalse(en.isBlank(), key + " in en_us");
            assertFalse(ru.isBlank(), key + " in ru_ru");
            assertEquals(placeholders(en), placeholders(ru), key);
        }
    }

    @Test
    void localizesEntriesWhoseKeysAreBuiltAtRuntime() throws Exception {
        Set<String> keys = translations("en_us").keySet();
        require(keys, "ability", Ability.values());
        require(keys, "skill", Skill.values());
        require(keys, "proficiency", ProficiencyLevel.values());
        require(keys, "race", RaceType.values());
        require(keys, "background", BackgroundType.values());
        require(keys, "class", ClassType.values());
        require(keys, "subclass", SubclassType.values());
        require(keys, "feat", FeatType.values());
        require(keys, "condition", ConditionType.values());
        require(keys, "life", DeathSaveTracker.State.values());
        require(keys, "damage", DamageType5e.values());
        require(keys, "school", SpellSchool.values());
        require(keys, "time", Spell.CastingTime.values());
        require(keys, "pool", SpellSlotTable.Pool.values());
        require(keys, "area", AoEGeometry.values());
        require(keys, "resource", TurnEconomy.Resource.values());
        require(keys, "reaction", ReactionWindowManager.Kind.values());
        require(keys, "attitude", NpcAttitude.values());
        require(keys, "zone.mode", TriggerZoneSpec.Mode.values());
        require(keys, "method", Protocol.AbilityMethod.values());
        require(keys, "campaign.action", net.dnd5ecraft.server.CampaignActions.Action.values());
        require(keys, "director.tool", net.dnd5ecraft.server.DmDirector.Tool.values());
        require(keys, "cannon", net.dnd5ecraft.entity.EldritchCannonEntity.Mode.values());
        for (var weapon : net.dnd5ecraft.rules.equipment.WeaponStatsRegistry.Weapon.values()) {
            assertTrue(keys.contains("item.dnd-5e-craft." + weapon.name().toLowerCase(Locale.ROOT)), weapon.name());
        }
        for (var property : net.dnd5ecraft.rules.equipment.WeaponProperty.values()) {
            assertTrue(keys.contains("dnd-5e-craft.weapon.property." + property.name().toLowerCase(Locale.ROOT)), property.name());
        }
        for (var theme : net.dnd5ecraft.entity.CrowdTheme.values()) {
            assertTrue(keys.contains("dnd-5e-craft.crowd." + theme.id()), theme.name());
        }

        SpellRegistry5e.all().keySet().forEach(id -> assertTrue(keys.contains(UI + "spell." + id), id));
        for (BestiaryPreset preset : BestiaryPreset.values()) {
            assertTrue(keys.contains("entity.dnd-5e-craft.npc." + preset.id()), preset.id());
        }
        for (String id : List.of("dm_master_wand", "npc_spawner_wand", "dungeon_brush", "trigger_zone_wand",
                "character_folio", "physical_d20", "healing_draught")) {
            assertTrue(keys.contains("item.dnd-5e-craft." + id), id);
        }
        assertTrue(keys.contains("itemGroup.dnd-5e-craft.tools"));
    }

    @Test
    void localizesObserverBadgeAndEveryBuiltInDialogueBranch() throws Exception {
        Set<String> keys = translations("en_us").keySet();
        assertTrue(keys.contains(UI + "hud.dm_observing"));
        var trees = new java.util.ArrayList<DialogueTree>();
        trees.add(DialogueTree.defaultTree());
        BestiaryRegistry5e.civilians().forEach(preset -> trees.add(BestiaryRegistry5e.starterDialogue(preset)));
        for (var tree : trees) {
            for (var node : tree.nodes()) {
                assertTrue(keys.contains(node.text()), node.text());
                node.choices().forEach(choice -> assertTrue(keys.contains(choice.label()), choice.label()));
            }
        }
    }

    private static List<String> placeholders(String value) {
        return FORMAT.matcher(value).results().map(match -> match.group()).sorted().toList();
    }

    private static void require(Set<String> keys, String family, Enum<?>[] entries) {
        for (Enum<?> entry : entries) {
            String key = UI + family + "." + entry.name().toLowerCase(Locale.ROOT);
            assertTrue(keys.contains(key), key);
        }
    }

    private static JsonObject translations(String locale) throws Exception {
        var resource = LocalizationTest.class.getResourceAsStream("/assets/dnd-5e-craft/lang/" + locale + ".json");
        assertNotNull(resource, locale);
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
