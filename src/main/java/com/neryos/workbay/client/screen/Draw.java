package com.neryos.workbay.client.screen;

import com.neryos.workbay.world.FaceConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Every shape the three screens are made of. SPEC.md §4 and §7.
 *
 * <p>Drawn from rectangles rather than a background PNG. That is not a shortcut: the layout in
 * SPEC.md §4 is explicitly a starting point rather than a freeze, and a hand-cut texture makes
 * every later nudge a round trip through an image editor. It also means the status colours are one
 * palette in one file instead of pixels somebody has to match by eye.
 *
 * <p><b>Nothing outside this class draws a bare rectangle, and nothing outside it draws a
 * string.</b> SPEC.md §7's screen-chrome rules come down to one thing - everything has a light
 * top-left edge and a dark bottom-right one - and they hold only if there is exactly one place that
 * knows how. {@link #text} is the same argument applied to words: see its comment.
 */
public final class Draw {
    private Draw() {}

    // The palette. One place, so a status colour cannot mean two things in two screens.
    public static final int PANEL = 0xFF2B2E33;
    public static final int PANEL_LIGHT = 0xFF3D424A;
    /** A big content area: the links list, the face preview. Content sits inside it. */
    public static final int WELL = 0xFF191B1F;
    /** One slot. Lighter than a well on purpose - a black square reads as a hole, not a slot. */
    public static final int SLOT = 0xFF212429;
    /**
     * The inside of a gauge. Far darker than {@link #WELL} and than the panel, because a gauge is
     * the one container here whose <em>contents</em> are the reading - the tube has to get out of
     * the way of two pixels of fluid.
     */
    public static final int TUBE = 0xFF0D1015;
    /** The line along the top of whatever a gauge holds. Turns a sliver into a surface. */
    public static final int SURFACE = 0x99FFFFFF;
    /** Quarter marks, light enough to survive a bright fluid and a dark tube alike. */
    public static final int GRADUATION = 0x40FFFFFF;

    // Far enough apart to survive a dark panel. At one pixel, a one-shade edge is no edge.
    public static final int EDGE_LIGHT = 0xFF6A7280;
    public static final int EDGE_DARK = 0xFF0D0F12;

    /** Two weights, per SPEC.md §7: bright for what the player acts on, dim for labels. */
    public static final int TEXT = 0xFFF0F2F5;
    public static final int TEXT_DIM = 0xFFA6AEBA;

    /** A blacklist's sheet. Not pure black: the panel behind it is dark and a black sheet on it
     * is a hole, so this is the darkest ink that still reads as an object. */
    public static final int PAPER_BLACK = 0xFF23272E;
    /** The third exists only for disabled, and disabled must look disabled. */
    public static final int TEXT_FAINT = 0xFF636A76;

    public static final int GREEN = 0xFF4CC46A;
    public static final int BLUE = 0xFF4C8FD6;
    public static final int AMBER = 0xFFD8A33A;
    public static final int RED = 0xFFD65C4C;
    public static final int GREY = 0xFF565C66;
    /**
     * Power, and only power. Mekanism's own bar green (#3BFB98, sampled off
     * {@code gui/bar/horizontal_power.png}), because a player coming from that mod already reads
     * this colour as a charge level. Deliberately mintier than {@link #GREEN}, which means "in" on
     * a face and "running" on a row - one shade for two ideas is how a swatch stops answering.
     */
    public static final int ENERGY = 0xFF3BFB98;
    /**
     * The other two resources, for the same reason {@link #ENERGY} exists: a link's kind is read
     * off its icon's colour before its shape, and three kinds sharing {@link #BLUE} would each be
     * saying what the direction arrow beside them already says.
     */
    public static final int FLUID = 0xFF4FA8E0;
    public static final int CHEMICAL = 0xFFB07BD6;
    public static final int SELECT = 0xFF5AA9E6;


    // -------------------------------------------------------------- shapes

    /**
     * <b>The rounded, shaded panel this mod is not made of, drawn without a library.</b>
     *
     * <p>Everything below exists to answer one question honestly: what can be drawn from
     * {@code GuiGraphics#fill} alone, given that every shape on these screens already comes through
     * this file. The answer is more than it looks - a filled rectangle can be one pixel tall, so a
     * gradient is a loop over rows, and a corner is that loop with an inset. Anti-aliasing is the
     * fractional part of the inset, drawn as one dimmer pixel at each end of the row.
     *
     * <p>The cost is not the code. It is that this is a second visual language living beside the
     * game's, and every vanilla widget the screen still borrows - the EditBox, the tooltip frame,
     * the item sprite - stays square-cornered and flat beside it.
     */
    public static void round(GuiGraphics g, int x, int y, int w, int h, int r, int top, int bottom) {
        round(g, x, y, w, h, r, r, top, bottom);
    }

    /** The same, with the top and bottom corners rounded by different amounts. */
    public static void round(GuiGraphics g, int x, int y, int w, int h, int topR, int bottomR,
        int top, int bottom) {
        for (int row = 0; row < h; row++) {
            int colour = mix(top, bottom, h <= 1 ? 0 : (float) row / (h - 1));
            double inset = 0;
            int r = row < topR ? topR : bottomR;
            int from = row < topR ? topR - row : row >= h - bottomR ? bottomR - (h - 1 - row) : 0;
            if (from > 0) {
                double dy = from - 0.5;
                inset = r - Math.sqrt(Math.max(0, (double) r * r - dy * dy));
            }
            int whole = (int) inset;
            float fringe = (float) (inset - whole);
            g.fill(x + whole, y + row, x + w - whole, y + row + 1, colour);
            if (fringe > 0) {
                int soft = alpha(colour, 1 - fringe);
                g.fill(x + whole - 1, y + row, x + whole, y + row + 1, soft);
                g.fill(x + w - whole, y + row, x + w - whole + 1, y + row + 1, soft);
            }
        }
    }

    /** One flat colour in a rounded box. */
    public static void round(GuiGraphics g, int x, int y, int w, int h, int r, int colour) {
        round(g, x, y, w, h, r, colour, colour);
    }

    /**
     * The soft edge around a rounded shape: three rings, each dimmer and one pixel further out.
     * A drop shadow is what stops a rounded panel reading as a hole cut in the world behind it.
     */
    public static void shadow(GuiGraphics g, int x, int y, int w, int h, int r) {
        for (int ring = 3; ring >= 1; ring--) {
            round(g, x - ring, y - ring + 1, w + ring * 2, h + ring * 2, r + ring, 0x22000000);
        }
    }

    /** Linear blend of two ARGB colours, channel by channel. */
    public static int mix(int from, int to, float t) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = from >>> shift & 0xFF;
            int b = to >>> shift & 0xFF;
            out |= (int) (a + (b - a) * t) << shift;
        }
        return out;
    }

    /** The same colour with its alpha scaled. */
    public static int alpha(int argb, float scale) {
        return (argb & 0x00FFFFFF) | ((int) ((argb >>> 24) * Math.clamp(scale, 0F, 1F)) << 24);
    }

    /**
     * The one helper SPEC.md §7 asks for. Raised puts the light edge top-left, sunken flips it;
     * that single difference is what separates a button from a slot at a glance.
     */
    public static void bevel(GuiGraphics g, int x, int y, int w, int h, boolean raised) {
        bevel(g, x, y, w, h, raised, EDGE_LIGHT, EDGE_DARK);
    }

    public static void bevel(GuiGraphics g, int x, int y, int w, int h, boolean raised,
        int light, int dark) {
        int topLeft = raised ? light : dark;
        int bottomRight = raised ? dark : light;
        g.fill(x, y, x + w, y + 1, topLeft);
        g.fill(x, y, x + 1, y + h, topLeft);
        g.fill(x, y + h - 1, x + w, y + h, bottomRight);
        g.fill(x + w - 1, y, x + w, y + h, bottomRight);
    }

    /** How round. One number, so nothing on the screen is rounder than anything else. */
    private static final int RADIUS = 6;

    public static final int PANEL_TOP = 0xFF32363E;
    public static final int PANEL_BOTTOM = 0xFF22252B;

    /** The window: a drop shadow, a vertical gradient in a rounded box, and a hairline rim. */
    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        shadow(g, x, y, w, h, RADIUS);
        round(g, x, y, w, h, RADIUS, PANEL_TOP, PANEL_BOTTOM);
        rim(g, x, y, w, h, RADIUS, 0x30FFFFFF, 0x50000000);
    }


    /**
     * The chrome band across the top of a page: rounded where the window is, square where the
     * content begins. It moved here from the page the moment the window grew corners - a square
     * strip drawn a pixel inside a rounded panel pokes out of it at all four of them.
     */
    public static void band(GuiGraphics g, int x, int y, int w, int h) {
        round(g, x, y, w, h, RADIUS - 1, 0, 0xFF2B3038, 0xFF232730);
        g.fill(x, y + h - 1, x + w, y + h, 0x50000000);
    }

    /** A sunken well: darker, rounded, with the rim inverted so it reads as a recess. */
    public static void well(GuiGraphics g, int x, int y, int w, int h) {
        round(g, x, y, w, h, 3, mix(WELL, 0xFF000000, 0.15F), WELL);
        rim(g, x, y, w, h, 3, 0x30000000, 0x18FFFFFF);
    }

    /**
     * The card a refusal is drawn on: dark, rounded, with an amber edge along the top.
     *
     * <p>A refusal is the one thing on the screen the player did not ask to see, so it must read
     * as laid <em>over</em> the page rather than as another row of it. A plain well did not - it
     * is the same shape the links list is drawn in, one row lower.
     */
    public static void notice(GuiGraphics g, int x, int y, int w, int h) {
        shadow(g, x, y, w, h, 4);
        round(g, x, y, w, h, 4, 0xFF2A2620, 0xFF1E1B17);
        rim(g, x, y, w, h, 4, alpha(AMBER, 0.75F), 0x60000000);
    }

    /**
     * A one-pixel rim on a rounded box: lit along the top, shaded along the bottom. Drawn as the
     * ends of the same rows the fill uses rather than as four lines, because a rounded shape has
     * no corners for four lines to meet in.
     */
    private static void rim(GuiGraphics g, int x, int y, int w, int h, int r, int top, int bottom) {
        for (int row = 0; row < h; row++) {
            int from = row < r ? r - row : row >= h - r ? r - (h - 1 - row) : 0;
            double inset = from > 0
                ? r - Math.sqrt(Math.max(0, (double) r * r - (from - 0.5) * (from - 0.5))) : 0;
            int whole = (int) inset;
            int colour = mix(top, bottom, h <= 1 ? 0 : (float) row / (h - 1));
            g.fill(x + whole, y + row, x + whole + 1, y + row + 1, colour);
            g.fill(x + w - whole - 1, y + row, x + w - whole, y + row + 1, colour);
            if (row == 0 || row == h - 1) {
                g.fill(x + whole, y + row, x + w - whole, y + row + 1, colour);
            }
        }
    }

    /**
     * One slot. Sunken, mid-grey, with an inner shadow along the top and left - vanilla's
     * treatment of every inventory cell, and the reason an empty one still reads as a slot.
     */
    /**
     * A dashed rectangle, two pixels on and two off.
     *
     * <p>What an empty slot looks like everywhere else software has one: a solid rim is a thing
     * that is there and empty, a dashed rim is a place where a thing goes. A bay's slot had the
     * solid one, so a fresh Workbay's rack read as eight recesses rather than as eight invitations.
     */
    public static void dashed(GuiGraphics g, int x, int y, int w, int h, int argb) {
        for (int i = 0; i < w; i += 4) {
            int end = Math.min(i + 2, w);
            g.fill(x + i, y, x + end, y + 1, argb);
            g.fill(x + i, y + h - 1, x + end, y + h, argb);
        }
        for (int i = 0; i < h; i += 4) {
            int end = Math.min(i + 2, h);
            g.fill(x, y + i, x + 1, y + end, argb);
            g.fill(x + w - 1, y + i, x + w, y + end, argb);
        }
    }

    public static void slot(GuiGraphics g, int x, int y, int w, int h) {
        int r = Math.min(3, Math.min(w, h) / 4);
        round(g, x, y, w, h, r, mix(SLOT, 0xFF000000, 0.25F), SLOT);
        rim(g, x, y, w, h, r, 0x40000000, 0x1AFFFFFF);
    }

    /**
     * The wash over something present but not usable. One colour, one call, so "unavailable" looks
     * the same everywhere - SPEC.md §7's rule that a disabled thing must not read as an enabled one.
     *
     * <p>Rounded, because everything it is laid over now is: a square wash inside a rounded slot
     * puts four bright corner pixels back that the slot had just taken off, and five locked bays
     * in a column is twenty of them.
     */
    public static void disabled(GuiGraphics g, int x, int y, int w, int h) {
        round(g, x, y, w, h, Math.min(3, Math.min(w, h) / 4), 0x66101216);
    }

    /**
     * <b>A ring around a rounded box.</b> The one thing on these screens that is drawn around
     * something else rather than as something itself: the hover outline, the selected swatch, the
     * slot that will take what the cursor is carrying.
     *
     * <p>It has to follow the same curve the fill does or it does not read as belonging to it -
     * a square outline one pixel outside a rounded button is four bright corners hanging in the
     * air, and with the hover ring on every one of forty regions that is the whole screen
     * flickering square corners as the cursor moves.
     */
    public static void ring(GuiGraphics g, int x, int y, int w, int h, int r, int colour) {
        rim(g, x, y, w, h, r, colour, colour);
    }

    public static void button(GuiGraphics g, int x, int y, int w, int h, boolean hovered, boolean active) {
        button(g, x, y, w, h, hovered, active, true);
    }

    /**
     * A button. <b>Disabled draws sunken and unlit</b> rather than identical to enabled: the first
     * playable screens had four buttons side by side where one worked, and all four looked alike.
     */
    public static void button(GuiGraphics g, int x, int y, int w, int h, boolean hovered,
        boolean active, boolean enabled) {
        int r = Math.min(4, Math.min(w, h) / 4);
        if (!enabled) {
            round(g, x, y, w, h, r, 0xFF2A2D33, 0xFF23262B);
            rim(g, x, y, w, h, r, 0x18FFFFFF, 0x30000000);
            return;
        }
        // Hover is a fade, not a swap. Keyed on where the button is, which is the only identity a
        // control drawn from coordinates has.
        float lit = approach("btn:" + x + "," + y, hovered ? 1F : 0F, 16.0F);
        int top = active ? 0xFF5C87B8 : mix(0xFF3B4048, 0xFF4E5560, lit);
        int bottom = active ? 0xFF3F5F84 : mix(0xFF2B2E35, 0xFF383C44, lit);
        round(g, x, y, w, h, r, top, bottom);
        rim(g, x, y, w, h, r, mix(0x40FFFFFF, alpha(SELECT, 0.9F), lit), 0x50000000);
    }

    /**
     * A horizontal fill bar.
     *
     * <p>The empty part is the bar's own colour at a fifth, not the bare slot. An outlined
     * rectangle with nothing in it reads as a text field that failed to render, which is exactly
     * how the upgrades screen's power bar looked sitting above "0 / 100000 FE" - so the one reading
     * a player most needs to recognise was the one that did not look like a bar at all. Tinted, the
     * empty channel is visibly the same object as the full one, and zero is a bar that is empty.
     *
     * <p>Zero still fills no pixels: the minimum of one pixel applies only above zero, so a bar
     * that is nearly empty is distinguishable from one that is empty.
     */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, int value, int max, int argb) {
        int r = Math.min(4, h / 2);
        round(g, x, y, w, h, r, mix(TUBE, 0xFF000000, 0.4F), TUBE);
        // The empty channel carries a breath of the bar's own colour. Without it an empty bar is a
        // black box with three white tally marks in it, which is what "0 / 100.0k" photographed as
        // on the very first screen a player sees -- the graduations became the brightest thing in
        // the shape and the shape stopped being a gauge.
        round(g, x + 1, y + 1, w - 2, h - 2, Math.max(1, r - 1), alpha(argb, 0.10F));
        rim(g, x, y, w, h, r, 0x40000000, 0x14FFFFFF);
        for (int mark = 1; mark < 4; mark++) {
            int mx = x + 1 + (w - 2) * mark / 4;
            g.fill(mx, y + 2, mx + 1, y + h - 2, 0x26FFFFFF);
        }
        if (max <= 0 || value <= 0) {
            return;
        }
        int target = (int) ((long) (w - 2) * Math.min(value, max) / max);
        int filled = Math.max(2, Math.round(approach("bar:" + x + "," + y, target, 9.0F)));
        // A gradient down the fill and a lit line along the top of it: the two things that make a
        // flat colour read as something with a volume rather than as a coloured rectangle.
        round(g, x + 1, y + 1, filled, h - 2, Math.max(1, r - 1),
            mix(argb, 0xFFFFFFFF, 0.35F), mix(argb, 0xFF000000, 0.25F));
        g.fill(x + 1 + filled - 1, y + 1, x + 1 + filled, y + h - 1, alpha(0xFFFFFFFF, 0.75F));
    }

    /**
     * A vertical gauge: a tall, narrow tube filled from the bottom.
     *
     * <p>Vertical because that is what a buffer looks like everywhere else in the genre, and a
     * player reads "half full" off a tall column without reading anything. A ten-pixel horizontal
     * strip with a number beside it reads as a progress bar at best and as nothing at worst.
     *
     * <p>Three things make a <em>low</em> level still read as a level, and all three come from
     * Mekanism's {@code GuiGauge}: the tube is much darker than the panel, so it is visibly a
     * container rather than a shadow; {@link #gaugeGlass} draws quarter graduations over the
     * contents, so an almost-empty tube is still a scale with something at the bottom of it; and
     * the fill carries a bright line along its surface, so two pixels of content read as a
     * surface rather than as an edge.
     */
    public static void gauge(GuiGraphics g, int x, int y, int w, int h, int value, int max,
        int argb) {
        gaugeTube(g, x, y, w, h);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, (argb & 0x00FFFFFF) | 0x33000000);
        if (max > 0 && value > 0) {
            int top = y + h - 1 - fill(h, value, max);
            g.fill(x + 1, top, x + w - 1, y + h - 1, argb);
            g.fill(x + 1, top, x + w - 1, top + 1, SURFACE);
        }
        gaugeGlass(g, x, y, w, h);
    }

    /**
     * The same gauge, filled with the fluid's own still texture. EnderIO's
     * {@code FluidStackWidget}, which is public domain and does exactly this.
     *
     * <p>Not {@link #gauge} tinted with {@code getTintColor}. Water's tint is white - the blue is
     * in the texture - so a tinted column draws every ordinary fluid as a white smear and says
     * nothing about which one it is. The texture is what a player recognises.
     *
     * <p>Tiled upwards at the sprite's native 16 pixels rather than stretched to fit, and clipped
     * to the fill line, so a gauge that is a third full shows a third of a real fluid rather than a
     * whole one squashed. The tube's inside is sixteen pixels wide for that reason: at fourteen,
     * every tile lost two columns of the sprite.
     */
    /**
     * One fluid, filling a square. The filter panel's answer to "which fluid is this slot", where
     * an item slot would draw an item.
     *
     * <p>The still texture, tinted, exactly as {@link #fluidGauge} draws it - a fluid a player
     * recognises in a gauge and does not recognise in a filter slot is two names for one thing.
     */
    public static void fluidIcon(GuiGraphics g, int x, int y, int size,
        net.minecraft.world.level.material.Fluid fluid) {
        var stack = new net.neoforged.neoforge.fluids.FluidStack(fluid, 1000);
        var extensions = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
            .of(fluid);
        var sprite = Minecraft.getInstance()
            .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
            .apply(extensions.getStillTexture(stack));
        int tint = extensions.getTintColor(stack);
        g.setColor((tint >> 16 & 0xFF) / 255.0F, (tint >> 8 & 0xFF) / 255.0F,
            (tint & 0xFF) / 255.0F, 1.0F);
        g.blit(x, y, 0, size, size, sprite);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void fluidGauge(GuiGraphics g, int x, int y, int w, int h,
        net.neoforged.neoforge.fluids.FluidStack fluid, int capacity) {
        gaugeTube(g, x, y, w, h);
        if (fluid.isEmpty() || capacity <= 0) {
            gaugeGlass(g, x, y, w, h);
            return;
        }
        int filled = fill(h, fluid.getAmount(), capacity);
        var extensions = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
            .of(fluid.getFluid());
        var sprite = Minecraft.getInstance()
            .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
            .apply(extensions.getStillTexture(fluid));
        int tint = extensions.getTintColor(fluid);
        g.setColor((tint >> 16 & 0xFF) / 255.0F, (tint >> 8 & 0xFF) / 255.0F,
            (tint & 0xFF) / 255.0F, 1.0F);
        int bottom = y + h - 1;
        g.enableScissor(x + 1, bottom - filled, x + w - 1, bottom);
        for (int up = 0; up < filled; up += 16) {
            g.blit(x + 1, bottom - up - 16, 0, 16, 16, sprite);
        }
        g.disableScissor();
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        g.fill(x + 1, bottom - filled, x + w - 1, bottom - filled + 1, SURFACE);
        gaugeGlass(g, x, y, w, h);
    }

    /**
     * How many pixels of a gauge {@code value} fills. <b>Two at the minimum, not one.</b> A single
     * lit row sitting against the tube's own bottom edge is an edge, not a level - which is how
     * 2,000 of 32,000 came to read as an empty box.
     */
    private static int fill(int h, int value, int max) {
        return Math.clamp((long) (h - 2) * value / max, 2, h - 2);
    }

    /**
     * The empty tube: a recess much darker than the panel, with the sunken bevel every other
     * container on these screens has.
     *
     * <p>Darker than {@link #WELL}, which was the fault. A well one shade off the panel is a shape
     * the eye does not separate from it, so a gauge with a little in it read as a flat grey box
     * with nothing in it at all.
     */
    private static void gaugeTube(GuiGraphics g, int x, int y, int w, int h) {
        int r = Math.min(3, Math.min(w, h) / 4);
        round(g, x, y, w, h, r, mix(TUBE, 0xFF000000, 0.4F), TUBE);
        rim(g, x, y, w, h, r, 0x40000000, 0x14FFFFFF);
    }

    /**
     * The glass, drawn <b>over</b> the contents: quarter graduations, a highlight down the left
     * edge and a shadow down the right and across the top.
     *
     * <p>Over rather than under is the whole point - it is what makes the fluid look like it is
     * inside something. Mekanism does the same thing with a one-bit overlay texture
     * ({@code gui/gauge/standard.png}: a half-width tick every six pixels and a full-width one at
     * the midpoint); this draws the same idea in code because this mod has no gauge texture and
     * quarters survive a gauge of any height, which a fixed six-pixel pitch does not.
     */
    private static void gaugeGlass(GuiGraphics g, int x, int y, int w, int h) {
        int left = x + 1;
        int right = x + w - 1;
        int top = y + 1;
        int bottom = y + h - 1;
        for (int quarter = 1; quarter < 4; quarter++) {
            int ty = bottom - (h - 2) * quarter / 4;
            g.fill(left, ty, quarter == 2 ? right : left + (w - 2) / 2, ty + 1, GRADUATION);
        }
        g.fill(left, top, left + 1, bottom, 0x26FFFFFF);
        g.fill(right - 1, top, right, bottom, 0x33000000);
        g.fill(left, top, right, top + 1, 0x33000000);
    }


    // ------------------------------------------------------------- motion

    /**
     * <b>The only clock these screens have.</b> A GUI redraws every frame whether anything moved or
     * not, so "animate" here means one thing: keep a number per named thing and walk it towards
     * whatever the snapshot now says. Kept in {@code Draw} for the same reason the palette is -
     * a second easing curve somewhere else is a second speed the screen moves at.
     *
     * <p>{@link #frame} is called once per render, before anything draws. Everything else reads
     * {@link #delta}, which is capped: a frame that took two seconds (a chunk rebuild, a window
     * drag) must not teleport every animation to its target and read as a glitch.
     */
    private static final java.util.Map<String, float[]> ANIMATED = new java.util.HashMap<>();

    private static long lastFrameAt;

    private static float delta;

    public static void frame() {
        long now = net.minecraft.Util.getMillis();
        delta = lastFrameAt == 0 ? 0 : Math.min((now - lastFrameAt) / 1000.0F, 0.1F);
        lastFrameAt = now;
    }

    /**
     * A number that walks towards its target instead of jumping to it. Exponential rather than
     * linear, so it arrives without a stop: {@code rate} is roughly how much of the remaining gap
     * is closed per second.
     *
     * <p>A first sighting is not animated - it is set. Otherwise every bar on a screen counts up
     * from zero the moment it opens, which reads as the machine filling rather than as the screen
     * arriving.
     */
    public static float approach(String key, float target, float rate) {
        float[] held = ANIMATED.get(key);
        if (held == null) {
            ANIMATED.put(key, new float[] { target, 0 });
            return target;
        }
        held[0] += (target - held[0]) * Math.min(1.0F, rate * delta);
        return held[0];
    }

    /** Puts an animated value back to a known start, so the next {@link #approach} walks from it. */
    public static void reset(String key, float value) {
        ANIMATED.put(key, new float[] { value, 0 });
    }

    /**
     * 1 the frame a value changes, decaying to 0 over {@code seconds}. The whole of "motion on a
     * state change": a pip that goes red is a pixel a player scanning the rack does not see move,
     * and a pip that <em>flashes</em> once is.
     */
    public static float pulse(String key, int value, float seconds) {
        float[] held = ANIMATED.get(key);
        if (held == null) {
            ANIMATED.put(key, new float[] { value, 0 });
            return 0;
        }
        if (held[0] != value) {
            held[0] = value;
            held[1] = 1.0F;
        }
        held[1] = Math.max(0, held[1] - delta / seconds);
        return held[1];
    }

    /** White at {@code alpha}, for a flash laid over whatever is underneath it. */
    public static int flash(float alpha) {
        return (int) (Math.clamp(alpha, 0, 1) * 255) << 24 | 0x00FFFFFF;
    }

    // ------------------------------------------------------------------ text

    /**
     * <b>The one place this mod draws a string, and it is always told the width it has.</b>
     *
     * <p>Text running off its box was fixed twice and came back twice, because both fixes audited a
     * list of call sites. Auditing a list closes the list; it does not close the hole the list came
     * out of. The hole was {@code GuiGraphics#drawString}, which takes a position and no width, so
     * every one of forty-odd call sites was free to be wrong on its own and nothing anywhere could
     * tell. Here a width is not optional, so a string that does not fit is <em>cut</em> rather than
     * spilled - that outcome is structural, not something a call site remembers to ask for.
     * A pre-commit check fails the build on any {@code drawString} outside this file, so the
     * next call site cannot opt out either.
     *
     * <p><b>And a cut string is still a fault</b> - the player cannot read it - so with the F3
     * debug overlay on, every box is outlined: <span>red where the string was cut</span>, faint
     * cyan where it fit. One screenshot of a screen then shows every overflow at once, and a box
     * drawn wider than the panel it sits in shows up in the same picture, which is the fault
     * clipping alone cannot catch. F3 rather than a config or a keybind: it is already the
     * game's "show me the internals" toggle, and it costs one field read. Vanilla only accepts F3
     * with no screen open, so it is pressed in the world and the screen opened after.
     *
     * @return true when the whole string fitted; false when it was cut, so the caller can offer
     *         the whole of it in a tooltip
     */
    public static boolean text(GuiGraphics g, Font font, String s, int px, int py, int room,
        int colour) {
        return draw(g, font, s, px, px, py, room, colour);
    }

    /** Right-aligned: the box ends at {@code rightX}, and a cut string still starts inside it. */
    public static boolean textRight(GuiGraphics g, Font font, String s, int rightX, int py,
        int room, int colour) {
        return draw(g, font, s, rightX - room, rightX - Math.min(room, width(font, s)), py, room,
            colour);
    }

    /** Centred on {@code centreX}, inside a box of {@code room} centred on the same point. */
    public static boolean textCentre(GuiGraphics g, Font font, String s, int centreX, int py,
        int room, int colour) {
        return draw(g, font, s, centreX - room / 2,
            centreX - Math.min(room, width(font, s)) / 2, py, room, colour);
    }

    /**
     * {@code boxX} is where the width the string was <em>given</em> starts; {@code textX} is where
     * the string itself lands inside it. They differ for anything not left-aligned, and the outline
     * must follow the box rather than the text - an outline drawn from the text would sit further
     * right than the space the string was actually allowed, which is the one thing the outline
     * exists to show. It said so on its own first screenshot.
     */
    private static boolean draw(GuiGraphics g, Font font, String s, int boxX, int textX, int py,
        int room, int colour) {
        boolean fits = width(font, s) <= room;
        g.drawString(font, styled(fits ? s : cut(font, s, room)).getVisualOrderText(),
            textX, py, colour, false);
        box(g, boxX, py, room, font.lineHeight, fits);
        return fits;
    }

    /**
     * <b>This mod's own typeface, and the whole of what it took to get one.</b>
     *
     * <p>Minecraft has shipped a TrueType glyph provider since 1.13: a font is a resource like any
     * other, {@code assets/workbay/font/ui.json} names a {@code .ttf} and an oversample, and the
     * game bakes antialiased glyphs from it at load. Nothing is patched and no library is needed.
     * A string is drawn in it by carrying the font on its {@code Style}, which is why this had to
     * be here and nowhere else - every string in the mod already comes through this method, so the
     * whole screen changed face for the price of one wrapper.
     *
     * <p>What it does <em>not</em> buy: vanilla's own widgets. The rename box, the search box and
     * every tooltip frame the game draws for us are still in the bitmap font, so a screen that
     * switches typeface switches only the half of itself this file draws.
     */
    public static final net.minecraft.resources.ResourceLocation UI_FONT =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("workbay", "ui");

    private static Component styled(String s) {
        return Component.literal(s).withStyle(style -> style.withFont(UI_FONT));
    }

    /**
     * How wide a string is <em>in the font it will be drawn in</em>. Public because the pages do
     * their own packing off it, and a layout measured in one font and drawn in another is a layout
     * that is wrong everywhere at once.
     */
    public static int width(Font font, String s) {
        return font.width(styled(s));
    }

    /**
     * The longest head of a string that fits, plus the ellipsis. Vanilla's
     * {@code plainSubstrByWidth} measures in the default font and would cut in the wrong place, so
     * the cut is walked instead - once, on a string that did not fit, which is rare and short.
     */
    private static String cut(Font font, String s, int room) {
        int fit = Math.max(0, room - width(font, ELLIPSIS));
        int end = s.length();
        while (end > 0 && width(font, s.substring(0, end)) > fit) {
            end--;
        }
        return s.substring(0, end) + ELLIPSIS;
    }

    /**
     * A sentence too long for one line, wrapped to {@code room} and drawn down from {@code py}.
     * The wrapped form is the only kind of long text on these screens that is not a fault, so it
     * says so here rather than each caller splitting by hand and hoping the widths match.
     */
    public static void wrapped(GuiGraphics g, Font font, Component text, int px, int py, int room,
        int colour) {
        List<net.minecraft.util.FormattedCharSequence> lines =
            font.split(text.copy().withStyle(style -> style.withFont(UI_FONT)), room);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), px, py + i * 10, colour, false);
            box(g, px, py + i * 10, room, font.lineHeight, true);
        }
    }

    /**
     * A tooltip, wrapped and with its first line bolded.
     *
     * <p>This mod's tooltips are written as full sentences (SPEC.md §4: "tooltips are where this
     * mod's text budget is spent"), and {@code renderComponentTooltip} does not wrap - one of them
     * unbroken is half the screen wide. Every tooltip is (title, explanation), the convention
     * vanilla's own item tooltips use; the title is separated by <em>colour</em> - {@link #TEXT}
     * over {@link #TEXT_DIM} - and never by bold.
     *
     * <p><b>Nothing in this mod may ask for bold, and this is where that was learnt.</b> Vanilla
     * has no bold face: {@code Font#renderChar} draws the same glyph a second time at
     * {@code x + getBoldOffset()}, which is one pixel. On the bitmap font, whose thinnest stroke
     * is a whole pixel, the copy overlaps and reads as weight. On an antialiased TTF at 9.5px a
     * comma and a slash <em>are</em> one pixel, so the copy lands beside the original rather than
     * over it and the player reads {@code 1,,598,,000 // 1,600,000}. Photographed on a power
     * tooltip; OPEN_ISSUES #78, whose investigation went to the font's {@code size} and
     * {@code oversample} because the grep that cleared "fake bold" looked for
     * {@code ChatFormatting.BOLD} and this line said {@code withBold(true)}.
     * The same pre-commit check is what keeps it deleted.
     *
     * <p>Here rather than on one screen because there are two screens that draw tooltips, and a
     * rule that lives on one of them is a rule the other gets wrong.
     */
    public static List<net.minecraft.util.FormattedCharSequence> tooltip(Font font,
        List<Component> lines) {
        List<net.minecraft.util.FormattedCharSequence> wrapped = new java.util.ArrayList<>();
        for (Component line : lines) {
            wrapped.addAll(font.split(line.copy().withStyle(style -> style.withFont(UI_FONT)),
                TOOLTIP_WIDTH));
        }
        return wrapped;
    }

    /**
     * <b>The tooltip frame, drawn by this mod rather than borrowed from the game.</b>
     *
     * <p>It is the last square-cornered bitmap-font rectangle on these screens, and it was the
     * loudest one: a tooltip is what the player is looking straight at while they read it, so a
     * purple-bordered vanilla box popping out of a rounded, antialiased panel is the seam nobody
     * can miss. {@code GuiGraphics#renderTooltip} owns its own frame and its own text pass, so the
     * only way to change either is not to call it.
     *
     * <p>Vanilla's own tooltips stay vanilla and should: an item's tooltip belongs to the game,
     * and a mod restyling every tooltip in the pack is what the Modern UI option was rejected for.
     * This draws the ones <em>this mod</em> writes, on <em>this mod's</em> screens.
     *
     * <p>Positioned the way vanilla positions one - below and right of the cursor, flipped when it
     * would leave the window - and drawn at Z 400, above every item sprite a page renders.
     */
    public static void tooltip(GuiGraphics g, Font font,
        List<net.minecraft.util.FormattedCharSequence> lines, int mouseX, int mouseY,
        int screenW, int screenH) {
        if (lines.isEmpty()) {
            return;
        }
        int text = 0;
        for (var line : lines) {
            text = Math.max(text, font.width(line));
        }
        int w = text + TOOLTIP_PAD * 2;
        // Ten between lines, which is the pitch {@link #wrapped} uses and the pitch vanilla's own
        // tooltip uses. At font.lineHeight the wrapped sentences these tooltips are made of close
        // up into a block.
        int h = lines.size() * 10 + TOOLTIP_PAD * 2 - 2;
        int x = mouseX + 12;
        int y = mouseY - 12;
        if (x + w > screenW) {
            x = Math.max(2, mouseX - 16 - w);
        }
        y = Math.clamp(y, 2, Math.max(2, screenH - h - 2));
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        shadow(g, x, y, w, h, RADIUS);
        round(g, x, y, w, h, RADIUS, 0xF21E2128, 0xF2141619);
        rim(g, x, y, w, h, RADIUS, 0x40FFFFFF, 0x60000000);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), x + TOOLTIP_PAD,
                y + TOOLTIP_PAD + i * 10, i == 0 ? TEXT : TEXT_DIM, false);
        }
        g.pose().popPose();
    }

    /** Room around a tooltip's text. Six, so a rounded corner has something to be rounded out of. */
    private static final int TOOLTIP_PAD = 6;

    private static final int TOOLTIP_WIDTH = 200;

    private static final String ELLIPSIS = "…";

    /** The debug outline. Costs one boolean read per string when it is off, which it normally is. */
    private static void box(GuiGraphics g, int px, int py, int room, int h, boolean fits) {
        if (!Minecraft.getInstance().getDebugOverlay().showDebugScreen()) {
            return;
        }
        int colour = fits ? 0x5533D6D6 : 0xFFFF0000;
        int top = py - 1;
        int bottom = py + h;
        g.fill(px, top, px + room, top + 1, colour);
        g.fill(px, bottom, px + room, bottom + 1, colour);
        g.fill(px, top, px + 1, bottom + 1, colour);
        g.fill(px + room - 1, top, px + room, bottom + 1, colour);
    }

    public static int roleColour(FaceConfig.Role role) {
        return switch (role) {
            case INPUT -> GREEN;
            case OUTPUT -> BLUE;
            case NONE -> GREY;
        };
    }

    /** Compact energy: 12.4k rather than 12400, which does not fit and nobody reads anyway. */
    public static String compact(int value) {
        if (value < 10_000) {
            return String.valueOf(value);
        }
        if (value < 10_000_000) {
            return (value / 100 / 10.0) + "k";
        }
        return (value / 100_000 / 10.0) + "M";
    }

    /**
     * The full figure, grouped. For tooltips, where there is room and the player asked.
     *
     * <p><b>{@code Locale.ROOT}, not the default one.</b> {@code String.format("%,d", n)} groups
     * with whatever the JVM's locale says, so the same buffer reads {@code 1,600,000} here and
     * {@code 1.600.000} on a German client and {@code 1 600 000} on a French one - three different
     * numbers on one screenshot, and one of those separators is a narrow no-break space the mod's
     * font has no glyph for. Nothing else in this mod is locale-sensitive and this should not be.
     */
    public static String exact(int value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }
}
