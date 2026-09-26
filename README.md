# Ruins template loader for Fabric 26.3 (experimental source)

This is the first stage of an independent Fabric implementation of the documented Ruins `.tml` text format. It does not contain AtomicStryker's code or template library. Keep the original NeoForge mod out of the Fabric mods folder. Extract your own `.tml` files to `config/ruins_config/generic/` or a biome named subfolder.

## Current behavior

- Reads dimensions, layers, rule selection chances, block states, relative weight, and biome/dimension identifiers from `.tml` files.
- `/ruinsfabric reload` scans the config folder; `/ruinsfabric list` shows template names.
- `/testruin generic/tikihead1` places a template at the command user's position. Commands require admin permission and change blocks directly. Test in a disposable world first.
- No automatic random spawning is enabled yet. Cristel Lib's structure-set controls apply to registered vanilla structures, not these runtime `.tml` files.

## Known gaps

This early implementation does not apply terrain leveling, site acceptance, structure spacing, adjoining templates, block-entity NBT, spawner contents, loot tables, structure parsing, or all variant rule modes. These require their own implementation and world tests. The supplied 25 `.tml` layouts were checked for layer dimensions. The GitHub Actions build checks compilation; this still requires an in-game placement test before release. Do not present this source as a ready-to-install mod JAR.

## Build prerequisites

Java 25, Gradle 9.7.1, Fabric Loom 1.17.20, Fabric Loader 0.19.5, Fabric API 0.160.7+26.3, Minecraft 26.3. Run `gradle build` in this directory once those repositories are reachable. A successful build creates the mod JAR under `build/libs/`.

This work implements ideas and a documented file format independently. See the original author's license before distributing any of their original code, binaries, or template collection.
