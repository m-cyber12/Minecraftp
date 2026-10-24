# CraftRealm resource pack

The optional pack that gives CraftRealm its *craftsman's workshop* look: parchment
plaques for every menu icon, a struck gold coin for the Crown item and a signed
parchment note for banknotes. It is **not required** — without it the plugin falls
back to plain vanilla materials and still works exactly the same, only plainer.

```
resourcepack/
├── pack.mcmeta                     # pack_format 46 (Minecraft 1.21.2 – 1.21.4)
├── pack.png                        # 128×128 pack icon
├── assets/
│   ├── craftrealm/
│   │   ├── textures/item/gui/*.png        # 25 hand-drawn 16×16 plaques
│   │   ├── textures/item/currency/*.png   # coin.png, note.png
│   │   └── models/item/gui/*.json         # item/generated wrappers
│   │       models/item/currency/*.json
│   └── minecraft/models/item/*.json       # 18 vanilla items gaining CMD overrides
└── tools/generate_textures.py      # regenerates every PNG + JSON (Pillow)
```

## Regenerating

Every texture and model in `assets/` is generated, never hand-edited. From the
repository root:

```bash
python3 -m pip install pillow
python3 resourcepack/tools/generate_textures.py
```

Output is deterministic (no timestamps, fixed pixel operations), so a re-run on
unchanged code produces a byte-identical tree and an empty `git diff`. To restyle
an icon, edit its entry in the `ICONS` table or its drawing function inside the
script, then re-run it — do not touch the PNGs directly.

## How the icons are addressed

`GuiTheme` attaches `custom_model_data = offset + index` to each menu icon, where
`offset` is `theme.custom-model-data.offset` in `config.yml` (default `10000`) and
`index` is the icon's position in the `GuiTheme.Icon` enum. The order in the enum
and the order of the `ICONS` table in the generator **must stay identical**, because
the pack's `minecraft:item/<material>.json` override files are keyed by that number.

```
0 bank        1 jobs       2 punishments   3 events     4 settings
5 players     6 economy    7 health        8 logs       9 world
10 shop      11 auction   12 claims       13 quests    14 cosmetics
15 seasons   16 bounty    17 back         18 close     19 next
20 previous  21 confirm   22 cancel       23 mailbox   24 treasure
```

Currency items use a separate range so they never collide with menu skins:
`offset + 500` for the Crown coin (a `gold_nugget`) and `offset + 501` for the
banknote (a `paper`), as set in `CurrencyItems.java`. Those two vanilla models
therefore carry the extra overrides alongside `paper`'s quests icon at 10013.

Turning `theme.custom-model-data.enabled` off in `config.yml` makes the plugin
stop sending the predicate entirely; the pack can stay installed, the items just
render vanilla.

## Shipping it to players

The pack has to reach the client before the artwork shows up. Package it with
Gradle, then hand the zip to your server:

```bash
./gradlew :craftrealm-plugin:buildResourcePack
# -> craftrealm-plugin/build/dist/CraftRealm-ResourcePack.zip
```

```properties
# server.properties — vanilla push, players get an accept/decline prompt
resource-pack=https://example.com/CraftRealm-ResourcePack.zip
resource-pack-sha1=<sha1 of the zip>
require-resource-pack=false
```

CraftRealm does not serve the pack itself; host the zip on any static web server
(or the same reverse proxy in front of the web panel) and put that URL here. Keep
`require-resource-pack=false` — a declined pack only costs the player the plaques,
it must never stop them from joining.

## Vanilla safety rules

The pack only replaces item models that are flat `item/generated` sprites (plus
`template_skull` and `template_spawn_egg`, which draw their own layers). It never
touches items whose rendering depends on an animated model or a block parent, so
these stay **strictly vanilla**:

- `clock` (animated frames drive its dial)
- `compass` (animated; also re-pointed at lodestone by vanilla code)
- `chest` (block-entity parent)
- `oak_fence`, `grass_block` (block parents)
- `tnt` (fuse/primed states share the model)
- `comparator`, `redstone_lamp` (texture swap driven by block state)

Those eight icons — punishments (TNT), events (clock), settings (comparator),
health (redstone lamp), world (grass block), claims (oak fence), mailbox (chest),
treasure (compass) — still work in every menu, they simply keep the vanilla sprite
instead of a parchment plaque. The other 17 icons get their plaque.
Breaking a vanilla item to gain one skin is a bad trade, so the generator skips
these via its `SAFE_MATERIALS` allow-list.

## pack_format per Minecraft version

| Minecraft | `pack_format` |
|-----------|---------------|
| 1.21.4    | 46            |
| 1.21.2–3  | 42            |
| 1.21–1.21.1 | 34          |
| 1.20.5–1.20.6 | 32        |

Only the resource-pack format changes between those versions; the model and
texture layout is identical. Edit `pack_format` in `pack.mcmeta`, rebuild the zip
and bump the sha1 in `server.properties`. The plugin itself supports 1.21.x and
does not read the pack file at runtime.

## Licence

The artwork is generated for this project and ships under the repository's MIT
licence (see `../LICENSE`). No Mojang assets are redistributed: the override models
re-use texture paths that already exist in the client.
