# Emitter mappings

Each `*.json` file in `data/<any namespace>/pollution_emitters/` maps one block (or block tag) to a pollution
rate. The shipped mappings live under `data/pollutioncraft/pollution_emitters/<target mod id>/`; the folder
after `pollution_emitters/` names the mod the blocks belong to (`minecraft` here).

```json
{ "block": "minecraft:furnace", "rate": 1.0, "active_when": { "property": "lit", "value": "true" } }
```

| Field | Meaning |
|---|---|
| `block` or `tag` | exactly one: a block id, or a block tag id without `#` |
| `rate` | PU per interval while active; a number, finite and `>= 0`. 1 PU = one lit furnace for one interval |
| `active_when.property` | a block-state property the block has (for example `lit`) |
| `active_when.value` | that property's value as a string (for example `"true"`) |

- The rate is multiplied by the `emissionMultiplier` config value (`/pollution tune emissionMultiplier ...`).
- An invalid entry is skipped with one log line naming the file; it never stops the mod loading. Invalid means a
  missing or duplicate target, a bad `rate`, an unknown block, tag, property or value.
- An unknown tag counts as an empty tag.
- If two entries can apply to the same block state, the one with the lowest resource id wins and a warning names both.
- A datapack overrides a rate by replacing the file with the same path. Mappings load on server start and on `/reload`.
- Without any mapping file the mod loads and emits nothing.
