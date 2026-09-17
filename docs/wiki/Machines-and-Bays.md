A **bay** is one slot in a Workbay. Rack a machine into it and the machine moves into the mod's private dimension; the bay becomes that machine's handle - its screen, its faces, and its channels.

![Eight machines spread across a stone floor, before they are hosted](https://raw.githubusercontent.com/neryosx/workbay/master/docs/media/01-floor-of-machines.png)

The eight machines above all fit in one Workbay. They keep running while they are in there - hosting is not storage.

## What can be hosted

- Vanilla single-block machines: furnaces, blast furnaces, smokers, and the like.
- Most single-block machines from other mods.
- Mekanism machines. Mekanism is the integration tested against every build.
- Rooms. The gesture is identical, but a room bay behaves differently - see [[Rooms]].

![A hosted Mekanism Rotary Condensentrator opened from the Workbay, with the line: Opened from here. Bay 2. The machine itself stays in its bay.](https://raw.githubusercontent.com/neryosx/workbay/master/docs/media/wiki/06-remote-mekanism-screen.png)

The machine keeps its own screen. Opening it from the Workbay does not move it - the line at the bottom says which bay it is answering from, and the machine stays there.

## What is refused, and why

The check runs in order, and the first rule with an opinion wins.

| Rule | What it stops |
| --- | --- |
| `#workbay:host_allowed` | Nothing. An explicit allow, checked first, that beats every rule below it - including the Workbay guard. |
| A Workbay | A Workbay cannot be racked into a Workbay. |
| `#workbay:host_denied`, `#workbay:host_denied_types` | Whatever a mod or a modpack has tagged. Beds, doors, trial spawners and vaults ship on that list. Reported as the pack's decision. |
| `#c:relocation_not_supported` | Blocks whose own mod says they cannot be moved. Reported as "cannot be moved", which is a different problem. |
| No block entity | A plain building block. There is nothing in it to keep running. |
| Three or more connection properties | Cables, pipes and fences - a block that works by joining up with its neighbours, and a bay has none. |
| Everything else | Nothing. The default is to allow. |

**There is no multiblock check and no rotation check.** A bay holds exactly one block, so a multiblock cannot go in as a multiblock - but nothing stops you racking one *part* of one, and the structure it belonged to then breaks. That case is refused only when the pack, or the block's own mod, has tagged it. Facing is not refused either: a hosted block is turned to face north as it is racked.

If something in your pack should be refused and is not, `#workbay:host_denied` is the answer; see **[[Modpacks]]**.

Nothing is refused silently. The tooltip names the rule that applied - unless a pack has turned the hostability tooltip off with the client option `showHostabilityInTooltips` - and so does the command:

```
/workbay why <block id>
```

## Bays and upgrades

A Workbay starts with **two bays**, and each Expansion Plate adds one up to a hard ceiling of **eight**. The **UPGRADES** screen adds:

- more bays,
- longer reach, so a link can cross into another dimension,
- faster links: an Impeller doubles the rate and halves the wait, so one is worth four times the throughput,
- chunk loading, so a chain keeps running while you are online but somewhere else. It stops when you log out, after a grace period a server sets (five minutes by default).

Every upgrade is built on a **Housing**, and they all share one crafting shape with a different item in the middle - the full table is in [[Recipes and Upgrades]].

## Faces

![The WORKBAY screen: the bay list, the racked machine, the face cube and the links](https://raw.githubusercontent.com/neryosx/workbay/master/docs/media/wiki/02-workbay-screen.png)

Each bay gets a face cube, and there is **one set of faces per resource** - items, fluids, energy, and chemicals with Mekanism installed. Drag the cube to look at the block from another angle; **click** a face to cycle it **in** (green), **out** (blue) or off.

Dragging does not turn the hosted block. It was turned to face north when it was racked, and the cube is a view of it.

A bay you never touch leaves every face unset, and unset means *any face*, not *no face* - links use whichever side answers. You only set faces when you want an input chest on one side and an output chest on another.

## Energy

Energy moves like everything else: an energy link from a bay to a Connector on your power source. **It is not shared across the network** - each bay that needs power gets its own energy link.

The Workbay block has an FE buffer of its own, and that is a separate thing. It accepts power on any face, up to 10,000 FE/t into a 100,000 FE buffer, and it never hands energy back out. It exists only to pay for running links, which a server switches on with `powerPerLinkPerTick` and `powerPerMove`. Both ship at zero, so on a default server a Workbay costs nothing to run and the power readouts stay hidden. See [[Server Configuration]].

## If Mekanism is removed

A world that had Mekanism machines racked still opens cleanly without Mekanism installed, and those bays read as empty.

Be clear about what survives: the world keeps a record of **which block** used to be in each bay - `/workbay orphans` lists them - so you can see exactly what an update cost you. The machines themselves are gone. The game discards blocks whose mod is missing, and putting Mekanism back does not bring them or their contents back.

## Performance

A hosted machine is the same block entity it always was, ticking at the same rate, just located in a dimension the mod manages. Workbay buys space, not tick time. Measured cost is roughly **0.05 ms per tick per busy network**, with no growth over an hour of running.

On chunks: the bay column is mirrored only while the Workbay's own chunk is already loaded, so it costs no chunk somebody is not already paying for by standing there. Only an **Anchor** keeps anything loaded while nobody is present.

A network holds at most **64 links**.

---

Next: [[Connectors and FLOW]].
