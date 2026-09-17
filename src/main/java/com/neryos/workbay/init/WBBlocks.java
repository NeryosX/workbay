package com.neryos.workbay.init;

import com.neryos.workbay.Workbay;
import com.neryos.workbay.content.connector.ConnectorBlock;
import com.neryos.workbay.content.connector.ConnectorItem;
import com.neryos.workbay.content.port.PortBlock;
import com.neryos.workbay.content.room.RoomBlock;
import com.neryos.workbay.content.room.RoomWallBlock;
import com.neryos.workbay.content.workbay.WorkbayBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Function;

public class WBBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Workbay.MOD_ID);
    /** Block items. Plain items live in {@link WBItems}. */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Workbay.MOD_ID);

    public static final DeferredBlock<WorkbayBlock> WORKBAY = registerWorkbay();

    /**
     * Generated into a bay, never given to a player. No BlockItem at all, so it cannot be crafted,
     * picked or given, and -1 hardness so it cannot be broken. SPEC.md §2: a bay with a missing wall
     * is an unrecoverable state.
     */
    /**
     * The world end of a link (SPEC.md §0). A thin plate on any block, paired to a Workbay before
     * it is placed. Its BlockItem is custom only so the tooltip can name what it is paired to.
     */
    public static final DeferredBlock<ConnectorBlock> CONNECTOR = registerConnector();


    public static final DeferredBlock<PortBlock> PORT = BLOCKS.register("port", () ->
        new PortBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_BLACK)
            .sound(SoundType.METAL)
            .strength(-1.0F, 3600000.0F)
            .noLootTable()
            .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
            .isValidSpawn((state, level, pos, type) -> false)));

    /**
     * A room's shell, generated with the room and painted by its record. No BlockItem, -1 hardness
     * and its own refusal to be destroyed, for the reason bedrock had before it: a hole in a shell
     * is a hole into the void. SPEC.md §8.
     */
    public static final DeferredBlock<RoomWallBlock> ROOM_WALL = BLOCKS.register("room_wall", () ->
        new RoomWallBlock(BlockBehaviour.Properties.of()
            .mapColor(MapColor.STONE)
            .sound(SoundType.STONE)
            // A room lights itself. The Backshop has ambient light, so nothing was ever pitch
            // black, but "not black" is not "lit": an empty room read as a grey box and a player
            // had to floor it with torches before it looked like anywhere. 11 is under a torch's
            // 14 and over the 9 crops want, so a bare room grows things and still leaves a reason
            // to hang a lamp. It is BLOCK light, not sky -- nothing here feeds a solar panel.
            // The ceiling's fixtures are the one part above that, at 15: a room lit to the same
            // value off every one of its faces has no source, which is OPEN_ISSUES #45.
            .lightLevel(state -> state.getValue(com.neryos.workbay.content.room.RoomWallBlock.PART)
                == com.neryos.workbay.content.room.RoomPart.LIGHT ? 15 : 11)
            .strength(-1.0F, 3600000.0F)
            .noLootTable()
            .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
            .isValidSpawn((state, level, pos, type) -> false)));

    /**
     * The three rooms, one block each so a bay's {@code hosted} id says the size. SPEC.md §0. A
     * room block only ever stands in a bay; its item is what the player holds, and
     * {@link com.neryos.workbay.content.room.RoomItem} refuses to place it anywhere else.
     */
    public static final DeferredBlock<RoomBlock> ROOM = registerRoom("room", 1);
    public static final DeferredBlock<RoomBlock> WIDE_ROOM = registerRoom("wide_room", 2);
    public static final DeferredBlock<RoomBlock> VAST_ROOM = registerRoom("vast_room", 3);

    private static DeferredBlock<RoomBlock> registerRoom(String name, int tier) {
        var holder = BLOCKS.registerBlock(name, props -> new RoomBlock(tier, props),
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                .mapColor(MapColor.COLOR_BLACK)
                .sound(SoundType.METAL)
                .strength(3.5F)
                // Two of the three are smaller than a block (RoomBlock#inset), so the faces
                // behind them must not be culled away.
                .noOcclusion()
                .noLootTable());
        ITEMS.register(name, () -> new com.neryos.workbay.content.room.RoomItem(holder.get(),
            new net.minecraft.world.item.Item.Properties().stacksTo(1)));
        return holder;
    }

    /**
     * <b>A plain BlockItem, because placing a Workbay is never refused.</b> It used to be a
     * {@code WorkbayItem} whose whole job was to say no on {@code useOn} - before the block went
     * down, since {@code setPlacedBy} only ever sees a placement that already happened. One block
     * is one network now (SPEC.md §0), and a player at their network limit gets a block that
     * stands there holding nothing and opens on the list of their networks with a Transfer beside
     * each. Nothing is left to refuse, so nothing is left of the class.
     */
    private static DeferredBlock<WorkbayBlock> registerWorkbay() {
        var holder = BLOCKS.registerBlock("workbay", WorkbayBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                .mapColor(MapColor.COLOR_BLACK)
                .sound(SoundType.METAL)
                .strength(3.5F)
                // The other half of SPEC.md §7's "distinguishable in the dark". A texture is only
                // as visible as the light falling on it, so an unlit base would hide all three
                // readings equally well; the block lights itself instead, the way a furnace does.
                // Never 15: a Workbay is furniture, not a lamp, and a wall of them should not
                // light a base for free.
                .lightLevel(state -> switch (state.getValue(WorkbayBlock.STATE)) {
                    case IDLE -> 3;
                    case RUNNING -> 10;
                    case STUCK -> 8;
                })
                .noOcclusion());
        ITEMS.register("workbay", () -> new net.minecraft.world.item.BlockItem(holder.get(),
            new net.minecraft.world.item.Item.Properties()));
        return holder;
    }

    private static DeferredBlock<ConnectorBlock> registerConnector() {
        var holder = BLOCKS.registerBlock("connector", ConnectorBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                .mapColor(MapColor.COLOR_BLACK)
                .sound(SoundType.METAL)
                .strength(1.5F)
                .noOcclusion());
        ITEMS.register("connector", () -> new ConnectorItem(holder.get(), new net.minecraft.world.item.Item.Properties()));
        return holder;
    }

    static <B extends Block> DeferredBlock<B> registerWithItem(String name,
        Function<BlockBehaviour.Properties, ? extends B> func, BlockBehaviour.Properties props) {
        var blockHolder = BLOCKS.<B>registerBlock(name, func, props);
        ITEMS.registerSimpleBlockItem(blockHolder);
        return blockHolder;
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
