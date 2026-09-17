package com.neryos.workbay.host;

import com.neryos.workbay.Workbay;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What may be racked in a bay, and why not. SPEC.md §11.
 *
 * <p>Block tags rather than a data map or config, because the value is boolean-with-a-reason and a
 * third-party mod can opt its own block in or out with <b>zero dependency on us</b> by shipping
 * {@code data/workbay/tags/block/host_denied.json} in its own jar.
 *
 * <p>Every verdict carries the reason that produced it. A player who cannot host something has to
 * be told which rule stopped them, or the denylist becomes a support burden instead of a boundary.
 */
public final class HostChecks {
    private HostChecks() {}

    public static final TagKey<Block> HOST_DENIED =
        TagKey.create(Registries.BLOCK, Workbay.rl("host_denied"));
    public static final TagKey<Block> HOST_ALLOWED =
        TagKey.create(Registries.BLOCK, Workbay.rl("host_allowed"));
    public static final TagKey<BlockEntityType<?>> HOST_DENIED_TYPES =
        TagKey.create(Registries.BLOCK_ENTITY_TYPE, Workbay.rl("host_denied_types"));

    /**
     * Neoforge's convention tag for blocks that cannot survive being moved. Its own javadoc
     * describes our failure mode, so it is honoured before any heuristic of ours.
     */
    private static final TagKey<Block> RELOCATION_NOT_SUPPORTED =
        TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "relocation_not_supported"));

    /**
     * The connection properties nearly every mod's cable, pipe and fence uses. Three or more of them
     * on one block means it works by joining up with its neighbours, and a bay has none.
     */
    private static final Set<String> CONNECTION_PROPERTIES =
        Set.of("north", "east", "south", "west", "up", "down");

    private static final int CONNECTION_THRESHOLD = 3;

    /**
     * What a structural multiblock part calls itself. Mekanism's casings, valves and ports are all
     * {@code BlockBasicMultiblock}; its structural glass is not, but the block entity behind it is
     * a {@code TileEntityStructuralMultiblock}, which is why both hierarchies are read.
     */
    private static final Set<String> MULTIBLOCK_TOKENS = Set.of("multiblock", "multi_block");

    /** One answer per block, for ever: the hierarchy of a class cannot change while we run. */
    private static final Map<Block, Boolean> MULTIBLOCK_PARTS = new ConcurrentHashMap<>();

    private static final List<HostCheck> CHECKS = new ArrayList<>();

    /**
     * Adds a code-level check. Tri-state on purpose: a check must be able to say "no opinion" rather
     * than clobbering the tag layer, so the compat module can answer only about the mod it knows.
     */
    public static synchronized void register(HostCheck check) {
        CHECKS.add(check);
    }

    /** Evaluation order from SPEC.md §11. First non-PASS wins. */
    public static HostResult evaluate(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return HostResult.deny("not_a_block", stack.getHoverName());
        }
        Block block = blockItem.getBlock();
        BlockState state = block.defaultBlockState();

        // 1. An explicit allow beats everything, including our own heuristics. This is the escape
        // hatch a pack author reaches for when a heuristic is wrong about their favourite mod.
        if (state.is(HOST_ALLOWED)) {
            return HostResult.allow();
        }

        // 2. Registered checks, most recently registered first.
        for (int i = CHECKS.size() - 1; i >= 0; i--) {
            HostResult result = CHECKS.get(i).check(state, stack);
            if (!result.isPass()) {
                return result;
            }
        }

        // 3. The denylist, and the block-entity-type denylist that takes every tier of a machine at
        // once rather than one id per tier.
        if (state.is(HOST_DENIED)) {
            return HostResult.deny("pack_denied", stack.getHoverName());
        }
        if (state.hasBlockEntity() && deniedByType(state)) {
            return HostResult.deny("pack_denied", stack.getHoverName());
        }

        // 4. Kept separate from the denylist above so the player is told it cannot be moved rather
        // than that the pack forbade it. Two different problems, two different things to do next.
        if (state.is(RELOCATION_NOT_SUPPORTED)) {
            return HostResult.deny("immovable", stack.getHoverName());
        }

        // 5. Heuristics, all registry-level and cheap.
        //
        // SPEC.md §11 lists a fifth here - deny when getPistonPushReaction() is BLOCK - and it is
        // deliberately not implemented. Measured: every Mekanism machine sets PushReaction.BLOCK,
        // as most modded machines with a block entity do, because a piston moving one corrupts it.
        // The heuristic would therefore reject the entire mod this one exists to host. It also asks
        // the wrong question: a bay does not push a machine, it breaks and re-places it. The tag
        // that means what we need is c:relocation_not_supported, checked at step 4 above.
        // A casing, valve, port or structural pane is a piece of a building, not a machine: alone
        // it does nothing at all, and a bay holds exactly one block. Asked before no_machine so the
        // player is told what the block is rather than that it has nothing running inside it.
        //
        // A machine that is merely *large* is not this. Mekanism's Digital Miner is one block in
        // one slot - one item, one block entity, no structure to assemble - and nothing here
        // touches it. What it runs into instead is the size of a bay, and that is answered where
        // the block is actually placed ({@code BayHosting}), not here: a machine that claims the
        // space around it cannot be told apart from one that does not until it is asked to stand up.
        if (isMultiblockPart(state)) {
            return HostResult.deny("multiblock", stack.getHoverName());
        }
        if (!state.hasBlockEntity()) {
            return HostResult.deny("no_machine", stack.getHoverName());
        }
        if (connectionProperties(state) >= CONNECTION_THRESHOLD) {
            return HostResult.deny("needs_neighbours", stack.getHoverName());
        }
        // 6. Default allow. Anything actually wrong with the machine shows up as the non-blocking
        // inert flag after it is racked, never as a second rejection (SPEC.md §11, two-phase).
        return HostResult.allow();
    }

    /**
     * <b>A heuristic, not a proof</b>, and the same kind of bargain as {@link
     * #connectionProperties}: there is no vanilla or NeoForge API that says "this block is a piece
     * of a structure", so the only registry-level signal available is what the mod named its own
     * classes. Mekanism names them well - every casing, valve, port and controller is a {@code
     * BlockBasicMultiblock}, and the structural glass's block entity is a {@code
     * TileEntityStructuralMultiblock} - and a mod that names them differently simply falls through
     * to the rules below, exactly as it did before this existed.
     *
     * <p>Wrong about a block? {@code workbay:host_allowed} beats it, at step 1, from any pack.
     */
    private static boolean isMultiblockPart(BlockState state) {
        return MULTIBLOCK_PARTS.computeIfAbsent(state.getBlock(), HostChecks::sniffMultiblock);
    }

    private static boolean sniffMultiblock(Block block) {
        if (namesMultiblock(block.getClass())) {
            return true;
        }
        // The block class is not always the one that knows. Building the block entity is the only
        // way to read its class without a level, it happens once per block, and a mod whose block
        // entity refuses to be built outside a world is simply left alone.
        if (block instanceof EntityBlock entityBlock) {
            try {
                BlockEntity be = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                return be != null && namesMultiblock(be.getClass());
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    /** The class, everything it extends, and every interface either of those carries. */
    private static boolean namesMultiblock(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
            current = current.getSuperclass()) {
            String name = current.getSimpleName().toLowerCase(java.util.Locale.ROOT);
            for (String token : MULTIBLOCK_TOKENS) {
                if (name.contains(token)) {
                    return true;
                }
            }
            for (Class<?> parent : current.getInterfaces()) {
                if (namesMultiblock(parent)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A heuristic, not a proof. It false-positives on fences and panes, which have no block entity
     * and are rejected before they reach here, and false-negatives on any mod using its own property
     * names. Reflection-based override sniffing is deliberately not used: essentially every machine
     * overrides {@code neighborChanged} for redstone, so it proves nothing.
     */
    private static int connectionProperties(BlockState state) {
        int found = 0;
        for (Property<?> property : state.getProperties()) {
            if (property.getValueClass() == Boolean.class && CONNECTION_PROPERTIES.contains(property.getName())) {
                found++;
            }
        }
        return found;
    }

    /**
     * Walks only the tagged types rather than the whole registry: one entry here takes every tier
     * of a machine at once, which is the reason the tag is on the block entity type at all.
     */
    private static boolean deniedByType(BlockState state) {
        return BuiltInRegistries.BLOCK_ENTITY_TYPE.getTag(HOST_DENIED_TYPES)
            .map(tagged -> tagged.stream().anyMatch(holder -> holder.value().isValid(state)))
            .orElse(false);
    }

    @FunctionalInterface
    public interface HostCheck {
        HostResult check(BlockState state, ItemStack stack);
    }
}
