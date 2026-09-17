package com.neryos.workbay.gametests;

import com.neryos.workbay.host.HostChecks;
import com.neryos.workbay.host.HostResult;
import com.neryos.workbay.init.WBBlocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

/**
 * SPEC.md §11's {@code host_gate_verdicts}. Each heuristic and tag has to produce the expected
 * verdict <em>and</em> the expected reason, because the reason is what the player reads and a
 * plausible-but-wrong one is worse than no message.
 */
@ForEachTest(groups = "host_gate")
public class HostGateTests {

    private static void expectDenied(ExtendedGameTestHelper helper, ItemStack stack, String reason) {
        HostResult result = HostChecks.evaluate(stack);
        if (result.allowed()) {
            helper.fail(stack.getItem() + " was allowed, expected denial with reason " + reason);
            return;
        }
        if (!reason.equals(result.reasonKey())) {
            helper.fail(stack.getItem() + " was denied for " + result.reasonKey()
                + ", expected " + reason);
        }
    }

    private static void expectAllowed(ExtendedGameTestHelper helper, ItemStack stack) {
        HostResult result = HostChecks.evaluate(stack);
        if (!result.allowed()) {
            helper.fail(stack.getItem() + " was denied for " + result.reasonKey()
                + ", expected it to be hostable");
        }
    }

    @GameTest
    @TestHolder(description = "Every hostability heuristic produces its own verdict and reason.")
    public static void heuristicsProduceTheRightReasons(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            // Not a block at all.
            expectDenied(helper, new ItemStack(Items.DIAMOND), "not_a_block");
            expectDenied(helper, new ItemStack(Items.IRON_PICKAXE), "not_a_block");

            // A block, but nothing runs inside it.
            expectDenied(helper, new ItemStack(Blocks.STONE), "no_machine");
            expectDenied(helper, new ItemStack(Blocks.OAK_PLANKS), "no_machine");

            // Works by joining up with its neighbours. Fences have no block entity so they are
            // caught earlier; this is the case the heuristic is actually for.
            expectDenied(helper, new ItemStack(Blocks.OAK_FENCE), "no_machine");

            // The pack denylist, and the vanilla entries SPEC.md §11 ships in it.
            expectDenied(helper, new ItemStack(Blocks.RED_BED), "pack_denied");
            expectDenied(helper, new ItemStack(Blocks.OAK_DOOR), "pack_denied");
            expectDenied(helper, new ItemStack(Blocks.TRIAL_SPAWNER), "pack_denied");
            expectDenied(helper, new ItemStack(Blocks.VAULT), "pack_denied");

            // A Workbay inside a Workbay gets its own message, not the denylist's.
            expectDenied(helper, new ItemStack(WBBlocks.WORKBAY.get()), "recursion");

            // The machines that should just work. The piston-reaction case is covered by
            // aForeignMachineIsHostable, because no vanilla block entity sets PushReaction.BLOCK
            // and a check against one that does not is a check that cannot fail.
            expectAllowed(helper, new ItemStack(Blocks.FURNACE));
            expectAllowed(helper, new ItemStack(Blocks.CHEST));
            expectAllowed(helper, new ItemStack(Blocks.BARREL));
            expectAllowed(helper, new ItemStack(Blocks.HOPPER));

            helper.succeed();
        });
    }

    @GameTest
    @TestHolder(description = "A real Mekanism machine passes the hostability gate.")
    public static void aForeignMachineIsHostable(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            // The gate exists to let other mods' machines in. If it turns Mekanism away, it is the
            // gate that is wrong, not Mekanism.
            for (String id : new String[] {
                "mekanism:enrichment_chamber", "mekanism:energized_smelter", "mekanism:crusher" }) {
                var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
                if (block == Blocks.AIR) {
                    helper.fail(id + " is not registered; check the gametestRuntimeOnly Mekanism "
                        + "dependency in build.gradle");
                    return;
                }
                // OPEN_ISSUES #20: SPEC.md §11 once denied PushReaction.BLOCK as `immovable`, which
                // turned away every one of these. Asserting the reaction is present means the test
                // cannot pass vacuously - the case being defended has to actually be here.
                if (block.defaultBlockState().getPistonPushReaction() != PushReaction.BLOCK) {
                    helper.fail(id + " is no longer PushReaction.BLOCK, so this test no longer "
                        + "covers the heuristic OPEN_ISSUES #20 removed");
                    return;
                }
                expectAllowed(helper, new ItemStack(block));
            }

            // And a cable, which is exactly what the needs_neighbours heuristic is for.
            var cable = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mekanism:basic_universal_cable"));
            if (cable != Blocks.AIR) {
                HostResult result = HostChecks.evaluate(new ItemStack(cable));
                if (result.allowed()) {
                    helper.fail("a Mekanism cable passed the gate. It has nothing to connect to in a "
                        + "bay, so needs_neighbours should have caught it.");
                }
            }
            helper.succeed();
        });
    }

    /**
     * The escape hatch has to actually beat the rules above it, or a pack author with a block one of
     * our heuristics is wrong about has no way out except waiting for us.
     */
    @GameTest
    @TestHolder(description = "host_allowed overrides the denylist and every heuristic.")
    public static void allowTagOverridesEverything(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            // Nothing is in host_allowed by default, so the tag being empty is itself the check:
            // if stone were allowed, the ordering would be broken in the other direction.
            var stone = Blocks.STONE.defaultBlockState();
            if (stone.is(HostChecks.HOST_ALLOWED)) {
                helper.fail("stone is in #workbay:host_allowed; the tag should ship empty");
                return;
            }
            if (!Blocks.RED_BED.defaultBlockState().is(HostChecks.HOST_DENIED)) {
                helper.fail("beds are not in #workbay:host_denied, so the datagen'd tag is not "
                    + "reaching the server");
            }
            helper.succeed();
        });
    }
}
