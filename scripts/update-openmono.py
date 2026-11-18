#!/usr/bin/env python3
"""Refreshes the bundled OpenMono fonts from an OpenMono app build.

Converts the build's text-only WOFF2 files to the TTFs Android needs
(`core/src/main/res/font/openmono_*.ttf`) and copies its licence to
`docs/licenses/OpenMono-OFL.txt`. Needs fontTools with WOFF2 support:

    uvx --from 'fonttools[woff]' python scripts/update-openmono.py <app build folder>

The folder holds `woff2/OpenMonoNerd-<Style>-text.woff2` and `LICENSE`. The text-only files have
every text character and none of the Nerd Fonts icons. Each font is checked before it is written:
its weight and italic flags match the file it becomes (`OpenCodeFonts.monospace` declares them),
every character is one cell wide, and it has no ligatures. The same input gives the same bytes.
"""

import shutil
import sys
from pathlib import Path

from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent.parent
FONTS = ROOT / "core/src/main/res/font"
LICENSE = ROOT / "docs/licenses/OpenMono-OFL.txt"

# style in the build's file names -> (resource name, OS/2 weight, italic)
STYLES = {
    "Regular": ("openmono_regular", 400, False),
    "Bold": ("openmono_bold", 700, False),
    "Italic": ("openmono_italic", 400, True),
    "BoldItalic": ("openmono_bold_italic", 700, True),
}
LIGATURE_FEATURES = {"liga", "clig", "dlig", "hlig", "rlig", "calt"}
# The text-only fonts have about 3,000 glyphs; the ones with icons have about 14,000.
MAX_GLYPHS = 5000


def check(font: TTFont, source: Path, weight: int, italic: bool) -> None:
    def fail(message: str) -> None:
        sys.exit(f"{source.name}: {message}")

    os2, head, post = font["OS/2"], font["head"], font["post"]
    if os2.usWeightClass != weight:
        fail(f"weight is {os2.usWeightClass}, expected {weight}")
    flags = (bool(os2.fsSelection & 1), bool(head.macStyle & 2), post.italicAngle != 0)
    if flags != (italic, italic, italic):
        fail(f"italic flags are {flags}, expected {italic}")
    if bool(os2.fsSelection & 32) != (weight >= 700) or bool(head.macStyle & 1) != (weight >= 700):
        fail("bold flags don't match the weight")
    if not post.isFixedPitch:
        fail("not marked fixed pitch")
    metrics = font["hmtx"].metrics
    cell = metrics[font.getBestCmap()[ord("m")]][0]
    widths = {metrics[glyph][0] for glyph in font.getBestCmap().values()} - {0, cell}
    if widths:
        fail(f"characters wider or narrower than one cell ({cell}): {sorted(widths)}")
    features = {record.FeatureTag for record in font["GSUB"].table.FeatureList.FeatureRecord} if "GSUB" in font else set()
    if features & LIGATURE_FEATURES:
        fail(f"has ligature features {sorted(features & LIGATURE_FEATURES)}")
    if font["maxp"].numGlyphs > MAX_GLYPHS:
        fail(f"{font['maxp'].numGlyphs} glyphs: this is not the text-only file")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    build = Path(sys.argv[1]).expanduser()
    fonts = []
    for style, (resource, weight, italic) in STYLES.items():
        source = build / "woff2" / f"OpenMonoNerd-{style}-text.woff2"
        if not source.is_file():
            sys.exit(f"missing {source}")
        font = TTFont(source, recalcTimestamp=False)
        check(font, source, weight, italic)
        font.flavor = None
        fonts.append((font, FONTS / f"{resource}.ttf"))
    if not (build / "LICENSE").is_file():
        sys.exit(f"missing {build / 'LICENSE'}")
    for font, target in fonts:
        font.save(target)
        print(f"{target.relative_to(ROOT)}: {target.stat().st_size:,} bytes, {font['maxp'].numGlyphs:,} glyphs, {font['name'].getDebugName(5)}")
    shutil.copyfile(build / "LICENSE", LICENSE)
    print(f"{LICENSE.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
