A hosted machine still has to reach the world: ore has to get in, ingots have to come out. Workbay does that with **Connectors** instead of pipes, and shows you the result on the **FLOW** map.

## Connectors

A Connector is a small plate you stick on any chest, tank or machine out in the world. It is the endpoint a bay talks to.

![One Workbay where a floor of machines used to be, with chests plugged in through Connectors](https://raw.githubusercontent.com/neryosx/workbay/master/docs/media/wiki/01-workbay-in-world.png)

That is the whole network above ground: one block, and the chests it feeds. Everything else is inside.

![A chest feeding a hosted furnace through a Connector; the link goes Running](https://raw.githubusercontent.com/neryosx/workbay/master/docs/media/03-link-running.gif)

To wire one up:

1. Craft Connectors and **hold them**. Right-click the Workbay with the stack - that is the whole pairing gesture, and it pairs everything in your hand at once. (The **Pair** button on the screen does the same thing for a Connector in either hand, which is useful when the screen is already open.)
2. **Then** place a paired Connector against the chest, tank or machine you want reached. Its tooltip names the network it belongs to.
3. Open the bay that should use it, press **Add**, tick the Connector, and switch the new row on. A new channel always starts switched off.

> **Pair before you place, and pairing is not per bay.** A Connector belongs to a *network*, not to a bay - one plate can carry a channel on every bay at once, and which bays use it is decided in step 3. Place an unpaired Connector and it tells you so and sits inactive; right-clicking a Connector that is already down opens its rename panel and nothing else, so you have to break it and pair it in hand.

No cable is run at any point. Distance inside one dimension costs nothing. A link whose two ends are in **different** dimensions needs the Resonator upgrade - without one the row reads *Needs a Resonator* - though links into your own bays and rooms never do.

## What a channel carries

The screen calls each row a **channel**: one bay, one Connector, one resource. A channel and a link are the same thing; the screen says channel, FLOW and this wiki say link.

| Carries | Needs | Notes |
| --- | --- | --- |
| Items | - | Filter by item; drag one in from JEI or EMI. |
| Fluids | - | Tanks and fluid-handling machines. |
| Energy | - | One channel per bay, like items. Nothing to filter. Hosted machines do not share power. |
| Chemicals | Mekanism | Appears only when Mekanism is installed. |

Every channel has four dials:

- **Direction** - which way the contents move between the bay and the Connector.
- **Filter** - what is allowed through. Drag an ingredient in from JEI or EMI.
- **Rate** - how much moves per step.
- **Speed** - ticks between steps, from a fixed list: 10, 20, 40, 60, 100, 200.

An **Impeller** doubles what every link moves in a step and halves the wait between steps: four times the throughput, and two may be fitted, for sixteen.

A network holds at most 64 links.

## The FLOW map

![The FLOW map: an ore chest feeding a furnace and a blast furnace, a food chest feeding a smoker, pips walking on every arrow](https://raw.githubusercontent.com/neryosx/workbay/master/docs/media/04-flow-map.png)

FLOW draws every link that has a machine on both ends: a box per endpoint, one arrow per pair of boxes, resource and direction; three links from one chest into one furnace are one arrow, and arrows of one resource from different boxes join before the box they enter. Drag to move, scroll to zoom. A link parked off its bay, or pointing at an empty bay, is not on the map - the LINKS list is where those live.

The example above reads left to right - an ore chest feeds a Furnace and a Blast Furnace, both feed an ingot chest, and a food chest feeds a Smoker that fills a pantry. The ore chest reaches the Furnace through three links, one per ore, drawn as one arrow; the food chest reaches the Smoker through two.

### Reading the lines

Shape and colour say different things.

**Shape**

| Shape | Meaning |
| --- | --- |
| Solid | An ordinary link, to a Connector anywhere - including one you placed inside a Room. |
| Blue dashed | A bay-to-bay link, with no Connector at all. A dashed link stays blue whatever it is doing. |

**Colour**, on solid links

| Colour | Meaning |
| --- | --- |
| Green, with pips walking along it | Moving right now. This is what a healthy link looks like. |
| Pale grey | Resting. Nothing to move, or a target that is simply full. Normal. |
| Dark grey | Switched off, held by the bay's redstone mode, or taken off its bay. |
| Amber | Needs you: the target's chunk is unloaded, the target or the machine offers no port on a usable face, no face is set for that direction, the link needs a Resonator, or there is no power. |
| Red | Broken. The Connector was destroyed, or the dimension it pointed into is gone. |

![Pips walking along the links as items move](https://raw.githubusercontent.com/neryosx/workbay/master/docs/media/04-flow-pips.gif)

Note that a **full target is grey, not amber**. Amber means something needs a decision from you; a chest with no room in it is just resting until there is.

## Reliability

Every packet the mod sends is bounded and validated on the server, and a menu is budgeted to 80 actions per five ticks, so a network cannot be used to hammer a server. The Workbay registry is written on every change and never throws while loading: anything a build cannot read is carried forward rather than dropped. The one hard stop is a save written by a **newer** build - the file is kept untouched and nothing in it is reachable until you update the mod.

---

Next: [[Rooms]] and [[Networks and Locking]].
