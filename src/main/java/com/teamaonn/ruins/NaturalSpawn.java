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

/** Inspects already loaded chunks ahead of players, without requesting new chunks. */
final class NaturalSpawn {
    private static final int[][] RING = makeRing(4);
    private final Map<String, Set<Long>> processed = new HashMap<>();
    private final Map<String, List<BlockPos>> previous = new HashMap<>();
    private int ticks, cursor;
    private int chance = 48;
    private int minimumDistance = 128;
    private boolean configLoaded;

    void reset() {
        processed.clear();
        previous.clear();
        ticks = cursor = 0;
        configLoaded = false;
    }

    void tick(MinecraftServer server) {
        if (++ticks % 20 != 0) return;
        if (!configLoaded) loadConfig();
        List<TmlTemplate> installed = RuinsFabric.templates();
        if (installed.isEmpty()) return;
        int[] offset = RING[cursor++ % RING.length];
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel world = player.level();
            int cx = (player.blockPosition().getX() >> 4) + offset[0];
            int cz = (player.blockPosition().getZ() >> 4) + offset[1];
            if (!world.hasChunk(cx, cz)) continue;
            String dimension = world.dimension().identifier().toString();
            long key = key(cx, cz);
            Set<Long> done = processed.computeIfAbsent(dimension, d -> loadProcessed(server, d));
            if (done.contains(key)) continue;
            int x = (cx << 4) + 8, z = (cz << 4) + 8;
            int y = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos center = new BlockPos(x, y, z);
            List<TmlTemplate> eligible = installed.stream().filter(t -> t.appliesTo(world, center)).toList();
            if (eligible.isEmpty()) continue;
            // Mark before placing so re-entrancy or an invalid site cannot create duplicates.
            done.add(key);
            remember(server, dimension, "C," + cx + "," + cz);
            if (world.getRandom().nextInt(chance) != 0) continue;
            int total = eligible.stream().mapToInt(t -> t.weight).sum();
            int pick = world.getRandom().nextInt(total);
            TmlTemplate selected = eligible.getFirst();
            for (TmlTemplate template : eligible) {
                pick -= template.weight;
                if (pick < 0) { selected = template; break; }
            }
            int baseX = x - selected.columns / 2;
            int baseZ = z - selected.rows / 2;
            BlockPos base = safeSite(world, selected, baseX, baseZ);
            if (base == null) continue;
            List<BlockPos> sites = previous.computeIfAbsent(dimension, d -> new ArrayList<>());
            if (sites.stream().anyMatch(p -> p.distSqr(base) < (long) minimumDistance * minimumDistance)) continue;
            int rotation = selected.preventRotation ? 0 : world.getRandom().nextInt(4);
            int count = selected.place(world, base, rotation);
            if (count > 0) {
                sites.add(base);
                remember(server, dimension, "P," + base.getX() + "," + base.getY() + "," + base.getZ());
                RuinsFabric.LOG.info("Generated {} at {} in {} ({} blocks)", selected.name, base, dimension, count);
            }
        }
    }

    private static BlockPos safeSite(ServerLevel world, TmlTemplate template, int x, int z) {
        int extent = Math.max(template.columns, template.rows);
        // The chosen rotation can extend in either horizontal direction.
        for (int px = x - extent; px <= x + extent; px += 16)
            for (int pz = z - extent; pz <= z + extent; pz += 16)
                if (!world.hasChunk(px >> 4, pz >> 4)) return null;
        if (!world.hasChunk((x + extent) >> 4, (z + extent) >> 4)) return null;
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        int[][] samples = {{x, z}, {x + template.columns, z}, {x, z + template.rows},
                {x + template.columns, z + template.rows}, {x + template.columns / 2, z + template.rows / 2}};
        for (int[] sample : samples) {
            int surface = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sample[0], sample[1]);
            if (surface <= world.getMinY() + 1) return null;
            BlockPos ground = new BlockPos(sample[0], surface - 1, sample[1]);
            if (!world.getBlockState(ground).getFluidState().isEmpty()) return null;
            low = Math.min(low, surface);
            high = Math.max(high, surface);
        }
        if (high - low > Math.max(0, template.maxLeveling)) return null;
        int y = low - template.embed;
        if (y <= world.getMinY() || y + template.height >= world.getMinY() + world.getHeight()) return null;
        return new BlockPos(x, y, z);
    }

    private void loadConfig() {
        configLoaded = true;
        Path path = FabricLoader.getInstance().getConfigDir().resolve("ruins_fabric.properties");
        try {
            if (!Files.exists(path)) Files.writeString(path,
                    "# Natural generation in loaded chunks ahead of players. Restart to apply changes.\n" +
                    "spawnChanceDenominator=48\nminimumDistanceBlocks=128\n");
            var props = new java.util.Properties();
            try (var stream = Files.newInputStream(path)) { props.load(stream); }
            chance = Math.max(1, Integer.parseInt(props.getProperty("spawnChanceDenominator", "48")));
            minimumDistance = Math.max(0, Integer.parseInt(props.getProperty("minimumDistanceBlocks", "128")));
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

    private static int[][] makeRing(int radius) {
        List<int[]> offsets = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            if (Math.max(Math.abs(x), Math.abs(z)) == radius) offsets.add(new int[]{x, z});
        return offsets.toArray(int[][]::new);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
