"""Draw the Radarcraft title-screen art: background (1920x1080 with logo) and FancyMenu button skins (808x161).

    python -X utf8 modpack/titlescreen/make_art.py      (needs Pillow; plain python, not -I)

Output: modpack/titlescreen/assets/{bg,s,sh,m,mh,mod,modh,o,oh,qg,qgh,rs,rsh}.png
Flat-art dusk scene: twilight sky, stars, moon, floating islands with waterfalls, an airship, and Radar the tuxedo
cat on the foreground island. The centre band (logo at y 430-650, buttons below) is kept calm so UI stays readable.
"""
import math, random
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUT = Path(__file__).resolve().parent / "assets"
OUT.mkdir(exist_ok=True)
W, H = 1920, 1080
FONTS = Path("C:/Windows/Fonts")
random.seed(7)


def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def gradient_color(t):
    keys = [(0.0, (9, 12, 36)), (0.42, (52, 34, 92)), (0.62, (150, 70, 80)), (0.72, (232, 150, 96)),
            (0.78, (246, 190, 120)), (0.84, (120, 82, 118)), (1.0, (22, 20, 44))]
    for (t0, c0), (t1, c1) in zip(keys, keys[1:]):
        if t0 <= t <= t1:
            return lerp(c0, c1, (t - t0) / (t1 - t0))
    return keys[-1][1]


def sky():
    img = Image.new("RGB", (W, H))
    px = img.load()
    for y in range(H):
        c = gradient_color(y / H)
        for x in range(W):
            px[x, y] = c
    return img.convert("RGBA")


def stars(img):
    d = ImageDraw.Draw(img, "RGBA")
    for _ in range(420):
        x, y = random.randint(0, W), random.randint(0, int(H * 0.62))
        fade = max(0.0, 1 - y / (H * 0.62))
        a = int(90 + 165 * random.random() * fade)
        r = random.choice([1, 1, 1, 2, 2, 3])
        d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 250, 235, a))
        if r == 3 and random.random() < 0.5:
            d.line((x - 7, y, x + 7, y), fill=(255, 250, 235, a // 2)); d.line((x, y - 7, x, y + 7), fill=(255, 250, 235, a // 2))


def moon(img, cx=1500, cy=250, r=118):
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    for k in range(10, 0, -1):
        rr = r + k * 16
        g.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), fill=(255, 226, 170, 9))
    glow = glow.filter(ImageFilter.GaussianBlur(28))
    img.alpha_composite(glow)
    d = ImageDraw.Draw(img, "RGBA")
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(250, 238, 212, 255))
    for (dx, dy, cr) in [(-30, -20, 22), (25, 30, 16), (40, -45, 11), (-55, 40, 9), (5, -5, 7)]:
        d.ellipse((cx + dx - cr, cy + dy - cr, cx + dx + cr, cy + dy + cr), fill=(228, 212, 184, 255))


def clouds(img):
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for _ in range(26):
        cx, cy = random.randint(-100, W + 100), random.randint(int(H * 0.60), int(H * 0.82))
        w, h = random.randint(180, 520), random.randint(28, 70)
        col = random.choice([(255, 196, 150, 70), (255, 170, 140, 60), (240, 150, 160, 55)])
        for i in range(4):
            ox, oy = random.randint(-w // 3, w // 3), random.randint(-h // 2, h // 2)
            d.ellipse((cx + ox - w // 2, cy + oy - h // 2, cx + ox + w // 2, cy + oy + h // 2), fill=col)
    img.alpha_composite(layer.filter(ImageFilter.GaussianBlur(22)))


def island(img, cx, top, width, depth, dist, waterfall=None):
    """dist 0 = foreground (dark), 1 = far (hazy)."""
    sky_c = gradient_color(min(1.0, (top + depth / 2) / H))
    rock = lerp((28, 24, 50), sky_c, dist * 0.65)
    rock_dark = lerp((16, 14, 32), sky_c, dist * 0.65)
    grass = lerp((52, 112, 72), sky_c, dist * 0.6)
    grass_dark = lerp((34, 84, 56), sky_c, dist * 0.6)
    d = ImageDraw.Draw(img, "RGBA")
    hw = width / 2
    # jagged rock body hanging below the surface
    pts = [(cx - hw, top)]
    n = 7
    for i in range(1, n):
        t = i / n
        x = cx - hw + width * t
        y = top + depth * (1 - abs(2 * t - 1)) ** 0.8 * random.uniform(0.8, 1.05)
        pts.append((x, y))
    pts.append((cx + hw, top))
    d.polygon(pts, fill=rock + (255,))
    # darker facet on the right half
    facet = [(cx, top)] + [p for p in pts if p[0] >= cx]
    d.polygon(facet, fill=rock_dark + (255,))
    # grass cap with a soft bulge
    cap_h = max(10, int(width * 0.06))
    d.rounded_rectangle((cx - hw - 6, top - cap_h, cx + hw + 6, top + cap_h // 2), radius=cap_h, fill=grass + (255,))
    d.rounded_rectangle((cx - hw - 6, top - cap_h // 3, cx + hw + 6, top + cap_h // 2), radius=cap_h // 2, fill=grass_dark + (255,))
    # a few trees as simple silhouettes on far/mid islands
    if dist > 0.15:
        for _ in range(random.randint(1, 3)):
            tx = random.randint(int(cx - hw * 0.7), int(cx + hw * 0.7)); th = int(cap_h * random.uniform(2.5, 4))
            d.rectangle((tx - 2, top - cap_h - th // 2, tx + 2, top - cap_h), fill=rock_dark + (255,))
            d.ellipse((tx - th // 2, top - cap_h - th, tx + th // 2, top - cap_h - th // 4), fill=grass_dark + (255,))
    if waterfall:
        wx, length = waterfall
        wf = Image.new("RGBA", (W, H), (0, 0, 0, 0)); wd = ImageDraw.Draw(wf)
        for k in range(14):
            yy = top + k * (length / 14)
            a = int(190 * (1 - k / 14))
            wd.line((cx + wx, yy, cx + wx + random.randint(-2, 2), yy + length / 14 + 4), fill=(190, 230, 255, a), width=random.randint(4, 7))
        img.alpha_composite(wf.filter(ImageFilter.GaussianBlur(1.2)))


def airship(img, cx=1300, cy=345):
    d = ImageDraw.Draw(img, "RGBA")
    dark = (24, 20, 42, 255)
    d.ellipse((cx - 170, cy - 62, cx + 170, cy + 62), fill=dark)                      # balloon
    d.polygon([(cx + 150, cy - 10), (cx + 230, cy - 40), (cx + 225, cy + 30), (cx + 150, cy + 12)], fill=dark)  # tail fin
    hull = [(cx - 115, cy + 92), (cx + 95, cy + 92), (cx + 70, cy + 130), (cx - 85, cy + 130)]
    d.polygon(hull, fill=dark)
    for x0 in (-90, -30, 30, 80):
        d.line((cx + x0, cy + 60, cx + x0 * 0.9, cy + 92), fill=dark, width=3)          # rigging
    for i, x0 in enumerate((-70, -40, -10, 20, 50)):                                    # lit windows
        d.rectangle((cx + x0, cy + 104, cx + x0 + 12, cy + 114), fill=(255, 206, 128, 230))
    d.ellipse((cx - 135, cy + 100, cx - 115, cy + 120), fill=dark)                      # propeller hub
    d.line((cx - 125, cy + 86, cx - 125, cy + 134), fill=dark, width=4)


def cat(img, x, ground):
    """Radar, a tuxedo cat, sitting and looking right (toward the moon)."""
    d = ImageDraw.Draw(img, "RGBA")
    black = (14, 12, 18, 255); white = (242, 240, 236, 255)
    # tail curling around the left
    tail = [(x - 60, ground - 20), (x - 120, ground - 10), (x - 150, ground - 50), (x - 135, ground - 95), (x - 105, ground - 105)]
    d.line(tail, fill=black, width=26, joint="curve")
    d.ellipse((x - 118, ground - 118, x - 92, ground - 92), fill=white)
    # body
    d.ellipse((x - 78, ground - 190, x + 78, ground + 6), fill=black)
    d.polygon([(x - 60, ground - 150), (x + 60, ground - 150), (x + 86, ground), (x - 86, ground)], fill=black)
    # white bib and belly
    d.polygon([(x + 10, ground - 160), (x + 62, ground - 150), (x + 70, ground - 40), (x + 30, ground - 5), (x + 6, ground - 60)], fill=white)
    # front legs + white paws
    d.rectangle((x + 10, ground - 70, x + 34, ground), fill=black); d.rectangle((x + 44, ground - 70, x + 68, ground), fill=black)
    d.ellipse((x + 4, ground - 16, x + 40, ground + 6), fill=white); d.ellipse((x + 40, ground - 16, x + 76, ground + 6), fill=white)
    # head
    hx, hy, r = x + 34, ground - 232, 58
    d.polygon([(hx - 52, hy - 20), (hx - 44, hy - 86), (hx - 8, hy - 44)], fill=black)   # left ear
    d.polygon([(hx + 52, hy - 20), (hx + 46, hy - 86), (hx + 10, hy - 44)], fill=black)  # right ear
    d.polygon([(hx - 44, hy - 30), (hx - 40, hy - 70), (hx - 16, hy - 44)], fill=(60, 44, 60, 255))
    d.ellipse((hx - r, hy - r, hx + r, hy + r), fill=black)
    d.ellipse((hx - 6, hy + 2, hx + 40, hy + 46), fill=white)                           # muzzle patch
    d.polygon([(hx + 16, hy + 12), (hx + 28, hy + 12), (hx + 22, hy + 20)], fill=(232, 150, 160, 255))  # nose
    for ex in (hx - 24, hx + 18):                                                       # eyes
        d.ellipse((ex - 12, hy - 18, ex + 12, hy), fill=(126, 220, 110, 255))
        d.ellipse((ex - 3, hy - 16, ex + 3, hy - 2), fill=(10, 20, 10, 255))
        d.ellipse((ex + 3, hy - 15, ex + 7, hy - 11), fill=(255, 255, 255, 220))
    for (dx, dy) in ((-30, 24), (-34, 32), (-28, 40)):                                  # whiskers (left)
        d.line((hx - 2, hy + 18, hx + dx, hy + dy), fill=(200, 200, 210, 160), width=2)
    for (dx, dy) in ((60, 20), (66, 28), (60, 36)):
        d.line((hx + 30, hy + 18, hx + dx, hy + dy), fill=(200, 200, 210, 160), width=2)


def text_size(font, s):
    b = font.getbbox(s); return b[2] - b[0], b[3] - b[1], b


def logo(img):
    font = ImageFont.truetype(str(FONTS / "impact.ttf"), 196)
    small = ImageFont.truetype(str(FONTS / "bahnschrift.ttf"), 42)
    word = "RADARCRAFT"; spacing = 10
    widths = [text_size(font, ch)[0] for ch in word]
    total = sum(widths) + spacing * (len(word) - 1)
    x0 = (W - total) // 2; y = 400
    # shadow
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0)); sd = ImageDraw.Draw(sh); x = x0
    for ch, w in zip(word, widths):
        sd.text((x + 6, y + 14), ch, font=font, fill=(6, 6, 20, 190)); x += w + spacing
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(10)))
    # fill through a mask with a vertical gradient (cream -> amber), outlined
    mask = Image.new("L", (W, H), 0); md = ImageDraw.Draw(mask); x = x0
    for ch, w in zip(word, widths):
        md.text((x, y), ch, font=font, fill=255); x += w + spacing
    grad = Image.new("RGBA", (W, H), (0, 0, 0, 0)); gp = grad.load()
    bb = mask.getbbox()
    for yy in range(bb[1], bb[3]):
        t = (yy - bb[1]) / max(1, bb[3] - bb[1]); c = lerp((255, 244, 214), (255, 178, 96), t) + (255,)
        for xx in range(bb[0], bb[2]):
            gp[xx, yy] = c
    outline = Image.new("RGBA", (W, H), (0, 0, 0, 0)); od = ImageDraw.Draw(outline); x = x0
    for ch, w in zip(word, widths):
        od.text((x, y), ch, font=font, fill=(26, 18, 44, 255), stroke_width=9, stroke_fill=(26, 18, 44, 255)); x += w + spacing
    img.alpha_composite(outline)
    img.paste(grad, (0, 0), mask)
    # subtitle
    sub = "A  S K Y B O U N D  F O R K   ·   S I X  C A T S ,  O N E  N A M E  T A G"
    sw, shh, _ = text_size(small, sub)
    d = ImageDraw.Draw(img, "RGBA")
    d.text(((W - sw) // 2 + 2, y + 232 + 2), sub, font=small, fill=(10, 8, 24, 170))
    d.text(((W - sw) // 2, y + 232), sub, font=small, fill=(244, 232, 246, 235))


def background():
    img = sky()
    stars(img)
    moon(img)
    clouds(img)
    random.seed(11)
    island(img, 1180, 170, 150, 90, 0.9)
    island(img, 300, 330, 260, 150, 0.7)
    island(img, 1760, 450, 230, 140, 0.65)
    island(img, 470, 560, 380, 230, 0.4, waterfall=(60, 230))
    island(img, 1620, 660, 330, 210, 0.35)
    airship(img)
    island(img, 330, 870, 760, 420, 0.0, waterfall=(-180, 260))
    island(img, 1780, 960, 520, 300, 0.05)
    cat(img, 175, 858)
    logo(img)
    img.convert("RGB").save(OUT / "bg.png", optimize=True)


def button(label, hover, name):
    w, h = 808, 161
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0)); d = ImageDraw.Draw(img, "RGBA")
    fill = (62, 40, 96, 240) if hover else (20, 16, 40, 215)
    border = (255, 236, 184, 255) if hover else (236, 184, 108, 210)
    d.rounded_rectangle((8, 8, w - 8, h - 8), radius=26, fill=fill, outline=border, width=6)
    gloss = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(gloss).rounded_rectangle((20, 18, w - 20, h // 2 - 6), radius=14, fill=(255, 255, 255, 18))
    img.alpha_composite(gloss)
    font = ImageFont.truetype(str(FONTS / "bahnschrift.ttf"), 78)
    try:
        font.set_variation_by_name("SemiBold")
    except Exception:
        pass
    spacing = 6
    widths = [text_size(font, ch)[0] for ch in label]
    total = sum(widths) + spacing * (len(label) - 1)
    _, th, bb = text_size(font, "MULTIPLAYER")          # cap height of the label font
    x = (w - total) // 2; y = (h - th) // 2 - bb[1]
    for ch, cw in zip(label, widths):
        d.text((x + 3, y + 4), ch, font=font, fill=(0, 0, 0, 150))
        d.text((x, y), ch, font=font, fill=(255, 246, 226, 255) if hover else (240, 236, 230, 255)); x += cw + spacing
    img.save(OUT / f"{name}.png", optimize=True)


def panorama():
    """Six 1024x1024 cube faces (panorama_0..5) replacing vanilla's trial-chamber title panorama, which 1.21 also
    shows blurred behind every menu and loading screen. Faces 0-3 go around the horizon, 4 is up, 5 is down."""
    S = 1024
    random.seed(23)
    strip = Image.new("RGBA", (S * 4, S))
    px = strip.load()
    for y in range(S):  # top of a side face looks 45 deg up, bottom 45 deg down; horizon at the middle
        c = gradient_color(0.30 + 0.62 * y / S)
        for x in range(S * 4):
            px[x, y] = c + (255,)
    d = ImageDraw.Draw(strip, "RGBA")
    for _ in range(900):
        x, y = random.randint(0, S * 4), random.randint(0, int(S * 0.42))
        a = int(80 + 160 * random.random() * (1 - y / (S * 0.42)))
        r = random.choice([1, 1, 2, 2, 3])
        d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 250, 235, a))
    # moon on face 1, low warm haze along the horizon
    glow = Image.new("RGBA", strip.size, (0, 0, 0, 0)); g = ImageDraw.Draw(glow)
    mx, my, mr = S + 560, 330, 92
    for k in range(10, 0, -1):
        rr = mr + k * 16; g.ellipse((mx - rr, my - rr, mx + rr, my + rr), fill=(255, 226, 170, 9))
    strip.alpha_composite(glow.filter(ImageFilter.GaussianBlur(26)))
    d.ellipse((mx - mr, my - mr, mx + mr, my + mr), fill=(250, 238, 212, 255))
    haze = Image.new("RGBA", strip.size, (0, 0, 0, 0)); hd = ImageDraw.Draw(haze)
    for _ in range(60):
        cx, cy = random.randint(0, S * 4), random.randint(int(S * 0.48), int(S * 0.70))
        w, h = random.randint(260, 700), random.randint(36, 90)
        hd.ellipse((cx - w // 2, cy - h // 2, cx + w // 2, cy + h // 2), fill=random.choice([(255, 196, 150, 60), (240, 150, 160, 50)]))
    strip.alpha_composite(haze.filter(ImageFilter.GaussianBlur(26)))
    # floating islands all around, far ones small and hazy
    global W, H
    W0, H0 = W, H
    W, H = S * 4, S
    for _ in range(16):
        dist = random.uniform(0.35, 0.95)
        width = int(380 * (1.2 - dist) + 60)
        island(strip, random.randint(width // 2 + 20, S * 4 - width // 2 - 20), random.randint(int(S * 0.40), int(S * 0.62)), width, int(width * 0.6), dist,
               waterfall=(random.randint(-width // 4, width // 4), int(width * 0.7)) if random.random() < 0.3 else None)
    airship(strip, cx=2 * S + 520, cy=360)
    W, H = W0, H0
    for i in range(4):
        strip.crop((i * S, 0, (i + 1) * S, S)).convert("RGB").save(OUT / f"panorama_{i}.png", optimize=True)
    # up: dark starry dome; down: a dim cloud sea
    up = Image.new("RGB", (S, S)); upx = up.load()
    for y in range(S):
        for x in range(S):
            t = min(1.0, math.hypot(x - S / 2, y - S / 2) / (S * 0.71))
            upx[x, y] = lerp((6, 8, 26), gradient_color(0.30), t)
    ud = ImageDraw.Draw(up)
    for _ in range(500):
        x, y = random.randint(0, S), random.randint(0, S); r = random.choice([1, 1, 2])
        ud.ellipse((x - r, y - r, x + r, y + r), fill=(255, 250, 235))
    up.save(OUT / "panorama_4.png", optimize=True)
    down = Image.new("RGBA", (S, S), gradient_color(0.93) + (255,)); dd = ImageDraw.Draw(down)
    for _ in range(70):
        cx, cy = random.randint(0, S), random.randint(0, S); w = random.randint(120, 380)
        dd.ellipse((cx - w, cy - w // 3, cx + w, cy + w // 3), fill=random.choice([(150, 90, 120, 60), (90, 60, 100, 70)]))
    down.filter(ImageFilter.GaussianBlur(20)).convert("RGB").save(OUT / "panorama_5.png", optimize=True)


if __name__ == "__main__":
    panorama()
    background()
    for name, label in (("s", "SINGLEPLAYER"), ("m", "MULTIPLAYER"), ("mod", "MODS"), ("o", "OPTIONS"), ("qg", "QUIT GAME"), ("rs", "REALMS")):
        button(label, False, name); button(label, True, name + "h")
    print("wrote", sorted(p.name for p in OUT.iterdir()))
