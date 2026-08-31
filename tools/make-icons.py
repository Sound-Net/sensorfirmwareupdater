#!/usr/bin/env python3
"""Regenerates the application icons from the master artwork.

Run from the repository root:  python3 tools/make-icons.py
Needs Pillow (pip install pillow). The .icns step additionally needs macOS,
and is skipped elsewhere - the committed .icns only changes when the artwork
does, so a non-mac contributor can leave it alone.

The master art is a rounded square drawn on a white page. Two obvious ways to
drop that page do not work:

  * making every white pixel transparent punches holes in the dolphin's belly,
    the net and the axis labels, which are white too;
  * flooding inwards from the border leaks along the white net lines, which run
    right up to the edge of the artwork.

So the outline is generated instead: the artwork's bounding box and corner
radius are measured, and a rounded rectangle of those dimensions becomes the
alpha channel. It is drawn supersampled for a smooth edge, and inset a few
pixels because the master is a JPEG whose edge is an anti-aliased white-to-navy
ramp - cutting exactly on the outline would leave a white fringe behind.
"""
import os
import subprocess
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("Pillow is required: pip install pillow")

# Where the master artwork lives, and where the generated icons go.
SOURCE = "src/main/resources/sensor_firmware_icon.jpeg"
# Installer icons, alongside the NSIS template they are used by.
PACKAGING_DIR = "src/main/installer"
# The window/taskbar icon, which has to be on the classpath.
APP_ICON = "src/main/resources/app-icon.png"

# A pixel counts as artwork when any channel is below this.
ART_LEVEL = 235
# Supersampling factor for the mask, so the rounded edge comes out smooth.
SS = 4
# Pixels trimmed off the outline to clear the JPEG's white edge ramp. Measured
# on the master art: the fringe is gone by 6, out of an ~987px icon.
INSET = 6
# Sizes packed into the .ico, which is what Windows Explorer and the taskbar read.
ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)
# The window icon is scaled by the toolkit, so one reasonably sized image does.
APP_ICON_PX = 256


def artwork_box(im):
    """Bounding box of the artwork within the white page."""
    px = im.load()
    w, h = im.size

    def is_art(x, y):
        r, g, b = px[x, y]
        return min(r, g, b) < ART_LEVEL

    xs = [x for x in range(w) if any(is_art(x, y) for y in range(0, h, 2))]
    ys = [y for y in range(h) if any(is_art(x, y) for x in range(0, w, 2))]
    return xs[0], ys[0], xs[-1] + 1, ys[-1] + 1


def corner_radius(im, box):
    """On a rounded rectangle the topmost row spans left+R .. right-R."""
    px = im.load()
    left, top, right, _ = box

    def is_art(x, y):
        r, g, b = px[x, y]
        return min(r, g, b) < ART_LEVEL

    return next(x for x in range(left, right) if is_art(x, top)) - left


def rounded_mask(size, radius):
    w, h = size
    big = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        [INSET * SS, INSET * SS, w * SS - INSET * SS - 1, h * SS - INSET * SS - 1],
        radius=max(1, radius - INSET) * SS, fill=255)
    return big.resize((w, h), Image.LANCZOS)


def master_image(src):
    """The artwork, cut out of its page and centred on a square canvas."""
    im = Image.open(src).convert("RGB")
    box = artwork_box(im)
    radius = corner_radius(im, box)
    art = im.crop(box)
    art.putalpha(rounded_mask(art.size, radius))
    print(f"  {os.path.basename(src)}: artwork {box}, corner radius {radius}")

    # Square canvas, so nothing is distorted when the icon is scaled down.
    side = max(art.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(art, ((side - art.width) // 2, (side - art.height) // 2))
    return square.resize((1024, 1024), Image.LANCZOS)


def write_icns(master, path):
    """iconutil is macOS-only and wants a folder of specifically-named sizes."""
    if sys.platform != "darwin":
        print(f"  skipped {path} (needs macOS iconutil)")
        return
    iconset = path.replace(".icns", ".iconset")
    os.makedirs(iconset, exist_ok=True)
    try:
        for size in (16, 32, 128, 256, 512):
            master.resize((size, size), Image.LANCZOS).save(
                os.path.join(iconset, f"icon_{size}x{size}.png"))
            master.resize((size * 2, size * 2), Image.LANCZOS).save(
                os.path.join(iconset, f"icon_{size}x{size}@2x.png"))
        subprocess.run(["iconutil", "-c", "icns", iconset, "-o", path], check=True)
        print(f"  wrote {path}")
    finally:
        subprocess.run(["rm", "-rf", iconset], check=False)


def build(src, packaging_dir, app_icon_path):
    master = master_image(src)
    os.makedirs(packaging_dir, exist_ok=True)

    png = os.path.join(packaging_dir, "icon.png")
    ico = os.path.join(packaging_dir, "icon.ico")
    icns = os.path.join(packaging_dir, "icon.icns")

    master.save(png)
    print(f"  wrote {png}")
    # bitmap_format="bmp" rather than Pillow's default PNG-compressed entries:
    # NSIS's Icon directive and Windows' own resource stamping both accept BMP
    # entries everywhere, while PNG-compressed .ico files are only handled in
    # some places and fail in others. The file is bigger; it is still ~360KB.
    master.save(ico, sizes=[(s, s) for s in ICO_SIZES], bitmap_format="bmp")
    print(f"  wrote {ico}")
    write_icns(master, icns)

    os.makedirs(os.path.dirname(app_icon_path), exist_ok=True)
    master.resize((APP_ICON_PX, APP_ICON_PX), Image.LANCZOS).save(app_icon_path)
    print(f"  wrote {app_icon_path}")


if __name__ == "__main__":
    build(SOURCE, PACKAGING_DIR, APP_ICON)
