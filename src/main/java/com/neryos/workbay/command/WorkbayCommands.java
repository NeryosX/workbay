package com.neryos.workbay.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.neryos.workbay.Workbay;
import com.neryos.workbay.host.HostChecks;
import com.neryos.workbay.host.HostResult;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * SPEC.md §11. Thirty lines that turn the denylist from a support burden into self-service: a pack
 * author who wants to know why their favourite machine will not go in a bay can ask, instead of
 * filing an issue and waiting.
 */
@EventBusSubscriber(modid = Workbay.MOD_ID)
public class WorkbayCommands {

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("workbay")
            .then(Commands.literal("why")
                .then(Commands.argument("block", ResourceLocationArgument.id())
                    .suggests((context, builder) -> {
                        BuiltInRegistries.BLOCK.keySet().forEach(id -> builder.suggest(id.toString()));
                        return builder.buildFuture();
                    })
                    .executes(context -> why(context.getSource(),
                        ResourceLocationArgument.getId(context, "block")))))
            .then(Commands.literal("ports")
                .executes(context -> ports(context.getSource())))
            // A test fixture that fills any energy block for free: operators only.
            .then(Commands.literal("charge")
                .requires(source -> source.hasPermission(2))
                .executes(context -> charge(context.getSource())))
            // Every bay whose machine's mod is gone: operators only, because it lists every
            // network on the server. SPEC.md §14; it was cited and not registered (#111).
            .then(Commands.literal("orphans")
                .requires(source -> source.hasPermission(2))
                .executes(context -> orphans(context.getSource())))
            .then(Commands.literal("room")
                .then(Commands.argument("room", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                    .executes(context -> room(context.getSource(),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "room")))))
            .then(Commands.literal("remote")
                .then(Commands.argument("bay", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                    .executes(context -> remote(context.getSource(),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "bay")))));
        event.getDispatcher().register(root);
    }

    /**
     * What one call to {@link #chargeBlock} did: the FE the block <b>kept</b>, and the FE its
     * handler <b>claimed</b> to accept. They are not the same number, and the gap is the point.
     */
    public record Charged(int stored, int claimed) {}

    /**
     * Fills a block's energy buffer, and answers with what actually landed in it.
     *
     * <p>A test fixture, not a feature: the mod's own gametests charge a source through the
     * capability in one line, and without this there is no way to do the same by hand.
     *
     * <p><b>Measured, not asked.</b> {@code receiveEnergy}'s return is what a handler
     * <em>says</em> it took, and Mekanism's creative Energy Cube says it took
     * {@code Integer.MAX_VALUE} and keeps nothing -- its container combines every insert with
     * {@code SIMULATE} so a creative buffer cannot be filled from outside at all. Trusting that
     * return printed "Pushed 2147483646 FE" over an empty cube, and the empty cube then made a
     * perfectly healthy energy link read Idle for a session: OPEN_ISSUES #81, and the whole of it.
     * So this reads {@code getEnergyStored} either side of the push and reports the difference.
     * Same rule as everywhere else in the mod -- never a foreign handler's word for what it did.
     */
    public static Charged chargeBlock(net.minecraft.server.level.ServerLevel level,
        net.minecraft.core.BlockPos pos) {
        int claimed = 0;
        int stored = 0;
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            var store = level.getCapability(
                net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, pos, side);
            if (store == null) {
                continue;
            }
            int before = store.getEnergyStored();
            int said = store.receiveEnergy(Integer.MAX_VALUE, false);
            int landed = store.getEnergyStored() - before;
            claimed = Math.max(claimed, said);
            if (landed > 0) {
                stored = landed;
                break;
            }
        }
        return new Charged(stored, claimed);
    }

    /**
     * The {@code was:} record, read out: a bay keeps the id of the block last racked in it even
     * after that block's mod is uninstalled and vanilla has dropped the block itself, so a pack
     * author auditing an update finds every hole at once rather than one empty bay at a time.
     */
    private static int orphans(CommandSourceStack source) {
        int found = 0;
        for (var record : com.neryos.workbay.world.RoomRegistry.get(source.getServer()).all()) {
            for (var bay : record.bays()) {
                if (bay.hosted().isEmpty() || BuiltInRegistries.BLOCK.containsKey(bay.hosted().get())) {
                    continue;
                }
                found++;
                String id = bay.hosted().get().toString();
                source.sendSuccess(() -> Component.literal(record.label() + " (" + record.ownerName()
                    + "), bay " + (bay.index() + 1) + ": was " + id).withStyle(ChatFormatting.RED), false);
            }
        }
        int total = found;
        source.sendSuccess(() -> Component.literal(total == 0
            ? "No bay is missing its machine's mod."
            : total + " bay(s) hold a block whose mod is not installed."), false);
        return found;
    }

    private static int charge(CommandSourceStack source) {
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            source.sendFailure(Component.literal("Run this as a player, looking at a block."));
            return 0;
        }
        net.minecraft.world.phys.HitResult hit = player.pick(8.0, 0.0F, false);
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult block)) {
            source.sendFailure(Component.literal("Look at a block first."));
            return 0;
        }
        net.minecraft.core.BlockPos pos = block.getBlockPos();
        Charged charged = chargeBlock(player.serverLevel(), pos);
        String name = player.serverLevel().getBlockState(pos).getBlock().getName().getString();
        source.sendSuccess(() -> Component.literal(
            "Pushed " + charged.stored() + " FE into " + name), false);
        if (charged.stored() == 0 && charged.claimed() > 0) {
            // The one line that would have ended #81 in a minute instead of a session.
            source.sendSuccess(() -> Component.literal("  it reported accepting "
                + charged.claimed() + " FE and kept none, so it holds nothing for a link to carry")
                .withStyle(ChatFormatting.RED), false);
        }
        return 1;
    }

    /**
     * Opens the machine in one bay of the Workbay you are looking at, from where you stand.
     *
     * <p>The measuring instrument for the remote screen, and deliberately not a button yet: this is
     * the path SPEC.md §0 ruled out on three counts, and two of them are now patched. Whether the
     * third -- a mod whose own buttons resolve against the player's level -- still bites is a
     * question only a real machine can answer, so the cheapest way to ask it is a command.
     */
    private static int remote(CommandSourceStack source, int bay) {
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            source.sendFailure(Component.literal("Run this as a player, looking at a Workbay."));
            return 0;
        }
        if (!com.neryos.workbay.remote.RemoteConfig.remoteScreensEnabled()) {
            source.sendFailure(Component.literal(
                "Remote screens are off in config/workbay-mixins.properties."));
            return 0;
        }
        net.minecraft.world.phys.HitResult hit = player.pick(8.0, 0.0F, false);
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult block)
            || !(player.serverLevel().getBlockEntity(block.getBlockPos())
                instanceof com.neryos.workbay.content.workbay.WorkbayBlockEntity workbay)) {
            source.sendFailure(Component.literal("Look at a Workbay first."));
            return 0;
        }
        var record = workbay.record();
        net.minecraft.server.level.ServerLevel backshop =
            player.server.getLevel(com.neryos.workbay.world.WorkbayDimensions.BACKSHOP);
        if (record.isEmpty() || backshop == null || bay < 0 || bay >= record.get().bayCapacity()) {
            source.sendFailure(Component.literal("No such bay."));
            return 0;
        }
        // The one lock, because this is the menu's button without the menu.
        if (record.get().refuses(player)) {
            return 0;
        }
        net.minecraft.core.BlockPos machine =
            com.neryos.workbay.world.BayGeometry.machinePos(record.get().bayColumn(), bay);
        if (!com.neryos.workbay.remote.RemoteScreens.open(player, backshop, machine)) {
            source.sendFailure(Component.literal(
                "Bay " + bay + " has nothing with a screen in it."));
            return 0;
        }
        return 1;
    }

    /**
     * Puts you in the room standing in bay {@code index} of the looked-at Workbay, building it if
     * this is its first visit. The bay panel's Enter without the screen.
     */
    private static int room(CommandSourceStack source, int index) {
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            source.sendFailure(Component.literal("Run this as a player, looking at a Workbay."));
            return 0;
        }
        net.minecraft.world.phys.HitResult hit = player.pick(8.0, 0.0F, false);
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult block)
            || !(player.serverLevel().getBlockEntity(block.getBlockPos())
                instanceof com.neryos.workbay.content.workbay.WorkbayBlockEntity workbay)) {
            source.sendFailure(Component.literal("Look at a Workbay first."));
            return 0;
        }
        var record = workbay.record();
        if (record.isEmpty()) {
            source.sendFailure(Component.literal("That Workbay is not bound to a network."));
            return 0;
        }
        // The one lock: this is the ROOMS page's Enter without the page, and the page would not
        // have opened. A guest's invite is asked after it, by RoomVisit.
        if (record.get().refuses(player)) {
            return 0;
        }
        if (!com.neryos.workbay.world.RoomVisit.enter(player, record.get(), index)) {
            // Deliberately vague about which of the two it was: RoomVisit puts "that room isn't
            // yours" on the action bar itself when that is the reason, and a command that then
            // adds "no room in bay 2" is the mod contradicting itself in two places at once.
            source.sendFailure(Component.literal("Could not enter the room in bay " + index + "."));
            return 0;
        }
        return 1;
    }

    /**
     * What the block you are looking at offers a link, face by face. SPEC.md §11's argument again:
     * "my link says no port" is the other question this mod gets, and the answer is never in the
     * mod - it is in what the other machine chose to expose. A player can now read that themselves.
     *
     * <p>Deliberately the same question {@code BusEndpoint} asks. It never uses the null side, so
     * the null row is printed apart and marked: a machine that answers only there is one Bay View
     * can show and no link can reach, which is the single most confusing state this mod has.
     */
    private static int ports(CommandSourceStack source) {
        if (!(source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            source.sendFailure(Component.literal("Run this as a player, looking at a block."));
            return 0;
        }
        net.minecraft.world.phys.HitResult hit = player.pick(8.0, 0.0F, false);
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult block)
            || player.serverLevel().getBlockState(block.getBlockPos()).isAir()) {
            source.sendFailure(Component.literal("Look at a block first."));
            return 0;
        }
        net.minecraft.core.BlockPos pos = block.getBlockPos();
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        source.sendSuccess(() -> Component.literal(
            level.getBlockState(pos).getBlock().getName().getString())
            .withStyle(ChatFormatting.AQUA)
            .append(Component.literal(" at " + pos.toShortString()).withStyle(ChatFormatting.GRAY)),
            false);

        report(source, level, pos, null, "no side (Bay View reads this; links never do)");
        for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
            report(source, level, pos, side, side.getName());
        }
        return 1;
    }

    /** One face, and what it answers. Nothing is inferred: every line is a capability lookup. */
    private static void report(CommandSourceStack source, net.minecraft.server.level.ServerLevel level,
        net.minecraft.core.BlockPos pos, @org.jetbrains.annotations.Nullable net.minecraft.core.Direction side,
        String label) {
        StringBuilder found = new StringBuilder();
        var items = level.getCapability(
            net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, side);
        if (items != null) {
            found.append("items ").append(items.getSlots());
            int accepts = 0;
            for (int slot = 0; slot < items.getSlots(); slot++) {
                if (items.insertItem(slot, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT),
                    true).isEmpty()) {
                    accepts++;
                }
            }
            found.append(" (").append(accepts).append(" take an item)");
        }
        var fluids = level.getCapability(
            net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK, pos, side);
        if (fluids != null) {
            found.append(found.isEmpty() ? "" : " · ").append("fluid tanks ").append(fluids.getTanks());
        }
        var energy = level.getCapability(
            net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK, pos, side);
        if (energy != null) {
            found.append(found.isEmpty() ? "" : " · ").append("energy");
        }
        int chemicals = com.neryos.workbay.compat.MekanismChemicals.tanks(level, pos).size();
        if (side == null && chemicals > 0) {
            found.append(found.isEmpty() ? "" : " · ").append("chemical tanks ").append(chemicals);
        }
        String line = found.isEmpty() ? "nothing" : found.toString();
        source.sendSuccess(() -> Component.literal("  " + label + ": ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal(line).withStyle(found.isEmpty()
                ? ChatFormatting.DARK_GRAY : ChatFormatting.GREEN)), false);
    }

    private static int why(CommandSourceStack source, ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null || (block == Blocks.AIR && !id.equals(ResourceLocation.withDefaultNamespace("air")))) {
            source.sendFailure(Component.literal("No block is registered as " + id));
            return 0;
        }

        BlockState state = block.defaultBlockState();
        HostResult result = HostChecks.evaluate(new ItemStack(block));

        source.sendSuccess(() -> Component.literal(id.toString()).withStyle(ChatFormatting.AQUA)
            .append(result.allowed()
                ? Component.literal(" can be hosted.").withStyle(ChatFormatting.GREEN)
                : Component.literal(" can't be hosted: ").withStyle(ChatFormatting.RED)
                    .append(result.message())), false);

        // The inputs behind the verdict, so the answer is checkable rather than an oracle.
        line(source, "in #workbay:host_allowed", state.is(HostChecks.HOST_ALLOWED));
        line(source, "in #workbay:host_denied", state.is(HostChecks.HOST_DENIED));
        line(source, "has a block entity", state.hasBlockEntity());
        line(source, "obtainable as an item", block.asItem() != net.minecraft.world.item.Items.AIR);
        source.sendSuccess(() -> Component.literal("  piston reaction: ")
            .withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(state.getPistonPushReaction().name()).withStyle(ChatFormatting.GRAY)), false);
        source.sendSuccess(() -> Component.literal("  connection properties: ")
            .withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(String.valueOf(connectionProperties(state))).withStyle(ChatFormatting.GRAY)), false);
        return 1;
    }

    private static void line(CommandSourceStack source, String label, boolean value) {
        source.sendSuccess(() -> Component.literal("  " + label + ": ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(value ? "yes" : "no")
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.GRAY)), false);
    }

    private static int connectionProperties(BlockState state) {
        int found = 0;
        for (Property<?> property : state.getProperties()) {
            if (property.getValueClass() == Boolean.class) {
                switch (property.getName()) {
                    case "north", "east", "south", "west", "up", "down" -> found++;
                    default -> { }
                }
            }
        }
        return found;
    }
}
