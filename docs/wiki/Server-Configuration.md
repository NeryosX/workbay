Workbay has three config files.

| File | Where it lives | Who it is for |
| --- | --- | --- |
| `workbay-server.toml` | `config/` - a NeoForge **server** config; a copy in `<world>/serverconfig/` overrides it for that world | The host. Everything on this page except the last two sections. |
| `workbay-client.toml` | `config/` in the instance | Each player, for their own client. |
| `workbay-mixins.properties` | `config/` in the instance | The host, once. Read before Minecraft's own classes load, so it is the one file here that needs a full restart. |

A modpack ships its own values in that same `config/` file - see **[[Modpacks]]**.

Defaults below are what ships with the mod, and the range is what the file will accept.

## Bays, networks and rooms

Top-level keys in `workbay-server.toml`.

| Option | Default | Range | What it does |
| --- | :-: | :-: | --- |
| `maxBaysPerWorkbay` | 8 | 2 - 8 | Hard ceiling on bays. The base Workbay grants two, so this also sets how many Expansion Plates fit: this minus two. Eight is a geometry limit baked into saved worlds, not a number to raise. |
| `maxNetworksPerPlayer` | 2 | 1 - 64 | How many separate Workbay networks one player may own. |
| `roomsLoadWithWorkbay` | false | - | Off: a room runs only while somebody is standing in it. On: each room in a bay is one more loaded chunk for as long as the Workbay's own chunk is loaded. |
| `blockSounds` | true | - | Whether a Workbay makes any sound at its own position. |
| `greeting` | true | - | Whether a player meeting Workbay for the first time gets its one line in chat, with a link to the issue tracker. Said once per game, remembered by the client; off, the mod never writes to chat on joining. |
| `allowCrossDimensionLinks` | true | - | Whether the Resonator upgrade does anything. Off means no link may cross a dimension, and the row reads *Needs a Resonator* for good. |

## Links and throughput

These seven live under a **`[throughput]`** section in the TOML, not at the top level. Put them at the top level and they are ignored.

| Option | Default | Range | What it does |
| --- | :-: | :-: | --- |
| `linkDefaultRate` | 8 | 1 - 100000 | What one step of a new link is worth, per resource. |
| `linkMaxRate` | 64 | 1 - 1000000 | The ceiling on one link's rate, before Impellers. |
| `linkDefaultSpeed` | 20 | 5 - 1200 | Ticks between steps for a new link. It must divide 1200, and the screen only ever offers 10, 20, 40, 60, 100 and 200 - a value outside that list cannot be picked back once a player changes it. |
| `impellerStep` | 2 | 1 - 16 | What one Impeller is worth. It applies **twice**: the rate is multiplied by it and the wait is divided by it, so one level is worth this squared in throughput. |
| `maxImpellers` | 2 | 0 - 16 | How many Impellers one Workbay may hold. |
| `powerPerLinkPerTick` | 0 | 0 - 10000 | FE per tick each switched-on link draws, whether or not it moves anything. |
| `powerPerMove` | 0 | 0 - 10000 | FE one link draws for one step. |

Both power options ship at zero, so a Workbay costs no energy out of the box - and while they are zero every power readout is hidden, because a bar that prices something free is a bill for nothing.

Raise either and the Workbay needs charging: the block accepts FE on any face, up to 10,000 FE/t into a 100,000 FE buffer, and never hands energy back out. That buffer pays for the links only. **It does not power hosted machines** - each of those gets its own energy link.

## Chunk loading

These decide what the Anchor upgrade is allowed to do. On a busy server this is the section to look at first.

| Option | Default | Range | What it does |
| --- | :-: | :-: | --- |
| `allowAnchors` | true | - | Whether the Anchor upgrade does anything at all. Off means nothing is ever kept loaded while nobody is there, and the upgrade cannot be installed. |
| `maxAnchoredWorkbaysPerPlayer` | 4 | 0 - 1024 | How many of one player's Workbays may force-load at once. |
| `anchorGraceMinutes` | 5 | 0 - 43200 | How long a player's anchored Workbays keep running after they log out. Zero drops every ticket on the logout tick; the ceiling is thirty days. |
| `chunkTicketRadius` | 1 | 1 - 2 | How far every ticket the mod holds reaches, in chunks. The cost of one ticket is (2r+1)^2 chunks, so 1 holds 9 and 2 holds 25. At 1, block entities and random ticks run and entities freeze, which is everything a hosted machine needs. Two is the hard maximum. |

**What the mod force-loads, precisely.** An Anchor is the only thing that keeps chunks loaded while nobody is present. Separately, the bay column is mirrored while the Workbay's own chunk is already loaded by something else - that is no chunk a player is not already paying for by standing there - and `roomsLoadWithWorkbay = true` adds one chunk per room on the same terms. An Anchor holds its tickets only while its owner is **online**; log out and they are released once the grace period expires.

## Client options

Per-player, in `config/workbay-client.toml`. Rendering and tooltips only.

| Option | Default | What it does |
| --- | :-: | --- |
| `showHostabilityInTooltips` | true | Adds one line to every block tooltip saying whether hosting is possible. |
| `renderMiniatures` | true | Draws a miniature of each hosted machine in the display case. Turn off on a weak GPU. |

## Mixins

`config/workbay-mixins.properties` is written on first launch and holds one setting:

| Option | Default | What it does |
| --- | :-: | --- |
| `remoteScreens` | `true` | Whether a Workbay may open a hosted machine's own screen from where the player stands. |

That feature needs five narrow Mixin injections into vanilla classes. Setting `remoteScreens=false` means those classes are **never patched at all**, rather than patched with a handler that returns early - which is the difference between a host being able to say the mod does not touch `Level` and having to take that on trust.

Class transformation happens before any config could be loaded, which is why this one option cannot live in the TOML and why changing it needs the process restarted, not just the world reloaded. With it off, the bay button reads **Enter bay** instead of **Open its screen**, `/workbay remote` says so, and nothing else changes.

## Commands

| Command | Needs op | What it does |
| --- | :-: | --- |
| `/workbay why <block id>` | no | Prints the whole hosting verdict for that block: the rule that decided it, its piston reaction, its connection properties and its tag membership. |
| `/workbay ports` | no | The ports on the bay you are looking at. |
| `/workbay room <index>` | no | Puts you inside the room in that bay. |
| `/workbay remote <bay>` | no | Opens that bay's machine screen, or says why it cannot. |
| `/workbay charge <amount>` | level 2 | Puts FE into the Workbay you are looking at. |
| `/workbay orphans` | level 2 | Lists blocks recorded in bays whose mod is no longer installed. It reads across the server, which is why it is op-only. |

## Notes for server owners

The measured cost per network, the link cap, the packet budget and what is force-loaded are on [[Machines and Bays]] under Performance.

---

See also: **[[Modpacks]]**, **[[Items]]**, **[[Connectors and FLOW]]**.
