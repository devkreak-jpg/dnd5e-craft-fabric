# DnD 5e Craft

Minecraft **1.20.1**, Java **17**, official Mojang mappings, mod ID **`dnd-5e-craft`**, and base package **`net.dnd5ecraft`**. The project pins Fabric Loader **0.20.1** and Fabric API **0.92.12+1.20.1**.

Use the existing checkout and keep `loom.officialMojangMappings()` and `splitEnvironmentSourceSets()` enabled. Put client API calls and renderers in `src/client/java`. Keep common classes loadable on a dedicated server and keep each Mixin configuration on its intended side. Prefix Mixin members with `dnd5e$`, mark additions `@Unique`, and use injections. Register NPC attributes on the common entry point and the matching renderer on the client entry point. Use Minecraft 1.20.1's three-argument `DimensionDataStorage.computeIfAbsent` and the supplier form of `CommandSourceStack.sendSuccess`.

Run from the repository root with Java 17 and a populated Gradle cache.

```bash
set -o pipefail
./gradlew --offline --no-daemon compileJava compileClientJava --console=plain -q | tail -n 60
java -cp build/classes/java/main net.dnd5ecraft.rules.RulesSelfTestRunner
./gradlew --offline --no-daemon build --console=plain -q | tail -n 60
```

The installable remapped artifact is `build/libs/dnd-5e-craft-1.0.0.jar`. Install the same artifact and Fabric API on each client and the server. Protocol version 2 includes the advanced trigger settings and sheet armor field. Worlds saved using the old `dnd5ecraft` registry namespace require a migration before upgrading. Test with a new world.

## Phase checklist

The phase checks cover the offline build, the dedicated-server GameTests, and the graphical Minecraft client smoke test.

- [x] Phase 0. Namespace, build setup, Mojang mappings, resources, and entry points.
- [x] Phase 1. Dice, abilities, equipment, conditions, character progression, rests, and persistence.
- [x] Phase 2. Spell registry with 47 entries, spell slots, concentration, 3D targeting, turn economy, combat actions, and reactions.
- [x] Phase 3. Canonical bestiary of 15 presets, NPCs, dialogue, traps, cutscenes, seven items, commands, and server Mixins.
- [x] Phase 4. Versioned C2S requests and S2C updates for characters, encounters, reactions, DM party data, notices, and screen signals.
- [x] Phase 5A. Client state, keybinds, interactive character sheet, combat HUD, NPC renderer, and starter English and Russian strings.
- [x] Phase 5B. Character creation, spellbook, dialogue, NPC Studio, trigger-zone editor, and DM console screens.
- [x] Phase 6. Tactical world rendering, spinning die, AoE previews, floating combat bars, camera, darkvision, and NPC rendering.
- [x] Phase 7. English and Russian localizations, Mixin side audit, and remapped release verification.

## Phase 0 files

- `settings.gradle`, `build.gradle`, `gradle.properties`, and the Gradle wrapper define the offline build.
- `src/main/resources/fabric.mod.json` declares `Dnd5eCraftMod` and `Dnd5eCraftClient`. `src/main/resources/dnd-5e-craft.mixins.json` and `src/client/resources/dnd-5e-craft.client.mixins.json` register their respective Mixins.
- `src/main/resources/assets/dnd-5e-craft/` contains the icon, language files, models, and textures. `src/main/resources/data/dnd-5e-craft/recipes/physical_d20.json` gives the physical d20 a survival recipe.

## Common and server files

Source paths below are relative to `src/main/java/net/dnd5ecraft/`.

### Phase 1

- `Dnd5eCraftMod.java` registers the common implementation.
- `rules/RulesSelfTestRunner.java` runs from compiled main classes with a plain JVM.
- `rules/ability/` contains `Ability.java`, `AbilityCheckEngine.java`, `DiceRoller.java`, `PassiveSenseTracker.java`, `ProficiencyLevel.java`, `Skill.java`.
- `rules/character/` contains `BackgroundType.java`, `CharacterProgression.java`, `CharacterResources.java`, `CharacterSheetData.java`, `ClassFeature.java`, `ClassType.java`, `FeatType.java`, `MulticlassValidator.java`, `PlayerSheetStorage.java`, `PointBuyCalculator.java`, `RaceType.java`, `SubclassType.java`.
- `rules/conditions/` contains `ConditionEffectApplier.java`, `ConditionInstance.java`, `ConditionType.java`, `DeathSaveTracker.java`.
- `rules/equipment/` contains `ArmorCategory.java`, `ArmorFormulaEngine.java`, `AttunementManager.java`, `CoinPurse.java`, `DamagePipeline.java`, `DamageType5e.java`, `ExhaustionLevel.java`, `WeaponProperty.java`, `WeaponStatsRegistry.java`.

- `server/PlayerCharacter.java`, `server/PlayerRules.java`, and `server/PlayerSheets.java` expose player state, check and rest entry points, legacy sheet migration, and persisted state.

### Phase 2

- `rules/spells/` contains `AoEGeometry.java`, `AoETargetResolver.java`, `ConcentrationManager.java`, `Spell.java`, `SpellAccess.java`, `SpellRegistry5e.java`, `SpellSchool.java`, `SpellSlotTable.java`.
- `combat/` contains `CombatEncounterState.java`, `CombatManager.java`, `CombatantEntry.java`, `CoverRaycaster.java`, `EncounterDifficultyCalculator.java`, `GridMovementTracker.java`, `LightingVisionEvaluator.java`, `MonsterAiDirector.java`, `OpportunityAttackDetector.java`, `ReactionWindowManager.java`, `TurnEconomy.java`.

- `server/TacticalCombat.java` binds the turn engine to Minecraft. `server/MinecraftBattlefield.java` provides collision, light, cover, and terrain.
- Rule distance uses five feet per block. The encounter keeps primary HP, temporary HP, beast HP, Arcane Ward, and death saves independently. One concentration spell per caster applies scoped effects. Reaction opportunities expire after 3.5 seconds. Paladin Aura of Protection applies the strongest nearby allied aura. A Sorcerer of level 3 or higher can use `/dnd action quicken_spell` to prepare Quickened Spell for the next eligible spell.

### Phase 3

- `entity/` contains `BestiaryPreset.java`, `BestiaryRegistry5e.java`, `BestiaryTurnState.java`, `DiceDisplaySpawner.java`, `DndNpcEntity.java`, `ModEntities.java`, `NpcSpawns.java`, `TriggerOccupancy.java`, `TriggerZoneBlock.java`, `TriggerZoneBlockEntity.java`, `TriggerZoneSpec.java`.
- `dialogue/` contains `DialogueChoice.java`, `DialogueNbt.java`, `DialogueNode.java`, `DialogueTree.java`, `NpcAttitude.java`.
- `item/` contains `CharacterFolioItem.java`, `DmMasterWandItem.java`, `DungeonBrushItem.java`, `HealingDraughtItem.java`, `ModItems.java`, `NpcSpawnerWandItem.java`, `PhysicalD20Item.java`, `TriggerZoneWandItem.java`, `WandSelections.java`.
- `mixin/` contains `EntityScaleMixin.java`, `LivingEntityCombatMixin.java`, `PlayerCharacterMixin.java`, `PlayerMovementLockMixin.java`.

- `server/DndCommands.java`, `server/DialogueSessions.java`, `server/DmMode.java`, and `server/CinematicFreeze.java` handle operator tools, dialogue sessions, possession, temporary party freezes, and door state.
- Bestiary and appearance fields track on the entity. Dialogue trees persist on the server and the network sends the active node or an authorized editor view.
- Travel, damage, trigger occupancy, and encounter timing are server-authoritative. Armed traps have independent Perception, Investigation, disarm, and Dexterity save DCs. Use `/dnd investigate <position>` to investigate or a held tripwire hook with `/dnd disarm <position>` to disarm. Configure cinematic ambushes with the trigger-zone editor.

### Phase 4

- `network/` contains `CharacterDraft.java`, `ModPackets.java`, `PacketCodec.java`, `PacketPolicy.java`, `Protocol.java`.

- Every tactical action supplies the current encounter, round, and controlled actor. The server rechecks identity and turn ownership before it mutates state. Reaction responses use an offered, unexpired window for that actor. World editing, possession, and party snapshots require operator level 2.

## Client files

Source paths below are relative to `src/client/java/net/dnd5ecraft/client/`.

### Phase 5A

- `Dnd5eCraftClient.java`, `ClientActions.java`, `ClientDndState.java`, `ClientPacketHandlers.java`, and `keybind/ModKeybinds.java` initialize client features, handle input, and apply synchronized state.
- `gui/theme/DndUiTheme.java`, `gui/screen/DndScreen.java`, `gui/screen/CharacterSheetScreen.java`, `gui/screen/ReactionPromptScreen.java`, and `gui/hud/CombatHudOverlay.java` define the sheet and combat views. `render/DndNpcEntityRenderer.java` renders tracked NPC appearance.
- Use **C** for the character sheet, **K** for the spellbook, **R** for a d20 or an offered reaction, **Space** to end a tactical turn, **M** to toggle DM mode, and **N** for the DM console.
- `net.dnd5ecraft.client` is the only source package that imports Minecraft or Fabric client APIs or Blaze3D. The language files live in the common resource pack so the server can send translatable messages.

### Phase 5B

- `gui/screen/CharacterCreationScreen.java`, `SpellbookScreen.java`, `DialogueScreen.java`, `NpcStudioScreen.java`, `TriggerZoneEditScreen.java`, and `DmMasterConsoleScreen.java` contain the existing screens.

### Phase 6

- `render/` contains `AreaWireframe.java`, `DndNpcEntityRenderer.java`, `FloatingCombatBarRenderer.java`, `Icosahedron.java`, `Spinning3dDieRenderer.java`, `TacticalGridWorldRenderer.java`, `WorldDrawing.java`.
- `mixin/` contains `CameraRpgMixin.java`, `ClientPlayerMoveClampMixin.java`, `DarkvisionLightmapMixin.java`, `EntityRenderScaleMixin.java`, `KeyboardHandlerMixin.java`, `MinecraftInteractionMixin.java`.

- Exercise the tactical grid and movement path, 3D spell previews, roll animation, NPC scaling, camera, and darkvision in a graphical client after changes to rendering or tactical input.

### Phase 7

- `src/main/resources/assets/dnd-5e-craft/lang/en_us.json` and `ru_ru.json` localize items, spells, classes, skills, conditions, screens, and HUD labels. `src/test/java/net/dnd5ecraft/resources/LocalizationTest.java` verifies key coverage and argument formats.
- Verify both Mixin configurations, packaged resources, and installer metadata on the remapped JAR. Fabric Mixin reports the explicitly annotated injection handlers as redundant. Keep the annotations required by this project.
