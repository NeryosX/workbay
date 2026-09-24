package com.neryos.workbay.gametests;

import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.ExtendedGameTestHelper;
import net.neoforged.testframework.gametest.StructureTemplateBuilder;

/**
 * FLOW's layout, which is plain arithmetic with no Minecraft in it, reached by reflection because it
 * is package-private in the client screens. The map is drawn on a client; this proves the routes.
 */
@ForEachTest(groups = "flow")
public class FlowLayoutTests {

    @GameTest
    @TestHolder(description = "Two furnaces feeding one ingot chest enter it through one arrowhead;"
        + " a second resource keeps its own.")
    public static void twoFurnacesIntoOneChestDrawOneArrowhead(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            // Boxes 0 and 1 are the furnaces, 2 the chest; 3 feeds both furnaces, so the furnaces
            // sit one above the other with the chest's middle between them, as on the gallery map.
            List<int[]> wires = List.of(new int[] {3, 0}, new int[] {3, 1},
                new int[] {0, 2}, new int[] {1, 2});
            int[][] joined = heads(4, wires, List.of(0, 0, 0, 0));
            helper.assertTrue(joined[2][0] == joined[3][0] && joined[2][1] == joined[3][1],
                "one kind into one box: the two heads should be one point, were "
                    + joined[2][0] + "," + joined[2][1] + " and " + joined[3][0] + "," + joined[3][1]);
            // The positive control: a second kind into the same box keeps a port of its own.
            int[][] apart = heads(4, wires, List.of(0, 0, 0, 2));
            helper.assertTrue(apart[2][1] != apart[3][1],
                "two kinds into one box should keep two ports, both were at y " + apart[2][1]);
            helper.succeed();
        });
    }

    @GameTest
    @TestHolder(description = "A chest feeding a single machine stands level with it, even when"
        + " the machine is pushed down by the boxes above it.")
    public static void aChestFeedingOneMachineStandsLevelWithIt(final DynamicTest test) {
        test.registerGameTestTemplate(() -> StructureTemplateBuilder.withSize(1, 1, 1));

        test.onGameTest(ExtendedGameTestHelper.class, helper -> {
            // The gallery network, boxes in FlowPage's order: the three bays, then the chests as
            // their links name them. Furnace 0, Blast Furnace 1, Smoker 2, Ore chest 3, Ingots 4,
            // Food chest 5, Pantry 6. The Smoker is pushed below the Blast Furnace; the Food chest
            // chased it and stopped halfway, so its arrow had a step in it.
            List<int[]> wires = List.of(new int[] {3, 0}, new int[] {3, 1}, new int[] {0, 4},
                new int[] {1, 4}, new int[] {5, 2}, new int[] {2, 6});
            int[] y = rows(7, wires, List.of(0, 0, 0, 0, 0, 0));
            helper.assertTrue(y[5] == y[2] && y[2] == y[6], "Food chest, Smoker and Pantry should"
                + " stand on one row, were at " + y[5] + ", " + y[2] + " and " + y[6]);
            helper.succeed();
        });
    }

    /** Each box's top. */
    private static int[] rows(int count, List<int[]> wires, List<Integer> kinds) {
        try {
            Object layout = layout(count, wires, kinds);
            var field = layout.getClass().getDeclaredField("nodeY");
            field.setAccessible(true);
            return (int[]) field.get(layout);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("FlowLayout's shape changed; update this test", e);
        }
    }

    private static Object layout(int count, List<int[]> wires, List<Integer> kinds)
        throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.neryos.workbay.client.screen.FlowLayout");
        var make = type.getDeclaredConstructor(int.class, List.class, List.class, int.class);
        make.setAccessible(true);
        return make.newInstance(count, wires, kinds, 100);
    }

    /** Each edge's last point, {x, y}. */
    private static int[][] heads(int count, List<int[]> wires, List<Integer> kinds) {
        try {
            Object layout = layout(count, wires, kinds);
            Class<?> type = layout.getClass();
            var xsField = type.getDeclaredField("edgeXs");
            var ysField = type.getDeclaredField("edgeYs");
            xsField.setAccessible(true);
            ysField.setAccessible(true);
            @SuppressWarnings("unchecked") List<int[]> xs = (List<int[]>) xsField.get(layout);
            @SuppressWarnings("unchecked") List<int[]> ys = (List<int[]>) ysField.get(layout);
            int[][] out = new int[wires.size()][];
            for (int e = 0; e < wires.size(); e++) {
                int[] x = xs.get(e);
                int[] y = ys.get(e);
                out[e] = new int[] {x[x.length - 1], y[y.length - 1]};
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("FlowLayout's shape changed; update this test", e);
        }
    }
}
