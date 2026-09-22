package com.neryos.workbay.client.screen;

import com.neryos.workbay.bus.BusConfig;
import com.neryos.workbay.bus.BusRunner;
import com.neryos.workbay.menu.WorkbaySnapshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Screen 2 - the flow map. SPEC.md §4.
 *
 * <p><b>A canvas, not three columns.</b> Three fixed columns can draw one hop and no more: a chest
 * feeding a bay that feeds another bay that fills a barrel is four steps, and in three columns the
 * middle one has to be two of them at once. So the boxes are free objects relaxed into place, the
 * panel grows to whatever the flow needs, and a link that goes back the way it came is drawn
 * going back.
 *
 * <p>A <b>dashed blue</b> edge never leaves the Workbay; a solid one crosses the world boundary,
 * and stalled draws amber. Bay nodes carry the machine's own item, because an eighty-pixel node
 * cannot hold "Metallurgic Infuser" and the sprite is what a player recognises first.
 */
class FlowPage extends WorkbayPage {

    /** The panel is one size again. What used to grow is now the view onto something bigger. */
    private static final int WIDTH = 320;

    private static final int MARGIN = 12;
    /** Room between two columns, which is all an arrow gets to say which way it points. */
    private static final int GAP = 22;
    private static final int NODE_H = FlowLayout.NODE_H;
    private static final int PITCH = 22;
    private static final int TOP_Y = 26;
    private static final int NODE_W = 100;

    /**
     * One box: a bay of this Workbay, or a block somewhere that a link reaches.
     *
     * @param tip what hovering it says. Built here rather than at draw time because everything it
     *            needs - which bay, which block, where it stands - is in hand while the map is
     *            being assembled and gone by the time the box is a rectangle.
     */
    private record Node(Component label, @Nullable ResourceLocation icon, boolean bay,
        List<Component> tip, int px, int py) {}

    /** One link, as the two boxes it joins. */
    private record Edge(int[] xs, int[] ys, int colour, int dash, boolean running) {}

    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();

    /** Header, the gap under the canvas, three legend rows, and the bottom margin. */
    private static final int CHROME = TOP_Y + 8 + 3 * 12 + 8;

    /** The canvas, fitted to the window rather than written down: the shared panel height less the chrome. */
    private final int canvasH;

    private final int nodeW;
    private final int graphW;
    private final int graphH;

    /** What the view is doing right now: how far in, and where over the graph it is looking. */
    private float zoom = 1.0F;
    private float panX;
    private float panY;
    private boolean dragging;

    FlowPage(WorkbayScreen screen) {
        super(screen);
        // The canvas is whatever the shared panel height leaves after the chrome, so this page is
        // exactly as tall as BAYS and the tab strip does not move between them (#89). That height
        // is already clamped to what the window has (OPEN_ISSUES #37).
        this.canvasH = screen.panelHeight() - CHROME;
        WorkbaySnapshot snap = screen.snapshot();

        // ------------------------------------------------------------------ what is on the map
        // A key per box, so the same target reached by two links is one node with two edges into
        // it, which is the difference between a map and a list drawn sideways.
        Map<String, Integer> index = new HashMap<>();
        List<Component> labels = new ArrayList<>();
        List<ResourceLocation> icons = new ArrayList<>();
        List<Boolean> isBay = new ArrayList<>();
        List<List<Component>> tips = new ArrayList<>();
        List<int[]> wires = new ArrayList<>();
        List<int[]> style = new ArrayList<>();

        for (WorkbaySnapshot.Bay bay : snap.bays()) {
            if (bay.hosted().isPresent()) {
                index.put("bay:" + bay.index(), labels.size());
                labels.add(bayName(bay));
                icons.add(bay.hosted().orElse(null));
                isBay.add(true);
                tips.add(bayTip(bay));
            }
        }
        for (WorkbaySnapshot.Link link : snap.links()) {
            Integer bayNode = index.get("bay:" + link.config().bay());
            if (bayNode == null) {
                continue;
            }
            boolean internal = isInternal(snap, link);
            // An internal link's far end is a bay, and it is the *same* bay node the middle column
            // already drew. That is what makes a chain a chain rather than the same machine drawn
            // twice under two different names.
            String key = internal ? "bay:" + link.targetBay().orElse(-1)
                : "at:" + link.config().target().pos().asLong();
            Integer far = index.get(key);
            if (far == null) {
                if (internal) {
                    continue;
                }
                far = labels.size();
                index.put(key, far);
                labels.add(targetName(link));
                tips.add(targetTip(link));
                // The target's own block, when this client happens to know it. A map of eight
                // boxes reading "Connector, Connector, Connector" is a map of one thing; the
                // sprite is what tells a barrel from a chest from a machine at a glance.
                icons.add(link.targetBlock()
                    .filter(id -> !id.equals(ResourceLocation.withDefaultNamespace("air")))
                    .orElse(null));
                isBay.add(false);
            }
            boolean insert = link.config().mode() == BusConfig.Mode.INSERT;
            wires.add(insert ? new int[] {bayNode, far} : new int[] {far, bayNode});
            style.add(new int[] {internal ? Draw.BLUE : colourFor(link.status()), internal ? 3 : 1,
                link.status() == BusRunner.BusStatus.RUNNING ? 1 : 0});
        }

        // ------------------------------------------------------------------ where each one goes
        // FlowLayout does the whole of it. See the class: it is the layered algorithm every flow
        // editor runs, and the two phases that matter here are the two this screen used to skip --
        // a port per edge on a box's side, and a track per vertical run in a channel.
        int n = labels.size();
        // As wide as the widest name asks for, up to a ceiling: at a fixed hundred the box cut
        // "Enrichment Chamber" and "Rotary Condensentrator" on every map that had one, and a map
        // is the one page whose boxes carry nothing but the name. The fit-to-view below pays for
        // it in zoom, which is the right currency -- a smaller whole map beats a cut name.
        // OPEN_ISSUES #87. The label's room is the box less its icon and two margins (node()).
        int widest = labels.stream().mapToInt(l -> Draw.width(screen.font(), l.getString()))
            .max().orElse(0);
        this.nodeW = Math.clamp(widest + 24, n > 12 ? 84 : NODE_W, 140);
        FlowLayout layout = new FlowLayout(n, wires, nodeW);
        for (int i = 0; i < n; i++) {
            nodes.add(new Node(labels.get(i), icons.get(i), isBay.get(i), tips.get(i),
                layout.nodeX[i], layout.nodeY[i]));
        }
        for (int i = 0; i < wires.size(); i++) {
            edges.add(new Edge(layout.edgeXs.get(i), layout.edgeYs.get(i), style.get(i)[0],
                style.get(i)[1], style.get(i)[2] == 1));
        }
        this.graphW = layout.width;
        this.graphH = layout.height;

        // Opens showing all of it, however big it is, and never bigger than life size. Everything
        // after that is the player's: the wheel zooms about the pointer, a drag moves the view.
        // Eight pixels of air inside the frame, because a fit that touches the border reads as a
        // graph that has been cut off.
        this.zoom = Math.clamp(Math.min((WIDTH - 2 * MARGIN - 8) / (float) Math.max(1, graphW),
            (canvasH - 8) / (float) Math.max(1, graphH)), MIN_ZOOM, 1.0F);
        centre();
    }

    /** Puts the whole graph in the middle of the view at the current zoom. */
    private void centre() {
        panX = ((WIDTH - 2 * MARGIN) / zoom - graphW) / 2;
        panY = (canvasH / zoom - graphH) / 2;
    }

    private static final float MIN_ZOOM = 0.35F;
    private static final float MAX_ZOOM = 2.0F;

    @Override
    int width() {
        return WIDTH;
    }

    @Override
    int height() {
        return canvasH + CHROME;
    }

    private int viewLeft() {
        return x(MARGIN);
    }

    private int viewTop() {
        return y(TOP_Y);
    }

    private int viewW() {
        return WIDTH - 2 * MARGIN;
    }

    /** Graph x to screen x. Only the tooltips need it; everything drawn goes through the pose. */
    private int screenX(int gx) {
        return Math.round(viewLeft() + (gx + panX) * zoom);
    }

    private int screenY(int gy) {
        return Math.round(viewTop() + (gy + panY) * zoom);
    }

    private boolean inside(double mouseX, double mouseY) {
        return mouseX >= viewLeft() && mouseX < viewLeft() + viewW()
            && mouseY >= viewTop() && mouseY < viewTop() + canvasH;
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        header(g, mouseX, mouseY, "FLOW");
        // What the map is and how to drive it, on the page's own title. It used to be the canvas's
        // tooltip, which meant pointing anywhere in the empty half of the graph raised a paragraph
        // about panning -- every time, over the thing you were trying to look at. A description is
        // for asking about once; a box you are pointing at is what the cursor is for.
        screen.hit(x(8), y(6), 90, 16, () -> { },
            WorkbayScreen.gui("flow.canvas"), WorkbayScreen.gui("flow.canvas.tip"));
        Draw.well(g, viewLeft(), viewTop(), viewW(), canvasH);
        // Registered with no tooltip: it exists to swallow clicks on the empty canvas, not to say
        // anything. Empty space answers nothing.
        screen.hit(viewLeft(), viewTop(), viewW(), canvasH, () -> { });
        // The gesture, written on the thing it drives, faint, in the corner. It was only in the
        // title's tooltip, which is a place nobody hovers: the map is the one page in the mod you
        // cannot use without knowing something that nothing on it said. Create writes "Scroll to
        // Modify" on every control it applies to for exactly this reason.
        text(g, WorkbayScreen.gui("flow.drive"), viewLeft() + 5,
            viewTop() + canvasH - 11, viewW() - 10, Draw.TEXT_FAINT);
        if (nodes.isEmpty()) {
            text(g, WorkbayScreen.gui("flow.empty"), viewLeft() + 6, viewTop() + 8, viewW() - 12,
                Draw.TEXT_FAINT);
            legend(g);
            return;
        }

        // Clipped to the well, so a flow bigger than the view is cut off at its edge instead of
        // drawn over the legend and out across the world behind the screen.
        g.enableScissor(viewLeft() + 1, viewTop() + 1, viewLeft() + viewW() - 1,
            viewTop() + canvasH - 1);
        g.pose().pushPose();
        g.pose().translate(viewLeft(), viewTop(), 0);
        g.pose().scale(zoom, zoom, 1.0F);
        g.pose().translate(panX, panY, 0);
        for (Edge edge : edges) {
            route(g, edge);
        }
        for (Node node : nodes) {
            node(g, node);
        }
        g.pose().popPose();
        g.disableScissor();

        // The tooltips live outside the pose, because the hit list is in screen pixels. Registered
        // only for what is actually on screen, or a box panned out of view would still answer.
        for (Node node : nodes) {
            int sx = screenX(node.px());
            int sy = screenY(node.py());
            int sw = Math.round(nodeW * zoom);
            int sh = Math.round(NODE_H * zoom);
            if (sx + sw > viewLeft() && sx < viewLeft() + viewW()
                && sy + sh > viewTop() && sy < viewTop() + canvasH) {
                // <b>Clamped to the canvas, not merely tested against it.</b> The map itself is
                // scissored, so a node hanging off the edge is cut -- but its hit region was the
                // node's full size, and the hover ring is drawn from the hit region. A box half
                // off the right edge lit a blue outline out across the world behind the screen,
                // and its tooltip was raised by pointing at nothing. Found in play.
                int hx = Math.max(sx, viewLeft() + 1);
                int hy = Math.max(sy, viewTop() + 1);
                screen.hit(hx, hy,
                    Math.min(sx + sw, viewLeft() + viewW() - 1) - hx,
                    Math.min(sy + sh, viewTop() + canvasH - 1) - hy,
                    () -> { }, node.tip().toArray(new Component[0]));
            }
        }
        legend(g);
    }

    @Override
    boolean scrolled(double mouseX, double mouseY, double delta) {
        if (!inside(mouseX, mouseY)) {
            return false;
        }
        // About the pointer, not about the corner: zooming towards what you are looking at is the
        // whole difference between a canvas and a pair of sliders.
        float was = zoom;
        float now = Math.clamp(zoom * (delta > 0 ? 1.25F : 0.8F), MIN_ZOOM, MAX_ZOOM);
        if (now == was) {
            return true;
        }
        double overX = (mouseX - viewLeft()) / was - panX;
        double overY = (mouseY - viewTop()) / was - panY;
        zoom = now;
        panX = (float) ((mouseX - viewLeft()) / now - overX);
        panY = (float) ((mouseY - viewTop()) / now - overY);
        clampPan();
        return true;
    }

    /**
     * <b>Keeps the map where it can be seen</b> (OPEN_ISSUES #124). The zoom is about the pointer,
     * and nothing bounded the view after one: each notch towards a point right of centre pushed
     * the left of the map further out, and two notches in the whole graph had drifted off the left
     * edge. So after every zoom and every drag, per axis: a map that fits stays wholly inside the
     * view, and a map bigger than the view always covers it, never leaving an empty band on one
     * side while the other side is cut.
     */
    private void clampPan() {
        panX = clampAxis(panX, graphW, viewW() / zoom);
        panY = clampAxis(panY, graphH, canvasH / zoom);
    }

    private static float clampAxis(float pan, int graph, float view) {
        float slack = view - graph;
        return slack >= 0 ? Math.clamp(pan, 0, slack) : Math.clamp(pan, slack, 0);
    }

    @Override
    boolean mousePressed(double mouseX, double mouseY, int button) {
        dragging = button == 0 && inside(mouseX, mouseY);
        return dragging;
    }

    @Override
    boolean mouseDragged(double dragX, double dragY) {
        if (!dragging) {
            return false;
        }
        panX += (float) (dragX / zoom);
        panY += (float) (dragY / zoom);
        // Never so far that the graph leaves the window: a canvas you can lose is one a player has
        // to close and reopen to get back.
        clampPan();
        return true;
    }

    @Override
    boolean mouseReleased(double mouseX, double mouseY) {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    /**
     * One box. A bay is drawn in the panel's own light shade, with a blue cap on its left edge and
     * its machine's item; a block out in the world is drawn in a well with whatever item the client
     * knows it to be. The whole label goes in a tooltip, because at this width most machine names
     * are cut and the cut is where mod names usually differ.
     */
    private void node(GuiGraphics g, Node node) {
        int px = node.px();
        int py = node.py();
        // Draw's own rounding, not this page's. A box on the map had its four corner pixels
        // knocked off by hand while every other shape in the mod was being cut on a real curve --
        // one file with two ideas of what a rounded rectangle is.
        int fill = node.bay() ? Draw.PANEL_LIGHT : Draw.WELL;
        Draw.round(g, px, py, nodeW, NODE_H, 4, Draw.EDGE_DARK);
        Draw.round(g, px + 1, py + 1, nodeW - 2, NODE_H - 2, 3,
            Draw.mix(fill, 0xFFFFFFFF, 0.10F), Draw.mix(fill, 0xFF000000, 0.10F));
        if (node.bay()) {
            g.fill(px + 1, py + 3, px + 3, py + NODE_H - 3, Draw.BLUE);
        }
        int textX = px + 5;
        if (node.icon() != null) {
            var item = BuiltInRegistries.ITEM.get(node.icon());
            if (item != Items.AIR) {
                g.pose().pushPose();
                g.pose().translate(px + (node.bay() ? 5 : 4), py + 3, 0);
                g.pose().scale(0.75F, 0.75F, 1.0F);
                g.renderItem(new ItemStack(item), 0, 0);
                g.pose().popPose();
                textX = px + (node.bay() ? 19 : 18);
            }
        }
        // A bay is one of this Workbay's own and a target is somebody else's block, so they are
        // not the same weight. Every box was TEXT_DIM, which made the machines the map is about
        // exactly as loud as the chests they feed.
        text(g, node.label(), textX, py + 5, px + nodeW - 4 - textX,
            node.bay() ? Draw.TEXT : Draw.TEXT_DIM);
    }

    /** One edge, along the polyline {@link FlowLayout} routed for it, head on its last leg. */
    private void route(GuiGraphics g, Edge edge) {
        int[] xs = edge.xs();
        int[] ys = edge.ys();
        for (int leg = 0; leg + 1 < xs.length; leg++) {
            line(g, xs[leg], ys[leg], xs[leg + 1], ys[leg + 1], edge.colour(), edge.dash());
        }
        int last = xs.length - 1;
        if (last > 0) {
            head(g, xs[last], ys[last], Integer.signum(xs[last] - xs[last - 1]),
                Integer.signum(ys[last] - ys[last - 1]), edge.colour());
        }
        if (edge.running()) {
            travel(g, xs, ys);
        }
    }

    /**
     * A solid triangle, five long and five across at the base, pointing along one of the four
     * directions a right-angled route can arrive from.
     */
    private static void head(GuiGraphics g, int px, int py, int dx, int dy, int colour) {
        for (int step = 0; step < 5; step++) {
            int spread = (4 - step) / 2;
            if (dx > 0) {
                g.fill(px - 5 + step, py - spread, px - 4 + step, py + spread + 1, colour);
            } else if (dx < 0) {
                g.fill(px + 4 - step, py - spread, px + 5 - step, py + spread + 1, colour);
            } else if (dy > 0) {
                g.fill(px - spread, py - 5 + step, px + spread + 1, py - 4 + step, colour);
            } else {
                g.fill(px - spread, py + 4 - step, px + spread + 1, py + 5 - step, colour);
            }
        }
    }

    /** Three pips walking the route, so a link that is carrying something looks like it is. */
    private void travel(GuiGraphics g, int[] xs, int[] ys) {
        double total = 0;
        double[] leg = new double[Math.max(1, xs.length - 1)];
        for (int i = 0; i + 1 < xs.length; i++) {
            leg[i] = Math.abs(xs[i + 1] - xs[i]) + Math.abs(ys[i + 1] - ys[i]);
            total += leg[i];
        }
        if (total <= 0) {
            return;
        }
        long now = net.minecraft.Util.getMillis();
        for (int pip = 0; pip < 3; pip++) {
            double walked = (now / 9.0 + pip * total / 3) % total;
            for (int i = 0; i + 1 < xs.length; i++) {
                if (walked > leg[i]) {
                    walked -= leg[i];
                    continue;
                }
                double along = leg[i] == 0 ? 0 : walked / leg[i];
                int mx = (int) Math.round(xs[i] + (xs[i + 1] - xs[i]) * along);
                int my = (int) Math.round(ys[i] + (ys[i + 1] - ys[i]) * along);
                g.fill(mx - 1, my - 1, mx + 2, my + 2, Draw.GREEN);
                break;
            }
        }
    }

    /**
     * @param dash 1 draws solid; anything higher skips two pixels in every {@code dash}, which is
     *             what tells a bay-to-bay flow apart from one that leaves the block
     */
    private void line(GuiGraphics g, int x1, int y1, int x2, int y2, int colour, int dash) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i++) {
            if (dash > 1 && (i / 2) % dash != 0) {
                continue;
            }
            int px = x1 + (x2 - x1) * i / Math.max(1, steps);
            int py = y1 + (y2 - y1) * i / Math.max(1, steps);
            g.fill(px, py, px + 1, py + 1, colour);
        }
    }

    /**
     * Six keys in three rows: two for what a line's shape means and four for what its colour does.
     *
     * <p>The colours were the half that was missing. Half the lines on a resting base are grey, and
     * a grey line with nothing saying what grey is reads as a line the screen forgot to finish.
     */
    private void legend(GuiGraphics g) {
        int py = y(TOP_Y + canvasH + 8);
        int right = WIDTH / 2 + 4;
        int leftRoom = right - MARGIN - 20 - 6;
        int rightRoom = WIDTH - right - 20 - MARGIN;

        rule(g, x(MARGIN), py, Draw.TEXT_DIM, 1);
        text(g, WorkbayScreen.gui("flow.legend.out"), x(MARGIN + 20), py, leftRoom, Draw.TEXT_DIM);
        rule(g, x(MARGIN), py + 12, Draw.BLUE, 3);
        text(g, WorkbayScreen.gui("flow.legend.internal"), x(MARGIN + 20), py + 12, leftRoom,
            Draw.TEXT_DIM);
        // Solid, with a pip on it, because that is what a live edge is: the map draws a running
        // link as a solid run carrying travelling pips, and a dashed sample was the one thing it
        // never looks like -- dashed is what "stays inside" means, two lines above.
        rule(g, x(MARGIN), py + 24, Draw.GREEN, 1);
        g.fill(x(MARGIN + 7), py + 26, x(MARGIN + 10), py + 29, Draw.GREEN);
        text(g, WorkbayScreen.gui("flow.legend.live"), x(MARGIN + 20), py + 24, leftRoom,
            Draw.TEXT_DIM);

        rule(g, x(right), py, Draw.EDGE_LIGHT, 1);
        text(g, WorkbayScreen.gui("flow.legend.resting"), x(right + 20), py, rightRoom,
            Draw.TEXT_DIM);
        rule(g, x(right), py + 12, Draw.AMBER, 1);
        text(g, WorkbayScreen.gui("flow.legend.stalled"), x(right + 20), py + 12, rightRoom,
            Draw.TEXT_DIM);
        rule(g, x(right), py + 24, Draw.RED, 1);
        text(g, WorkbayScreen.gui("flow.legend.broken"), x(right + 20), py + 24, rightRoom,
            Draw.TEXT_DIM);
    }

    /** A sixteen-pixel sample of a line, drawn the way the map draws that line. */
    private void rule(GuiGraphics g, int px, int py, int colour, int dash) {
        for (int i = 0; i < 16; i++) {
            if (dash > 1 && (i / 2) % dash != 0) {
                continue;
            }
            g.fill(px + i, py + 3, px + i + 1, py + 4, colour);
        }
    }

    /**
     * A link is internal when it says so, and <b>not</b> when its target happens to be in the
     * Backshop.
     *
     * <p>Those were the same thing right up until a Connector could stand in a room, and then they
     * were not: a room link's target is a Backshop position, so this called it internal, looked up
     * a bay node for it, found none and {@code continue}d - and the link <em>vanished from the
     * map</em>. Found by drawing the map with one in it. {@code BusConfig#internal} is the field
     * that exists to answer this and it is the only thing that should.
     */
    private static boolean isInternal(WorkbaySnapshot snap, WorkbaySnapshot.Link link) {
        return link.config().internal();
    }

    private static int colourFor(BusRunner.BusStatus status) {
        return switch (status) {
            case RUNNING -> Draw.GREEN;
            case IDLE -> Draw.EDGE_LIGHT;
            case DISABLED, HELD_BY_REDSTONE, DETACHED -> Draw.GREY;
            case TARGET_MISSING, CONNECTOR_GONE -> Draw.RED;
            case TARGET_NOT_LOADED, TARGET_NO_PORT, MACHINE_NO_PORT, MACHINE_NO_FACE,
                NEEDS_RESONATOR, NO_POWER -> Draw.AMBER;
        };
    }

    /**
     * What a bay box says when you point at it: which bay, what is in it, and what it is doing.
     * The name alone was all a box carried, and on a map whose whole point is "where is my stuff
     * going" the box that holds the machine was the one saying least.
     */
    private List<Component> bayTip(WorkbaySnapshot.Bay bay) {
        List<Component> lines = new ArrayList<>();
        Component title = bayName(bay);
        lines.add(title);
        // Only when it is not the title again. A bay nobody has renamed *is* named after the block
        // it holds, so the two lines read "Furnace / Furnace" -- the same fault the LINKS name
        // column was fixed for, printed downwards instead of across.
        bay.hosted()
            .filter(id -> !id.equals(ResourceLocation.withDefaultNamespace("air")))
            .map(FlowPage::name)
            .filter(blockName -> !blockName.getString().equals(title.getString()))
            .ifPresent(blockName -> lines.add(blockName.copy().withStyle(
                net.minecraft.ChatFormatting.GRAY)));
        lines.add(WorkbayScreen.gui("flow.node.bay", bay.index() + 1)
            .copy().withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        return lines;
    }

    /**
     * And what a box out in the world says: the block, where it stands, and which of its faces the
     * link is pinned to. The position is the half a player cannot get anywhere else - the row in
     * LINKS has fifty pixels and spends them on the name.
     */
    private List<Component> targetTip(WorkbaySnapshot.Link link) {
        List<Component> lines = new ArrayList<>();
        lines.add(targetName(link));
        var pos = link.config().target().pos();
        lines.add(WorkbayScreen.gui("flow.node.at", pos.getX(), pos.getY(), pos.getZ())
            .copy().withStyle(net.minecraft.ChatFormatting.GRAY));
        lines.add(WorkbayScreen.gui("flow.node.face", link.config().targetFace()
                .<Component>map(face -> Component.translatable("gui.workbay.links.face."
                    + face.getSerializedName()))
                .orElseGet(() -> WorkbayScreen.gui("links.face.any")))
            .copy().withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        return lines;
    }

    /**
     * What a box out in the world is called: the name the player gave the link, then the target
     * block's own name, then - when this client cannot know it - <b>where</b> it is. Five nodes all
     * reading "not loaded" is a map of one place.
     */
    private Component targetName(WorkbaySnapshot.Link link) {
        if (link.label().isPresent()) {
            return Component.literal(link.label().get());
        }
        // A stage of a chain that happens to be inside a room is named by the room, and by the
        // room alone: a node box is narrower than a LINKS row, the icon on it is already saying
        // which block, and "which room" is the half that differs from stage to stage.
        if (link.targetRoom().isPresent()) {
            return snapshot().roomLabel(link.targetRoom().get());
        }
        return link.targetBlock()
            .filter(id -> !id.equals(ResourceLocation.withDefaultNamespace("air")))
            .map(FlowPage::name)
            .orElseGet(() -> Component.literal(link.config().target().pos().getX() + " "
                + link.config().target().pos().getZ()));
    }

    /** The name the player gave the bay, or the machine's own. */
    private static Component bayName(WorkbaySnapshot.Bay bay) {
        return bay.name().isBlank() ? name(bay.hosted().orElse(null))
            : Component.literal(bay.name());
    }

    private static Component name(ResourceLocation id) {
        if (id == null) {
            return WorkbayScreen.gui("flow.unknown");
        }
        var block = BuiltInRegistries.BLOCK.get(id);
        if (block != net.minecraft.world.level.block.Blocks.AIR) {
            return block.getName();
        }
        var item = BuiltInRegistries.ITEM.get(id);
        return item != Items.AIR ? item.getDescription() : Component.literal(id.getPath());
    }
}
