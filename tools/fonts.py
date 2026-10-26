"""Builds the extra fonts for Settings > Appearance > Font: Inter, Nunito and Lora from Google Fonts (SIL OFL 1.1).

Each variable font is checked against the SHA-256 below, cut down to Latin text (keeping its weight axes, layout
features and licence names) and written to app/src/main/res/font/; its licence goes to licenses/. Lora is copied as it
is: its licence reserves the name "Lora" for unmodified copies, and cutting it down would make a modified one.

Sources, from github.com/google/fonts at commit e7db6ca603fd4862010383ff17ffd6cc4ffac1c0 (26 Oct 2026):
    ofl/inter/Inter[opsz,wght].ttf   29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031
    ofl/nunito/Nunito[wght].ttf      bb55a5ca5c2042335b3991af27c4d0705d0ef41cac6164ac737fd8f2a1e85207
    ofl/lora/Lora[wght].ttf          822a6621ccbe8d97d20ac88c1c41f5615c9c2c202eaa75f272cd452aac6475a7

    python3 -m venv /tmp/fv && /tmp/fv/bin/pip install fonttools==4.55.3
    curl -L -o DIR/Inter.ttf 'https://raw.githubusercontent.com/google/fonts/e7db6ca603fd4862010383ff17ffd6cc4ffac1c0/ofl/inter/Inter%5Bopsz,wght%5D.ttf'
    (and Nunito%5Bwght%5D.ttf, Lora%5Bwght%5D.ttf, and each folder's OFL.txt as DIR/<Name>-OFL.txt)
    /tmp/fv/bin/python -I tools/fonts.py DIR
"""
import hashlib
import pathlib
import shutil
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

FONTS = {  # output name: (downloaded file, sha256, cut down to Latin)
    "inter": ("Inter.ttf", "29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031", True),
    "nunito": ("Nunito.ttf", "bb55a5ca5c2042335b3991af27c4d0705d0ef41cac6164ac737fd8f2a1e85207", True),
    "lora": ("Lora.ttf", "822a6621ccbe8d97d20ac88c1c41f5615c9c2c202eaa75f272cd452aac6475a7", False),  # Reserved Font Name
}

# Latin text in English and other Latin-script languages, punctuation, the rupee and other currency signs, arrows and the
# like. Other scripts (Devanagari, say) fall back to the phone's own fonts, as they do with Album's default fonts.
LATIN = [
    *range(0x20, 0x7F), *range(0xA0, 0x250), *range(0x300, 0x370), *range(0x1E00, 0x1F00), *range(0x2000, 0x2070),
    *range(0x20A0, 0x20D0), *range(0x2100, 0x2150), *range(0x2190, 0x2200), 0x2212, 0x2215, 0x2219, 0x221E, 0xFFFD,
]


def main(src: str) -> None:
    root = pathlib.Path(__file__).resolve().parent.parent
    for name, (file, sha, cut) in FONTS.items():
        path = pathlib.Path(src) / file
        got = hashlib.sha256(path.read_bytes()).hexdigest()
        assert got == sha, f"{file}: sha256 {got}, expected {sha}"
        out = root / f"app/src/main/res/font/{name}.ttf"
        shutil.copyfile(pathlib.Path(src) / f"{file.removesuffix('.ttf')}-OFL.txt", root / f"licenses/{name}-OFL.txt")
        if not cut:
            shutil.copyfile(path, out)
            print(f"{name}: {path.stat().st_size // 1024} KB, as it is")
            continue
        font = TTFont(path)
        options = subset.Options()
        options.layout_features = ["*"]  # kerning, ligatures, tabular figures
        options.name_IDs = ["*"]  # keep the copyright and licence names
        options.notdef_outline = True
        options.hinting = False  # phone screens are dense enough; saves a third of the size
        sub = subset.Subsetter(options)
        sub.populate(unicodes=LATIN)
        sub.subset(font)
        font.save(out)
        print(f"{name}: {path.stat().st_size // 1024} KB -> {out.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main(sys.argv[1])
