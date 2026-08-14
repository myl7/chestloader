# Chest Loader

[![CurseForge Downloads](https://img.shields.io/curseforge/dt/1638282?logo=curseforge&label=CurseForge&color=F16436)](https://www.curseforge.com/minecraft/mc-mods/chestloader)

Chest Loader is a server-side Fabric mod that turns a chest or barrel into a chunk loader made from vanilla items. Vanilla clients can join without installing the mod.

This document is also available in [中文](README.zh.md).

## Minecraft version

- 26.2

## Installation

Install Fabric Loader on the server, then put Fabric API and the Chest Loader JAR in the server's `mods` directory. Clients do not need Chest Loader or Fabric API.

For single-player use, install the same files in the local game instance.

## Usage

Place the configured pattern in a chest or barrel, then open the container. The default pattern uses ten obsidian, at least four powered rails in one slot, and one minecart:

```text
O O O O
O R M O
O O O O
```

`O` is obsidian, `R` is powered rail, and `M` is a minecart. Every slot outside the pattern must be empty. The pattern may start in any column where it fits. The rail and minecart may trade places.

The loader keeps a 7×7 chunk area loaded in its own dimension. The central 3×3 chunks process entities. The loader remains active after a server restart.

Removing an item from the pattern deactivates the loader when the container closes. Hopper changes may take up to one scan interval to register.

## Configuration

Chest Loader creates `config/chestloader.json` on the first start:

```json
{
  "patterns": [
    {
      "name": "obsidian-frame",
      "dimensions": ["minecraft:overworld", "minecraft:the_nether"],
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

Each entry in `patterns` defines one accepted layout.

- `shape` draws the layout one row at a time. A `.` or space requires an empty slot. Other characters refer to entries in `keys`.
- `keys` lists the accepted items and the minimum and maximum count for each slot.
- `slide` lets the pattern start at any position where it fits. Slots outside the pattern must stay empty.
- `mirror` also accepts a left-to-right reflection of the pattern.
- `dimensions` lists where the pattern works. Add `minecraft:the_end` to enable it in the End.

You can add more entries to `patterns` for other layouts or dimensions.

`ticketLevel` controls the loaded range. The default value, 30, loads a 7×7 area with a 3×3 entity-processing center. Values 32 and 33 do not process entities in the center.

`scanIntervalTicks` controls how often the mod checks active loaders for hopper changes. `maxLoadersPerDimension` and `maxLoadersTotal` cap the number of active loaders. The two feedback options control activation messages and portal particles.

## Commands

```text
/chestloader list
/chestloader disable <x> <y> <z> [dimension]
/chestloader enable <x> <y> <z> [dimension]
/chestloader check <x> <y> <z>
```

`list` shows every tracked loader and provides clickable enable or disable buttons in chat. `disable` releases its chunks but keeps the loader on the list. `enable` restores it from any location. These commands require permission level 1.

`check` evaluates the container at the given position. It requires permission level 2.

## Limits

- An active loader uses the whole container, so you cannot use that container for storage.
- Chunk loading does not bypass Minecraft's mob spawning and despawning distance rules. The loader cannot run a mob farm without a nearby player.
- Keep a machine inside the central 3×3 chunks if it needs entity processing.
- A disabled loader broken while its chunk is unloaded may remain in `list` until the chunk loads or someone tries to enable it.

## Build and test

Install JDK 25, then run:

```bash
./gradlew test
./gradlew runGameTest
./gradlew build
```

The built JAR is written to `build/libs/`.

## License

[Apache License 2.0](LICENSE)
