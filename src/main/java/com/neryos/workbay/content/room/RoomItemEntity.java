package com.neryos.workbay.content.room;

import com.neryos.workbay.init.WBEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * A room lying on the ground. SPEC.md §0: never destroyed.
 *
 * <p>Two of the six ways an item dies are the entity's and not the item's, so a room on the
 * ground is this rather than a plain {@link ItemEntity}: it <b>never expires</b>, and one that
 * falls out of the world <b>turns up on the overworld's spawn</b> instead of being discarded --
 * the one place every player can find and nothing can hide.
 *
 * <p><b>The lifespan is unlimited, not the age.</b> Vanilla's {@code setUnlimitedLifetime} pins
 * the age at -32768, and the age is also what spins and bobs the item on the ground, so a room
 * marked that way lay frozen like a dropped block from a broken texture pack (seen in play, 09-14).
 * NeoForge's {@code lifespan} is the expiry alone; with it at {@link Integer#MAX_VALUE} the age
 * counts up like any item's and the room still never goes. Rooms never stack ({@code stacksTo(1)}),
 * which is what kept two of them from merging.
 */
public class RoomItemEntity extends ItemEntity {

    public RoomItemEntity(EntityType<? extends ItemEntity> type, Level level) {
        super(type, level);
        lifespan = Integer.MAX_VALUE;
    }

    /** Takes the place of the plain entity NeoForge was about to spawn, motion and all. */
    public RoomItemEntity(Level level, Entity was, ItemStack stack) {
        this(WBEntities.ROOM_ITEM.get(), level);
        setPos(was.getX(), was.getY(), was.getZ());
        setDeltaMovement(was.getDeltaMovement());
        setItem(stack.copy());
        if (was instanceof ItemEntity item && item.getOwner() != null) {
            setThrower(item.getOwner());
        }
        setDefaultPickUpDelay();
    }

    /**
     * The age is saved as a short and would wrap to exactly -32768 -- the frozen mark -- once in
     * 65,536 saves; a room saved by the release candidate carries that mark already. Both read
     * back as a fresh age, and an old save's 6000-tick lifespan is overridden.
     */
    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        if (tag.getShort("Age") == Short.MIN_VALUE) {
            tag.putShort("Age", (short) 0);
        }
        super.readAdditionalSaveData(tag);
        lifespan = Integer.MAX_VALUE;
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putShort("Age", (short) Math.floorMod(getAge(), 24000));
    }

    /**
     * The void. Vanilla discards anything sixty-four blocks under the world; a room goes to the
     * overworld's spawn instead, on top of whatever stands there, and stops falling.
     */
    @Override
    protected void onBelowWorld() {
        if (!(level() instanceof ServerLevel here)) {
            return;
        }
        ServerLevel overworld = here.getServer().overworld();
        BlockPos spawn = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
            overworld.getSharedSpawnPos());
        Vec3 at = Vec3.atBottomCenterOf(spawn);
        setDeltaMovement(Vec3.ZERO);
        teleportTo(overworld, at.x, at.y, at.z, Set.of(), getYRot(), getXRot());
    }
}
