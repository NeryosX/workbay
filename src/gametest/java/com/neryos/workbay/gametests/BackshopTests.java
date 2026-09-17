package com.neryos.workbay.gametests;

import com.neryos.workbay.world.WorkbayDimensions;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

@ForEachTest(groups = "backshop")
public class BackshopTests {

    /**
     * OPEN_ISSUES #14. GameTestServer builds its world from WorldPresets.FLAT, so whether a
     * mod-declared LEVEL_STEM is merged into that preset was read from a constant pool and never
     * run. Everything else in the mod assumes it, so this is the first test written.
     */
    @GameTest
    @TestHolder(description = "The workbay:backshop dimension exists in the gametest world.")
    public static void dimensionExists(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            ServerLevel backshop = helper.getLevel().getServer().getLevel(WorkbayDimensions.BACKSHOP);
            helper.assertNotNull(backshop, "getLevel(workbay:backshop) returned null");
            helper.assertValueEqual(backshop.dimensionType().hasSkyLight(), false, "backshop has_skylight");
            helper.assertValueEqual(backshop.getMinBuildHeight(), WorkbayDimensions.MIN_Y, "backshop min build height");
            helper.succeed();
        });
    }
}
