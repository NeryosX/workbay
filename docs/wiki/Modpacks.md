Workbay is **MIT licensed**. Put it in your pack, on your server, in a public modpack or a private one, with or without asking. No permission request, no attribution requirement. A link back is welcome and never expected.

This page is the short version of everything a pack author or server host needs. The full option list is on **[[Server Configuration]]**.

## What you are shipping

| What | Value |
| --- | --- |
| Mod id | `workbay` |
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.249 or newer |
| Java | 21 |
| Hard dependencies | none |
| Optional at runtime | JEI, EMI, Mekanism - all three are guarded, and the mod loads with none of them |
| Download | [Releases](https://github.com/neryosx/workbay/releases), [CurseForge](https://www.curseforge.com/minecraft/mc-mods/workbay), Modrinth |

Workbay adds one dimension of its own, `workbay:backshop`, for hosted machines and rooms. It registers nothing into another mod's registries.

## Shipping your own defaults

The server options are a NeoForge **server** config, so the live file lives inside each world at `<world>/serverconfig/workbay-server.toml` rather than in the instance's `config/` folder. A new world copies its defaults from the instance's `defaultconfigs/` folder, which is where a pack puts its own values:

```
<instance>/defaultconfigs/workbay-server.toml
```

Client options are an ordinary client config at `config/workbay-client.toml`. The third file, `config/workbay-mixins.properties`, is not TOML and is read before class transformation - see [[Server Configuration]].

> Seven of the server options live under a **`[throughput]`** section in the TOML, not at the top level: `linkDefaultRate`, `linkMaxRate`, `linkDefaultSpeed`, `impellerStep`, `maxImpellers`, `powerPerLinkPerTick` and `powerPerMove`. Written at the top level they are ignored.

## What to tune first

| Option | Default | Change it when |
| --- | :-: | --- |
| `allowAnchors` | `true` | You run a busy public server and do not want anything force-loaded while nobody is there. This is the first switch to look at. |
| `anchorGraceMinutes` | `5` | You want anchored networks to survive a longer logout - or none of it, at `0`. |
| `chunkTicketRadius` | `1` | Your pack expects mobs or item entities to keep moving inside an anchored room nobody is standing in. `2` costs nearly three times the chunks and is the hard maximum. |
| `maxBaysPerWorkbay` | `8` | You want the Expansion Plate ladder shorter. The floor is 2, the two bays every Workbay starts with, and 8 is a geometry ceiling you cannot raise. |
| `maxNetworksPerPlayer` | `2` | Your pack is built around one big base, or around many small ones. |
| `throughput.powerPerLinkPerTick` / `throughput.powerPerMove` | `0` | You want links to cost FE. Both zero means a Workbay is free to run, and every power readout stays hidden while they are. |
| `allowCrossDimensionLinks` | `true` | You do not want a network reaching into the Nether or the End. This disables the Resonator. |

## Deciding what may be hosted

Hosting is driven by **block tags**, so a pack changes it with a datapack and no code:

| Tag | Effect |
| --- | --- |
| `#workbay:host_allowed` | An explicit allow, checked **first**. It beats the heuristics, the deny tags below, `c:relocation_not_supported`, and the mod's own guards - tag `workbay:workbay` into it and a Workbay can be racked in a Workbay. The escape hatch, and the one tag that can break things if misused. |
| `#workbay:host_denied` | An explicit deny, reported to the player as "This pack doesn't allow ... to be hosted." Beds, doors, trial spawners and vaults ship on it. |
| `#workbay:host_denied_types` | The same, on a **block entity type**, so one entry covers every tier of a machine at once. |

Workbay also honours NeoForge's own `#c:relocation_not_supported`, and reports that case as "can't be safely relocated" rather than as a pack decision - two different problems, two different things for the player to do next.

A mod author can opt their own block in or out by shipping `data/workbay/tags/block/host_denied.json` in their own jar, with no dependency on Workbay.

**Beyond the tags there is less than you might assume.** The code-level rules, in the order they run after `host_allowed`, are: a Workbay is refused inside a Workbay; then the two deny tags above; then `c:relocation_not_supported`; then a block whose class, or whose block entity's class, has `multiblock` or `multi_block` in its name or its ancestors' names is refused as a multiblock part (Mekanism's casings, valves and ports, and its structural glass); then a block with no block entity; then a block with three or more of the boolean `north/east/south/west/up/down` properties, refused as a connecting block; and everything else is allowed. There is no rotation check. The multiblock rule is a heuristic on class names, so a mod that names its parts differently falls through it. If a machine in your pack should not be hostable, tag it - nothing else will catch it.

`/workbay why <block id>` prints the whole verdict chain for any block and is the fastest way to find out what a tag is actually doing.

## Recipes

Every recipe is an ordinary JSON recipe under `data/workbay/recipe/` - all shaped except `shopsteel`, which is shapeless - so a datapack, KubeJS or CraftTweaker overrides or removes any of them the usual way. The ids match the item names: `workbay:shopsteel`, `workbay:workbay`, `workbay:connector`, `workbay:housing`, `workbay:expansion_plate`, `workbay:impeller`, `workbay:resonator`, `workbay:anchor`, `workbay:room`, `workbay:wide_room`, `workbay:vast_room`.

The whole tree is deliberately cheap and early - see **[[Items]]**. If your pack is a progression pack, the two recipes worth gating are the **Workbay** itself and the **Housing**, because everything else hangs off those two.

## Performance

Workbay only relocates a machine; it does not make it cheaper. The measured cost, the link cap and exactly what is force-loaded are on [[Machines and Bays]] under Performance. The short version for a pack: nothing is kept loaded while nobody is there except by an Anchor, and `allowAnchors = false` removes that.

## Reporting a pack-specific problem

Open an issue with the [bug report template](https://github.com/neryosx/workbay/issues/new?template=bug_report.yml) and say which pack and which other mod is involved. If a machine will not rack, paste the output of `/workbay why <block id>`.

---

See also: **[[Server Configuration]]**, **[[Machines and Bays]]**, **[[Building and Contributing]]**.
