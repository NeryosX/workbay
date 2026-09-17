package com.neryos.workbay.remote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The one switch a server host has over this mod's mixins, read from a plain file.
 *
 * <p>It cannot be a {@code ModConfigSpec}: configs load long after class transformation, so by the
 * time one could be read the injections have already been applied. Read here instead, by
 * {@link com.neryos.workbay.mixin.RemoteScreenGate} before Mixin asks whether to apply anything, so
 * <b>off means the vanilla classes are never patched at all</b> rather than patched with a handler
 * that returns early. That is the difference between a host being able to say "this mod does not
 * touch {@code Level}" and having to take our word for it.
 *
 * <p>Read twice, once from the mixin plugin and once from mod code, because the two may not share a
 * class loader and a shared static would then be quietly wrong in one of them. Reading a four-line
 * file twice at startup costs nothing worth protecting.
 */
public final class RemoteConfig {
    private static final String FILE = "workbay-mixins.properties";
    private static final String KEY = "remoteScreens";

    private static Boolean enabled;

    private RemoteConfig() {}

    public static synchronized boolean remoteScreensEnabled() {
        if (enabled == null) {
            enabled = read();
        }
        return enabled;
    }

    private static boolean read() {
        Path path = configDir().resolve(FILE);
        try {
            if (Files.exists(path)) {
                Properties props = new Properties();
                try (var in = Files.newBufferedReader(path)) {
                    props.load(in);
                }
                return !"false".equalsIgnoreCase(props.getProperty(KEY, "true").trim());
            }
            write(path);
        } catch (IOException | RuntimeException e) {
            // A host who cannot read the file gets the feature, which is the shipped default; a
            // startup crash over a config file nobody edited would be the worse failure.
            return true;
        }
        return true;
    }

    /** Written on first launch, or the knob is one nobody discovers. */
    private static void write(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, """
            # Workbay: the only file in this mod read before Minecraft's classes are loaded.
            #
            # remoteScreens=true lets a Workbay open a hosted machine's own screen wherever the
            # player is. It needs five Mixin injections, every one of them narrow, every one of
            # them consulted only while such a screen is open, and every one of them answering
            # about a block rather than about the player:
            #   Player#canInteractWithBlock   - so the open screen is not closed for standing away
            #   ServerChunkCache#hasChunk     - a mod asks "is it loaded" before it asks for a tile
            #   Level#getBlockEntity          - so a mod's own button finds the machine
            #   ClientChunkCache#getChunk     - client only: the machine's chunk was never sent
            #   LevelChunk#getBlockEntity     - client only: and when that chunk coordinate really
            #                                   is loaded in the player's own dimension, the answer
            #                                   has to be given for one position instead
            #
            # Set it to false and none of those classes is patched: the mixins are not applied, and
            # the button is not offered. Everything else in the mod works exactly the same.
            remoteScreens=true
            """);
    }

    private static Path configDir() {
        try {
            return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
        } catch (Throwable t) {
            // Called from a mixin plugin, early enough that FMLPaths may not be initialised.
            return Path.of("config");
        }
    }
}
