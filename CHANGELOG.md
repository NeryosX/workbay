# Changelog

The top section is what goes out with the release. Newest first.

## 1.0.0 - 2026-09-14

First release. Minecraft 1.21.1, NeoForge 21.1.249 or newer, Java 21, no dependencies.

**The Workbay.** One block that hosts any mod's machines in a private dimension and reaches them
wirelessly: two bays to start, eight with six Expansion Plates. A hosted machine is the real block,
ticking as it would on the floor, with its own screen one button away. It buys space, not tick time.

- **Links through Connectors.** Craft a Connector, pair it to a Workbay, place it against any
  chest, tank or machine. Every bay can run its own channel through it: items, fluids, energy,
  and Mekanism chemicals when Mekanism is installed. Each channel has a direction, a filter
  (drag from JEI or EMI) and a rate. A channel and a link are the same thing: the screen says
  channel, FLOW says link. Energy is not shared between bays; a machine that needs power gets its
  own energy link.
- **Four screens.** WORKBAY: the bays, the racked machine, a cube to set which face takes what,
  and the bay's links. FLOW: the whole network as a map, a line per link, pips walking where
  something is moving. UPGRADES: more bays, other dimensions, higher rates, chunk loading.
  NETWORKS: which network this block holds, and Transfer to move one between blocks; a Workbay
  holding no network opens here.
- **Rooms.** A Room, a Wide Room and a Vast Room (3 x 3 x 3, 9 x 9 x 9 and 13 x 13 x 13 inside) rack in a bay like a
  machine. Build inside; enter from the bay; a lit door dead centre in each wall leads out. A room travels
  as an item that cannot be destroyed, keeps everything built in it, and nests as deep as you
  like. Only a room inside itself is refused.
- **Networks belong to players.** One block is one network; lose the block and a fresh one picks
  the network back up. A network is born locked; Unlock shares it. Two networks per player by
  default (`<world>/serverconfig/workbay-server.toml`).
- **On the ground, a room spins.** The room item's entity never expires, ages like any item so
  it bobs and turns, and never stacks; one saved with a frozen age reads back fresh.
- **Servers.** Every packet is bounded and validated; a menu is budgeted to 80 actions per five
  ticks; a network holds at most 64 links; the registry is written on every change and never
  throws on load: what it cannot read is carried, not dropped. Measured cost: about 0.05 ms per
  tick per busy network and no growth over an hour. Nothing is force-loaded except by an Anchor,
  and that only while its owner is online plus a grace period.
- **Without Mekanism** a world that had Mekanism machines racked opens clean; those bays empty
  and keep a record of what was there.
- **Guide.** Every item explains itself behind Shift; `/workbay why <block>` says whether a block
  can be hosted and why not.
- The Workbay costs 4 glass, 4 Shopsteel and an **ender pearl** (an eye in the pre-release
  builds), so it arrives with the first furnace.
