#!/usr/bin/env python3
"""
Fills a test phone or emulator with a realistic photo library for scroll benchmarks.

Writes COUNT photo-like JPEGs (noise and gradients, so they decode like real photos) with EXIF DateTimeOriginal
spread over the last two years in bursts of a few to a few dozen photos per day, across Camera, Screenshots,
WhatsApp and Download folders, then pushes them with adb and rescans, since MediaStore skips EXIF on some files it scans while adb is still pushing.
The same seed always produces the same library, so before/after numbers compare like with like.

Needs Pillow (python3 -m pip install Pillow) and adb on PATH or under $ANDROID_HOME.
    python3 tools/seed_library.py              # 3,000 photos
    python3 tools/seed_library.py --count 500 --out /tmp/seed --no-push
Remove them again with: adb shell rm -r /sdcard/DCIM/Camera/SEED_* /sdcard/Pictures/*/SEED_* /sdcard/Download/SEED_*
(then reboot the emulator, or open and close Album, so MediaStore drops the rows).
"""
import argparse
import datetime as dt
import os
import random
import shlex
import shutil
import subprocess
import tempfile

from PIL import Image, ImageDraw

# (device folder, share of the library, size in px): screenshots are tall, the rest camera-shaped
FOLDERS = [
    ("DCIM/Camera", 0.70, (1600, 1200)),
    ("Pictures/Screenshots", 0.15, (720, 1600)),
    ("Pictures/WhatsApp Images", 0.10, (1280, 960)),
    ("Download", 0.05, (1200, 1200)),
]


def days(count, rng, today):
    """Taken times, newest first: bursts on about one day in three over two years, like a real camera roll."""
    out = []
    day = today
    while len(out) < count:
        day -= dt.timedelta(days=rng.choice([1, 1, 2, 3, 5, 8]))
        burst = min(count - len(out), int(rng.paretovariate(1.2) * 3))
        start = dt.datetime.combine(day, dt.time(hour=rng.randint(7, 20)))
        out += [start + dt.timedelta(minutes=rng.randint(0, 180)) for _ in range(max(burst, 1))]
    return sorted(out[:count], reverse=True)


def photo(size, rng, i):
    w, h = size
    base = Image.linear_gradient("L").resize((w, h)).rotate(rng.randint(0, 359), expand=False)
    color = Image.merge("RGB", [base.point(lambda v, k=k: (v * k // 255 + rng.randint(0, 120)) % 256) for k in (rng.randint(80, 255) for _ in range(3))])
    noise = Image.effect_noise((w, h), rng.randint(20, 60)).convert("RGB")
    img = Image.blend(color, noise, 0.25)
    draw = ImageDraw.Draw(img)
    for _ in range(rng.randint(3, 9)):
        x, y = rng.randint(0, w), rng.randint(0, h)
        r = rng.randint(w // 20, w // 4)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=tuple(rng.randint(0, 255) for _ in range(3)))
    draw.text((20, 20), f"seed {i}", fill=(255, 255, 255))
    return img


def exif(taken):
    e = Image.Exif()
    stamp = taken.strftime("%Y:%m:%d %H:%M:%S")
    e[0x0110] = "Album seed"  # Model
    e[0x0132] = stamp  # DateTime
    sub = e.get_ifd(0x8769)
    sub[0x9003] = stamp  # DateTimeOriginal, what MediaStore reads as DATE_TAKEN
    sub[0x9011] = "+05:30"  # OffsetTimeOriginal, so the date doesn't depend on the device's time zone
    return e.tobytes()


def adb():
    home = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk"))
    local = os.path.join(home, "platform-tools", "adb")
    return local if os.path.exists(local) else "adb"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--count", type=int, default=3000)
    p.add_argument("--seed", type=int, default=78)
    p.add_argument("--out", help="where to write the JPEGs (default: a temporary folder, deleted after pushing)")
    p.add_argument("--no-push", action="store_true")
    args = p.parse_args()
    rng = random.Random(args.seed)
    out = args.out or tempfile.mkdtemp(prefix="album-seed-")
    taken = days(args.count, rng, dt.date(2026, 10, 1))  # fixed end date: same library on every run
    for i, t in enumerate(taken):
        folder, _, size = rng.choices(FOLDERS, weights=[f[1] for f in FOLDERS])[0]
        path = os.path.join(out, folder, f"SEED_{t:%Y%m%d_%H%M%S}_{i:05d}.jpg")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        photo(size, rng, i).save(path, "JPEG", quality=85, exif=exif(t))
        stamp = t.timestamp()
        os.utime(path, (stamp, stamp))
        if i % 250 == 0:
            print(f"{i}/{args.count}", flush=True)
    if not args.no_push:
        for folder, _, _ in FOLDERS:
            src = os.path.join(out, folder)
            if os.path.isdir(src):
                parent = "/sdcard/" + os.path.dirname(folder)
                subprocess.run([adb(), "shell", "mkdir -p " + shlex.quote(f"/sdcard/{folder}")], check=True)
                subprocess.run([adb(), "push", src, parent.rstrip("/") + "/"], check=True)
        subprocess.run([adb(), "shell", "content call --uri content://media --method scan_volume --arg external_primary"], check=True)
        if not args.out:
            shutil.rmtree(out)
    print(f"done: {args.count} photos" + ("" if args.no_push else " pushed"))


if __name__ == "__main__":
    main()
