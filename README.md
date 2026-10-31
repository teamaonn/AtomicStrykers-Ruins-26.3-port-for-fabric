# Ruins template loader for Fabric 26.3 (experimental source)

This is an experimental independent Fabric implementation of the documented Ruins `.tml` text format. It does not contain AtomicStryker's code or template library. Keep the original NeoForge mod out of the Fabric mods folder. Extract your own `.tml` files to `config/ruins_config/generic/` or a biome named subfolder.

The loader also supports an optional personal `ruins_defaults.zip` resource in the built JAR. On first launch it copies `.tml` files from that pack into `config/ruins_config/`, without overwriting any existing files. The public repository and its CI artifact do not bundle the original templates.

## Current behavior

- Reads dimensions, layers, rule selection chances, block states, relative weight, and biome/dimension identifiers from `.tml` files.
- `/ruinsfabric reload` scans the config folder; `/ruinsfabric list` shows template names.
- `/testruin generic/tikihead1` places a template at the command user's position. Commands require admin permission and change blocks directly. Test in a disposable world first.
- Natural generation checks loaded chunks four chunks ahead of players. It filters templates by biome and dimension, selects by weight, checks a sample of the terrain, then places one at a default chance of 1 in 20 eligible chunks. Processed chunks and generated positions are saved under the world folder in `ruins_fabric/`, preventing duplicate generation after a restart. Settings in `config/ruins_fabric.properties` are read at server start. An unchanged previous default config of 1 in 48 is upgraded automatically.
- Placed chests, trapped chests, and barrels receive a random loot-table seed. In the supplied pirate ship, barrels use vanilla shipwreck supply loot and its two chest rules use vanilla shipwreck map and treasure loot. Other containers use the `Ruins.entity.LootTable` or `Ruins.entity.ForgeData.LootTable` specified by their template; otherwise they receive dungeon or matching Nether Fortress loot.

## Known gaps

This early implementation samples terrain but does not level it or apply all original site acceptance rules. It does not support adjoining templates, arbitrary block-entity NBT, spawner contents, structure parsing, or all variant rule modes. The supplied 25 `.tml` layouts were checked for layer dimensions. GitHub Actions checks compilation, while natural placement and container contents still require in-game tests. Use a disposable world for this experimental build.

## Build prerequisites

Java 25, Gradle 9.7.1, Fabric Loom 1.17.20, Fabric Loader 0.19.5, Fabric API 0.160.7+26.3, Minecraft 26.3. Run `gradle build` in this directory once those repositories are reachable. A successful build creates the mod JAR under `build/libs/`.

This work implements ideas and a documented file format independently. See the original author's license before distributing any of their original code, binaries, or template collection.
