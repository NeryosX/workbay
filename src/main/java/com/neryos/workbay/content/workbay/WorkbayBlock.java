package com.neryos.workbay.content.workbay;

import com.mojang.serialization.MapCodec;
import com.neryos.workbay.WorkbayLang;
import com.neryos.workbay.WorkbaySounds;
import com.neryos.workbay.content.connector.ConnectorPairing;
import com.neryos.workbay.init.WBBlockEntities;
import com.neryos.workbay.init.WBBlocks;
import com.neryos.workbay.init.WBDataComponents;
import com.neryos.workbay.config.WorkbayConfig;
import com.neryos.workbay.world.RoomHolding;
import com.neryos.workbay.world.RoomRegistry;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The mod. A cabinet holding bays in the Backshop, reachable by bus. SPEC.md §2, §7 and §14.
 *
 * <p>{@code state} carries the whole at-a-glance signal (SPEC.md §7): a player should be able to see
 * that something is wrong from across the room without opening anything.
 */
public class WorkbayBlock extends BaseEntityBlock {
    public static final MapCodec<WorkbayBlock> CODEC = simpleCodec(WorkbayBlock::new);

    public static final EnumProperty<WorkbayState> STATE = EnumProperty.create("state", WorkbayState.class);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public WorkbayBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any()
            .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
            .setValue(STATE, WorkbayState.IDLE)
            .setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HorizontalDirectionalBlock.FACING, STATE, POWERED);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return WBBlockEntities.WORKBAY.get().create(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
        Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        return level.isClientSide ? null
            : createTickerHelper(type, WBBlockEntities.WORKBAY.get(), WorkbayBlockEntity::serverTick);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
            .setValue(HorizontalDirectionalBlock.FACING, context.getHorizontalDirection().getOpposite());
    }

    /**
     * Binding happens here, not in the block entity's constructor, because it is the only point that
     * knows both who placed the block and what the item they placed remembered.
     *
     * <p><b>A placement is never refused, and this is where that is true.</b> One Workbay block is
     * one network (SPEC.md §0), so the three things that can happen are: the item names a network
     * with no block on it and takes it; the player owns a <em>sleeping</em> network and this block
     * wakes it; or the player is at {@code maxNetworksPerPlayer} and the block stands there
     * <em>holding nothing</em> - which is a real, openable state, not a failure. Opening it lists
     * the player's networks with a Transfer beside each.
     *
     * <p>The old model refused instead, from the item's {@code useOn} so the block never went down.
     * Two knobs said no: a cap on networks per player, and {@code maxDeployedWorkbaysPerNetwork},
     * which let several blocks be doors onto one network. The doors are gone - a second door meant
     * two blocks ticking one set of links, two energy buffers and two answers to every question,
     * and every one of those was a bug before it was a feature.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel server)
            || !(level.getBlockEntity(pos) instanceof WorkbayBlockEntity workbay)) {
            return;
        }

        RoomRegistry registry = RoomRegistry.get(server.getServer());
        UUID ownerId = placer instanceof Player player ? player.getUUID() : UUID.randomUUID();
        String ownerName = placer instanceof Player player
            ? player.getGameProfile().getName() : "unknown";

        // 1. What the item remembers, if that network has no block on it. A Workbay broken and put
        //    back down is the whole reason the binding component exists.
        WorkbayBinding binding = stack.get(WBDataComponents.BINDING.get());
        WorkbayRecord record = binding == null ? null
            : registry.byId(binding.id()).filter(r -> !r.live()).orElse(null);
        boolean minted = false;

        // 2. Otherwise a network of this player's that is asleep -- what makes a freshly crafted
        //    Workbay find its way home rather than mint a second empty one beside a full base.
        if (record == null) {
            record = registry.ownedBy(ownerId).stream().filter(r -> !r.live()).findFirst()
                .orElse(null);
        }

        // 3. Otherwise a new one, if they are under the limit.
        if (record == null
            && registry.ownedBy(ownerId).size() < WorkbayConfig.SERVER.maxNetworksPerPlayer.get()) {
            record = registry.create(ownerId, ownerName, server.getRandom());
            minted = true;
        }

        // A network standing inside one of its own rooms is a room inside itself, and Leave would
        // have no way out (SPEC.md §0). The block stands holding nothing, and says why.
        if (record != null && RoomHolding.wouldCycle(registry, record,
            GlobalPos.of(server.dimension(), pos))) {
            if (placer instanceof Player player) {
                WorkbaySounds.refuse(player, WorkbayLang.message("room_cycle"));
            }
            return;
        }

        // 4. Otherwise nothing at all, and the block says so when it is opened.
        if (record == null) {
            if (placer instanceof Player player) {
                WorkbaySounds.confirm(player, WorkbayLang.message("network_quota"),
                    com.neryos.workbay.init.WBSounds.RELAY.get(), 0.8F);
            }
            return;
        }

        registry.put(record.withDeployedCount(record.deployedCount() + 1));
        workbay.bindTo(record.id());
        workbay.rememberPosition();

        if (placer instanceof Player player) {
            // The network's <b>name</b>, never its code. SPEC.md §0 mints a code so a lost network
            // can be recovered by typing one to an operator, and shows it nowhere else: a message
            // that names nothing cannot be checked, and one naming a code cannot be read.
            player.sendSystemMessage(WorkbayLang.message(
                minted ? "network_created" : "network_reused_named", record.label()));
        }
    }

    /**
     * The other half of the deployed count {@link #setPlacedBy} increments. On {@code BlockEntity}
     * removal, not here: that fires on chunk unload too, and a Workbay whose chunk merely unloaded
     * has not been given up - the player would come back to find their "lost" network silently
     * handed to whoever placed next.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide
            && level.getBlockEntity(pos) instanceof WorkbayBlockEntity workbay) {
            workbay.workbayId().ifPresent(id -> {
                if (level instanceof ServerLevel server) {
                    RoomRegistry registry = RoomRegistry.get(server.getServer());
                    registry.byId(id).ifPresent(record ->
                        registry.put(record.withDeployedCount(record.deployedCount() - 1)));
                }
            });
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * Right-clicking a Workbay with a Connector pairs that Connector to it. Pairing before placing
     * is what lets {@code setPlacedBy} create the link in one step - a Connector that had to be
     * paired after placement would sit in the world doing nothing, which reads as a broken mod.
     *
     * <p><b>No bay is chosen, here or anywhere.</b> The pairing names the network and nothing else,
     * so the common case - one machine, one Connector - needs no screen at all, and which bay talks
     * through the Connector is decided later, on that bay's own Add list. Screen 1's `+ Pair`
     * button makes the same pairing for a Connector held in the offhand.
     */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state,
        Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand,
        net.minecraft.world.phys.BlockHitResult hit) {
        boolean connector = stack.is(WBBlocks.CONNECTOR.get().asItem());
        boolean blank = stack.is(WBBlocks.WORKBAY.get().asItem())
            && stack.get(WBDataComponents.BINDING.get()) == null;
        if (!connector && !blank) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return net.minecraft.world.ItemInteractionResult.SUCCESS;
        }
        // A block holding no network has nothing to pair or stamp with, but it has a screen
        // (NETWORKS, with a Transfer beside each of the player's own), and the screen wins: FAIL
        // here let the held Workbay place a second one on top, and a Connector place itself
        // against the block. 1.0.1.
        if (!(level.getBlockEntity(pos) instanceof WorkbayBlockEntity workbay)
            || workbay.record().isEmpty()) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        WorkbayRecord record = workbay.record().get();
        // The one lock, on the world gesture too: a stranger must not pair a Connector into
        // somebody else's Add list, nor stamp a blank Workbay with their network for later.
        // CONSUME, not FAIL: FAIL lets the item's own use run next, which placed the Connector
        // against the block and wrote "isn't paired yet" over the refusal.
        if (player instanceof net.minecraft.server.level.ServerPlayer who && record.refuses(who)) {
            return net.minecraft.world.ItemInteractionResult.CONSUME;
        }
        if (blank) {
            return stamp(stack, record, player);
        }
        pair(stack, record, GlobalPos.of(level.dimension(), pos));
        // Which network, because that is the whole of what pairing decides now: a Connector is
        // owned by a network and used from whichever bays the player picks later.
        WorkbaySounds.confirm(player, WorkbayLang.message("connector_paired", record.label()),
            com.neryos.workbay.init.WBSounds.RELAY.get(), 1.6F);
        return net.minecraft.world.ItemInteractionResult.CONSUME;
    }

    /**
     * <b>Which network the next Workbay joins, chosen by pointing at one.</b> OPEN_ISSUES #63.
     *
     * <p>A fresh Workbay placed by somebody who already owns a network silently joins whichever
     * one {@code ownedBy} happened to return first, and with more than one there was no way to say
     * which -- not on placement, not afterwards. This is the way to say it, and it is the gesture
     * the mod already teaches: a Connector is bound to a Workbay by right-clicking that Workbay
     * with it, and now so is a Workbay.
     *
     * <p><b>A picker on placement is what was asked for and this is not that.</b> It is what fits
     * in the mod as it stands: the binding component, the tooltip that reads it and the rejoin path
     * in {@link #setPlacedBy} all exist and are tested, so stamping the item costs one branch and
     * no new screen, packet or menu. A list of networks to choose from -- Flux Networks' shape --
     * is still worth building, and is still open. Note that {@code maxNetworksPerPlayer} ships at
     * <b>2</b>, so a player meets this only once they have deliberately built a second network.
     */
    private static net.minecraft.world.ItemInteractionResult stamp(ItemStack stack,
        WorkbayRecord record, @Nullable Player player) {
        // The counts are the tooltip's, and they are the network's rather than the item's, because
        // that is what this item will be part of the moment it is placed.
        stack.set(WBDataComponents.BINDING.get(),
            WorkbayBinding.of(record, (int) java.util.stream.IntStream
                .range(0, record.bayCapacity())
                .filter(i -> record.bay(i).hosted().isPresent())
                .count(), record.buses().size(), 0));
        if (player != null) {
            // The code, here and nowhere else. SPEC.md §14 keeps codes off the screens because a
            // network is found by owner -- but this is the one moment a player is choosing between
            // two networks, and a name is what a choice needs.
            WorkbaySounds.confirm(player, WorkbayLang.message("workbay_stamped", record.label()),
                com.neryos.workbay.init.WBSounds.RELAY.get(), 1.2F);
        }
        return net.minecraft.world.ItemInteractionResult.CONSUME;
    }

    /**
     * An empty hand opens the screens. The whole snapshot rides the menu-open buffer, so the screen
     * draws the real state on its first frame rather than flashing defaults (SPEC.md §4).
     */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level,
        BlockPos pos, Player player, net.minecraft.world.phys.BlockHitResult hit) {
        if (level.isClientSide) {
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof WorkbayBlockEntity workbay)
            || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        // <b>No guard on having a network.</b> A block holding nothing is exactly the block that
        // has something to say: it opens on NETWORKS, listing the player's own with a Transfer
        // beside each. Refusing to open it was the old model's last refusal. The one refusal left
        // is the lock's, and `open` says it.
        com.neryos.workbay.menu.WorkbayMenu.open(serverPlayer, workbay, 0);
        return net.minecraft.world.InteractionResult.CONSUME;
    }

    /** Stamps a Connector item with the Workbay and bay its link will land on. */
    public static void pair(ItemStack stack, WorkbayRecord record, GlobalPos workbayPos) {
        stack.set(WBDataComponents.PAIRING.get(),
            new ConnectorPairing(record.id(), workbayPos, record.label()));
    }


    /**
     * SPEC.md §14: the machines keep running, so say so before the player walks away thinking they
     * lost them. Sent on the break rather than before it, because there is no "before" for a block
     * that breaks in one hit.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof WorkbayBlockEntity workbay) {
            workbay.record().ifPresent(record -> {
                if (!record.bays().isEmpty()) {
                    player.sendSystemMessage(WorkbayLang.message("break_warning"));
                }
            });
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * SPEC.md §14: pick-block returns an <em>unbound</em> Workbay. Handing back a bound one would
     * make middle-click duplicate a factory.
     */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return new ItemStack(this);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /** A comparator reads bay occupancy - the same thing the block's lit state says (SPEC.md §7). */
    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof WorkbayBlockEntity workbay ? workbay.occupiedBays() : 0;
    }

    @Override
    protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(HorizontalDirectionalBlock.FACING,
            rotation.rotate(state.getValue(HorizontalDirectionalBlock.FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(HorizontalDirectionalBlock.FACING)));
    }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0; // glass front: the interior is meant to be visible
    }
}
