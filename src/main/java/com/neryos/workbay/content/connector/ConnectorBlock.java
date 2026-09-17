package com.neryos.workbay.content.connector;

import com.mojang.serialization.MapCodec;
import com.neryos.workbay.WorkbayLang;
import com.neryos.workbay.WorkbaySounds;
import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.content.workbay.WorkbayBlockEntity;
import com.neryos.workbay.init.WBBlockEntities;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBDataComponents;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A link, as a block you can point at. SPEC.md §0 and §9.
 *
 * <p>The player pairs a Connector to a Workbay by right-clicking that Workbay with it, then sticks
 * it on any chest, tank or machine. Placing it is what creates the LINKS row; breaking it is what
 * removes it. That is the AE2 import-bus idiom with the cable removed, and it is what Flux Networks
 * and XNet both do - a link the player cannot see, find or break in the world is a link they cannot
 * debug either.
 *
 * <p><b>The block touches one face; the Workbay drives all six.</b> {@code FACING} points into the
 * target, and the target is simply {@code pos.relative(facing)}; which of the target's faces
 * actually answers is settled by {@link com.neryos.workbay.bus.BusEndpoint}, which tries all of
 * them. Nothing about the target's own orientation matters here.
 */
public class ConnectorBlock extends BaseEntityBlock {
    public static final MapCodec<ConnectorBlock> CODEC = simpleCodec(ConnectorBlock::new);

    /** Points <em>into</em> the block this Connector is stuck to, the way an observer's does. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    private static final VoxelShape[] SHAPES = new VoxelShape[6];

    static {
        // A 2px plate on the face it is stuck to. Thin enough to sit on a machine without hiding it.
        SHAPES[Direction.DOWN.ordinal()] = Block.box(4, 0, 4, 12, 2, 12);
        SHAPES[Direction.UP.ordinal()] = Block.box(4, 14, 4, 12, 16, 12);
        SHAPES[Direction.NORTH.ordinal()] = Block.box(4, 4, 0, 12, 12, 2);
        SHAPES[Direction.SOUTH.ordinal()] = Block.box(4, 4, 14, 12, 12, 16);
        SHAPES[Direction.WEST.ordinal()] = Block.box(0, 4, 4, 2, 12, 12);
        SHAPES[Direction.EAST.ordinal()] = Block.box(14, 4, 4, 16, 12, 12);
    }

    public ConnectorBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return WBBlockEntities.CONNECTOR.get().create(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // The plate sits against the face named by FACING, which points away from this block.
        return SHAPES[state.getValue(FACING).ordinal()];
    }

    /**
     * Facing the block that was clicked, so the Connector ends up stuck to it -- and null, which
     * is no placement, when the placer may not reach that block. A Connector is a hopper across a
     * claim border otherwise: the block it goes on is checked by the game, the block it points at
     * was checked by nobody. What is asked is what a claim mod answers: {@code mayInteract} and a
     * posted {@code RightClickBlock} on the target (night audit 1A, question 6).
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
        Player player = context.getPlayer();
        // The network's lock, on the server where the registry is: a Connector paired to somebody
        // else's locked network is a way into it, and placing one registers it there. Inside a
        // room the network is the room's holder, whatever the item says (SPEC.md §0).
        if (player instanceof net.minecraft.server.level.ServerPlayer who
            && networkFor(who.server, context.getLevel(), context.getClickedPos(),
                context.getItemInHand().get(WBDataComponents.PAIRING.get()))
                .map(record -> record.refuses(who)).orElse(false)) {
            return null;
        }
        if (player != null) {
            BlockPos target = target(state, context.getClickedPos());
            if (!context.getLevel().mayInteract(player, target)) {
                return null;
            }
            var asked = net.neoforged.neoforge.common.CommonHooks.onRightClickBlock(player,
                context.getHand(), target, new net.minecraft.world.phys.BlockHitResult(
                    net.minecraft.world.phys.Vec3.atCenterOf(target),
                    state.getValue(FACING).getOpposite(), target, false));
            if (asked.isCanceled()
                || asked.getUseBlock() == net.neoforged.neoforge.common.util.TriState.FALSE) {
                return null;
            }
        }
        return state;
    }

    /** Nothing to link to means nothing to be. Falls off if the target is mined out from under it. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return !level.getBlockState(target(state, pos)).isAir();
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
        net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return direction == state.getValue(FACING) && !canSurvive(state, level, pos)
            ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
            : state;
    }

    public static BlockPos target(BlockState state, BlockPos pos) {
        return pos.relative(state.getValue(FACING));
    }

    /**
     * Whether a Connector the record remembers is no longer standing in the world. Only ever
     * answered off a chunk already loaded - {@code getBlockState} on an unloaded one loads it
     * synchronously - so an unloaded Connector is assumed present. The one question behind the
     * runner's CONNECTOR_GONE and the Add list's refusal. OPEN_ISSUES #112.
     */
    public static boolean gone(net.minecraft.server.MinecraftServer server, GlobalPos at) {
        ServerLevel level = server.getLevel(at.dimension());
        return level != null && level.isLoaded(at.pos())
            && !level.getBlockState(at.pos()).is(WBBlocks.CONNECTOR.get());
    }

    /**
     * The network a Connector placed at {@code pos} would belong to: the holder of the room it
     * stands in, or else the network its pairing names.
     */
    private static java.util.Optional<WorkbayRecord> networkFor(
        net.minecraft.server.MinecraftServer server, Level level, BlockPos pos,
        @Nullable ConnectorPairing pairing) {
        if (level.dimension().equals(com.neryos.workbay.world.WorkbayDimensions.BACKSHOP)) {
            com.neryos.workbay.world.RoomRegistry registry =
                com.neryos.workbay.world.RoomRegistry.get(server);
            var holder = registry.roomAt(pos).flatMap(registry::holderOf);
            if (holder.isPresent()) {
                return holder.map(com.neryos.workbay.world.RoomRegistry.Holder::network);
            }
        }
        return networkOf(server, pairing);
    }

    /** The network a pairing names, through the registry so the Workbay's chunk need not load. */
    public static java.util.Optional<WorkbayRecord> networkOf(
        net.minecraft.server.MinecraftServer server, @Nullable ConnectorPairing pairing) {
        return pairing == null ? java.util.Optional.empty()
            : com.neryos.workbay.world.RoomRegistry.get(server).byId(pairing.workbayId());
    }

    /**
     * Placing a paired Connector hands it to the network, and <b>mints no channel</b>. An unpaired
     * one still places - it is a perfectly ordinary block that simply does nothing yet - and says
     * so, because silently doing nothing is how a player concludes the mod is broken.
     *
     * <p>It used to make one channel, on whichever bay was selected when the item was paired. That
     * is a row the player never asked for, on a bay they were not thinking about - SPEC.md §0's
     * <em>nothing is ever created by itself</em>. What placing a Connector answers is "this block
     * belongs to that network, stuck to that machine"; which bay talks through it is a question
     * about a bay, and it is answered on the bay's own Add list.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof ConnectorBlockEntity connector)) {
            return;
        }
        ConnectorPairing pairing = stack.get(WBDataComponents.PAIRING.get());
        // In a room, no pairing gesture: the room's holder is the network (SPEC.md §0), and
        // workbay() below reads it off the room. A room in nobody's bay keeps the Connector on
        // its own list until it is racked somewhere.
        if (connector.room().isPresent()) {
            registerInRoom(level, pos, state, connector,
                placer instanceof Player player ? player : null, nameOn(stack));
            return;
        }
        if (pairing == null) {
            if (placer instanceof Player player) {
                WorkbaySounds.refuse(player, WorkbayLang.message("connector_unpaired"));
            }
            return;
        }
        connector.pairTo(pairing);
        // <b>The name comes off the item, and the item is named in an anvil.</b> OPEN_ISSUES #67
        // asks for a Connector to be named before it is placed, so that a link is named before it
        // exists -- and an item already has a name a player can set, drawn on the thing in their
        // hand, kept through a stack split and shown in the hotbar. A rename box on the item would
        // have been a second screen, a second packet and a second place for "what is this called"
        // to live; renaming an item is the game's own answer to the same question. Empty custom
        // name leaves every row deriving its name from the target, exactly as before.
        register(level, pos, state, connector, placer instanceof Player player ? player : null,
            nameOn(stack));
    }

    /**
     * Right-clicking a placed Connector opens its <b>rename panel</b>, and does nothing else.
     * OPEN_ISSUES #77.
     *
     * <p>It used to add a link -- the next resource this Connector did not carry, then the same
     * again on the next bay of the network -- which is how one block came to have four rows in a
     * list that is meant to have one row per Connector. The model is one object, one name, one
     * home bay: <b>a Connector is not a thing you feed bays with, it is a thing a bay picks up.</b>
     * Which bay it lands on is a question about a bay, and this block cannot see which bay the
     * player is standing in, so the answer never belonged in the world gesture at all -- it is the
     * Add list on the bay screen, which already moves a link from any bay to the one you are in.
     *
     * <p>What is left is the one thing a block <em>can</em> answer for itself: what it is called.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
        Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof ConnectorBlockEntity connector)) {
            return InteractionResult.PASS;
        }
        // Refreshes the pairing off the room first, so a Connector in a room that moved names
        // the network holding it now.
        connector.workbay();
        if (connector.pairing().isEmpty()) {
            WorkbaySounds.refuse(player, WorkbayLang.message(
                connector.room().isPresent() ? "connector_room_asleep" : "connector_unpaired"));
            return InteractionResult.CONSUME;
        }
        // The network's lock: its Connectors are its doors too, and this panel renames one.
        if (player instanceof net.minecraft.server.level.ServerPlayer server
            && !networkOf(server.server, connector.pairing().get())
                .map(record -> record.refuses(server)).orElse(false)) {
            com.neryos.workbay.menu.ConnectorMenu.open(server, pos);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Breaking the Connector removes it and its channels. Done here rather than in
     * {@code BlockEntity#setRemoved}, which also fires on chunk unload - a Workbay whose Connectors
     * happen to be in an unloaded chunk must not quietly lose them.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide
            && level.getBlockEntity(pos) instanceof ConnectorBlockEntity connector) {
            GlobalPos here = GlobalPos.of(level.dimension(), pos);
            // Through the registry when the Workbay is not standing: a sleeping network, or one
            // whose block is elsewhere now, must still lose the Connector - the record kept it
            // otherwise and offered it on every Add list. OPEN_ISSUES #112.
            java.util.Optional<WorkbayBlockEntity> live = connector.workbay();
            if (live.isPresent()) {
                live.get().removeConnectorAt(here);
            } else {
                net.minecraft.server.MinecraftServer server = ((ServerLevel) level).getServer();
                networkFor(server, level, pos, connector.pairing().orElse(null)).ifPresent(record ->
                    com.neryos.workbay.world.RoomRegistry.get(server)
                        .put(record.withoutConnectorAt(here)));
            }
            // And off a pulled-out room's own list, where it sits while the room is an item.
            connector.room().filter(room -> connector.roomHolder().isEmpty()).ifPresent(room ->
                com.neryos.workbay.world.RoomRegistry.get(((ServerLevel) level).getServer())
                    .putRoom(room.withConnectors(room.connectors().stream()
                        .filter(c -> !c.pos().equals(here)).toList())));
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Whatever an anvil wrote on the stack, or "" for one that was never renamed. */
    private static String nameOn(ItemStack stack) {
        Component custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        return custom == null ? ""
            : com.neryos.workbay.network.ActionPacket.cleanName(custom.getString());
    }

    /**
     * A Connector placed inside a room: onto the holder's list, or onto the room's own while it
     * is an item, so it is there when the room is racked. SPEC.md §0.
     */
    private static void registerInRoom(Level level, BlockPos pos, BlockState state,
        ConnectorBlockEntity connector, @Nullable Player player, String name) {
        if (connector.roomHolder().isPresent()) {
            register(level, pos, state, connector, player, name);
            return;
        }
        BlockPos targetPos = target(state, pos);
        com.neryos.workbay.world.RoomRecord room = connector.room().orElseThrow();
        java.util.List<WorkbayRecord.Connector> list = new java.util.ArrayList<>(room.connectors());
        list.add(new WorkbayRecord.Connector(UUID.randomUUID(),
            GlobalPos.of(level.dimension(), pos), name,
            GlobalPos.of(level.dimension(), targetPos),
            java.util.Optional.ofNullable(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                    level.getBlockState(targetPos).getBlock()))));
        com.neryos.workbay.world.RoomRegistry.get(((ServerLevel) level).getServer())
            .putRoom(room.withConnectors(list));
        if (player != null) {
            WorkbaySounds.confirm(player, WorkbayLang.message("connector_in_sleeping_room",
                level.getBlockState(targetPos).getBlock().getName()),
                com.neryos.workbay.init.WBSounds.LINKED.get(), 1.0F);
        }
    }

    /** Hands this block to the network as a Connector it owns. No channel: SPEC.md §0. */
    private static void register(Level level, BlockPos pos, BlockState state,
        ConnectorBlockEntity connector, @Nullable Player player, String name) {
        WorkbayBlockEntity workbay = connector.workbay().orElse(null);
        if (workbay == null) {
            if (player != null) {
                WorkbaySounds.refuse(player, WorkbayLang.message("connector_no_workbay"));
            }
            return;
        }
        String network = workbay.record().map(WorkbayRecord::label).orElse("");
        BlockPos targetPos = target(state, pos);
        // Stamped here and nowhere else: this is the one moment the block at the far end is known
        // to be loaded, and from now on the screen can say "Chest" rather than two coordinates and
        // the flow map can draw the chest. See BusConfig#targetBlock.
        workbay.addConnector(new WorkbayRecord.Connector(UUID.randomUUID(),
            GlobalPos.of(level.dimension(), pos), name,
            GlobalPos.of(level.dimension(), targetPos),
            java.util.Optional.ofNullable(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                    level.getBlockState(targetPos).getBlock()))));
        if (player != null) {
            WorkbaySounds.confirm(player, WorkbayLang.message("connector_linked",
                level.getBlockState(targetPos).getBlock().getName(), network),
                com.neryos.workbay.init.WBSounds.LINKED.get(), 1.0F);
        }
    }
}
