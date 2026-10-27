# Emitter mappings

Each `*.json` file in `data/<any namespace>/pollution_emitters/` maps one block (or block tag) to a pollution
rate. The shipped mappings live under `data/pollutioncraft/pollution_emitters/<target mod id>/`; the folder
after `pollution_emitters/` names the mod the blocks belong to (`minecraft` here).

```json
{ "block": "minecraft:furnace", "rate": 1.0, "active_when": { "property": "lit", "value": "true" } }
```

A block that has no activity state, such as a torch, leaves `active_when` out. It is then **always active**: it
emits its `rate` every interval for as long as it stands in a loaded chunk where blocks tick.

```json
{ "block": "minecraft:torch", "rate": 0.01 }
```

| Field | Meaning |
|---|---|
| `block` or `tag` | exactly one: a block id, or a block tag id without `#` |
| `rate` | PU per interval while active; a number, finite and `>= 0`. 1 PU = one lit furnace for one interval |
| `active_when` | optional. Leave it out for an always-active block. If present it must be complete: an object with both fields below |
| `active_when.property` | a block-state property the block has (for example `lit`) |
| `active_when.value` | that property's value as a string (for example `"true"`) |

- The rate is multiplied by the `emissionMultiplier` config value (`/pollution tune emissionMultiplier ...`).
- An invalid entry is skipped with one log line naming the file; it never stops the mod loading. Invalid means a
  missing or duplicate target, a bad `rate`, an unknown block, tag, property or value, or an `active_when` that is
  not complete (not an object, empty, a property without a value, a value without a property, or an empty string).
  The mod does not guess what a half-written `active_when` meant.
- An unknown tag counts as an empty tag.
- If two entries can apply to the same block state, the one with the lowest resource id wins and a warning names both.
- A datapack overrides a rate by replacing the file with the same path. Mappings load on server start and on `/reload`.
- Without any mapping file the mod loads and emits nothing.

## The cap on tracked blocks per chunk

The engine remembers where the mapped blocks are, and once per interval it reads each one. To keep that cost and
memory bounded, it tracks at most `maxTrackedPerChunk` mapped blocks in one chunk (default 2,048; allowed 1 to
98,304; tunable with `/pollution tune maxTrackedPerChunk <n>`). Blocks past the cap are **not tracked and emit
nothing**. Their pollution is not merged into the others.

- **Which blocks are kept:** the ones with the lowest positions, in this order: the one furthest **west** (lowest
  x) first, then furthest **north** (lowest z), then **lowest** (lowest y). This is the same in a freshly loaded
  chunk, after a restart, and whatever order the blocks were placed in, so a capped chunk always emits for the same
  blocks.
- **If you place a block that ranks inside the cap** in a chunk that is already full, it takes a slot and the
  highest-ranked tracked block (the most east, south and up) stops emitting. A block that ranks outside the cap
  does nothing.
- **If you remove a tracked block** from a chunk that is over the cap, that slot is refilled by the next block in
  line. This happens within about a second of the next interval (the chunk is rescanned); for that moment the chunk
  emits for one block fewer than the cap.
- **When the cap changes** (for example with `/pollution tune`), every loaded chunk is rescanned within a few
  seconds. After you **lower** the cap, a chunk keeps its larger set until it is rescanned, so for a few seconds it
  can still emit for more blocks than the new cap. After you **raise** it, blocks that were left out are let in at
  that rescan.
- **The log:** a chunk found over the cap writes one line to the server log, at most once per minute per chunk,
  never one per block.
- This is a different limit from `maxEmittersPerChunk`, which caps the positions the pollution buffer attributes.
  If you set `maxTrackedPerChunk` above `maxEmittersPerChunk`, the extra blocks still emit and still count; they are
  only not named as the dominant source.
