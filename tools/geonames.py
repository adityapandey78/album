#!/usr/bin/env python3
"""Builds the offline reverse-geocoding asset from GeoNames (CC BY 4.0, https://www.geonames.org).

    python3 tools/geonames.py        # downloads from download.geonames.org into a temp folder
    python3 tools/geonames.py DIR    # uses cities5000.zip, admin1CodesASCII.txt and countryInfo.txt already in DIR

Writes app/src/main/assets/geonames/cities.tsv, one town per line:
latitude, longitude, name, state (admin1), country code, country, population.
GeoNames changes daily, so the output depends on the download date; the same inputs always give the same bytes.
Plain text on purpose: the APK deflates it to about 1.2 MB, and AAPT would silently unpack a .gz anyway.
"""
import csv
import io
import os
import sys
import tempfile
import urllib.request
import zipfile

BASE = "https://download.geonames.org/export/dump/"
FILES = ["cities5000.zip", "admin1CodesASCII.txt", "countryInfo.txt"]
# Neighbourhoods, historical, abandoned and destroyed places: a photo should name the town, not these.
SKIP = {"PPLX", "PPLH", "PPLQ", "PPLW", "PPLCH"}
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "geonames", "cities.tsv")


def rows(text):
    return csv.reader((line for line in io.StringIO(text) if not line.startswith("#")), delimiter="\t", quoting=csv.QUOTE_NONE)


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else tempfile.mkdtemp()
    for name in FILES:
        path = os.path.join(src, name)
        if not os.path.exists(path):
            urllib.request.urlretrieve(BASE + name, path)

    read = lambda name: open(os.path.join(src, name), encoding="utf-8").read()
    countries = {r[0]: r[4] for r in rows(read("countryInfo.txt")) if len(r) > 4}
    admin1 = {r[0]: r[1] for r in rows(read("admin1CodesASCII.txt")) if len(r) > 1}
    with zipfile.ZipFile(os.path.join(src, "cities5000.zip")) as z:
        cities = z.read("cities5000.txt").decode("utf-8")

    out = []
    for r in rows(cities):
        if r[7] in SKIP:
            continue
        cc = r[8]
        out.append((cc, admin1.get(f"{cc}.{r[10]}", ""), r[1], f"{float(r[4]):.4f}", f"{float(r[5]):.4f}", countries.get(cc, cc), r[14]))
    out.sort()  # country and state names repeat on neighbouring lines, so APK compression shrinks them to almost nothing

    lines = "".join(f"{lat}\t{lng}\t{name}\t{state}\t{cc}\t{country}\t{pop}\n" for cc, state, name, lat, lng, country, pop in out)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(lines)
    print(f"{len(out)} towns -> {os.path.normpath(OUT)} ({os.path.getsize(OUT) // 1024} KB)")


if __name__ == "__main__":
    main()
