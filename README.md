# Chest Loader

Vanilla-taste, server-only, and vanilla-client-friendly nether portal chunk loader that lives in a chest.

This document is also available in [中文](README.zh.md).

Arrange obsidian, powered rail and a minecart the right way inside a chest or a barrel, and the chunk that container sits in becomes force-loaded, over the same area and shape as a nether portal loader. Break the arrangement and the loading is revoked at once, and the loaded state survives a restart.

## Features

- Built from vanilla items. The loader is an obsidian ring around a powered rail and a minecart, placed in an ordinary chest or barrel. There is no custom block, no custom item, and no new recipe to learn.
- Range identical to a portal loader. The loaded area is level 30, radius 3, a 7×7 of chunks with a 3×3 entity-ticking core, the same as a nether portal loader. Machines keep ticking even after every player leaves the dimension.
- Server-side only, and vanilla clients can still join. Nothing is installed on the client, and the custom ticket type takes no part in registry sync. It works in single player and on a dedicated server alike.
- Survives a restart, and is safe to remove. The mod saves the active positions itself and puts the loading back on world load, with no need to reopen the container or enter the dimension. Uninstalling leaves nothing behind in vanilla's ticket storage.
- Configurable pattern. The shape, the items and the per-slot counts all live in a recipe-style grid in the config. Define several patterns, let each one slide or mirror, and set a min and max count per slot.
- Catches every change. Opening and closing a container is checked, and a periodic scan catches hopper transfers that skip those hooks. Loaders sharing a chunk are reference-counted, and the two halves of a double chest are judged separately.
- Switch a loader off and on from anywhere. `/chestloader disable` releases the loading but keeps the loader on record, and `/chestloader enable` brings it back remotely, with no need to visit the chunk. Both states survive a restart, and the commands, along with `list`, only ask for permission level 1.

## Versions

| | |
| --- | --- |
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.156.0+26.2 |
| Fabric Loom | 1.17.17 |
| Gradle | 9.5.1 |
| JDK | 25 or newer |

From 26.1 on, Minecraft ships deobfuscated, so the source uses Mojang official names throughout. The build script declares dependencies with `implementation`, and packaging is a plain `jar` task.

## Build

```bash
./gradlew build
```

The artifact lands at `build/libs/chestloader-0.1.0.jar`. `build` runs the tests along the way.

## Tests

39 JUnit cases cover shape matching, config parsing, ticket-level arithmetic, the saved-data codec round trip including the disabled set and its backward compatibility, and the unreadable-round counting during restore. They run without a server, so `./gradlew test` finishes in about a second. Shape matching is tested through a `SlotView` interface that exposes only the item and the count, because in 26.2 an `ItemStack`'s data components are bound only once the data packs load, and a plain JUnit test cannot build one.

9 GameTests cover what the JUnit tests cannot reach: whether the mixin really fires when a mock player opens and closes a container, whether the two halves of a double chest are judged separately, whether items carrying data components still count, and the enable and disable transitions with their ticket handling. Run them on their own with `./gradlew runGameTest`. That starts a test server, so `build.gradle` sets `eula = true`.

`fabric.mod.json` declares the environment as `*`, so the mod works in single player and on a dedicated server. Declaring `server` would keep it from loading in single player, because the integrated server there counts as a client environment. The custom ticket type is registered into `BuiltInRegistries.TICKET_TYPE`. That registry has no SYNCED attribute and takes no part in registry sync, so a vanilla client can still join a server that runs this mod.

## Shape

A chest and a barrel both hold 27 slots, laid out 9 columns by 3 rows. What shape counts is defined by `patterns` in the config. The default is one pattern: a 4-column, 3-row obsidian ring with a powered rail on the left of the two enclosed slots and a minecart on the right.

```
      0  1  2  3  4  5  6  7  8
row0  .  O  O  O  O  .  .  .  .
row1  .  O  R  M  O  .  .  .  .
row2  .  O  O  O  O  .  .  .  .
```

`O` is obsidian, at least one per slot. `R` is powered rail, at least four. `M` is one minecart. `.` must be empty.

The ring is exactly ten slots around, the same obsidian count as a minimal nether portal frame.

The frame slides horizontally over columns 0 through 5, six valid placements in all. Its row position is fixed, because this pattern is three rows tall and fills the container. `mirror` is on by default, which also accepts the left-to-right reflection of the frame. For this symmetric ring the effect is that the two middle slots may hold the minecart on the left and the rail on the right. Changing the shape, the items or the per-slot counts is all done in the config, see the config section below.

Matching ignores data components, so a renamed or enchanted obsidian still counts. Crying obsidian does not, and neither does a plain rail, a detector rail or an activator rail.

The two halves of a double chest are judged separately, each holding its own ticket.

## Loaded area

The custom ticket's default load level is 30, which is radius 3. The level spreads outward from the centre chunk, adding one per chunk of Chebyshev distance. The result is a 3×3 core of entity-ticking chunks, a ring of block-ticking chunks around it, then a border ring outside that, 7×7 loaded in total. A vanilla portal ticket is level 30 radius 3, so the shape is identical.

| Level | Behavior |
| --- | --- |
| 31 and below | every mechanic works |
| 32 | entities are not spawned or ticked but stay accessible, block ticks do not run |
| 33 | read and write only, no mechanic runs |
| 34 and above | not loaded |

The ticket carries three flags: does load, does simulate, and keep dimension active. The last one is required. Each dimension has its own idle timer, and at 300 ticks the dimension stops ticking entities, block entities, the ender dragon fight and lightning. The timer only resets while some ticket in that dimension carries this flag. Without it, once every player leaves the Nether, the loader chunks in the Nether stay in memory but the machines still stop.

## When it checks

A player opening the container and a player closing it each trigger one check. Closing is checked too, because the player may have just taken something out.

A hopper pulling items out or pushing them in does not trigger those hooks, and once a chest loads its own chunk it will not fail on chunk unload either. So there is also a periodic scan: every `scanIntervalTicks` ticks each dimension walks its active list, re-checks each entry, and revokes the ones that no longer match.

When a container is broken, `ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD` revokes it at once. When the server stops, every ticket is cleared.

When several loader containers share one chunk, the mod keeps a reference count per chunk and only adds the ticket as the count goes from 0 to 1 and removes it as the count goes from 1 to 0. Vanilla ticket storage collapses tickets of the same type and level within a chunk into one, so without a reference count, revoking one container would drop the loading for the other as well.

A disabled loader is exempt from all of the above. Its chunk is usually not loaded, so there is nothing to read and nothing to count rounds against. Opening or closing it never reactivates it, only the enable command does. What the hooks and the scan do instead is opportunistic housekeeping: whenever a disabled loader's chunk happens to be readable and the container no longer holds the pattern, or the container is gone, the record is discarded and players nearby are told. A disabled container broken while its chunk was unloaded keeps its record until something looks at it again, at the latest the enable command itself, which checks the pattern once the chunk is back and discards the record on a miss. The `BLOCK_ENTITY_UNLOAD` revocation above deliberately skips disabled positions: that event also fires on a plain chunk unload, which for a disabled loader is everyday life, not a broken container.

## Persistence

Active positions are stored per dimension at `dimensions/<namespace>/<path>/data/chestloader/loaders.dat`, as a run of packed block positions. When the world reopens, the mod puts the tickets back from there, with no need for a player to open the container again or even to enter that dimension.

The custom ticket's persist flag is off, so it does not go through vanilla's ticket save. Vanilla saves tickets per chunk, with only a `chunk_pos` and no container position. A restored ticket could not be traced back to which chest placed it, and the periodic scan, the count limits and the per-chunk reference count would all fall out of step.

Storing the positions itself has another benefit: removing the mod is safe. `chunk_tickets.dat` holds no entry from this mod, and `loaders.dat` sits under the `chestloader` namespace directory, which vanilla never reads. This was tested: activate two loaders, stop the server, copy the save out and open it with a vanilla 26.2 server, and there is no error, with `forceload query` empty in both dimensions.

On restore the ticket goes back unconditionally, without reading the container. That is because reading a container needs its chunk loaded, and loading the chunk is exactly what the ticket is for. The re-check rides on the periodic scan. The scan first asks whether the chunk is loaded, skips the round and counts it once if not, and otherwise takes the block entity and runs the full check, revoking on a miss. Counting rounds rather than waiting a fixed delay avoids a wrong revocation when chunk loading is held up by something else. A position that stays unreadable for ten scans in a row is revoked with a warning logged, so stale data cannot hold a ticket forever.

Restore also runs three checks. Anything over `maxLoadersPerDimension` or `maxLoadersTotal` is dropped with a log line, because the limits may have been lowered between two starts. A position outside the world border or the height range is dropped. When a data-pack dimension is removed, its records are no longer read, along with its dimension directory.

Disabled positions live in the same file, under their own `disabled` field, and a position is never in both sets at once. A save from before the disable feature simply decodes with an empty disabled set. Going back the other way, an older version of the mod ignores the unknown field, so the disabled records are forgotten without loading anything they should not. On restore a disabled position gets no ticket and no pending check, only the out-of-bounds check; the count limits do not apply to it, because they bound what is loaded and a disabled loader loads nothing. For the same reason the limits are re-checked when a loader is enabled, and enabling fails with a message when they are full.

## Config

The file is at `config/chestloader.json`. It is written with default values on the first start, when it does not yet exist.

```json
{
  "patterns": [
    {
      "name": "obsidian-frame",
      "shape": [
        "OOOO",
        "ORMO",
        "OOOO"
      ],
      "keys": {
        "O": { "items": ["minecraft:obsidian"], "min": 1 },
        "R": { "items": ["minecraft:powered_rail"], "min": 4 },
        "M": {
          "items": [
            "minecraft:minecart",
            "minecraft:chest_minecart",
            "minecraft:hopper_minecart",
            "minecraft:furnace_minecart"
          ],
          "min": 1,
          "max": 1
        }
      },
      "slide": true,
      "mirror": true
    }
  ],
  "ticketLevel": 30,
  "scanIntervalTicks": 200,
  "maxLoadersPerDimension": 32,
  "maxLoadersTotal": 128,
  "notifyOnActivate": true,
  "particleOnActive": true
}
```

`patterns` is a list. A container activates when it matches any one entry. Within each entry:

`shape` draws the layout row by row. A `.` or a space is a slot that must stay empty, any other character is a key. There are at most 3 rows and at most 9 columns, and every row is the same length. `keys` maps each character to a set of items and a count range. Any one of the items in `items` satisfies the slot. `min` is the fewest that slot may hold, default 1, and `max` is the most, which defaults to a full stack of 64 when omitted. The count is judged per slot, not summed over the whole container. With `slide` on, the shape may sit at any row and column offset it fits at, and every slot it does not cover must be empty. With it off, the shape is pinned to the top-left corner. With `mirror` on, the left-to-right reflection of the shape counts as well.

An item in `items` that cannot be found is logged as a warning on start and skipped. When every item of a key resolves to nothing, that whole pattern is dropped and logged. When every pattern is dropped, no container ever activates. To allow a TNT minecart, add `minecraft:tnt_minecart` to `M`'s `items`. A spawner minecart and a command block minecart are best left out, because they cannot be obtained in survival. To use a different shape, items or counts, edit `shape`, `keys` and `min`/`max` directly.

`ticketLevel` can be tuned, but 32 and above leaves the centre chunk without entity ticking. The value is clamped to the 25 through 33 range.

`maxLoadersPerDimension` and `maxLoadersTotal` are count limits. If a loaded chunk itself holds another loader container, it chains outward, and without a limit one chain can drag in a large number of chunks. Over the limit, activation is refused and the triggering player gets a chat message.

## Commands

```
/chestloader list
```

Lists every tracked container, enabled and disabled, grouped by dimension, along with the ticket level, radius and loaded side length. A position not yet re-checked after a restart or a remote enable is marked as such, and a disabled one is marked `disabled`. In the chat, each entry ends in a clickable `[disable]` or `[enable]` button that runs the matching command; the button spells the dimension out, so it works from any dimension. The console shows the buttons as plain text.

```
/chestloader disable <x> <y> <z> [dimension]
/chestloader enable <x> <y> <z> [dimension]
```

`disable` releases the loading of an active loader but keeps it on record; the container stays where it is and the chunk unloads like any other. If another loader shares the chunk, the chunk stays loaded and the reply says so. `enable` gives a disabled loader its ticket back from anywhere: the chunk does not need to be loaded, and nobody needs to travel there. Since reading the container needs the chunk and loading the chunk is what the ticket is for, a remote enable works like the restore after a restart: ticket first, then the periodic scan runs the first check once the chunk is readable, revoking on a miss. When the chunk happens to be readable already, the check runs on the spot, and enabling a container that no longer holds the pattern discards the record instead. Without the dimension argument the command acts in the sender's dimension.

`list`, `enable` and `disable` need the moderator permission level (1), so trusted players can manage the loading without full command access. `check` drives the whole activation path and keeps the gamemaster level (2).

```
/chestloader check <x> <y> <z>
```

Runs the same check as opening the container at the given position, activating on a match and revoking on a miss. On a disabled position it reports the disabled state and never activates. Use it to read a position's state without touching the container. It is also the only way to drive the whole activation path from the console, which helps when verifying on a headless server.

## Player feedback

On activation, the player who opened the container gets a message that says the chunk is now force-loaded and reminds them the container can no longer be used for storage. On deactivation, players within 32 blocks get a message. With `particleOnActive` on, an active container emits a few portal particles above it each second.

The chat messages are English literals and do not go through a translation key, so any client shows them correctly.

## Known limits

Loading is not spawning. Only a chunk whose centre is within 128 blocks of a player runs chunk ticks, and a hostile mob spawned more than 128 blocks from every player despawns at once. This loader cannot drive a mob farm.

Chunk borders cut some mechanics off. A fluid or fire spreading into the first block past the block-ticking area stalls, and an entity walking from an entity-ticking chunk into a block-ticking one stops moving. A machine has to sit entirely within the central 3×3 entity-ticking area.

An active container is filled by this shape and cannot store anything else, nor take a hopper input.

After a restart the loaded positions come back at once, but the re-check waits for the next scan cycle. During that window a container broken while offline still holds its ticket, for at most one `scanIntervalTicks`. A remote enable has the same window.

A loader dismantled while disabled goes unnoticed for as long as its chunk stays unloaded, so `/chestloader list` can carry a stale disabled entry until something looks at the chunk again. And once a disabled record is discarded, re-forming the pattern in the same container starts a brand-new loader, activated by the usual open-and-check path; the mod does not remember that it was once disabled.

## How to verify

Place an automatic machine in a loaded chunk, for example a hopper clock driving a piston, walk out of the loaded area, and come back a few minutes later to confirm the machine is still running.

Place a loader in the Nether, have every player return to the Overworld and wait more than 300 ticks, and confirm the Nether machine has not stopped. This one is aimed squarely at keep dimension active.

Watch the chunk borders with `F3` plus `G`, together with `/chestloader list`, to confirm the loaded area is 7×7.

Take one obsidian out of an active container and close it, and confirm the loading is revoked.

Push one item into an active container with a hopper, wait one scan cycle, and confirm the loading is revoked.

Break an active container and confirm the loading is revoked with nothing left behind.

Activate a loader, stop the server and restart. `/chestloader list` should list it right away and mark it as not yet re-checked, and the mark should clear after one scan cycle.

Place a loader in the Nether, restart with every player staying in the Overworld, and confirm the Nether machine keeps running.

Stop the server, hand-edit the container's contents to break them, and restart. Confirm the loading is revoked after one scan cycle.

Disable an active loader from `/chestloader list`, walk away, and confirm with `F3` plus `G` that the chunks unload. Restart the server and confirm the loader is still listed as disabled.

From another dimension, click `[enable]` on a disabled loader, wait one scan cycle, and confirm the machine at the loader is running again without anyone having gone there.

Disable a loader, break its pattern while the chunk is loaded, and confirm the record disappears from the list. Repeat with the chunk unloaded, and confirm the record survives until `enable` discards it.

## Next

Client-side recoloring is not done yet. A barrel is a normal block model and a chest goes through a block entity renderer, so the two take different tint paths, and the active state would need syncing to the client.
