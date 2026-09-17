package com.neryos.workbay.content.workbay;

import com.neryos.workbay.config.WorkbayConfig;
import com.neryos.workbay.init.WBItems;
import com.neryos.workbay.world.WorkbayRecord;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Every upgrade a v1 Workbay can hold, on one enum. SPEC.md §1.
 *
 * <p>Upgrades are <b>consumed on install</b> and recorded as counters, so there is no upgrade
 * inventory, no slot grid and no removal path. The dropped Workbay item carries every counter, so
 * moving a base loses nothing.
 *
 * <p>The Anchor, the Room Frames and the Annex Plate are in the tier table but are v2 (SPEC.md §16),
 * and are deliberately absent here rather than present and inert.
 */
public enum WorkbayUpgrade implements StringRepresentable {
    /**
     * <b>The maximum is a config value, not a number argued for here.</b> How long the ladder
     * should be is a balance question, and balance numbers in this mod come from measurement -
     * so the ceiling is {@code maxBaysPerWorkbay} minus the bays the base Workbay already grants,
     * and a pack that has actually played to the top can move it without a release. It is derived
     * from that knob rather than being a second one: two numbers that can disagree about the same
     * ceiling is how a player ends up holding a plate that installs and does nothing.
     */
    EXPANSION_PLATE("expansion_plate",
        () -> WorkbayConfig.SERVER.maxBaysPerWorkbay.get() - WorkbayRecord.BASE_BAYS,
        () -> WBItems.EXPANSION_PLATE.get()),
    /**
     * Cross-dimension reach, and a host's switch over it. Its max is
     * {@code allowCrossDimensionLinks} for the same reason the Anchor's is {@code allowAnchors}:
     * a host who does not want one network reaching into somebody else's End base gets an upgrade
     * that cannot be installed, rather than a registry that changes shape (SPEC.md §13). Every
     * cross-dimension link then reads "Needs Resonator" for ever, which is a refusal the row
     * already draws.
     */
    RESONATOR("resonator",
        () -> WorkbayConfig.SERVER.allowCrossDimensionLinks.get() ? 1 : 0,
        () -> WBItems.RESONATOR.get()),
    /**
     * Throughput, and the only upgrade that changes a number every link already has.
     *
     * <p>Two, because the third would be arguing about somebody else's mod: at two the ladder tops
     * out level with EnderIO's enhanced item conduit and just under its plain energy one, and a
     * mod that sells space rather than TPS (SPEC.md §0) has no business beating a cable mod at
     * cables. The rate a link is born with is deliberately modest -- an unupgraded link feeds a
     * furnace and starves a Mekanism machine, which is the shape of the ladder.
     *
     * <p>Two is now the <em>default</em> of {@code maxImpellers} rather than a number in this
     * file: the argument above is ours to make and a pack that has played past it is entitled to
     * disagree, which is the whole reason the ladder moved to config.
     */
    IMPELLER("impeller", () -> WorkbayConfig.SERVER.maxImpellers.get(),
        () -> WBItems.IMPELLER.get()),

    /**
     * Lets a room be kept running while it is empty, and the bay column while nobody is near.
     * Grants the <em>ability</em> only: SPEC.md §0 switches each room on separately, beside the
     * number of chunks it holds, so one upgrade cannot quietly light thirty-six of them.
     *
     * <p>Its max is the config switch, so a host who wants no force loading in their JVM gets an
     * upgrade that cannot be installed rather than a registry that changes shape (SPEC.md §13).
     */
    ANCHOR("anchor", () -> WorkbayConfig.SERVER.allowAnchors.get() ? 1 : 0,
        () -> WBItems.ANCHOR.get());

    private final String name;
    private final IntSupplier max;
    private final Supplier<Item> item;

    WorkbayUpgrade(String name, IntSupplier max, Supplier<Item> item) {
        this.name = name;
        this.max = max;
        this.item = item;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public int max() {
        return Math.max(0, max.getAsInt());
    }


    public Item item() {
        return item.get();
    }
}
