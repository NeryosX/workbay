package com.neryos.workbay.gametests;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.testframework.conf.Feature;
import net.neoforged.testframework.conf.FrameworkConfiguration;
import net.neoforged.testframework.impl.MutableTestFramework;

@Mod(WorkbayTests.MOD_ID)
public class WorkbayTests {
    public static final String MOD_ID = "workbay_tests";

    public WorkbayTests(IEventBus eventBus, ModContainer container) {
        final MutableTestFramework framework = FrameworkConfiguration
            .builder(ResourceLocation.fromNamespaceAndPath(MOD_ID, "tests"))
            .enable(Feature.CLIENT_SYNC, Feature.TEST_STORE)
            .build()
            .create();

        framework.init(eventBus, container);

        // -Dworkbay.tests=<regex on the test's method name> runs only those. GameTestServer is
        // handed its own copy of the registry's list before any mod event fires, and batches it
        // in initServer right after ServerAboutToStartEvent - so the rest are taken out of that
        // copy, through the one private field holding it. A day round runs the tests it wrote or
        // touched; the whole suite is one unfiltered run away.
        String only = System.getProperty("workbay.tests");
        if (only != null && !only.isBlank()) {
            java.util.regex.Pattern keep = java.util.regex.Pattern.compile(only);
            NeoForge.EVENT_BUS.addListener(
                (final net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) -> {
                    if (!(event.getServer() instanceof net.minecraft.gametest.framework.GameTestServer server)) {
                        return;
                    }
                    for (java.lang.reflect.Field field : server.getClass().getDeclaredFields()) {
                        if (java.util.Collection.class.isAssignableFrom(field.getType())) {
                            try {
                                field.setAccessible(true);
                                if (field.get(server) instanceof java.util.Collection<?> held
                                    && held.stream().allMatch(net.minecraft.gametest.framework.TestFunction.class::isInstance)) {
                                    held.removeIf(f -> !keep.matcher(
                                        ((net.minecraft.gametest.framework.TestFunction) f).testName()).find());
                                }
                            } catch (ReflectiveOperationException | UnsupportedOperationException e) {
                                throw new IllegalStateException("workbay.tests could not prune " + field, e);
                            }
                        }
                    }
                });
        }

        NeoForge.EVENT_BUS.addListener((final RegisterCommandsEvent event) -> {
            final LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("tests");
            framework.registerCommands(node);
            event.getDispatcher().register(node);
        });
    }
}
