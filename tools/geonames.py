#!/usr/bin/env python3
"""Builds the offline place assets from GeoNames (CC BY 4.0, https://www.geonames.org).

    python3 tools/geonames.py        # downloads from download.geonames.org into a temp folder
    python3 tools/geonames.py DIR    # uses the input files already in DIR

Inputs, all from https://download.geonames.org/export/dump/: cities500.zip, admin1CodesASCII.txt, admin2Codes.txt and
countryInfo.txt. The committed assets were built from the dump downloaded on 25 Oct 2026 (Last-Modified 25 Oct 2026
11:10 GMT, countryInfo.txt 11:16 GMT), with these SHA-256:
    cities500.zip         2709e682708bc5a88bb7ce359ab478a71e38db180c5b1c58ce15cf77e3c7a077
    admin1CodesASCII.txt  2e4867f5397acb056ea26377601b57af37cb4c81399770225018e9e439e91ad3
    admin2Codes.txt       9f49795da1fd460006a40e58a614dbc1a351c9277340a65fe8d73b5f97ec1852
    countryInfo.txt       c55f638cfaafb4fe45a9aa15120a0f1f78e8a34a8e1847d7b9320cf740fbc9ab
GeoNames changes daily, so a later download gives other bytes; the same inputs always give the same output. The tool
prints the SHA-256 of every input and output, to check against the list above and licenses/geonames-CC-BY-4.0.txt.

Writes two plain-text files to app/src/main/assets/geonames/ (the APK deflates them, and AAPT would unpack a .gz anyway):

places.tsv: every populated place of 500+ people, neighbourhoods included (historical, abandoned and destroyed places are
left out), grouped by country, state and district so the repeats compress away. Each line is one of
    =CC<TAB>Country                         a country: ISO code and name
    >State                                  a state of that country (GeoNames admin1), empty when it has none
    >>District                              a district of that state (GeoNames admin2), empty when it has none
    lat<TAB>lng<TAB>name                    a place in that district, coordinates rounded to 3 decimals (about 100 m)
    lat<TAB>lng<TAB>name<TAB>people<TAB>kind
                                            a town of 5,000+ people or a seat of government, where kind is C (capital),
                                            A (state seat), D (district seat), N (neighbourhood) or empty

aliases.tsv: other names people use for a place (Bombay, Madras, Gurgaon), one place per line: name<TAB>other<TAB>...
From GeoNames' alternate names, Latin script only, one or two words, for capitals, cities of 100,000+ and every place in
India (most of Album's users are there, and its renamed cities are the ones people still type the old way).
"""
import csv
import hashlib
import io
import os
import re
import sys
import tempfile
import unicodedata
import urllib.request
import zipfile

BASE = "https://download.geonames.org/export/dump/"
FILES = ["cities500.zip", "admin1CodesASCII.txt", "admin2Codes.txt", "countryInfo.txt"]
# Historical, abandoned and destroyed places: a photo should never be named after these.
SKIP = {"PPLH", "PPLQ", "PPLW", "PPLCH"}
# Places GeoNames gives a whole taluk's population, so they'd swallow the real city next door (geonameid: name).
# Kanayannur is the taluk around Kochi: with 851,406 people it took Fort Kochi from Kochi.
SKIP_IDS = {"12501153"}
KINDS = {"PPLC": "C", "PPLG": "C", "PPLA": "A", "PPLA2": "D", "PPLX": "N"}
TOWN = 5000  # people; smaller places only need a name and a position
CITY = 100000  # people; cities get their other names in aliases.tsv
ALIAS_COUNTRIES = {"IN"}
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "geonames")
# An alternate name worth searching for: Latin letters after accents are dropped, one or two words, a capital first.
# GeoNames' lower-case alternates are machine transliterations from other scripts ("bnghalwr"), so they're left out.
ALIAS = re.compile(r"^[A-Z][A-Za-z']*( [A-Za-z][A-Za-z']*)?$")


def rows(text):
    return csv.reader((line for line in io.StringIO(text) if not line.startswith("#")), delimiter="\t", quoting=csv.QUOTE_NONE)


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def plain(text):
    return "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")


def key(text):
    return re.sub(r"[^a-z0-9]", "", plain(text).lower())


def aliases(name, alternates):
    """The other names of a place worth matching in search, in GeoNames' order, each different from the rest."""
    seen = {key(name)}
    out = []
    for a in alternates.split(","):
        p = plain(a)
        # All-caps codes like BOM and DEL are airports, not names people type
        if not ALIAS.match(p) or not 3 <= len(p) <= 30 or (a.isupper() and len(a) <= 4) or key(p) in seen:
            continue
        seen.add(key(p))
        out.append(a)
    return out


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else tempfile.mkdtemp()
    for name in FILES:
        path = os.path.join(src, name)
        if not os.path.exists(path):
            urllib.request.urlretrieve(BASE + name, path)
        print(f"{sha256(path)}  {name}")

    read = lambda name: open(os.path.join(src, name), encoding="utf-8").read()
    countries = {r[0]: r[4] for r in rows(read("countryInfo.txt")) if len(r) > 4}
    admin1 = {r[0]: r[1] for r in rows(read("admin1CodesASCII.txt")) if len(r) > 1}
    admin2 = {r[0]: r[1] for r in rows(read("admin2Codes.txt")) if len(r) > 1}
    with zipfile.ZipFile(os.path.join(src, "cities500.zip")) as z:
        cities = z.read("cities500.txt").decode("utf-8")

    places = []  # (country code, state, district, name, lat, lng, people, kind)
    others = {}  # name -> other names
    for r in rows(cities):
        code = r[7]
        if code in SKIP or r[0] in SKIP_IDS:
            continue
        cc, people, kind = r[8], int(r[14] or 0), KINDS.get(code, "")
        state = admin1.get(f"{cc}.{r[10]}", "")
        district = admin2.get(f"{cc}.{r[10]}.{r[11]}", "") if r[11] else ""
        places.append((cc, state, district, r[1], f"{float(r[4]):.3f}", f"{float(r[5]):.3f}", people, kind))
        if kind in ("C", "A") or (people >= CITY and kind != "N") or cc in ALIAS_COUNTRIES:
            names = aliases(r[1], r[3])
            if names:
                known = others.setdefault(r[1], [])
                known += [n for n in names if key(n) not in {key(k) for k in known}]
    places.sort()

    lines = []
    last = (None, None, None)
    for cc, state, district, name, lat, lng, people, kind in places:
        if cc != last[0]:
            lines.append(f"={cc}\t{countries.get(cc, cc)}\n")
        if (cc, state) != last[:2]:
            lines.append(f">{state}\n")
        if (cc, state, district) != last:
            lines.append(f">>{district}\n")
        last = (cc, state, district)
        extra = f"\t{people}\t{kind}" if people >= TOWN or kind in ("C", "A", "D") else ""
        lines.append(f"{lat}\t{lng}\t{name}{extra}\n")

    os.makedirs(OUT, exist_ok=True)
    outputs = {
        "places.tsv": "".join(lines),
        "aliases.tsv": "".join(f"{name}\t" + "\t".join(names) + "\n" for name, names in sorted(others.items())),
    }
    for name, text in outputs.items():
        path = os.path.join(OUT, name)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print(f"{sha256(path)}  {os.path.normpath(path)} ({os.path.getsize(path) // 1024} KB)")
    print(f"{len(places)} places, {sum(map(len, others.values()))} other names of {len(others)} places")


if __name__ == "__main__":
    main()
