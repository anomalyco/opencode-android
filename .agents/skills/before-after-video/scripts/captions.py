#!/usr/bin/env python3
"""Reads a before/after video's video.json.

    captions.py names <video.json>               prints the segment names, in video order
    captions.py overlays <video.json> <out dir>  draws one overlay PNG per segment, prints the layout

An overlay is the header, the caption band, and transparent holes where the two phone videos show
through. The layout is printed as shell variables for compose.sh.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
# The app's own copy of Inter, four levels up at the repo root.
FONT = os.path.join(HERE, "..", "..", "..", "..", "core", "src", "main", "res", "font", "inter.ttf")

PHONE_W = 576
PHONE_H = 1280
GAP = 8
HEADER_H = 76
WIDTH = PHONE_W * 2 + GAP
MARGIN = 44
LINE_H = 36
MAX_LINES = 2


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ("names", "overlays"):
        sys.exit(__doc__)
    video = load(sys.argv[2])
    if sys.argv[1] == "names":
        for segment in video["segments"]:
            print(segment["name"])
        return
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    draw_all(video, sys.argv[3])


def load(path):
    with open(path, encoding="utf-8") as f:
        video = json.load(f)
    for key in ("left", "right", "segments"):
        if key not in video:
            sys.exit(f"{path}: missing \"{key}\"")
    seen = set()
    for segment in video["segments"]:
        name = segment.get("name", "")
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", name):
            sys.exit(f"{path}: segment name {name!r} must be lowercase letters, digits and dashes")
        if name in seen:
            sys.exit(f"{path}: segment {name} is listed twice")
        seen.add(name)
        if not segment.get("title") or not segment.get("caption"):
            sys.exit(f"{path}: segment {name} needs a title and a caption")
    return video


def draw_all(video, out):
    from PIL import Image, ImageDraw

    os.makedirs(out, exist_ok=True)
    body_font = font(25, "Regular")
    measure = ImageDraw.Draw(Image.new("RGB", (1, 1)))

    wrapped = []
    most = 1
    for segment in video["segments"]:
        lines = wrap(measure, segment["caption"], body_font, WIDTH - 2 * MARGIN)
        if len(lines) > MAX_LINES:
            print(f"warning: the {segment['name']} caption wraps to {len(lines)} lines; "
                  f"keep it to {MAX_LINES} so it can be read in time", file=sys.stderr)
        most = max(most, len(lines))
        wrapped.append(lines)

    # Every segment shares one band height, so the video size never changes between segments.
    caption_h = 104 + LINE_H * max(most, MAX_LINES)
    height = HEADER_H + PHONE_H + caption_h

    label_font = font(30, "SemiBold")
    for label in (video["left"], video["right"]):
        if measure.textlength(label, font=label_font) > PHONE_W - 24:
            print(f"warning: header label too wide for the phone: {label}", file=sys.stderr)

    for index, segment in enumerate(video["segments"], start=1):
        path = os.path.join(out, f"{index:02d}-{segment['name']}.png")
        draw_overlay(path, height, video["left"], video["right"], segment["title"], wrapped[index - 1])

    print(f"WIDTH={WIDTH} HEIGHT={height} PHONE_W={PHONE_W} PHONE_H={PHONE_H} GAP={GAP} HEADER_H={HEADER_H}")


def draw_overlay(path, height, left, right, title, lines):
    from PIL import Image, ImageDraw

    image = Image.new("RGBA", (WIDTH, height), (255, 255, 255, 255))
    draw = ImageDraw.Draw(image)

    # Header labels, centred over each phone.
    label_font = font(30, "SemiBold")
    for text, x in ((left, 0), (right, PHONE_W + GAP)):
        width = draw.textlength(text, font=label_font)
        draw.text((x + (PHONE_W - width) / 2, HEADER_H / 2), text, font=label_font,
                  fill=(17, 17, 17, 255), anchor="lm")
    draw.line([(0, HEADER_H - 1), (WIDTH, HEADER_H - 1)], fill=(224, 224, 224, 255), width=1)

    # Divider between the phones.
    draw.rectangle([PHONE_W, HEADER_H, PHONE_W + GAP - 1, HEADER_H + PHONE_H - 1], fill=(229, 229, 229, 255))

    # Holes where the phone videos show through.
    hole = (0, 0, 0, 0)
    draw.rectangle([0, HEADER_H, PHONE_W - 1, HEADER_H + PHONE_H - 1], fill=hole)
    draw.rectangle([PHONE_W + GAP, HEADER_H, WIDTH - 1, HEADER_H + PHONE_H - 1], fill=hole)

    # Caption band.
    top = HEADER_H + PHONE_H
    draw.line([(0, top), (WIDTH, top)], fill=(224, 224, 224, 255), width=1)
    y = top + 30
    draw.text((MARGIN, y), title, font=font(32, "SemiBold"), fill=(17, 17, 17, 255))
    y += 50
    body_font = font(25, "Regular")
    for line in lines:
        draw.text((MARGIN, y), line, font=body_font, fill=(80, 80, 80, 255))
        y += LINE_H
    image.save(path)


# Breaks a caption into lines that fit the width. A "\n" in the caption forces a break.
def wrap(draw, text, body_font, width):
    lines = []
    for paragraph in text.split("\n"):
        line = ""
        for word in paragraph.split():
            candidate = word if line == "" else line + " " + word
            if line != "" and draw.textlength(candidate, font=body_font) > width:
                lines.append(line)
                line = word
                continue
            line = candidate
        if line != "":
            lines.append(line)
    return lines


def font(size, weight):
    from PIL import ImageFont

    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_name(weight)
    return f


if __name__ == "__main__":
    main()
