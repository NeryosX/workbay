package com.neryos.workbay.client.screen;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The mod's whole icon set, as 12x12 pixel grids. SPEC.md §4.
 *
 * <p>SPEC.md calls for an icon atlas rather than text glyphs, and this is that atlas with the PNG
 * left out: same uniform 12x12 grid, drawn straight into the screen. Being data rather than a
 * texture buys two things a file could not - every icon tints to whatever colour the row needs, and
 * changing one is a line of text rather than a round trip through an image editor.
 *
 * <p>{@code #} is the icon, {@code .} is nothing. One string per row, twelve rows, twelve columns.
 */
public final class WBIcons {
    private WBIcons() {}

    /**
     * <b>An icon that is a real item rather than a drawn grid.</b>
     *
     * <p>The grids stay for everything that means an <em>action</em> or a <em>direction</em>: no
     * object means "down", "yes" or "sort", and a picture of one that happens to be near the idea
     * is the trick that put a grass block on "items". What an object <em>can</em> mean is a noun,
     * and where the noun exists in the game already the game's own picture beats a redrawing of
     * it - a player has seen a name tag ten thousand times and has never seen our twelve pixels.
     * The owner's call, after the items icon.
     *
     * <p>Carried as a one-element {@code String[]} so that not one of the thirty call sites, two
     * button helpers or the palette argument has to change: a row of pixels can never begin with
     * {@code @}, so {@link #draw} can tell the two apart in its first line and everything else in
     * this file stays what it was.
     */
    private static String[] item(String id) {
        return new String[] { "@" + id };
    }

    /**
     * <b>An icon that is one of the mod's own 16x16 textures.</b>
     *
     * <p>Twelve pixels in nine colours is a ceiling, not a style. {@link #draw}'s grids can carry
     * nine palette entries and no more, and a twelve-pixel object has no room for a rim, a lit
     * band, a shadow band and a glint -- so three rounds of them came back flat. A texture has the
     * whole palette and four more pixels each way, which is exactly what the game's own item
     * sprites have, and it is generated the same way every other texture this mod owns is.
     *
     * <p>The grids stay for the flat controls -- arrows, tick, cross, plus, power. Those are
     * symbols, and a symbol wants to be flat.
     *
     * <p>Carried as a one-element {@code String[]} for the same reason {@link #item} is: a row of
     * pixels can never begin with {@code ~}, so {@link #draw} tells the three apart in its first
     * lines and not one call site has to change.
     */
    private static String[] texture(String name) {
        return new String[] { "~" + name };
    }

    private static final java.util.Map<String, net.minecraft.resources.ResourceLocation> SHEETS =
        new java.util.HashMap<>();

    private static final java.util.Map<String, net.minecraft.world.item.ItemStack> STACKS =
        new java.util.HashMap<>();

    /**
     * One item sprite, {@code side} pixels wide instead of the sixteen it is drawn at, at the
     * brightness the caller's colour has.
     *
     * <p><b>Dimmed by tinting the sprite, not by laying anything over it.</b> The wash every
     * other disabled thing uses was drawn here, and it landed <em>behind</em> the icon: an item
     * sprite renders on the item renderer's own layer at Z 150 whatever the draw order was, so
     * {@code Draw.disabled} put a dark rounded panel behind the redstone dust instead of taking
     * the dust down -- which is what the whole set looked like it had been designed around.
     * The owner's call: solve it in the icon.
     *
     * <p>It <em>can</em> be solved in the icon, and the note that said otherwise was wrong:
     * {@code GuiGraphics#setColor} sets the shader's colour modulator and
     * {@code renderItem} flushes before it returns, so the multiply reaches the item's own
     * quads. Scaled by the tint's brightness exactly the way {@link #dim} scales a palette
     * colour, so a sprite and a drawn grid on the same disabled row go dark together.
     */
    public static void sprite(GuiGraphics graphics, net.minecraft.world.item.ItemStack stack,
        int x, int y, int side, boolean full) {
        sprite(graphics, stack, x, y, side, full ? 0xFF : FAINT);
    }

    /** How dark {@link Draw#TEXT_FAINT} is against {@link Draw#TEXT}, as the grids read it. */
    private static final int FAINT = 0x76;

    public static void sprite(GuiGraphics graphics, net.minecraft.world.item.ItemStack stack,
        int x, int y, int side, int lit) {
        float tint = Math.min(1.0F, lit / 240.0F);
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(side / 16.0F, side / 16.0F, 1.0F);
        if (tint < 1.0F) {
            graphics.setColor(tint, tint, tint, 1.0F);
        }
        graphics.renderItem(stack, 0, 0);
        if (tint < 1.0F) {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        graphics.pose().popPose();
    }

    /**
     * <b>Upgrade.</b> The mod's own Expansion Plate.
     *
     * <p>It was a chevron over a bar -- the genre's shape for "a step up", and the last drawn
     * glyph in a tab strip whose other entries are the Workbay and an iron door. The plate is the
     * first row of the page the tab opens and the one upgrade every network buys, so the tab is
     * now a picture of what is behind it rather than of the idea of improvement.
     */
    public static final String[] UPGRADE = texture("upgrade");

    /**
     * <b>The flow map.</b> A filled map, because the page is a map and the game has one.
     *
     * <p>The drawn version -- two sources routed into one target -- was a good diagram and a bad
     * tab: at twelve pixels beside two item sprites it read as a fork, and a diagram of a map is
     * a longer way round than the map.
     */
    public static final String[] MAP = texture("map");

    /**
     * <b>Networks.</b> Three nodes on two links -- the picture every network diagram is. The tab
     * borrowed the filter's sheet of paper until the icon set merged, and a sheet on a tab strip
     * read as "documents".
     */
    public static final String[] NETWORKS = texture("networks");

    // ------------------------------------------------------- what a link carries

    /**
     * <b>Fluid, energy and chemical, drawn here and shaded rather than borrowed from the game.</b>
     *
     * <p>They were vanilla item sprites -- a block of redstone, a water bucket, a bottle of
     * dragon's breath. On the LINKS list that reads as borrowed, and each names a <em>particular</em>
     * thing (water, redstone, dragon's breath) where the row means a whole kind. The first
     * redrawing was worse: flat one-colour glyphs next to sixteen-colour item sprites look like
     * programmer art, which is what {@link #ITEM} kept and these three did not.
     *
     * <p>So they are shaded the way an item texture is -- a dark outline, three bands lit from the
     * top left, and their own palette rather than the row's colour. The grids are
     * <b>generated from a silhouette</b>, not typed: hand-shading a 12x12 is how a row ends up
     * eleven characters long, and the silhouette is the only part that has to read. Judged on a
     * rendered mock at 1x, 3x and 8x on the panel grey, which is where a hollow-necked flask
     * turned out to read as a workbench.
     *
     * <p>Being data still buys what a PNG could not: {@link #dim} scales the whole palette, so one
     * of these goes dark with a disabled row instead of staying lit at Z 150 the way a sprite does.
     */
    public static final String[] FLUID = {
        ".....44.....",
        ".....14.....",
        "....4114....",
        "....4114....",
        "...411224...",
        "..41122224..",
        ".4112222234.",
        ".4122222334.",
        "412222233334",
        ".4222233334.",
        "..44233344..",
        "....4444....",
    };
    public static final int[] FLUID_COLOURS =
        { 0xFFEAF7FF, 0xFF7FC9F0, 0xFF3E9BD8, 0xFF1B5E92 };

    /** A bolt. The one shape that means electricity without naming a block that stores it. */
    public static final String[] ENERGY = {
        "......4444..",
        ".....41124..",
        "....41124...",
        "...41124....",
        "..41122244..",
        "..4422234...",
        "....42334...",
        "...42334....",
        "..42334.....",
        ".42334......",
        "..434.......",
        "..44........",
    };
    public static final int[] ENERGY_COLOURS =
        { 0xFFFFF7CE, 0xFFFFDD55, 0xFFEDA317, 0xFF7A4A04 };

    /**
     * A flask: pale glass for the neck and shoulder, violet for what is in it. Two palettes in one
     * -- 1-4 the glass, 5-8 the liquid -- because a substance that is neither an item you stack nor
     * a fluid you pour is read off the <em>container</em>, and a single-colour flask is a jar.
     */
    public static final String[] CHEMICAL = {
        "...444444...",
        "....4114....",
        "....4114....",
        "....4124....",
        "...412224...",
        "..41222224..",
        ".8555566668.",
        "855556666778",
        "855566667778",
        "855666677778",
        ".8666677778.",
        "..88888888..",
    };
    public static final int[] CHEMICAL_COLOURS =
        { 0xFFF4FBFF, 0xFFAFD4E4, 0xFF6E93A6, 0xFF24384A,
          0xFFF0D2FF, 0xFFC77BEA, 0xFF9B45CC, 0xFF6B2492 };

    /**
     * <b>Lock and unlock stay drawn, and they are the one pair that has to.</b> Every other noun
     * in this file is now the game's own picture of itself; vanilla has no padlock, and each
     * candidate names something else -- an iron door is the rooms tab, a barrier is a refusal, a
     * chest is a container. A shackle over a body is the shape the genre agreed on, and it is
     * only ever drawn next to other drawn controls.
     */
    public static final String[] LOCK = texture("lock");

    public static final String[] UNLOCK = texture("unlock");

    /**
     * <b>The trip to a hosted machine.</b> An ender pearl: the one object in the game whose whole
     * meaning is <em>reaching somewhere you are not</em>.
     *
     * <p>ART.md asks this to read as reaching the machine rather than as a doorway, because the
     * one button carries both "open its screen where you stand" and "walk into the bay"
     * (SPEC.md §5) -- and a pearl is the half of that pair a player already owns a word for. It
     * was the last drawn glyph in a button row of five sprites.
     */
    public static final String[] ENTER = texture("enter");

    /**
     * <b>Eject.</b> A piston: the one object in the game whose entire meaning is "push this back
     * out".
     *
     * <p>The drawn box with its lid off was the last hand-drawn glyph in a button row of five
     * sprites -- rename, redstone, copy, paste -- and one drawing among five renders is the
     * inconsistency you see before you read any of them.
     */
    public static final String[] EJECT = texture("eject");

    /**
     * <b>Rename.</b> The thing you rename with, and the one object in the game that means exactly that.
     */
    public static final String[] RENAME = texture("rename");

    /**
     * <b>Redstone, and which of its four modes this bay is on.</b>
     *
     * <p>Dust and torch by turns, which is how the genre has said this since 1.12: the object
     * changes with the meaning and the lighting says whether the gate is doing anything.
     *
     * <ul>
     *   <li><b>Always</b> -- dust, dimmed. Redstone is not part of this bay's answer at all.
     *   <li><b>With a signal</b> -- a torch, lit. It runs when there is power.
     *   <li><b>Without a signal</b> -- the same torch, dimmed. It runs when there is none, so the
     *       unlit torch is not a disabled control, it is the state itself.
     *   <li><b>Pulse</b> -- a repeater, lit: the one vanilla object that means timing rather than
     *       level, and the only mode that spends an edge instead of reading a level.
     * </ul>
     */
    public static String[] redstone(com.neryos.workbay.world.RedstoneMode mode) {
        return switch (mode) {
            case ALWAYS -> REDSTONE_DUST;
            case PULSE -> REDSTONE_PULSE;
            default -> REDSTONE_TORCH;
        };
    }

    /** True while the mode is one the icon should be drawn lit for. See {@link #redstone}. */
    public static boolean redstoneLit(com.neryos.workbay.world.RedstoneMode mode) {
        return mode == com.neryos.workbay.world.RedstoneMode.WITH_SIGNAL
            || mode == com.neryos.workbay.world.RedstoneMode.PULSE;
    }

    /**
     * <b>Back to the machine.</b> The Workbay's own item, because that is the sentence: the three
     * other pages had a tab and BAYS had none, so the only way back was to press the lit tab --
     * which works, is in its tooltip, and was found by nobody. A fourth tab is twenty-two pixels
     * of a header that had them.
     */
    public static final String[] BAYS = item("workbay:workbay");

    private static final String[] REDSTONE_DUST = texture("redstone_dust");
    private static final String[] REDSTONE_TORCH = texture("redstone_torch");
    private static final String[] REDSTONE_PULSE = texture("redstone_pulse");

    /**
     * <b>Copy.</b> Book and quill: the pair reads as copy/paste the way a floppy disk reads as save, and the quill is what tells it from PASTE on the button beside it.
     */
    public static final String[] COPY = texture("copy");

    /**
     * <b>Paste.</b> The written half of that pair.
     */
    public static final String[] PASTE = texture("paste");

    /** Into the machine: the arrow passes through the wall of the box, which is the only part of
     *  the picture that separates it from {@link #EXTRACT}. */
public static final String[] INSERT = {
        "............",
        ".....#######",
        ".....#.....#",
        "..#..#.....#",
        "..##.#.....#",
        "#####......#",
        "#####......#",
        "..##.#.....#",
        "..#..#.....#",
        ".....#.....#",
        ".....#######",
        "............",
    };

    /** Out of the machine, and the mirror of {@link #INSERT} on purpose: the pair only works if
     *  the two are the same drawing seen from opposite sides. */
public static final String[] EXTRACT = {
        "............",
        "#######.....",
        "#.....#.....",
        "#.....#..#..",
        "#.....#..##.",
        "#......#####",
        "#......#####",
        "#.....#..##.",
        "#.....#..#..",
        "#.....#.....",
        "#######.....",
        "............",
    };

    /** Out of the machine, into the target. The machine is the left of a row, the target its right. */
    public static final String[] ARROW_RIGHT = {
        "............",
        "............",
        ".......#....",
        "........#...",
        ".#########..",
        ".##########.",
        ".##########.",
        ".#########..",
        "........#...",
        ".......#....",
        "............",
        "............",
    };

    /**
     * Down the exchange column: what goes in the top slot comes out of the bottom one. The only
     * arrow on these screens that means item flow rather than which end of a link is which.
     */
    public static final String[] ARROW_DOWN = {
        "............",
        "....####....",
        "....####....",
        "....####....",
        "....####....",
        "....####....",
        ".##########.",
        "..########..",
        "...######...",
        "....####....",
        ".....##.....",
        "............",
    };

    /** Out of the target, into the machine. */
    public static final String[] ARROW_LEFT = {
        "............",
        "............",
        "....#.......",
        "...#........",
        "..#########.",
        ".##########.",
        ".##########.",
        "..#########.",
        "...#........",
        "....#.......",
        "............",
        "............",
    };

    public static final String[] CHECK = {
        "............",
        "............",
        "..........#.",
        ".........##.",
        "........##..",
        ".#.....##...",
        ".##...##....",
        "..##.##.....",
        "...###......",
        "....#.......",
        "............",
        "............",
    };

    /**
     * <b>Cross.</b> Two strokes that actually cross.
     *
     * <p>The first one met in a two-pixel waist and read as an hourglass at the size it is drawn.
     * Four pixels through the middle is what makes an X an X.
     */
    public static final String[] CROSS = {
        "............",
        ".##......##.",
        ".###....###.",
        "..###..###..",
        "...######...",
        "....####....",
        "....####....",
        "...######...",
        "..###..###..",
        ".###....###.",
        ".##......##.",
        "............",
    };

    /**
     * <b>Filter.</b> A funnel, one thing going in and one turned away at the rim.
     *
     * <p>The vanilla hopper was tried and rejected: at twelve pixels it is a grey wedge, and a
     * funnel alone says "narrows" rather than "chooses". The two pips are the whole idea -- green
     * over the mouth, red bouncing off the lip -- and they are the only part that has to survive
     * being small, which is why they sit on the rim rather than inside the cone.
     */
    public static final String[] FILTER = texture("filter");

        /**
     * <b>A sheet of paper, drawn in whatever colour the caller asks for.</b> White is a whitelist
     * and near-black is a blacklist, which is one icon for a control that used to spend a hundred
     * and ten pixels on the word.
     */
    public static final String[] PAPER = {
        "..######....",
        "..######3...",
        "..##33###...",
        "..#######3..",
        "..##3333#.3.",
        "..########3.",
        "..##3333#.3.",
        "..########3.",
        "..##333####3",
        "..#########3",
        "..3333333333",
        "............",
    };

    /** Only the fold and the ruled lines are palette; the sheet itself takes the row's colour. */
    public static final int[] PAPER_COLOURS = { 0xFF000000, 0xFF000000, 0xFF6A7383 };

    public static final String[] SORT = texture("sort");


    /**
     * <b>Door.</b> The rooms tab, and a room's way out is a door.
     */
    public static final String[] DOOR = texture("door");

    /**
     * <b>Anchor.</b> A respawn anchor -- vanilla's own block called an anchor, and the only one
     * that means "this place stays real while you are not in it", which is the whole of what the
     * upgrade buys.
     */
    public static final String[] ANCHOR = texture("anchor");

    /**
     * <b>Guest.</b> Rendered as the block model, which is a head: the one icon here that is 3D and is better for it.
     */
    public static final String[] GUEST = texture("guest");

    /** The other half of a stepper. Same bar as PLUS, so the pair reads as one control. */
    public static final String[] MINUS = {
        "............",
        "............",
        "............",
        "............",
        "............",
        "...######...",
        "...######...",
        "............",
        "............",
        "............",
        "............",
        "............",
    };

    public static final String[] PLUS = {
        "............",
        "............",
        "............",
        ".....##.....",
        ".....##.....",
        "...######...",
        "...######...",
        ".....##.....",
        ".....##.....",
        "............",
        "............",
        "............",
    };

    /**
     * On/off. The universal power symbol, and deliberately <b>not</b> a tick: a tick in the picker
     * means "selected for adding" and a tick on a row meant "enabled", which is one control saying
     * two things. SPEC.md §7.
     */
    public static final String[] POWER = {
        "............",
        ".....##.....",
        ".....##.....",
        "..##.##.##..",
        ".##..##..##.",
        ".##......##.",
        ".##......##.",
        ".##......##.",
        "..##....##..",
        "...######...",
        "............",
        "............",
    };

    /**
     * Draws one icon with its top-left corner at (x, y). {@code #} takes {@code argb}; the digits
     * {@code 1}..{@code 9} take {@code palette[0]}..{@code palette[8]}.
     *
     * <p><b>A palette is what made three of these icons possible at all.</b> Items is a bar, a gem
     * and a pinch of dust; in one tint those are a smudge, and in three colours they are three
     * things. The redstone torch is the same story - a blob on a stick is a trophy until the blob
     * is red.
     *
     * <p>A palette colour is still <b>scaled by how bright the tint is</b>, so a coloured icon
     * dims exactly where a monochrome one does: a control drawn with {@link Draw#TEXT_FAINT}
     * must not be the one thing on a disabled row that stays at full strength.
     */
    /**
     * An icon's own colours, so a call site cannot draw one without them.
     *
     * <p>The palette used to be an argument, which meant every place that drew the redstone torch
     * had to remember to pass {@code REDSTONE_COLOURS} and a place that forgot drew nothing at
     * all. A coloured icon is coloured wherever it is drawn; an explicit palette still wins, for
     * the one control that recolours itself.
     */
    private static final java.util.Map<String[], int[]> PALETTES = java.util.Map.of(
        PAPER, PAPER_COLOURS,
        FLUID, FLUID_COLOURS, ENERGY, ENERGY_COLOURS, CHEMICAL, CHEMICAL_COLOURS);

    public static void draw(GuiGraphics graphics, String[] icon, int x, int y, int argb,
        int... palette) {
        if (palette.length == 0) {
            palette = PALETTES.getOrDefault(icon, palette);
        }
        int lit = Math.max(argb >> 16 & 0xFF, Math.max(argb >> 8 & 0xFF, argb & 0xFF));
        // A row of pixels cannot start with '~'; one of the mod's own icon textures is nothing
        // else. Sixteen pixels drawn centred on the twelve the call site asked for, so a set of
        // textures and a set of grids sit on the same centre line and no call site moved.
        if (icon.length == 1 && icon[0].charAt(0) == '~') {
            float tint = Math.min(1.0F, lit / 240.0F);
            if (tint < 1.0F) {
                graphics.setColor(tint, tint, tint, 1.0F);
            }
            graphics.blit(SHEETS.computeIfAbsent(icon[0], id ->
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        "workbay", "textures/gui/icon/" + id.substring(1) + ".png")),
                x - 2, y - 2, 0, 0, 16, 16, 16, 16);
            if (tint < 1.0F) {
                graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            }
            return;
        }
        // A row of pixels cannot start with '@'; an item icon is nothing else. See #item.
        if (icon.length == 1 && icon[0].startsWith("@")) {
            sprite(graphics, STACKS.computeIfAbsent(icon[0], id ->
                new net.minecraft.world.item.ItemStack(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.resources.ResourceLocation.parse(id.substring(1))))),
                x, y, 12, lit);
            return;
        }
        for (int row = 0; row < icon.length; row++) {
            String line = icon[row];
            int run = -1;
            int runColour = 0;
            for (int col = 0; col <= line.length(); col++) {
                char at = col < line.length() ? line.charAt(col) : '.';
                int colour = at == '#' ? argb
                    : at >= '1' && at <= '9' && at - '1' < palette.length
                        ? dim(palette[at - '1'], lit) : 0;
                if (colour != 0 && run >= 0 && colour != runColour) {
                    graphics.fill(x + run, y + row, x + col, y + row + 1, runColour);
                    run = -1;
                }
                if (colour != 0 && run < 0) {
                    run = col;
                    runColour = colour;
                } else if (colour == 0 && run >= 0) {
                    // One fill per horizontal run rather than per pixel: a solid icon costs a
                    // dozen quads instead of a hundred and forty-four.
                    graphics.fill(x + run, y + row, x + col, y + row + 1, runColour);
                    run = -1;
                }
            }
        }
    }

    /** A palette colour at the tint's own brightness. See {@link #draw}. */
    private static int dim(int argb, int lit) {
        int out = argb & 0xFF000000;
        for (int shift = 0; shift < 24; shift += 8) {
            out |= (argb >>> shift & 0xFF) * lit / 255 << shift;
        }
        return out;
    }
}
