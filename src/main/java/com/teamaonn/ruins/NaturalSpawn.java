package com.teamaonn.ruins;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generation candidates originate exclusively from newly generated chunks. */
final class NaturalSpawn {
    private final Map<String, Set<Long>> processed = new HashMap<>();
    private final Map<String, List<BlockPos>> previous = new HashMap<>();
    private final Map<String, Map<Long, Integer>> fresh = new HashMap<>();
    private final List<Candidate> pending = new ArrayList<>();
    private record Candidate(ServerLevel world, int cx, int cz, int expires) {}
    private int ticks;
    private int chance = 40;
    private int minimumDistance = 256;
    private boolean configLoaded;

    void reset() {
        processed.clear(); previous.clear(); fresh.clear(); pending.clear();
        ticks = 0; configLoaded = false;
    }

    void onChunkLoad(ServerLevel world, net.minecraft.world.level.chunk.LevelChunk chunk, boolean generated) {
        if (!generated) return;
        MinecraftServer server = world.getServer();
        if (!configLoaded) loadConfig();
        String dimension = world.dimension().identifier().toString();
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        long key = key(cx, cz);
        Set<Long> done = processed.computeIfAbsent(dimension, d -> loadProcessed(server, d));
        if (!done.add(key)) return;
        remember(server, dimension, "C," + cx + "," + cz);
        fresh.computeIfAbsent(dimension, d -> new HashMap<>()).put(key, ticks + 200);
        if (world.getRandom().nextInt(chance) == 0) pending.add(new Candidate(world, cx, cz, ticks + 100));
    }

    void tick(MinecraftServer server) {
        ticks++;
        for (var chunks : fresh.values()) chunks.values().removeIf(expiry -> expiry < ticks);
        // Callbacks caused during placement must not mutate the iterator.
        List<Candidate> work = new ArrayList<>(pending);
        pending.clear();
        for (Candidate candidate : work) {
            if (candidate.expires < ticks) continue;
            ServerLevel world = candidate.world;
            int x = (candidate.cx << 4) + 8, z = (candidate.cz << 4) + 8;
            if (!world.hasChunk(candidate.cx, candidate.cz)) continue;
            BlockPos center = new BlockPos(x, world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            List<TmlTemplate> eligible = RuinsFabric.templates().stream().filter(t -> t.appliesTo(world, center)).toList();
            if (eligible.isEmpty()) continue;
            // Wait briefly for adjacent fresh terrain; never revisit old terrain later.
            if (candidate.expires - ticks > 60) { pending.add(candidate); continue; }
            int total = eligible.stream().mapToInt(t -> t.weight).sum();
            int pick = world.getRandom().nextInt(total);
            TmlTemplate selected = eligible.getFirst();
            for (TmlTemplate template : eligible) {
                pick -= template.weight;
                if (pick < 0) { selected = template; break; }
            }
            int rotation = selected.preventRotation ? 0 : world.getRandom().nextInt(4);
            int width = rotation % 2 == 0 ? selected.rows : selected.columns;
            int depth = rotation % 2 == 0 ? selected.columns : selected.rows;
            // Place the rotated template around the candidate chunk's center.
            int baseX = x + (rotation == 1 || rotation == 2 ? width / 2 : -width / 2);
            int baseZ = z + (rotation == 2 || rotation == 3 ? depth / 2 : -depth / 2);
            int minX = baseX - (rotation == 1 || rotation == 2 ? width - 1 : 0);
            int minZ = baseZ - (rotation == 2 || rotation == 3 ? depth - 1 : 0);
            String dimension = world.dimension().identifier().toString();
            Map<Long, Integer> chunks = fresh.getOrDefault(dimension, Map.of());
            boolean safe = true;
            for (int cx = minX >> 4; cx <= (minX + width - 1) >> 4; cx++)
                for (int cz = minZ >> 4; cz <= (minZ + depth - 1) >> 4; cz++)
                    if (!chunks.containsKey(key(cx, cz)) || !world.hasChunk(cx, cz)) safe = false;
            if (!safe) continue;
            // Avoid visible pop-in and terrain where a player is already working.
            if (world.players().stream().anyMatch(p -> Math.abs(p.getX() - x) < width / 2.0 + 64
                    && Math.abs(p.getZ() - z) < depth / 2.0 + 64)) continue;
            BlockPos base = safeSite(world, selected, baseX, baseZ, minX, minZ, width, depth);
            if (base == null) continue;
            List<BlockPos> sites = previous.computeIfAbsent(dimension, d -> new ArrayList<>());
            if (sites.stream().anyMatch(p -> horizontalDistanceSquared(p, base) < (long) minimumDistance * minimumDistance)) continue;
            int count = selected.place(world, base, rotation);
            if (count > 0) {
                sites.add(base);
                remember(server, dimension, "P," + base.getX() + "," + base.getY() + "," + base.getZ());
                RuinsFabric.LOG.info("Generated {} at {} in {} ({} blocks)", selected.name, base, dimension, count);
            }
        }
    }

    private static long horizontalDistanceSquared(BlockPos a, BlockPos b) {
        long dx = (long)a.getX() - b.getX(), dz = (long)a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static BlockPos safeSite(ServerLevel world, TmlTemplate template, int baseX, int baseZ,
                                     int minX, int minZ, int width, int depth) {
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int x = minX; x < minX + width; x++) for (int z = minZ; z < minZ + depth; z++) {
            int surface = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (surface <= world.getMinY() + 1 || !template.acceptsGround(world.getBlockState(new BlockPos(x, surface - 1, z)))) return null;
            low = Math.min(low, surface); high = Math.max(high, surface);
        }
        if (high - low > Math.max(0, template.maxLeveling)) return null;
        int offset = template.minHeightOffset + world.getRandom().nextInt(template.maxHeightOffset - template.minHeightOffset + 1);
        int y = low - template.embed + offset;
        if (y <= world.getMinY() || y + template.height >= world.getMinY() + world.getHeight()) return null;
        return new BlockPos(baseX, y, baseZ);
    }

    private void loadConfig() {
        configLoaded = true;
        Path path = FabricLoader.getInstance().getConfigDir().resolve("ruins_fabric.properties");
        try {
            String header = "# Natural generation in newly generated chunks only. Restart to apply changes.\n";
            String legacyHeader = "# Natural generation in loaded chunks ahead of players. Restart to apply changes.\n";
            String oldDefault = legacyHeader + "spawnChanceDenominator=48\nminimumDistanceBlocks=128\n";
            String newDefault = header + "spawnChanceDenominator=40\nminimumDistanceBlocks=256\n";
            if (!Files.exists(path) || Files.readString(path).equals(oldDefault) || Files.readString(path).equals(legacyHeader + "spawnChanceDenominator=20\nminimumDistanceBlocks=128\n")) Files.writeString(path, newDefault);
            var props = new java.util.Properties();
            try (var stream = Files.newInputStream(path)) { props.load(stream); }
            chance = Math.max(1, Integer.parseInt(props.getProperty("spawnChanceDenominator", "40")));
            minimumDistance = Math.max(0, Integer.parseInt(props.getProperty("minimumDistanceBlocks", "256")));
        } catch (Exception e) { RuinsFabric.LOG.warn("Could not read natural generation settings", e); }
    }

    private static Path history(MinecraftServer server, String dimension) {
        String safeName = dimension.replaceAll("[^a-zA-Z0-9_-]", "_");
        return server.getWorldPath(LevelResource.ROOT).resolve("ruins_fabric").resolve(safeName + ".txt");
    }

    private Set<Long> loadProcessed(MinecraftServer server, String dimension) {
        Set<Long> done = new HashSet<>();
        List<BlockPos> sites = previous.computeIfAbsent(dimension, d -> new ArrayList<>());
        Path file = history(server, dimension);
        if (!Files.exists(file)) return done;
        try {
            for (String line : Files.readAllLines(file)) {
                String[] parts = line.split(",");
                if (parts.length == 3 && parts[0].equals("C")) done.add(key(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                if (parts.length == 4 && parts[0].equals("P")) sites.add(new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
            }
        } catch (Exception e) { RuinsFabric.LOG.warn("Could not load generation history from {}", file, e); }
        return done;
    }

    private static void remember(MinecraftServer server, String dimension, String line) {
        Path file = history(server, dimension);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { RuinsFabric.LOG.error("Could not save generation history to {}", file, e); }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
