A Room racks into a bay with the same gesture as a machine. What it hosts is different: not a block you place, but a space you build in.

![Entering a room, building in it, ejecting it as an item and racking it in another Workbay](https://raw.githubusercontent.com/neryosx/workbay/master/docs/media/05-room.gif)

## The three sizes

| Room | Interior |
| --- | --- |
| Room | 3 x 3 x 3 |
| Wide Room | 9 x 9 x 9 |
| Vast Room | 13 x 13 x 13 |

Each one is a cube, and each one fits inside a single chunk. All three are built on a **Housing**, like the upgrades.

## Using one

1. Rack the Room into an empty bay. A Room **cannot be placed in the world** - right-clicking the ground with one is refused and tells you so.
2. Press **Enter** on that bay's panel.
3. Build whatever you want inside.
4. Leave through a door.

![Inside a room: tiled walls, lit doors centred in each wall, and a build in progress](https://raw.githubusercontent.com/neryosx/workbay/master/docs/media/05-inside-room.png)

Each wall has a lit door dead centre, so **Leave** is always where you expect it.

A bay holding a room is not like a bay holding a machine: it has **no face cube and no channels of its own**, and any channels that bay was carrying are taken off it and parked under **Add** rather than deleted. A room reaches the network through Connectors you place *inside* it, and those travel with the room.

## Rooms travel

![A Room as an item on the ground](https://raw.githubusercontent.com/neryosx/workbay/master/docs/media/05-room-item.png)

Pull the Room out of the bay and it is an item again - one that:

- **carries everything built inside it**, exactly as you left it. Pulling it out disturbs nothing: anybody standing inside stays inside, and Leave still brings them out;
- **cannot be destroyed**, so there is no way to lose a build to fire, lava or a careless explosion;
- **racks into any other Workbay**, with the build still there;
- **nests** - a Room can go inside a Room, as deep as you like.

What nesting refuses is a loop: a Room may not go into a Workbay that is standing inside that Room, or inside anything that Room contains. A Room already racked somewhere else is refused too, and so is a *copy* of a Room item - only the item that came out of the bay opens it.

On the ground a Room item behaves like a proper item entity: it never expires, it bobs and turns like anything else you drop, and it never stacks with another Room. One that was saved mid-spin reads back fresh rather than frozen.

## What Rooms are good for

- A workshop you can carry between bases.
- A storage hall that takes up one bay instead of a building.
- Anything you want to build once and take with you - a Room racked in a Workbay that is itself in your inventory is a base in a pocket.

---

Next: [[Networks and Locking]].
