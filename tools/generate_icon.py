"""Generate an original lineal-color HoldFrame app icon (no downloaded imagery)."""
from PIL import Image, ImageDraw
from pathlib import Path

base = Path(__file__).resolve().parent.parent
out = base / "assets"
out.mkdir(exist_ok=True)
size = 256
im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
draw = ImageDraw.Draw(im)
draw.rounded_rectangle((8, 8, 248, 248), radius=57, fill=(43, 104, 193, 255))
draw.rounded_rectangle((41, 53, 215, 180), radius=19, fill=(250, 250, 251, 255), outline=(19, 52, 105, 255), width=8)
draw.line([(60, 141), (97, 95), (128, 132), (158, 103), (201, 149)], fill=(72, 158, 216, 255), width=16, joint="curve")
draw.ellipse((60, 139, 79, 158), fill=(244, 186, 61, 255))
draw.rounded_rectangle((51, 195, 206, 218), radius=8, fill=(255, 193, 79, 255), outline=(54, 70, 96, 255), width=4)
draw.line([(113, 196), (113, 217)], fill=(42, 116, 203, 255), width=6)
im.save(out / "HoldFrame.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
im.save(out / "HoldFrame.png")
print(out / "HoldFrame.ico")
