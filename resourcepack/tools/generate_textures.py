#!/usr/bin/env python3
"""
CraftRealm resource pack texture generator.

Run from the repository root:

    python3 resourcepack/tools/generate_textures.py

It rewrites every PNG under resourcepack/assets/ and every model JSON, using only
Pillow. The output is deterministic, so re-running it produces byte-identical
files and a clean `git diff`.

Design rules (the same rules as the in-game menus):

* 16x16, hard pixel edges, no gradients, no glow.
* Parchment face, spruce border, one accent per icon.
* Every glyph is hand-drawn on an 10x10 grid; nothing is scaled or blurred,
  because a blurry 16x16 icon looks like a mistake.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "assets"

# ----------------------------------------------------------------------------- palette

PARCHMENT = (232, 216, 176, 255)
PARCHMENT_DARK = (214, 195, 150, 255)
SPRUCE = (107, 74, 47, 255)
OAK = (176, 125, 79, 255)
GOLD = (232, 179, 57, 255)
COPPER = (199, 123, 83, 255)
REDSTONE = (192, 57, 43, 255)
EMERALD = (47, 191, 113, 255)
LAVA = (242, 107, 33, 255)
DEEPSLATE = (54, 57, 61, 255)

CLEAR = (0, 0, 0, 0)

# ----------------------------------------------------------------------------- glyphs
#
# '.' parchment   '#' ink (spruce)   '+' accent   'o' deepslate   ' ' transparent
# The block is 10x10 and is pasted 3px in from the left and top of the 16px tile.

GLYPHS: dict[str, list[str]] = {
    "bank": [  # a struck coin with a crown mark
        "  ######  ",
        " ##++++## ",
        "##+####+##",
        "#+##++##+#",
        "#+#++++#+#",
        "#+#++++#+#",
        "#+##++##+#",
        "##+####+##",
        " ##++++## ",
        "  ######  ",
    ],
    "jobs": [  # a wheat sheaf, three stalks
        "  #  #  # ",
        "  #+ #+ #+",
        "  ## ## ##",
        "   #  #  #",
        "   #+ #+ #",
        "   ## ## #",
        "    #  #  ",
        "    #+ #+ ",
        "    ## ## ",
        "     #### ",
    ],
    "punishments": [  # a powder keg
        "  ######  ",
        " #++++++# ",
        " #+####+# ",
        " #+#++#+# ",
        " #+#++#+# ",
        " #+####+# ",
        " #++++++# ",
        " ######## ",
        "  ##  ##  ",
        "  ##  ##  ",
    ],
    "events": [  # a clock face
        "  ooooooo ",
        " oo####oo ",
        " o###+### ",
        " o###+### ",
        " o###++## ",
        " o###++## ",
        " o######o ",
        " oo####oo ",
        "  ooooooo ",
        "          ",
    ],
    "settings": [  # a slider on a rail
        "          ",
        " ######## ",
        " #      # ",
        " #  ##  # ",
        " # #++# # ",
        " # #++# # ",
        " #  ##  # ",
        " #      # ",
        " ######## ",
        "          ",
    ],
    "players": [  # a head and shoulders
        "  ######  ",
        " ##++++## ",
        " #+#++#+# ",
        " #+#++#+# ",
        " ##++++## ",
        "  ##++##  ",
        " ######## ",
        " #########",
        " #########",
        " ######## ",
    ],
    "economy": [  # stacked ingots
        "   ####   ",
        "  #++++#  ",
        "  ######  ",
        " ######## ",
        " #++++++# ",
        " ######## ",
        "##########",
        "#++++++++#",
        "##########",
        "          ",
    ],
    "health": [  # a redstone lamp, lit
        "  ######  ",
        " #++++++# ",
        " #+####+# ",
        " #+#++#+# ",
        " #+#++#+# ",
        " #+####+# ",
        " #++++++# ",
        " ######## ",
        "  ##  ##  ",
        "          ",
    ],
    "logs": [  # an open ledger
        " ######## ",
        " #++++++# ",
        " #+####+# ",
        " #+#++#+# ",
        " #+#++#+# ",
        " #+####+# ",
        " #++++++# ",
        " ######## ",
        "   ##  #  ",
        "          ",
    ],
    "world": [  # a hill with a tree
        "     ##   ",
        "    #++#  ",
        "    ####  ",
        "     ##   ",
        "   ###### ",
        "  ########",
        " ######## ",
        "##########",
        "##########",
        " oooooooo ",
    ],
    "shop": [  # a market stall awning
        "##########",
        "#+#+#+#+#+",
        " #+#+#+#+ ",
        " ######## ",
        "  #+##+#  ",
        "  #+##+#  ",
        "  #+##+#  ",
        "  #+##+#  ",
        "  ######  ",
        "          ",
    ],
    "auction": [  # a gavel
        "   #####  ",
        "  #+++++# ",
        "  ####### ",
        "    ###   ",
        "   ####   ",
        "  ####    ",
        "  ###     ",
        " ###      ",
        "###       ",
        "          ",
    ],
    "claims": [  # a fence post
        " #+#  #+# ",
        " #+#  #+# ",
        " #########",
        " #+#  #+# ",
        " #+#  #+# ",
        " #+#  #+# ",
        " #########",
        " #+#  #+# ",
        " #+#  #+# ",
        "          ",
    ],
    "quests": [  # a parchment scroll with a seal
        " ######## ",
        " #++++++# ",
        " #+#+++#+ ",
        " #++++++# ",
        " #+#+++#+ ",
        " #++++++# ",
        " #+#+++#+ ",
        " #+++++++ ",
        " ######## ",
        "   ++++   ",
    ],
    "cosmetics": [  # a crystal shard
        "    ##    ",
        "   #++#   ",
        "  #++++#  ",
        "  #++++#  ",
        "  #++++#  ",
        "   #++#   ",
        "   #++#   ",
        "    ##    ",
        "    ##    ",
        "          ",
    ],
    "seasons": [  # a sun over a furrow
        "   ####   ",
        "  #++++#  ",
        " ##++++## ",
        " #+#++#+# ",
        " ##++++## ",
        "  #++++#  ",
        "   ####   ",
        " ######## ",
        " ## ## ## ",
        "          ",
    ],
    "bounty": [  # a sword
        "    ##    ",
        "   #++#   ",
        "   #++#   ",
        "   #++#   ",
        "   #++#   ",
        "  ######  ",
        "   #++#   ",
        "   #++#   ",
        "    ##    ",
        "          ",
    ],
    "back": [  # a doorway
        " ######## ",
        " #o####o# ",
        " #o#++#o# ",
        " #o#++#o# ",
        " #o####o# ",
        " #o####o# ",
        " #o####o# ",
        " #o####o# ",
        " #o#++#o# ",
        " ######## ",
    ],
    "close": [  # a cross
        "          ",
        "  ##  ##  ",
        "  ##  ##  ",
        "   ####   ",
        "    ##    ",
        "    ##    ",
        "   ####   ",
        "  ##  ##  ",
        "  ##  ##  ",
        "          ",
    ],
    "next": [  # arrow right
        "          ",
        "   #      ",
        "   ##     ",
        "   #+#    ",
        "   #++#   ",
        "   #++#   ",
        "   #+#    ",
        "   ##     ",
        "   #      ",
        "          ",
    ],
    "previous": [  # arrow left
        "          ",
        "      #   ",
        "     ##   ",
        "    #+#   ",
        "   #++#   ",
        "   #++#   ",
        "    #+#   ",
        "     ##   ",
        "      #   ",
        "          ",
    ],
    "confirm": [  # a tick
        "          ",
        "        # ",
        "       ## ",
        "      ##  ",
        " #   ##   ",
        " ## ##    ",
        "  ###     ",
        "   #      ",
        "          ",
        "          ",
    ],
    "cancel": [  # a cross, thinner
        "          ",
        " ##    ## ",
        "  ##  ##  ",
        "   ####   ",
        "    ##    ",
        "    ##    ",
        "   ####   ",
        "  ##  ##  ",
        " ##    ## ",
        "          ",
    ],
    "mailbox": [  # a strongbox
        " ######## ",
        " #++++++# ",
        " #+#++#+# ",
        " #+#++#+# ",
        " #++++++# ",
        " #+####+# ",
        " #+#++#+# ",
        " #+####+# ",
        " #++++++# ",
        " ######## ",
    ],
    "treasure": [  # a compass rose
        "    ##    ",
        "   #++#   ",
        "  ##++##  ",
        " #++##++# ",
        " #+####+# ",
        " #+####+# ",
        " #++##++# ",
        "  ##++##  ",
        "   #++#   ",
        "    ##    ",
    ],
}

# accent colour per glyph
ACCENT = {
    "bank": GOLD,
    "jobs": EMERALD,
    "punishments": REDSTONE,
    "events": COPPER,
    "settings": DEEPSLATE,
    "players": OAK,
    "economy": EMERALD,
    "health": REDSTONE,
    "logs": OAK,
    "world": EMERALD,
    "shop": EMERALD,
    "auction": GOLD,
    "claims": OAK,
    "quests": GOLD,
    "cosmetics": COPPER,
    "seasons": GOLD,
    "bounty": REDSTONE,
    "back": OAK,
    "close": REDSTONE,
    "next": OAK,
    "previous": OAK,
    "confirm": EMERALD,
    "cancel": REDSTONE,
    "mailbox": COPPER,
    "treasure": GOLD,
    "coin": GOLD,
    "note": PARCHMENT_DARK,
}

# Icon enum order and material — must stay in sync with GuiTheme.Icon.
ICONS = [
    ("bank", "gold_ingot"),
    ("jobs", "villager_spawn_egg"),
    ("punishments", "tnt"),
    ("events", "clock"),
    ("settings", "comparator"),
    ("players", "player_head"),
    ("economy", "diamond"),
    ("health", "redstone_lamp"),
    ("logs", "writable_book"),
    ("world", "grass_block"),
    ("shop", "emerald"),
    ("auction", "golden_horse_armor"),
    ("claims", "oak_fence"),
    ("quests", "paper"),
    ("cosmetics", "amethyst_shard"),
    ("seasons", "sunflower"),
    ("bounty", "iron_sword"),
    ("back", "oak_door"),
    ("close", "barrier"),
    ("next", "spectral_arrow"),
    ("previous", "arrow"),
    ("confirm", "lime_dye"),
    ("cancel", "red_dye"),
    ("mailbox", "chest"),
    ("treasure", "compass"),
]

# Materials whose vanilla item model can be replaced safely: a flat generated
# texture (or a template that draws its own). Clock and compass animate via
# model overrides and chest/fence are block parents, so those keep the vanilla
# look — the pack must never break a vanilla item.
SAFE_MATERIALS = {
    "gold_ingot", "villager_spawn_egg", "player_head", "diamond", "writable_book",
    "emerald", "golden_horse_armor", "paper", "amethyst_shard", "sunflower",
    "iron_sword", "oak_door", "barrier", "spectral_arrow", "arrow", "lime_dye",
    "red_dye", "gold_nugget",
}

TEMPLATE_PARENTS = {
    "player_head": "minecraft:item/template_skull",
    "villager_spawn_egg": "minecraft:item/template_spawn_egg",
}


# ----------------------------------------------------------------------------- helpers

def blank() -> Image.Image:
    return Image.new("RGBA", (16, 16), PARCHMENT)


def frame(image: Image.Image) -> None:
    """Spruce border with a lighter top-left bevel, the 'carved wood' look."""
    pixels = image.load()
    for i in range(16):
        pixels[i, 0] = SPRUCE
        pixels[i, 15] = SPRUCE
        pixels[0, i] = SPRUCE
        pixels[15, i] = SPRUCE
    for i in range(1, 15):
        pixels[i, 1] = PARCHMENT_DARK
        pixels[1, i] = PARCHMENT_DARK


def draw_glyph(image: Image.Image, glyph: list[str], accent: tuple[int, int, int, int]) -> None:
    pixels = image.load()
    for y, row in enumerate(glyph):
        for x, cell in enumerate(row):
            if cell == " ":
                continue
            colour = {
                "#": SPRUCE,
                "+": accent,
                "o": DEEPSLATE,
                ".": PARCHMENT,
            }[cell]
            pixels[3 + x, 3 + y] = colour


def plaque(key: str) -> Image.Image:
    image = blank()
    frame(image)
    draw_glyph(image, GLYPHS[key], ACCENT[key])
    return image


def coin_texture() -> Image.Image:
    """A struck coin: gold disc, spruce rim, crown mark."""
    image = Image.new("RGBA", (16, 16), CLEAR)
    pixels = image.load()
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            distance = (dx * dx + dy * dy) ** 0.5
            if distance <= 7.2:
                pixels[x, y] = GOLD if distance > 6.0 else (255, 214, 102, 255)
    # square the corners so it reads as pixel art, not an anti-aliased circle
    for (x, y) in [(0, 0), (1, 0), (0, 1), (15, 0), (14, 0), (15, 1),
                   (0, 15), (1, 15), (0, 14), (15, 15), (14, 15), (15, 14)]:
        pixels[x, y] = CLEAR
    crown = [
        "#..#..#",
        "#..#..#",
        "######.",
        ".####..",
    ]
    for y, row in enumerate(crown):
        for x, cell in enumerate(row):
            if cell == "#":
                pixels[5 + x, 6 + y] = SPRUCE
    return image


def note_texture() -> Image.Image:
    """A signed banknote: parchment sheet, red seal, gold rule."""
    image = Image.new("RGBA", (16, 16), CLEAR)
    pixels = image.load()
    for y in range(2, 14):
        for x in range(1, 15):
            pixels[x, y] = PARCHMENT if y not in (2, 13) else PARCHMENT_DARK
    for x in range(2, 14):
        pixels[x, 2] = SPRUCE
        pixels[x, 13] = SPRUCE
    for y in range(3, 13):
        pixels[1, y] = SPRUCE
        pixels[14, y] = SPRUCE
    for x in range(4, 12):
        pixels[x, 6] = GOLD
        pixels[x, 10] = GOLD
    for y in range(8, 10):
        pixels[11, y] = REDSTONE
    pixels[10, 8] = REDSTONE
    pixels[12, 8] = REDSTONE
    return image


def write_png(path: Path, image: Image.Image) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2)
        handle.write("\n")


# ----------------------------------------------------------------------------- build

def build(offset: int = 10000) -> None:
    for name, _material in ICONS:
        image = plaque(name)
        write_png(ASSETS / "craftrealm" / "textures" / "item" / "gui" / f"{name}.png", image)

        model = {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"craftrealm:item/gui/{name}"},
        }
        write_json(ASSETS / "craftrealm" / "models" / "item" / "gui" / f"{name}.json", model)

    write_png(ASSETS / "craftrealm" / "textures" / "item" / "currency" / "coin.png", coin_texture())
    write_png(ASSETS / "craftrealm" / "textures" / "item" / "currency" / "note.png", note_texture())
    write_json(ASSETS / "craftrealm" / "models" / "item" / "currency" / "coin.json", {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "craftrealm:item/currency/coin"},
    })
    write_json(ASSETS / "craftrealm" / "models" / "item" / "currency" / "note.json", {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "craftrealm:item/currency/note"},
    })

    # One vanilla item model per touched material, carrying the CMD overrides.
    overrides: dict[str, list[dict]] = {}
    for index, (name, material) in enumerate(ICONS):
        if material not in SAFE_MATERIALS:
            continue
        overrides.setdefault(material, []).append({
            "predicate": {"custom_model_data": offset + index},
            "model": f"craftrealm:item/gui/{name}",
        })
    # Currency tokens live 500 numbers away from the GUI icon block.
    overrides.setdefault("gold_nugget", []).append({
        "predicate": {"custom_model_data": offset + 500},
        "model": "craftrealm:item/currency/coin",
    })
    overrides.setdefault("paper", []).append({
        "predicate": {"custom_model_data": offset + 501},
        "model": "craftrealm:item/currency/note",
    })

    for material, entries in sorted(overrides.items()):
        entries.sort(key=lambda entry: entry["predicate"]["custom_model_data"])
        if material in TEMPLATE_PARENTS:
            model = {"parent": TEMPLATE_PARENTS[material], "overrides": entries}
        else:
            model = {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": f"minecraft:item/{material}"},
                "overrides": entries,
            }
        write_json(ASSETS / "minecraft" / "models" / "item" / f"{material}.json", model)

    # Pack icon: a small oak-framed parchment tile.
    icon = plaque("bank").resize((128, 128), Image.NEAREST)
    write_png(ROOT / "pack.png", icon)

    print(f"wrote {len(ICONS)} icon plaques, 2 currency textures and "
          f"{len(overrides)} vanilla model overrides")


if __name__ == "__main__":
    os.chdir(ROOT)
    build()
