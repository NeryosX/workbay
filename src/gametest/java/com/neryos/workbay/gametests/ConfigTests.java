package com.neryos.workbay.gametests;

import com.neryos.workbay.config.WorkbayConfig;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

/**
 * SPEC.md §13. Reading a ModConfigSpec value that was never registered throws, so this is not a
 * test of the defaults so much as proof that the spec is actually loaded on a running server -
 * which is the way config silently fails to exist.
 */
@ForEachTest(groups = "config")
public class ConfigTests {

    @GameTest
    @TestHolder(description = "The server config is registered and readable, with SPEC's defaults.")
    public static void serverConfigIsLoaded(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            helper.assertValueEqual(WorkbayConfig.SERVER.allowAnchors.get(), true, "allowAnchors");
            helper.assertValueEqual(WorkbayConfig.SERVER.maxAnchoredWorkbaysPerPlayer.get(), 4,
                "maxAnchoredWorkbaysPerPlayer");
            helper.assertValueEqual(WorkbayConfig.SERVER.anchorGraceMinutes.get(), 5, "anchorGraceMinutes");
            helper.assertValueEqual(WorkbayConfig.SERVER.maxBaysPerWorkbay.get(), 8, "maxBaysPerWorkbay");
            helper.assertValueEqual(WorkbayConfig.SERVER.maxNetworksPerPlayer.get(), 2,
                "maxNetworksPerPlayer");
            // A knob nothing reads ships as a knob that does nothing, which has happened here
            // twice (OPEN_ISSUES' note about the Resonator and two anchor knobs). Reading it is
            // the cheap half; WorkbaySounds#at is the only place that acts on it.
            helper.assertValueEqual(WorkbayConfig.SERVER.blockSounds.get(), true, "blockSounds");
            helper.succeed();
        });
    }
}
