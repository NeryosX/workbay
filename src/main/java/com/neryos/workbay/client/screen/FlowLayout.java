package com.neryos.workbay.client.screen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Where the flow map's boxes go and how its arrows get there. No drawing, no Minecraft.
 *
 * <p>The <b>layered algorithm</b> - Sugiyama, Tagawa and Toda - in the five phases Eclipse ELK and
 * Graphviz's {@code dot} run it in: <b>break cycles, assign layers, minimise crossings, place
 * nodes, route edges</b>. Phase five is written against ELK's own
 * {@code OrthogonalRoutingGenerator}, which follows Sander's <i>Layout of directed hypergraphs with
 * orthogonal hyperedges</i> (GD '03).
 *
 * <p>Three things in that phase were guessed at first, and they are why arrows used to sit on
 * arrows.
 *
 * <p><b>An edge gets a port of its own where it arrives.</b> Sander spreads a hyperedge's ends
 * along the side they meet; skipping that put every arrival on one point, so three links into one
 * bay shared their last leg and one status colour covered all three. {@link #CLEAN_RUN} is what
 * that leg is worth, and the channel is sized from it.
 *
 * <p><b>A vertical run belongs to a source, not to an edge.</b> Everything leaving one box shares
 * one vertical line and branches off it - Sander's <i>hyperedge segment</i>. A line per edge put
 * six near-identical verticals in one channel: six things to tell apart instead of one fork to
 * read.
 *
 * <p><b>Which run goes in which slot is decided by a dependency graph, not by sorting.</b> Each
 * pair is costed both ways round: a <i>conflict</i> is two horizontals at the same height, which
 * would overlap in the gap between the verticals; a <i>crossing</i> is a horizontal ending inside
 * the other run's vertical span. ELK weighs a crossing sixteen times a conflict. The cheaper order
 * becomes "A is left of B", cycles are broken, and a topological numbering turns what is left into
 * slot numbers. A run that is already straight takes no slot at all.
 */
final class FlowLayout {

    /** Every box is this tall; only the width is the caller's business. */
    static final int NODE_H = 18;
    /** Clear air between two boxes in the same layer. */
    private static final int ROW_GAP = 10;
    /** Between two routing slots in a channel. ELK's edge spacing. */
    private static final int SPACING = 8;
    /**
     * The straight run every edge gets to itself where it meets the box it points at, with no other
     * edge on those pixels. A status is a <b>colour</b>, and a colour needs a length of line to be
     * read off: before this, three links into one bay all landed on the same middle-of-the-side
     * point and shared their last leg, so whichever drew last owned the colour of all three. The
     * channel is sized from this, so it is a floor and not a wish.
     *
     * <p>Sixteen because the arrowhead is five of it and the view is usually zoomed out to fit:
     * at ten, a graph fitted at half size left about two pixels of bare line past the head, which
     * is a colour you cannot read. Measured on screen, not chosen on paper.
     */
    private static final int CLEAN_RUN = 16;
    /** How far apart two ports on one side sit, and how far in from the box's corners they stop. */
    private static final int PORT_PITCH = 5;
    private static final int PORT_INSET = 4;
    /** A dummy is a point, so a long edge crossing a layer costs a sliver rather than a row. */
    private static final int DUMMY_H = 2;

    /** ELK's own weights: a crossing is sixteen times worse than a near miss. */
    private static final int CONFLICT_PENALTY = 1;
    private static final int CROSSING_PENALTY = 16;
    /** Two horizontals closer than this count as overlapping. Half the spacing, as ELK has it. */
    private static final int CONFLICT_THRESHOLD = SPACING / 2;

    private final int nodeW;

    /** Per real node, in the caller's own order. */
    final int[] nodeX;
    final int[] nodeY;

    /** Per edge, the polyline it is drawn along, already running source to target. */
    final List<int[]> edgeXs = new ArrayList<>();
    final List<int[]> edgeYs = new ArrayList<>();

    final int width;
    final int height;

    private final int[] vy;
    private final int[] size;
    private final List<List<Integer>> layers = new ArrayList<>();

    /**
     * Where each edge meets the box it points at, by segment identity. Keyed on the array instance
     * rather than on {@code {from, to}} because two links between the same pair of boxes are two
     * edges and want two ports, and a map keyed on the pair would hand them one.
     */
    private final java.util.IdentityHashMap<int[], Integer> ports = new java.util.IdentityHashMap<>();
    /**
     * Where a <em>parallel</em> edge leaves its box. Two or more edges between the same pair of
     * boxes - chemical out, fluid back, energy along - used to leave from one point, share one
     * vertical and land five pixels apart: three arrowheads in a scribble, and a reversed one
     * drawn over them (OPEN_ISSUES #124). Each now leaves at the same offset it arrives at, so
     * level boxes get straight parallel lanes and each arrow is its own line end to end.
     */
    private final java.util.IdentityHashMap<int[], Integer> departures = new java.util.IdentityHashMap<>();
    /**
     * The caller's kind of each edge's last segment, where it meets the box it points at. Arrivals of
     * one kind from different boxes share a port, and so a last leg and one head: two furnaces
     * feeding one ingot chest drew two arrowheads a few pixels apart, where the fan out of a box
     * has always been one fork. The owner's ask, the mirror of Sander's shared run on the way out.
     * A reversed edge's last segment is its tail, so it never joins.
     */
    private final java.util.IdentityHashMap<int[], Integer> joins = new java.util.IdentityHashMap<>();

    /** One vertical run: everything leaving one box into one channel, and where it branches to. */
    private static final class Run {
        int from;
        int start;
        /** The one segment this run carries, when it is a parallel edge's private lane. */
        int[] only;
        int[] ends = new int[0];
        int slot;
        final List<Run> before = new ArrayList<>();
        int incoming;

        boolean straight() {
            return ends.length == 1 && ends[0] == start;
        }

        int low() {
            int lo = start;
            for (int e : ends) {
                lo = Math.min(lo, e);
            }
            return lo;
        }

        int high() {
            int hi = start;
            for (int e : ends) {
                hi = Math.max(hi, e);
            }
            return hi;
        }
    }

    /** What order two runs would rather be in, and by how much. */
    private record Want(Run left, Run right, int weight) { }

    /**
     * @param count how many real boxes there are
     * @param wires one {@code {from, to}} per edge, in the caller's own order
     * @param kinds one per edge: edges of one kind into one box join before it
     */
    FlowLayout(int count, List<int[]> wires, List<Integer> kinds, int nodeW) {
        this.nodeW = nodeW;
        this.nodeX = new int[count];
        this.nodeY = new int[count];

        // ---------------------------------------------------------------- 1. break the cycles
        // Two bays feeding each other is a cycle, and every later phase needs a graph without one.
        // Reversed rather than dropped: the layout is done on the reversed version and the arrow is
        // drawn pointing the way it really goes.
        boolean[] flipped = new boolean[wires.size()];
        int[] state = new int[count];
        for (int start = 0; start < count; start++) {
            if (state[start] == 0) {
                visit(start, wires, flipped, state);
            }
        }

        // ---------------------------------------------------------------- 2. assign the layers
        int[] layer = new int[count];
        for (int pass = 0; pass < count; pass++) {
            boolean moved = false;
            for (int e = 0; e < wires.size(); e++) {
                int from = end(wires.get(e), flipped[e], 0);
                int to = end(wires.get(e), flipped[e], 1);
                if (layer[to] <= layer[from]) {
                    layer[to] = layer[from] + 1;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        int layerCount = 1;
        for (int l : layer) {
            layerCount = Math.max(layerCount, l + 1);
        }

        // -------------------------------------------- dummy chains, so long edges pass between
        List<int[]> chains = new ArrayList<>();
        List<Integer> dummyLayer = new ArrayList<>();
        for (int e = 0; e < wires.size(); e++) {
            int from = end(wires.get(e), flipped[e], 0);
            int to = end(wires.get(e), flipped[e], 1);
            List<Integer> chain = new ArrayList<>();
            chain.add(from);
            for (int l = layer[from] + 1; l < layer[to]; l++) {
                chain.add(count + dummyLayer.size());
                dummyLayer.add(l);
            }
            chain.add(to);
            chains.add(chain.stream().mapToInt(Integer::intValue).toArray());
        }
        int total = count + dummyLayer.size();
        int[] vertexLayer = new int[total];
        System.arraycopy(layer, 0, vertexLayer, 0, count);
        for (int d = 0; d < dummyLayer.size(); d++) {
            vertexLayer[count + d] = dummyLayer.get(d);
        }
        this.size = new int[total];
        Arrays.fill(size, DUMMY_H);
        Arrays.fill(size, 0, count, NODE_H);

        for (int l = 0; l < layerCount; l++) {
            layers.add(new ArrayList<>());
        }
        for (int v = 0; v < total; v++) {
            layers.get(vertexLayer[v]).add(v);
        }

        List<List<int[]>> segments = new ArrayList<>();
        for (int l = 0; l < layerCount; l++) {
            segments.add(new ArrayList<>());
        }
        // The same array instances the ports are keyed on, kept per chain so phase 5 can ask which
        // port this edge in particular lands on rather than where its target box happens to be.
        List<int[][]> chainSegments = new ArrayList<>();
        for (int e = 0; e < chains.size(); e++) {
            int[] chain = chains.get(e);
            int[][] mine = new int[Math.max(0, chain.length - 1)][];
            for (int i = 0; i + 1 < chain.length; i++) {
                int[] seg = {chain[i], chain[i + 1]};
                segments.get(vertexLayer[chain[i]]).add(seg);
                mine[i] = seg;
            }
            if (mine.length > 0 && !flipped[e]) {
                joins.put(mine[mine.length - 1], kinds.get(e));
            }
            chainSegments.add(mine);
        }

        // ---------------------------------------------------------- 3. minimise the crossings
        reorder(segments, layerCount);

        // ------------------------------------------------------------------ 4. place the nodes
        this.vy = new int[total];
        place(segments, layerCount);
        ports(segments);

        // ------------------------------------------------------------------ 5. route the edges
        List<List<Run>> runs = new ArrayList<>();
        int[] channel = new int[Math.max(1, layerCount)];
        for (int l = 0; l + 1 < layerCount; l++) {
            List<Run> here = build(segments.get(l));
            slots(here);
            int most = 0;
            for (Run run : here) {
                most = Math.max(most, run.straight() ? 0 : run.slot + 1);
            }
            // The slots, then the run the last leg keeps for itself: from the outermost slot to
            // the box is exactly CLEAN_RUN, and every inner slot's leg is longer than that.
            channel[l] = most * SPACING + CLEAN_RUN;
            runs.add(here);
        }
        int[] layerX = new int[layerCount];
        for (int l = 1; l < layerCount; l++) {
            layerX[l] = layerX[l - 1] + nodeW + channel[l - 1];
        }
        for (int v = 0; v < count; v++) {
            nodeX[v] = layerX[layer[v]];
            nodeY[v] = vy[v];
        }
        draw(chains, chainSegments, flipped, vertexLayer, layerX, runs);

        int right = 0;
        int bottom = 0;
        for (int v = 0; v < count; v++) {
            right = Math.max(right, nodeX[v] + nodeW);
            bottom = Math.max(bottom, nodeY[v] + NODE_H);
        }
        this.width = Math.max(1, right);
        this.height = Math.max(1, bottom);
    }

    // ------------------------------------------------------------------------------- phase 1

    /** Depth-first, marking a back edge as one to reverse. */
    private static void visit(int at, List<int[]> wires, boolean[] flipped, int[] state) {
        state[at] = 1;
        for (int e = 0; e < wires.size(); e++) {
            if (flipped[e] || wires.get(e)[0] != at) {
                continue;
            }
            int to = wires.get(e)[1];
            if (state[to] == 1) {
                flipped[e] = true;
            } else if (state[to] == 0) {
                visit(to, wires, flipped, state);
            }
        }
        state[at] = 2;
    }

    private static int end(int[] wire, boolean flipped, int which) {
        return flipped ? wire[1 - which] : wire[which];
    }

    // ------------------------------------------------------------------------------- phase 3

    /** The median heuristic, swept down and up, the ordering with fewest crossings kept. */
    private void reorder(List<List<int[]>> segments, int layerCount) {
        List<List<Integer>> best = copy();
        int fewest = crossings(segments, layerCount);
        for (int sweep = 0; sweep < 4; sweep++) {
            boolean down = sweep % 2 == 0;
            for (int step = 0; step < layerCount; step++) {
                int l = down ? step : layerCount - 1 - step;
                if ((down && l == 0) || (!down && l == layerCount - 1)) {
                    continue;
                }
                List<Integer> here = layers.get(l);
                List<int[]> across = down ? segments.get(l - 1) : segments.get(l);
                double[] key = new double[here.size()];
                for (int i = 0; i < here.size(); i++) {
                    key[i] = median(here.get(i), across, down, i);
                }
                Integer[] index = new Integer[here.size()];
                for (int i = 0; i < index.length; i++) {
                    index[i] = i;
                }
                Arrays.sort(index, Comparator.comparingDouble(i -> key[i]));
                List<Integer> next = new ArrayList<>();
                for (int i : index) {
                    next.add(here.get(i));
                }
                layers.set(l, next);
            }
            int now = crossings(segments, layerCount);
            if (now < fewest) {
                fewest = now;
                best = copy();
            }
        }
        layers.clear();
        layers.addAll(best);
    }

    private List<List<Integer>> copy() {
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> l : layers) {
            out.add(new ArrayList<>(l));
        }
        return out;
    }

    /** A vertex with no neighbours in the layer being swept from keeps where it is. */
    private double median(int vertex, List<int[]> across, boolean down, int at) {
        List<Integer> seen = new ArrayList<>();
        for (int[] seg : across) {
            if (down && seg[1] == vertex) {
                seen.add(placeOf(seg[0]));
            } else if (!down && seg[0] == vertex) {
                seen.add(placeOf(seg[1]));
            }
        }
        if (seen.isEmpty()) {
            return at;
        }
        seen.sort(Comparator.naturalOrder());
        return seen.size() % 2 == 1 ? seen.get(seen.size() / 2)
            : (seen.get(seen.size() / 2 - 1) + seen.get(seen.size() / 2)) / 2.0;
    }

    private int placeOf(int vertex) {
        for (List<Integer> l : layers) {
            int at = l.indexOf(vertex);
            if (at >= 0) {
                return at;
            }
        }
        return 0;
    }

    private int crossings(List<List<int[]>> segments, int layerCount) {
        int total = 0;
        for (int l = 0; l + 1 < layerCount; l++) {
            List<int[]> here = segments.get(l);
            for (int a = 0; a < here.size(); a++) {
                for (int b = a + 1; b < here.size(); b++) {
                    if ((placeOf(here.get(a)[0]) - placeOf(here.get(b)[0]))
                        * (placeOf(here.get(a)[1]) - placeOf(here.get(b)[1])) < 0) {
                        total++;
                    }
                }
            }
        }
        return total;
    }

    // ------------------------------------------------------------------------------- phase 4

    /**
     * Rows, then passes pulling each vertex to the median of what it joins, then separated
     * ({@link #spread}), until nothing moves. Capped, in case two boxes pulling across a separation
     * trade a pixel forever.
     */
    private void place(List<List<int[]>> segments, int layerCount) {
        for (List<Integer> here : layers) {
            int at = 0;
            for (int v : here) {
                vy[v] = at;
                at += size[v] + ROW_GAP;
            }
        }
        int[] before = new int[vy.length];
        for (int pass = 0; pass < 64 && (pass == 0 || !Arrays.equals(before, vy)); pass++) {
            System.arraycopy(vy, 0, before, 0, vy.length);
            for (int l = 0; l < layerCount; l++) {
                for (int v : layers.get(l)) {
                    List<Integer> joined = new ArrayList<>();
                    for (List<int[]> side : segments) {
                        for (int[] seg : side) {
                            if (seg[0] == v) {
                                joined.add(anchor(seg[1]));
                            } else if (seg[1] == v) {
                                joined.add(anchor(seg[0]));
                            }
                        }
                    }
                    if (joined.isEmpty()) {
                        continue;
                    }
                    joined.sort(Comparator.naturalOrder());
                    int mid = joined.size() % 2 == 1 ? joined.get(joined.size() / 2)
                        : (joined.get(joined.size() / 2 - 1) + joined.get(joined.size() / 2)) / 2;
                    vy[v] = mid - size[v] / 2;
                }
                spread(layers.get(l));
            }
        }
        int top = Integer.MAX_VALUE;
        for (List<Integer> here : layers) {
            for (int v : here) {
                top = Math.min(top, vy[v]);
            }
        }
        for (List<Integer> here : layers) {
            for (int v : here) {
                vy[v] -= top;
            }
        }
    }

    /**
     * Separates one layer's boxes, keeping their order. Boxes that overlap become one block, and a
     * block stands where its members <em>asked</em> to be on average, not wherever pushing down
     * left it. Pushing only down made the map drift: a furnace and a blast furnace asking for one
     * spot were stacked below it, the chests they share re-centred on the pair, the pair followed,
     * and the whole cluster slid 14 pixels a pass while anything tied to it lagged a pass behind -
     * the gallery's Food chest stood 14 above the Smoker it feeds. Merging adjacent blocks until
     * none overlap is the pool-adjacent-violators rule, and it settles.
     */
    private void spread(List<Integer> here) {
        int[] wanted = new int[here.size()];
        for (int i = 0; i < here.size(); i++) {
            wanted[i] = vy[here.get(i)];
        }
        // {first, last, top}, and each member's offset from the block's top.
        List<int[]> blocks = new ArrayList<>();
        int[] offset = new int[here.size()];
        for (int i = 0; i < here.size(); i++) {
            blocks.add(new int[] {i, i, wanted[i]});
            while (blocks.size() > 1) {
                int[] above = blocks.get(blocks.size() - 2);
                int[] below = blocks.get(blocks.size() - 1);
                int aboveEnd = above[2] + offset[above[1]] + size[here.get(above[1])] + ROW_GAP;
                if (aboveEnd <= below[2]) {
                    break;
                }
                long sum = 0;
                int at = 0;
                for (int m = above[0]; m <= below[1]; m++) {
                    offset[m] = at;
                    sum += wanted[m] - at;
                    at += size[here.get(m)] + ROW_GAP;
                }
                int top = (int) Math.round(sum / (double) (below[1] - above[0] + 1));
                blocks.remove(blocks.size() - 1);
                blocks.set(blocks.size() - 1, new int[] {above[0], below[1], top});
            }
        }
        for (int[] block : blocks) {
            for (int m = block[0]; m <= block[1]; m++) {
                vy[here.get(m)] = block[2] + offset[m];
            }
        }
    }

    /** The middle of a box's side: where everything <em>leaves</em> from, and phase 4's handle. */
    private int anchor(int vertex) {
        return vy[vertex] + size[vertex] / 2;
    }

    /** Where this edge in particular meets the box it points at. */
    private int port(int[] seg) {
        Integer at = ports.get(seg);
        return at == null ? anchor(seg[1]) : at;
    }

    /**
     * A port per <em>kind</em> on the side it arrives at: arrivals of one kind share one, and so one
     * last leg and one head, while a second kind keeps a line of its own and its own colour for
     * {@link #CLEAN_RUN} pixels. Everything <em>leaving</em> a box shares one vertical run (Sander's
     * hyperedge, and a fork reads as a fork); arrivals of a kind now join the same way.
     *
     * <p>Ordered by where each bundle comes from, so the fan into a box does not cross itself, and
     * spread from the middle outwards inside the box's own side. A box with more bundles than fit
     * shrinks the pitch to nothing and lands them all on the middle, which is where they were.
     * Dummies carry one chain each and keep their centre.
     */
    private void ports(List<List<int[]>> segments) {
        java.util.Map<Integer, List<int[]>> arriving = new java.util.LinkedHashMap<>();
        for (List<int[]> side : segments) {
            for (int[] seg : side) {
                arriving.computeIfAbsent(seg[1], v -> new ArrayList<>()).add(seg);
            }
        }
        for (java.util.Map.Entry<Integer, List<int[]>> at : arriving.entrySet()) {
            List<int[]> here = at.getValue();
            int middle = anchor(at.getKey());
            if (here.size() == 1 || size[at.getKey()] != NODE_H) {
                here.forEach(seg -> ports.put(seg, middle));
                continue;
            }
            // A segment with no kind is a bundle of one, keyed on itself (an array's identity).
            java.util.Map<Object, List<int[]>> bundles = new java.util.LinkedHashMap<>();
            for (int[] seg : here) {
                Integer kind = joins.get(seg);
                bundles.computeIfAbsent(kind == null ? seg : kind, k -> new ArrayList<>()).add(seg);
            }
            List<List<int[]>> ordered = new ArrayList<>(bundles.values());
            ordered.sort(Comparator.comparingDouble(bundle ->
                bundle.stream().mapToInt(seg -> anchor(seg[0])).average().orElse(0)));
            int pitch = ordered.size() == 1 ? 0
                : Math.min(PORT_PITCH, (NODE_H - 2 * PORT_INSET) / (ordered.size() - 1));
            int first = middle - pitch * (ordered.size() - 1) / 2;
            for (int i = 0; i < ordered.size(); i++) {
                int port = first + i * pitch;
                ordered.get(i).forEach(seg -> ports.put(seg, port));
            }
            // Parallel edges: the same source twice or more. Each leaves at its arrival's offset.
            java.util.Map<Integer, Integer> perSource = new java.util.HashMap<>();
            here.forEach(seg -> perSource.merge(seg[0], 1, Integer::sum));
            for (int[] seg : here) {
                if (perSource.get(seg[0]) > 1 && size[seg[0]] == NODE_H) {
                    departures.put(seg, anchor(seg[0]) + ports.get(seg) - middle);
                }
            }
        }
    }

    /** Where this edge leaves its box: its own lane if it is one of a parallel set, else the middle. */
    private int departure(int[] seg) {
        Integer at = departures.get(seg);
        return at == null ? anchor(seg[0]) : at;
    }

    // ------------------------------------------------------------------------------- phase 5

    /** One run per source box in this channel, carrying every edge that leaves it. */
    private List<Run> build(List<int[]> segments) {
        List<Run> runs = new ArrayList<>();
        for (int[] seg : segments) {
            Run mine = null;
            if (departures.get(seg) == null) {
                for (Run run : runs) {
                    if (run.from == seg[0] && run.only == null) {
                        mine = run;
                        break;
                    }
                }
            }
            if (mine == null) {
                mine = new Run();
                mine.from = seg[0];
                mine.start = departure(seg);
                mine.only = departures.get(seg) == null ? null : seg;
                runs.add(mine);
            }
            int[] grown = Arrays.copyOf(mine.ends, mine.ends.length + 1);
            grown[grown.length - 1] = port(seg);
            Arrays.sort(grown);
            mine.ends = grown;
        }
        return runs;
    }

    /**
     * Which slot each run gets. Every pair is costed both ways round, the cheaper order becomes a
     * dependency, and a topological numbering turns the dependencies into slot numbers. The
     * dependencies are taken greedily by weight so the graph cannot cycle - ELK detects cycles and
     * reverses the weakest instead, which on a graph this size reaches the same answer with a great
     * deal more code.
     */
    private static void slots(List<Run> runs) {
        List<Want> wants = new ArrayList<>();
        for (int a = 0; a < runs.size(); a++) {
            for (int b = a + 1; b < runs.size(); b++) {
                Run one = runs.get(a);
                Run two = runs.get(b);
                if (one.straight() || two.straight()) {
                    continue;
                }
                int cost1 = cost(one, two);
                int cost2 = cost(two, one);
                if (cost1 < cost2) {
                    wants.add(new Want(one, two, cost2 - cost1));
                } else if (cost2 < cost1) {
                    wants.add(new Want(two, one, cost1 - cost2));
                }
            }
        }
        wants.sort(Comparator.comparingInt(Want::weight).reversed());
        for (Want want : wants) {
            if (!reaches(want.right(), want.left())) {
                want.left().before.add(want.right());
                want.right().incoming++;
            }
        }
        List<Run> ready = new ArrayList<>();
        for (Run run : runs) {
            if (run.incoming == 0) {
                ready.add(run);
            }
        }
        while (!ready.isEmpty()) {
            Run run = ready.remove(0);
            for (Run next : run.before) {
                next.slot = Math.max(next.slot, run.slot + 1);
                if (--next.incoming == 0) {
                    ready.add(next);
                }
            }
        }
    }

    /**
     * What it costs to put {@code left} left of {@code right}: one per conflict - two horizontals
     * at the same height, which would overlap in the gap between the two verticals - and sixteen
     * per crossing, a horizontal that ends inside the other run's vertical span. An end both runs
     * share is a join, not a clash: two runs meeting at one port cost nothing and take one slot,
     * one straight line through the port.
     */
    private static int cost(Run left, Run right) {
        int conflicts = 0;
        int crossings = 0;
        for (int end : left.ends) {
            if (Arrays.binarySearch(right.ends, end) >= 0) {
                continue;
            }
            if (Math.abs(end - right.start) < CONFLICT_THRESHOLD) {
                conflicts++;
            }
            if (end >= right.low() && end <= right.high()) {
                crossings++;
            }
        }
        if (right.start >= left.low() && right.start <= left.high()) {
            crossings++;
        }
        return CONFLICT_PENALTY * conflicts + CROSSING_PENALTY * crossings;
    }

    private static boolean reaches(Run from, Run to) {
        if (from == to) {
            return true;
        }
        for (Run next : from.before) {
            if (reaches(next, to)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The polyline for each edge: out of its box, along its source's own vertical run, in at the
     * middle of the far box's side. An edge whose two ends are already level skips the vertical
     * altogether - one fewer thing to follow and one fewer slot to pay for.
     */
    private void draw(List<int[]> chains, List<int[][]> chainSegments, boolean[] flipped,
        int[] vertexLayer, int[] layerX, List<List<Run>> runs) {
        for (int e = 0; e < chains.size(); e++) {
            int[] chain = chains.get(e);
            int[][] segs = chainSegments.get(e);
            List<Integer> xs = new ArrayList<>();
            List<Integer> ys = new ArrayList<>();
            for (int i = 0; i + 1 < chain.length; i++) {
                int l = vertexLayer[chain[i]];
                Run run = null;
                boolean parallel = departures.get(segs[i]) != null;
                for (Run candidate : runs.get(l)) {
                    if (parallel ? candidate.only == segs[i]
                        : candidate.from == chain[i] && candidate.only == null) {
                        run = candidate;
                        break;
                    }
                }
                int startY = departure(segs[i]);
                int endY = port(segs[i]);
                if (i > 0) {
                    add(xs, ys, layerX[l], startY);
                }
                add(xs, ys, layerX[l] + nodeW, startY);
                if (run != null && startY != endY) {
                    int slotX = layerX[l] + nodeW + (run.slot + 1) * SPACING;
                    add(xs, ys, slotX, startY);
                    add(xs, ys, slotX, endY);
                }
                add(xs, ys, layerX[l + 1], endY);
            }
            int[] px = xs.stream().mapToInt(Integer::intValue).toArray();
            int[] py = ys.stream().mapToInt(Integer::intValue).toArray();
            if (flipped[e]) {
                reverse(px);
                reverse(py);
            }
            edgeXs.add(px);
            edgeYs.add(py);
        }
    }

    private static void add(List<Integer> xs, List<Integer> ys, int px, int py) {
        if (!xs.isEmpty() && xs.get(xs.size() - 1) == px && ys.get(ys.size() - 1) == py) {
            return;
        }
        xs.add(px);
        ys.add(py);
    }

    private static void reverse(int[] values) {
        for (int i = 0, j = values.length - 1; i < j; i++, j--) {
            int swap = values[i];
            values[i] = values[j];
            values[j] = swap;
        }
    }
}
