package com.neryos.workbay;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import com.neryos.workbay.init.WBSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Every sound the mod makes. SPEC.md §7's "answers from across the room", heard rather than read.
 *
 * <p><b>All of them are vanilla recordings.</b> No {@code .ogg}: a custom set is a second art
 * budget and none of these moments needs one. What they need is to exist - a machine that racks
 * in silence reads as a mod that did not finish, whatever the inside is like. Each is the mod's
 * <em>own event</em> ({@link WBSounds}) borrowing a vanilla one, because a borrowed event carries
 * the other block's subtitle: "Vault rejects item" over a Workbay refusing. OPEN_ISSUES #91.
 *
 * <p>Two rules decide every entry below, and both come from playing the moment twenty times rather
 * than once.
 *
 * <ol>
 *   <li><b>A sound belongs to an edge, never to a state.</b> A link moves something every few
 *       seconds for as long as a base runs, so a sound per move is a metronome nobody can turn off.
 *       The Workbay's own blockstate already collapses that into IDLE/RUNNING/STUCK with four
 *       seconds of hysteresis and is written only on a change - so the state change <em>is</em> the
 *       edge, and {@link #started}, {@link #stopped} and {@link #stuck} hang off it and cost no new
 *       bookkeeping at all.
 *   <li><b>Loud for what a player did, quiet for what the mod noticed.</b> Racking is a hand
 *       movement and takes the machine's own placement sound at full volume; a chain deciding it
 *       has run dry is 0.3, which carries about five blocks and is gone in the room next door.
 * </ol>
 *
 * <p>A refusal is the player's own business, so {@link #refuse} sends it to that player alone
 * through {@code playNotifySound} rather than to the room.
 */
public final class WorkbaySounds {
    private WorkbaySounds() {}

    /**
     * Every action-bar rejection in the mod, said out loud as well as written. SPEC.md §6 puts a
     * rejection on the action bar, which is one line of grey text above the hotbar that a player
     * looking at the screen they just clicked does not see at all - the message was there from the
     * first version and was still being missed.
     *
     * <p>It is the <em>only</em> way to write one, so that a refusal cannot be added in silence:
     * the sound and the sentence are one call, the way {@code Draw#text} is the only way to write
     * a string.
     */
    public static void refuse(Player player, Component why) {
        player.displayClientMessage(why, true);
        // Vanilla's own "that did not go in": a quarter-second wooden knock, dry, with no tune in
        // it to get tired of. The alternative auditioned was ENTITY_VILLAGER_NO, which is funny
        // once and unbearable by the fifth full bay.
        player.playNotifySound(WBSounds.REFUSE.get(), SoundSource.BLOCKS, 0.7F, 1.0F);
    }

    /** The other half: something the player asked for that worked, said the same way. */
    public static void confirm(Player player, Component what) {
        confirm(player, what, WBSounds.CONFIRM.get(), 1.2F);
    }

    /**
     * The same, for a success that has its own voice. Two of them do: pairing a Connector to a
     * Workbay is a relay tick -- noted, not finished -- and the link that appears when it is
     * placed is a switch closing. They are two halves of one gesture and a player learns the
     * difference in one afternoon, which is exactly what a distinct sound is for.
     */
    public static void confirm(Player player, Component what, SoundEvent sound, float pitch) {
        player.displayClientMessage(what, true);
        player.playNotifySound(sound, SoundSource.BLOCKS, 0.7F, pitch);
    }

    /** A line with no verdict in it - where you are, what to do next - so no sound to go with it. */
    public static void note(Player player, Component what) {
        player.displayClientMessage(what, true);
    }

    /**
     * A machine going into a bay, in the machine's own voice - a Mekanism cube clanks, a barrel
     * thumps, a beehive rustles. Free variety, and it names what went in without a word.
     */
    public static void racked(Level level, BlockPos pos, BlockState machine) {
        at(level, pos, machine.getSoundType().getPlaceSound(), 1.0F, 0.9F);
    }

    /** And coming back out. */
    public static void ejected(Level level, BlockPos pos, BlockState machine) {
        at(level, pos, machine.getSoundType().getBreakSound(), 0.9F, 1.1F);
    }

    /** An upgrade fitted. One clank of a hammer on a plate; SPEC.md §1's rungs are rare. */
    public static void upgraded(Level level, BlockPos pos) {
        at(level, pos, WBSounds.UPGRADED.get(), 0.8F, 1.0F);
    }

    /** A room's Anchor, switched. Rare enough to afford a sound with a tail on it. */
    public static void anchored(Level level, BlockPos pos, boolean on) {
        at(level, pos, (on ? WBSounds.ANCHOR_ON : WBSounds.ANCHOR_OFF).get(), 0.5F, 1.4F);
    }

    /** A door in a room's wall, opening onto the list of ways out. */
    public static void door(Level level, BlockPos pos) {
        at(level, pos, WBSounds.DOOR.get(), 0.6F, 1.1F);
    }

    /**
     * A player moved between the world and the Backshop, heard at <b>both</b> ends: whoever is left
     * behind hears them go, and they hear themselves arrive. Chorus fruit's sound, because it is
     * already what "you are somewhere else now" means in this game and it is over in half a second.
     *
     * @param move the teleport itself, run between the two halves
     */
    public static void travel(ServerPlayer player, Runnable move) {
        ServerLevel from = player.serverLevel();
        Vec3 where = player.position();
        move.run();
        from.playSound(null, where.x, where.y, where.z,
            WBSounds.TRAVEL.get(), SoundSource.PLAYERS, 0.6F, 1.2F);
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
            WBSounds.TRAVEL.get(), SoundSource.PLAYERS, 0.6F, 1.2F);
    }

    /**
     * The base coming to life, and going quiet again. Both hang off the blockstate edge in
     * {@code WorkbayBlockEntity#refreshLitState}, which already holds RUNNING for four seconds
     * after the last move - so a link on the wheel that moves something every two seconds makes
     * exactly one of these when the chain starts and one when it runs dry, whatever it does in
     * between. Quiet on purpose: this is furniture noticing something, not an alarm.
     */
    public static void started(Level level, BlockPos pos) {
        at(level, pos, WBSounds.STARTED.get(), 0.35F, 1.3F);
    }

    /** The same edge, downwards. A chain that has run out of things to move says so. */
    public static void stopped(Level level, BlockPos pos) {
        at(level, pos, WBSounds.STOPPED.get(), 0.3F, 1.3F);
    }

    /** Something needs the player. Low and short - amber means a problem, and so does this. */
    public static void stuck(Level level, BlockPos pos) {
        at(level, pos, WBSounds.STUCK.get(), 0.5F, 0.7F);
    }

    /**
     * One sound, at a block, for everyone near enough to hear it. {@code null} for the player
     * argument means nobody is excluded - the player who caused it is standing right there and is
     * the one who most needs to hear it.
     *
     * <p><b>And the one place a Workbay can be silenced.</b> Every sound the block makes goes
     * through here, so {@code blockSounds} is one branch rather than a flag checked at nine call
     * sites - the same reason {@code Draw#text} is the only way to write a string. A busy Workbay
     * says so every time a link starts and stops, which is worth hearing while you are standing at
     * it and noise the rest of the time. The owner's ask.
     */
    private static void at(Level level, BlockPos pos, SoundEvent sound, float volume, float pitch) {
        if (!level.isClientSide
            && com.neryos.workbay.config.WorkbayConfig.SERVER.blockSounds.get()) {
            level.playSound(null, pos, sound, SoundSource.BLOCKS, volume, pitch);
        }
    }
}
