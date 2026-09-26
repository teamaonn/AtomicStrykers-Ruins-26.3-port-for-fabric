package com.teamaonn.ruins;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.io.InputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class RuinsFabric implements ModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("ruins_fabric");
    private static final Map<String, TmlTemplate> TEMPLATES = new HashMap<>();
    private static Path directory;
    private final NaturalSpawn spawner = new NaturalSpawn();

    @Override public void onInitialize() {
        directory = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("ruins_config");
        installBundledTemplates();
        reload();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> spawner.reset());
        ServerTickEvents.END_SERVER_TICK.register(spawner::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("testruin")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                .then(Commands.argument("template", StringArgumentType.greedyString()).executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "template").toLowerCase(Locale.ROOT);
                    TmlTemplate template = TEMPLATES.get(name);
                    if (template == null) {
                        ctx.getSource().sendFailure(Component.literal("Unknown template: " + name + ". Use /ruinsfabric list."));
                        return 0;
                    }
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    int placed = template.place(player.level(), player.blockPosition(), template.preventRotation ? 0 : player.level().getRandom().nextInt(4));
                    ctx.getSource().sendSuccess(() -> Component.literal("Placed " + template.name + " (" + placed + " blocks)"), false);
                    return 1;
                })));
            dispatcher.register(Commands.literal("ruinsfabric")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                .then(Commands.literal("reload").executes(ctx -> {
                    reload();
                    ctx.getSource().sendSuccess(() -> Component.literal("Loaded " + TEMPLATES.size() + " templates from " + directory), false);
                    return 1;
                }))
                .then(Commands.literal("list").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("Ruins templates (" + TEMPLATES.size() + "): " + String.join(", ", TEMPLATES.keySet())), false);
                    return 1;
                })));
        });
    }

    /** An optional personal template pack can be bundled without adding it to the public source. */
    private static void installBundledTemplates() {
        try (InputStream resource = RuinsFabric.class.getResourceAsStream("/ruins_defaults.zip")) {
            if (resource == null) return;
            int installed = 0;
            Path root = directory.toAbsolutePath().normalize();
            Files.createDirectories(root);
            try (ZipInputStream zip = new ZipInputStream(resource)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".tml")) continue;
                    Path target = root.resolve(entry.getName()).normalize();
                    if (!target.startsWith(root)) throw new IOException("Unsafe template path: " + entry.getName());
                    Files.createDirectories(target.getParent());
                    if (Files.notExists(target)) {
                        // Never replace a template the player edited.
                        Files.copy(zip, target);
                        installed++;
                    }
                    zip.closeEntry();
                }
            }
            LOG.info("Installed {} bundled Ruins templates into {}", installed, root);
        } catch (IOException e) { LOG.error("Could not install bundled Ruins templates", e); }
    }

    static List<TmlTemplate> templates() {
        return new ArrayList<>(TEMPLATES.values());
    }

    private static void reload() {
        TEMPLATES.clear();
        try {
            Files.createDirectories(directory.resolve("generic"));
            try (var stream = Files.walk(directory, 3)) {
                stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tml"))
                    .forEach(path -> {
                        try {
                            String folder = directory.relativize(path.getParent()).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                            TmlTemplate template = TmlTemplate.load(path, folder);
                            TEMPLATES.put(folder + "/" + template.name.toLowerCase(Locale.ROOT), template);
                        } catch (Exception e) { LOG.warn("Could not load template {}: {}", path, e.toString()); }
                    });
            }
            LOG.info("Loaded {} Ruins templates", TEMPLATES.size());
        } catch (Exception e) { LOG.error("Could not open {}", directory, e); }
    }
}
