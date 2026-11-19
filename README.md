# DnD 5e Craft

DnD 5e Craft brings character sheets, turn-based encounters, spellcasting, NPC dialogue, and DM tools to Minecraft Java Edition 1.20.1.

## Install

Use Java 17, Fabric Loader 0.20.1, and Fabric API 0.92.12+1.20.1. Put the mod JAR and Fabric API in the `mods` folder on the client and server. Get the installable JAR from a successful [GitHub Actions build](https://github.com/devkreak-jpg/dnd5e-craft-fabric/actions/workflows/build.yml) or build it locally. The installable file is `build/libs/dnd-5e-craft-1.0.0.jar`.

Existing worlds using the older `dnd5ecraft` mod ID require a migration before upgrading.

## Play

- Press **C** to create a character or open your character sheet.
- Press **K** for the spellbook and **R** to roll a d20 or take an offered reaction.
- Press **Space** to end your turn during an encounter.
- Operators can press **M** to toggle DM mode and **N** to open the DM console.

## Build

From a configured Java 17 environment with a populated Gradle cache, run `./gradlew --offline --no-daemon build`. See [AGENTS.md](AGENTS.md) for the source layout and verification commands.

## License

Project code uses CC0. The adapted rules from the System Reference Document 5.1 use [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). See [SRD attribution](src/main/resources/SRD-ATTRIBUTION.txt).
