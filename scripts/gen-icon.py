#!/usr/bin/env python3
"""Generates Insula's app icon from one geometry definition.

The mark is an island seen from above: Insula is Latin for island, and an offline archive is
exactly that — a piece of the world you keep, cut off from the network on purpose. The palette is
the app's own Lagoon & Shore tokens, so the icon and the UI cannot drift apart.

Geometry lives here once and is emitted twice: as real Bezier curves for the SVG, and flattened to
polygons for the PNGs. Hand-drawing an SVG and hand-drawing the rasters separately is how the two
end up subtly different, and nobody notices until the 16px one looks wrong in a task switcher.

Rasters are drawn at 4x and downsampled, because Pillow has no anti-aliasing of its own.

Usage: python3 scripts/gen-icon.py   (writes branding/)
"""

import os
import struct
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "branding")
S = 1024  # nominal canvas; everything below is in this space

# Lagoon & Shore, straight from insula.css.
DEEP = (8, 63, 56)        # lagoon floor
LAGOON = (14, 124, 107)   # -insula-accent
SURF_C = (63, 201, 172)   # -insula-accent, dark theme
SAND = (244, 233, 212)    # shore
SAND_HI = (255, 252, 246)
SKY_LOW = (18, 148, 128)  # the lagoon lightening toward the horizon
RADIUS = 0.22 * S         # tile corner

# The mark is two islands rising out of a lagoon, seen from the water.
#
# Three earlier attempts drew the island from above — an irregular outline floating in water, then
# a coastline dividing the tile. Both read as decoration: a soft blob at icon size looks like an
# egg, and a wavy two-tone split looks like wallpaper. Neither says "island" to someone glancing at
# a task switcher. A landmass above a horizon is the one form that does, because it is the shape
# everybody already reads as an island, and it survives being 16 pixels wide: a light mass, a hard
# horizontal edge, a darker band underneath.
#
# Two islands rather than one. A single hump is a hill; a second, smaller one offshore is an
# archipelago, and archipelago is what a library of separate archives actually is.
# Squat and lobed, not peaked. A tall triangle over a horizon is the universal photo-placeholder
# glyph, and an island is flatter than a mountain anyway — the silhouette is what keeps this from
# being read as "missing image".
HORIZON = 572
BIG = [(120, HORIZON), (162, 494), (232, 428), (322, 394), (410, 402), (486, 442), (552, 504),
       (600, HORIZON)]
SMALL = [(646, HORIZON), (688, 520), (748, 484), (812, 498), (862, 538), (890, HORIZON)]
# One line of surf under each island, and nothing else. Three scattered dashes read as redacted
# text; two, placed where the land is, read as water.
SURF_BARS = [(352, 700, 176, 30), (766, 786, 104, 26)]


def catmull_to_bezier(pts, closed):
    n = len(pts)
    out = []
    span = n if closed else n - 1
    for i in range(span):
        p0 = pts[(i - 1) % n] if closed else pts[max(i - 1, 0)]
        p1, p2 = pts[i % n], pts[(i + 1) % n]
        p3 = pts[(i + 2) % n] if closed else pts[min(i + 2, n - 1)]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6.0, p1[1] + (p2[1] - p0[1]) / 6.0)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6.0, p2[1] - (p3[1] - p1[1]) / 6.0)
        out.append((c1, c2, p2))
    return out


def flatten(pts, closed=False, steps=28):
    poly = [pts[0]]
    cur = pts[0]
    for c1, c2, end in catmull_to_bezier(pts, closed):
        for st in range(1, steps + 1):
            t = st / steps
            u = 1 - t
            poly.append((
                u**3 * cur[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t**3 * end[0],
                u**3 * cur[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t**3 * end[1],
            ))
        cur = end
    return poly


def svg_path(pts):
    """Open curve over the top, closed with a straight base — a Catmull-Rom loop would round the
    two corners where the land meets the water, and a rounded waterline is not a waterline."""
    d = f"M {pts[0][0]:.1f} {pts[0][1]:.1f}"
    for c1, c2, end in catmull_to_bezier(pts, False):
        d += f" C {c1[0]:.1f} {c1[1]:.1f} {c2[0]:.1f} {c2[1]:.1f} {end[0]:.1f} {end[1]:.1f}"
    return d + " Z"


def render(px):
    """One PNG at px, drawn 4x and downsampled — Pillow has no anti-aliasing of its own."""
    k = 4
    n = px * k
    sc = n / S
    img = Image.new("RGBA", (n, n), LAGOON + (255,))
    d = ImageDraw.Draw(img)
    for y in range(int(HORIZON * sc)):
        t = y / max(1, HORIZON * sc)
        d.line([(0, y), (n, y)], fill=tuple(
            int(LAGOON[i] + (SKY_LOW[i] - LAGOON[i]) * t) for i in range(3)))

    # Painted edge to edge; the rounded tile becomes the alpha channel at the end, so the horizon
    # can run the full width without a bleed at the corners.
    d.rectangle([0, HORIZON * sc, n, n], fill=DEEP)
    for pts in (BIG, SMALL):
        d.polygon([(x * sc, y * sc) for x, y in flatten(pts)], fill=SAND)
    for cx, cy, half, h in SURF_BARS:
        d.rounded_rectangle(
            [(cx - half) * sc, (cy - h / 2) * sc, (cx + half) * sc, (cy + h / 2) * sc],
            radius=h / 2 * sc, fill=SURF_C)

    tile = Image.new("L", (n, n), 0)
    ImageDraw.Draw(tile).rounded_rectangle([0, 0, n - 1, n - 1], radius=RADIUS * sc, fill=255)
    img.putalpha(tile)
    return img.resize((px, px), Image.LANCZOS)


def hexc(c):
    return "#%02X%02X%02X" % c


def write_svg(path):
    body = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {S} {S}" width="{S}" height="{S}">',
        "  <defs>",
        '    <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">',
        f'      <stop offset="0" stop-color="{hexc(LAGOON)}"/>',
        f'      <stop offset="1" stop-color="{hexc(SKY_LOW)}"/>',
        "    </linearGradient>",
        f'    <clipPath id="tile"><rect width="{S}" height="{S}" rx="{RADIUS:.0f}"/></clipPath>',
        "  </defs>",
        '  <g clip-path="url(#tile)">',
        f'    <rect width="{S}" height="{S}" fill="url(#sky)"/>',
        f'    <rect y="{HORIZON}" width="{S}" height="{S - HORIZON}" fill="{hexc(DEEP)}"/>',
        f'    <path d="{svg_path(BIG)}" fill="{hexc(SAND)}"/>',
        f'    <path d="{svg_path(SMALL)}" fill="{hexc(SAND)}"/>',
    ]
    for cx, cy, half, h in SURF_BARS:
        body.append(f'    <rect x="{cx - half:.0f}" y="{cy - h / 2:.0f}" width="{2 * half:.0f}"'
                    f' height="{h:.0f}" rx="{h / 2:.0f}" fill="{hexc(SURF_C)}"/>')
    body += ["  </g>", "</svg>"]
    with open(path, "w") as f:
        f.write("\n".join(body) + "\n")


ICNS_TYPES = [(b"icp4", 16), (b"icp5", 32), (b"icp6", 64), (b"ic07", 128),
              (b"ic08", 256), (b"ic09", 512), (b"ic10", 1024), (b"ic11", 32),
              (b"ic12", 64), (b"ic13", 256), (b"ic14", 512)]


def write_icns(path, images):
    """A minimal ICNS: PNG payloads in typed chunks. Written here because iconutil is macOS-only
    and the icon must be reproducible on the machine the developer actually has."""
    chunks = b""
    for tag, size in ICNS_TYPES:
        import io
        buf = io.BytesIO()
        images[size].save(buf, format="PNG")
        data = buf.getvalue()
        chunks += tag + struct.pack(">I", len(data) + 8) + data
    with open(path, "wb") as f:
        f.write(b"icns" + struct.pack(">I", len(chunks) + 8) + chunks)


def main():
    os.makedirs(OUT, exist_ok=True)
    sizes = [16, 24, 32, 48, 64, 128, 256, 512, 1024]
    images = {s: render(s) for s in sizes}
    for s in sizes:
        images[s].save(os.path.join(OUT, f"insula-{s}.png"))
    images[512].save(os.path.join(OUT, "insula.png"))
    images[256].save(os.path.join(OUT, "insula.ico"),
                     sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    write_icns(os.path.join(OUT, "insula.icns"), images)
    write_svg(os.path.join(OUT, "insula-icon.svg"))
    print("wrote", ", ".join(sorted(os.listdir(OUT))))


if __name__ == "__main__":
    main()
