package com.teamaonn.ruins;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Independent reader for the documented Ruins text template format. */
public final class TmlTemplate {
    public final String name;
    public final String folder;
    public final int height, rows, columns, weight, embed, maxLeveling;
    public final boolean preventRotation;
    public final int minHeightOffset, maxHeightOffset;
    private final Set<String> acceptableGround, unacceptableGround;
    public final Set<String> dimensions, biomes;
    private final Map<Integer, Rule> rules;
    private final int[][][] layers;

    private record Rule(int chance, List<CompoundTag> alternatives) {}

    private TmlTemplate(String name, String folder, Map<String, String> fields, Map<Integer, Rule> rules, int[][][] layers) {
        this.name = name;
        this.folder = folder;
        String[] size = fields.getOrDefault("dimensions", "0,0,0").split(",");
        if (size.length != 3) throw new IllegalArgumentException("bad dimensions");
        height = Integer.parseInt(size[0].trim());
        rows = Integer.parseInt(size[1].trim());
        columns = Integer.parseInt(size[2].trim());
        if (height < 1 || rows < 1 || columns < 1 || height > 256 || rows > 128 || columns > 128)
            throw new IllegalArgumentException("invalid size " + fields.get("dimensions"));
        if (layers.length != height) throw new IllegalArgumentException("expected " + height + " layers, got " + layers.length);
        for (int[][] layer : layers) for (int[] row : layer) for (int id : row)
            if (id != 0 && !rules.containsKey(id)) throw new IllegalArgumentException("missing rule " + id);
        for (int[][] layer : layers) {
            if (layer.length != rows) throw new IllegalArgumentException("wrong row count");
            for (int[] row : layer) if (row.length != columns) throw new IllegalArgumentException("wrong column count");
        }
        weight = Math.max(1, Integer.parseInt(fields.getOrDefault("weight", "1").trim()));
        String[] offset = fields.getOrDefault("random_height_offset", "0,0").split(",");
        int a = Integer.parseInt(offset[0].trim()), b = Integer.parseInt(offset[offset.length - 1].trim());
        minHeightOffset = Math.min(a, b);
        maxHeightOffset = Math.max(a, b);
        acceptableGround = blockIds(fields.getOrDefault("acceptable_target_blocks", ""));
        unacceptableGround = blockIds(fields.getOrDefault("unacceptable_target_blocks", ""));
        embed = Integer.parseInt(fields.getOrDefault("embed_into_distance", "0").trim());
        maxLeveling = Integer.parseInt(fields.getOrDefault("max_leveling", "2").trim());
        preventRotation = "1".equals(fields.getOrDefault("preventRotation", "0").trim());
        dimensions = commaSet(fields.getOrDefault("dimensionsToSpawnIn", ""));
        biomes = commaSet(fields.getOrDefault("biomesToSpawnIn", ""));
        this.rules = Map.copyOf(rules);
        this.layers = layers;
    }

    private static Set<String> blockIds(String text) {
        Set<String> ids = new HashSet<>();
        for (CompoundTag tag : splitCompounds(text)) {
            String id = tag.getString("Name").orElseGet(() -> tag.getString("id").orElse(""));
            if (!id.isEmpty()) ids.add(id);
        }
        return Set.copyOf(ids);
    }

    public boolean acceptsGround(BlockState state) {
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return (acceptableGround.isEmpty() || acceptableGround.contains(id)) && !unacceptableGround.contains(id);
    }

    private static Set<String> commaSet(String s) {
        Set<String> result = new HashSet<>();
        for (String part : s.split(",")) if (!part.isBlank()) result.add(part.trim().toLowerCase(Locale.ROOT));
        return Set.copyOf(result);
    }

    public static TmlTemplate load(Path file, String folder) throws IOException {
        Map<String, String> fields = new HashMap<>();
        Map<Integer, Rule> rules = new HashMap<>();
        List<int[][]> layers = new ArrayList<>();
        List<int[]> current = null;
        for (String line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.equals("layer")) { if (current != null) throw new IllegalArgumentException("nested layer"); current = new ArrayList<>(); continue; }
            if (line.equals("endlayer")) {
                if (current == null) throw new IllegalArgumentException("unexpected endlayer");
                layers.add(current.toArray(int[][]::new)); current = null; continue;
            }
            if (current != null) {
                String[] cells = line.split(",");
                int[] row = new int[cells.length];
                for (int i = 0; i < cells.length; i++) row[i] = Integer.parseInt(cells[i].trim());
                current.add(row);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (key.matches("rule\\d+")) {
                int id = Integer.parseInt(key.substring(4));
                String[] segments = value.split(",", 3);
                if (segments.length != 3) throw new IllegalArgumentException("bad rule " + id);
                int chance = Math.max(0, Math.min(100, Integer.parseInt(segments[1].trim())));
                List<CompoundTag> variants = splitCompounds(segments[2]);
                if (variants.isEmpty()) throw new IllegalArgumentException("empty rule " + id);
                rules.put(id, new Rule(chance, variants));
            } else fields.put(key, value);
        }
        if (current != null) throw new IllegalArgumentException("unclosed layer");
        return new TmlTemplate(file.getFileName().toString().replaceFirst("(?i)\\.tml$", ""), folder, fields, rules, layers.toArray(int[][][]::new));
    }

    private static List<CompoundTag> splitCompounds(String input) {
        List<CompoundTag> results = new ArrayList<>();
        int depth = 0, start = -1;
        char quote = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quote != 0) { if (c == '\\') i++; else if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}' && --depth == 0) {
                try { results.add(TagParser.parseCompoundFully(input.substring(start, i + 1))); }
                catch (CommandSyntaxException e) { throw new IllegalArgumentException("invalid block state", e); }
            }
        }
        if (depth != 0) throw new IllegalArgumentException("unbalanced rule braces");
        return results;
    }

    public boolean appliesTo(ServerLevel world, BlockPos pos) {
        String dimension = world.dimension().identifier().getPath().toLowerCase(Locale.ROOT);
        if (!dimensions.isEmpty() && !dimensions.contains(dimension)) return false;
        String biome = world.getBiome(pos).unwrapKey().map(k -> k.identifier().getPath()).orElse("").toLowerCase(Locale.ROOT);
        return (folder.equals("generic") && biomes.isEmpty()) || folder.equals(biome) || biomes.contains(biome);
    }

    public int place(ServerLevel world, BlockPos base, int turns) {
        int placed = 0;
        Rotation rotation = switch (Math.floorMod(turns, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        Random random = new Random(world.getRandom().nextLong());
        List<BlockPos> changed = new ArrayList<>();
        for (int y = 0; y < height; y++) for (int z = 0; z < rows; z++) for (int x = 0; x < columns; x++) {
            int ruleId = layers[y][z][x];
            if (ruleId == 0) continue;
            Rule rule = rules.get(ruleId);
            if (rule == null || random.nextInt(100) >= rule.chance()) continue;
            CompoundTag tag = rule.alternatives().get(random.nextInt(rule.alternatives().size()));
            CompoundTag stateTag = normalizeBlockTag(tag);
            BlockState state = NbtUtils.readBlockState(world.holderLookup(Registries.BLOCK), stateTag);
            if (state.is(Blocks.AIR) && !tag.toString().contains("minecraft:air")) continue;
            state = state.rotate(rotation);
            int dx = switch (Math.floorMod(turns, 4)) { case 1 -> -x; case 2 -> -z; case 3 -> x; default -> z; };
            int dz = switch (Math.floorMod(turns, 4)) { case 1 -> z; case 2 -> -x; case 3 -> -z; default -> x; };
            BlockPos target = base.offset(dx, y, dz);
            if (target.getY() > world.getMinY() && target.getY() < world.getMinY() + world.getHeight()) {
                // Ruins rows run along world X; columns run along world Z.
                // Defer neighbor/shape updates until both halves and their supports exist.
                world.setBlock(target, state, 2 | 16);
                changed.add(target);
                initializeSpawner(world, target, tag);
                if (world.getBlockEntity(target) instanceof RandomizableContainerBlockEntity container) {
                    String table = chooseLootTable(tag, state, ruleId);
                    try {
                        ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(table));
                        container.setLootTable(key, world.getRandom().nextLong());
                        container.setChanged();
                    } catch (IllegalArgumentException e) {
                        RuinsFabric.LOG.warn("Invalid loot table {} in {}", table, name);
                    }
                }
                placed++;
            }
        }
        for (BlockPos pos : changed) {
            BlockState state = world.getBlockState(pos);
            BlockState updated = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(state, world, pos);
            if (updated != state) world.setBlock(pos, updated, 2 | 16);
        }
        return placed;
    }

    static CompoundTag normalizeBlockTag(CompoundTag tag) {
        CompoundTag normalized = tag.copy();
        if (!normalized.contains("id")) tag.getString("Name").ifPresent(id -> normalized.putString("id", id));
        normalized.getString("id").ifPresent(id -> normalized.putString("id", switch (id) {
            case "minecraft:grass_path" -> "minecraft:dirt_path";
            case "minecraft:grass" -> "minecraft:short_grass";
            default -> id;
        }));
        if (!normalized.contains("properties")) tag.getCompound("Properties").ifPresent(p -> normalized.put("properties", p.copy()));
        return normalized;
    }

    private static void initializeSpawner(ServerLevel world, BlockPos pos, CompoundTag tag) {
        if (!(world.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity spawner)) return;
        CompoundTag entity = tag.getCompound("Ruins").flatMap(t -> t.getCompound("entity")).orElse(null);
        if (entity == null) return;
        CompoundTag data = entity.copy();
        if (!data.contains("SpawnData")) {
            data.getList("SpawnPotentials").ifPresent(potentials -> {
                if (!potentials.isEmpty() && potentials.get(0) instanceof CompoundTag first)
                    first.getCompound("data").ifPresent(spawnData -> data.put("SpawnData", spawnData.copy()));
            });
        }
        spawner.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, world.registryAccess(), data));
        spawner.setChanged();
        world.sendBlockUpdated(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
    }

    private static String lootTable(CompoundTag block) {
        CompoundTag entity = block.getCompound("Ruins").flatMap(ruins -> ruins.getCompound("entity")).orElse(null);
        if (entity == null) return null;
        String direct = entity.getString("LootTable").orElse(null);
        if (direct != null && !direct.isBlank()) return direct;
        return entity.getCompound("ForgeData").flatMap(data -> data.getString("LootTable")).orElse(null);
    }

    private String chooseLootTable(CompoundTag block, BlockState state, int ruleId) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("pirate") || lower.contains("ship")) {
            if (state.is(Blocks.BARREL)) return "minecraft:chests/shipwreck_supply";
            // The two chest rules in the supplied pirate ship become map and treasure chests.
            return ruleId == 5 ? "minecraft:chests/shipwreck_map" : "minecraft:chests/shipwreck_treasure";
        }
        String specified = lootTable(block);
        return specified != null && !specified.isBlank() ? specified : defaultLootTable();
    }

    private String defaultLootTable() {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("nether")) return "minecraft:chests/nether_bridge";
        if (lower.contains("pirate") || lower.contains("ship")) return "minecraft:chests/shipwreck_supply";
        return "minecraft:chests/simple_dungeon";
    }
}
