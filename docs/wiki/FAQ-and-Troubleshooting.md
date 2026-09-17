## A machine will not go into a bay

Ask the game why:

```
/workbay why <block id>
```

It will name the rule. Almost always it is one of three: the block has no block entity, it works by connecting to its neighbours (a cable, a pipe, a conduit), or the pack - or the block's own mod - has tagged it as not hostable. See [[Machines and Bays]].

There is no automatic multiblock or rotation check. A bay holds one block, so a multiblock cannot go in whole; racking one *part* of one is allowed unless somebody tagged it, and the structure it came from will break.

## A link is not moving anything

Open **FLOW** and look at the line.

| Line | What to do |
| --- | --- |
| Green with pips | It is working. |
| Pale grey (resting) | Nothing is wrong. There is nothing to move right now - including when the target is simply full. |
| Dark grey | The row is switched off, held by the bay's redstone mode, or parked off its bay. |
| Amber (stalled) | Something needs you: an unloaded chunk, no port on a usable face, no face set for that direction, a missing Resonator, or no power. |
| Red (broken) | The Connector was destroyed, or the dimension it pointed into is gone. |

If the line looks fine, check the direction and the filter on the WORKBAY screen, and that the row is switched on.

## The machine runs but nothing reaches it

Check the face cube for that bay, and check the right **resource** tab on it - items, fluids, energy and chemicals each have their own faces. An input chest feeding a face marked **out** will not do anything. Green is in, blue is out, and leaving every face unset means any face will answer.

## My second machine has no power

Energy is not shared. Each bay that needs power gets its own energy channel to a Connector on your power source. The Workbay's own FE buffer only pays for running links, and on a default server that costs nothing.

## Someone else cannot open my Workbay

![This Workbay is locked. Only its owner can open it.](https://raw.githubusercontent.com/neryosx/workbay/master/docs/media/06-locked.png)

That is the default - a network is born locked to the player who placed it. Use **Unlock** to share it. See [[Networks and Locking]].

## I broke my Workbay block. Is everything gone?

No. The network survives the block. Put the block it dropped back down and it reconnects to exactly that network; a freshly crafted Workbay picks up a sleeping network of yours, which may not be the one you meant if you have two. The **NETWORKS** screen moves one between blocks.

## I removed Mekanism and my machines vanished

The world reads cleanly and those bays show empty, and it keeps a record of **which block** used to be in each - `/workbay orphans` lists them. But the machines themselves are gone: the game discards blocks whose mod is missing, and re-installing Mekanism does not bring them back.

## Does hosting cost TPS?

A hosted machine is the same block entity ticking at the same rate - it is in a different dimension, not in a faster or slower one. Measured cost is about 0.05 ms per tick per busy network. The bay column is mirrored only while the Workbay's own chunk is already loaded, so it forces no chunk somebody is not already paying for; only an Anchor keeps anything loaded while nobody is there.

## Can I put a Room inside a Room?

Yes, as deep as you like. What is refused is a loop - a Room going into a Workbay that is standing inside that Room, or inside anything it contains - plus a Room that is already racked elsewhere, and a copy of a Room item.

## Is there a limit on links?

64 per network. Each player may own two networks by default, which a server can change in `<world>/serverconfig/workbay-server.toml`.

## Something else is wrong

Search the [open issues](https://github.com/neryosx/workbay/issues) first, then [open a bug report](https://github.com/neryosx/workbay/issues/new?template=bug_report.yml). Include the steps that reproduce it and, if you can, a screenshot or a short clip - it is usually the difference between a fix this week and a conversation that takes three rounds.
