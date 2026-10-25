#!/usr/bin/env python3
"""Builds describe-it search's model files from Meta's Perception Encoder PE-Core-S16-384 (Apache 2.0, see
licenses/perception-encoder-Apache-2.0.txt) and checks them against the original.

    python3 tools/describe_convert.py WORK OUT          # convert, check, write OUT/manifest.json
    python3 tools/describe_convert.py --check WORK OUT  # the same check again, in an older LiteRT (see below)

WORK holds the downloads. Each comes from its official source and is checked against the SHA-256 below:
  - Meta's model code and the CLIP BPE vocabulary: github.com/facebookresearch/perception_models at COMMIT
  - Meta's weights: huggingface.co/facebook/PE-Core-S16-384 at REVISION
  - 70 COCO val2017 photos (images.cocodataset.org), used only for the checks here, never shipped
OUT gets the two .tflite files, the model's licence and manifest.json (name, size and SHA-256 of each file) for the
GitHub release models-v1; they are not committed. app/src/main/assets/describe/bpe_merges.txt is written too.

Converts with Python 3.12 and exactly these PyPI packages: torch==2.14.1 torchvision==0.29.1 litert-torch==0.10.0
(which brings ai-edge-quantizer==0.10.0, ai-edge-litert==2.3.0 and litert-converter==0.5.0) einops==0.8.1 timm==1.0.15
ftfy==6.3.1 regex==2026.9.29 pillow==13.0.0 numpy==2.5.4.

--check needs only ai-edge-litert==1.3.0 and numpy==2.5.4 (a venv of its own): a runtime older than the app's LiteRT
1.4.2, whose macOS wheel doesn't load, so files that pass there load on the phone too.

What the files are:
  image: input 1 x 384 x 384 x 3 float, RGB 0 to 255, the photo squashed to a square (Meta's default transform);
         output 1 x 512, unit length. Weights int8 (dynamic-range), except the patch embedding and the attention
         pooling head, which stay float: quantised, they alone took the worst photo's cosine to the original from
         0.994 down to 0.990.
  text:  input 1 x 32 int32 token ids (ClipTokenizer); output 1 x 512, unit length. Weights int8 (dynamic-range),
         token table included. int4 failed the check (cosine 0.96 for the table alone, 0.69 for the image layers).
"""
import gzip
import hashlib
import json
import os
import shutil
import sys
import tarfile
import time
import urllib.request

import numpy as np

COMMIT = "3e352cca660658d4b5c90f42a7808b11469e4c66"
CODE = (f"https://github.com/facebookresearch/perception_models/archive/{COMMIT}.tar.gz",
        "63cc38a3248602cf9ad0a95c49fd2805b9116bf0683b7801532e988b4dedb43a")
REVISION = "aabf3b990573d8114ae6e501b4697106beac8f19"
WEIGHTS = (f"https://huggingface.co/facebook/PE-Core-S16-384/resolve/{REVISION}/PE-Core-S16-384.pt",
           "ccc8340a14ea3ebf557a288ba4ed4a5bc026ab98bb4da42fc745d44b4c5c5ffb")
# core/vision_encoder/bpe_simple_vocab_16e6.txt.gz in that commit: OpenAI CLIP's vocabulary file (MIT), byte for byte
BPE_SHA256 = "924691ac288e54409236115652ad4aa250f48203de50a9e4722a6ecd48d6804a"
COCO = "http://images.cocodataset.org/val2017/{:012d}.jpg"  # no https on that host; the set's SHA-256 guards it
COCO_SHA256 = "3dbb527f64563f89a6b046f1345b3b57ef4a62b460a057c92bbf1252d073d537"  # all 70, concatenated in id order

IMAGE, TEXT, LICENCE = "pe-core-s16-384-image.tflite", "pe-core-s16-384-text.tflite", "LICENSE-PE-Core-S16-384.txt"
RELEASE = "https://github.com/adityapandey78/album/releases/download/models-v1/"
REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
MERGES = os.path.join(REPO, "app", "src", "main", "assets", "describe", "bpe_merges.txt")

# Search's relevance floor, as in Embeddings.kt: a photo is a match from this cosine up, if it's also within WINDOW of
# the best match. See the floor study at the end of check().
FLOOR, WINDOW = 0.23, 0.06

# The zero-shot check: each photo's own caption must be its best one, in PyTorch and in LiteRT alike.
CAPTIONS = {
    285: "a brown bear", 724: "a stop sign by the road", 776: "a pile of teddy bears", 785: "a woman skiing on snow",
    885: "a man playing tennis", 1000: "a group of children posing for a photo", 1503: "a computer on an office desk",
    1584: "a red double-decker bus", 1761: "aeroplanes flying over a harbour bridge", 1818: "zebras grazing",
    2149: "green apples in a bowl", 2157: "a cake and snacks on a party table", 2299: "an old black and white class photo",
    3501: "a bowl of rice with curry and broccoli", 4765: "a surfer riding a wave", 5477: "an aeroplane on the runway",
    5992: "sheep on the grass", 6040: "a tram in the city", 6894: "a man with an elephant",
    39769: "two cats sleeping on a pink blanket",
}
# More photos, for the cosine check and the floor study.
OTHERS = [139, 632, 802, 872, 1268, 1296, 1353, 1425, 1490, 1532, 1675, 1993, 2006, 2153, 2261, 2431, 2473, 2532, 2587,
          2592, 2685, 2923, 3156, 3255, 3553, 3661, 3845, 3934, 4134, 4395, 4495, 4795, 5001, 5037, 5060, 5193, 5503, 5529,
          5586, 5600, 6012, 6213, 6460, 6471, 6614, 6723, 6763, 6771, 6818, 6954]
# Short queries as people type them, with the photos (of all 70) that show it, labelled by looking at them. An empty
# list is something none of them shows: whatever passes the floor for it is a false match.
QUERIES = {
    "bear": [285], "cat": [39769, 1675, 4795], "snow": [785, 2473, 2532, 3255, 5529], "bus": [1584, 2006, 5037],
    "tennis": [885, 5586, 1000], "elephant": [6894], "aeroplane": [1761, 5477], "children": [1000, 2299, 6954, 1353, 3934],
    "computer": [1503, 4795, 1675], "toys": [776], "fruit": [2149, 3661, 6012, 6614, 2587], "mountains": [3255, 2532],
    "food": [1425, 2157, 2431, 3501, 3845, 5600, 2587], "train": [6040], "sea": [1490, 2261, 4765, 6460, 2923],
    "kitchen": [802], "bathroom": [3156, 5503, 6213, 6818], "bedroom": [632, 1993], "living room": [139, 4495, 3934],
    "birthday cake": [2157], "beach": [], "dog": [], "wedding": [], "Taj Mahal": [], "fireworks": [], "a red car": [],
    "temple": [], "cricket match": [],
}
# ClipTokenizerTest compares the app's tokenizer with Meta's on these.
TOKENIZER_CASES = [
    "dog", "Dog on a BEACH", "a photo of a cat", "two kids playing cricket", "birthday cake with 25 candles",
    "Diwali lights at night!!!", "Mumbai, 2019", "it's my brother's wedding", "rock'n'roll", "dog’s ball",
    "“quoted” words", "café au lait", "naïve résumé", "  lots   of\tspace  ", "fish &amp; chips", "ﬁsh ﬂakes",
    "ＦＵＬＬ ｗｉｄｔｈ", "शादी की तस्वीरें", "東京タワー", "🐶🏖️ beach dog", "<end_of_text> sneaky", "e-mail: a.b@c.com",
    "a very long description of a family gathering at grandmother's house with lots of food on the table and children "
    "playing in the courtyard while the elders talk late into the night",
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch(url, path, digest):
    """Downloads [url] to [path] unless it's there, then insists on [digest]."""
    if not os.path.exists(path):
        print("downloading", url)
        with urllib.request.urlopen(url) as r, open(path + ".part", "wb") as f:
            shutil.copyfileobj(r, f)
        os.replace(path + ".part", path)
    if sha256(path) != digest:
        sys.exit(f"{path} is not the expected file (SHA-256 differs)")


def photos(work):
    """The 70 COCO photos, captioned ones first, as id -> path. Checked as one set."""
    folder = os.path.join(work, "coco")
    os.makedirs(folder, exist_ok=True)
    ids = list(CAPTIONS) + OTHERS
    paths = {i: os.path.join(folder, f"{i:012d}.jpg") for i in ids}
    for i, p in paths.items():
        if not os.path.exists(p):
            urllib.request.urlretrieve(COCO.format(i), p)
    h = hashlib.sha256()
    for i in sorted(ids):
        with open(paths[i], "rb") as f:
            h.update(f.read())
    if h.hexdigest() != COCO_SHA256:
        sys.exit("the COCO photos are not the expected ones")
    return paths


def main(work, out):
    os.makedirs(work, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    archive = os.path.join(work, f"perception_models-{COMMIT}.tar.gz")
    fetch(CODE[0], archive, CODE[1])
    code = os.path.join(work, f"perception_models-{COMMIT}")
    if not os.path.isdir(code):
        with tarfile.open(archive) as t:
            t.extractall(work, filter="data")  # refuses paths outside WORK
    weights = os.path.join(work, "PE-Core-S16-384.pt")
    fetch(WEIGHTS[0], weights, WEIGHTS[1])
    bpe = os.path.join(code, "core", "vision_encoder", "bpe_simple_vocab_16e6.txt.gz")
    if sha256(bpe) != BPE_SHA256:
        sys.exit("unexpected BPE vocabulary")
    paths = photos(work)

    sys.path.insert(0, code)  # Meta's own model code, from the archive checked above
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    import torchvision.transforms as T
    from PIL import Image
    import core.vision_encoder.pe as pe
    import core.vision_encoder.transforms as transforms

    clip = pe.CLIP.from_config("PE-Core-S16-384", pretrained=True, checkpoint_path=weights).eval()
    tokenizer = transforms.get_text_tokenizer(clip.context_length)

    class ImageEncoder(nn.Module):
        def __init__(self, visual):
            super().__init__()
            self.visual = visual

        def forward(self, rgb):  # (x / 255 - 0.5) / 0.5 is Meta's normalisation
            return F.normalize(self.visual(rgb.permute(0, 3, 1, 2) / 127.5 - 1), dim=-1)

    class TextEncoder(nn.Module):
        """Meta's TextTransformer.forward, except that end-of-text (the highest id) is found on float ids: LiteRT
        can't lower an int64 arg_max. Checked equal to clip.encode_text below."""

        def __init__(self, clip):
            super().__init__()
            self.clip = clip

        def forward(self, ids):
            c = self.clip
            x = c.token_embedding(ids) + c.positional_embedding
            x = c.ln_final(c.transformer(x, attn_mask=c.attn_mask))
            eot = ids.float().argmax(dim=-1)
            return F.normalize(x[torch.arange(x.shape[0]), eot] @ c.text_projection, dim=-1)

    # Check data: the photos as the model gets them (Meta's squash resize), the PyTorch embeddings to match
    resize = T.Resize((clip.image_size, clip.image_size), interpolation=T.InterpolationMode.BILINEAR)
    pixels = np.stack([np.asarray(resize(Image.open(paths[i]).convert("RGB"))) for i in paths])  # uint8
    texts = list(CAPTIONS.values()) + list(QUERIES)
    tokens = tokenizer(texts).numpy().astype(np.int32)
    image, text = ImageEncoder(clip.visual).eval(), TextEncoder(clip).eval()
    with torch.no_grad():
        image_ref = np.concatenate([image(torch.from_numpy(p.astype(np.float32))).numpy() for p in np.split(pixels, 7)])
        text_ref = text(torch.from_numpy(tokens)).numpy()
        same = clip.encode_text(torch.from_numpy(tokens).long(), normalize=True).numpy()
    assert np.abs(text_ref - same).max() < 1e-5, "TextEncoder differs from Meta's encode_text"
    np.savez(os.path.join(work, "check.npz"), pixels=pixels, tokens=tokens, image=image_ref, text=text_ref,
             ids=np.array(list(paths)), texts=np.array(texts))

    import litert_torch
    from ai_edge_quantizer import quantizer

    def convert(module, sample, name, keep_float=()):
        plain = os.path.join(work, name.replace(".tflite", "-f32.tflite"))
        litert_torch.convert(module, (sample,)).export(plain)
        int8 = {"num_bits": 8, "symmetric": True, "granularity": "CHANNELWISE", "dtype": "INT"}
        recipe = [{"regex": ".*", "operation": op, "algorithm_key": "min_max_uniform_quantize",
                   "op_config": {"weight_tensor_config": int8, "compute_precision": "INTEGER",
                                 "explicit_dequantize": False, "skip_checks": False, "min_weight_elements": 0}}
                  for op in ("FULLY_CONNECTED", "CONV_2D", "EMBEDDING_LOOKUP")]
        # Matched against PyTorch module paths in the op names; later entries win
        recipe += [{"regex": k, "operation": "*", "algorithm_key": "no_quantize", "op_config": {}} for k in keep_float]
        q = quantizer.Quantizer(plain)
        q.load_quantization_recipe(recipe)
        q.quantize().export_model(os.path.join(out, name), overwrite=True)

    with torch.no_grad():
        convert(image, torch.from_numpy(pixels[:1].astype(np.float32)), IMAGE, ("Conv2d_conv1", "AttentionPooling_attn_pool"))
        convert(text, torch.from_numpy(tokens[:1]), TEXT)

    os.makedirs(os.path.dirname(MERGES), exist_ok=True)
    with open(MERGES, "w", encoding="utf-8") as f:  # the merges CLIP's tokenizer uses: lines 1 to 48894 of the file
        f.write("\n".join(gzip.open(bpe).read().decode("utf-8").split("\n")[1:49152 - 256 - 2 + 1]) + "\n")
    shutil.copyfile(os.path.join(REPO, "licenses", "perception-encoder-Apache-2.0.txt"), os.path.join(out, LICENCE))
    files = [{"name": n, "size": os.path.getsize(os.path.join(out, n)), "sha256": sha256(os.path.join(out, n))}
             for n in (IMAGE, TEXT, LICENCE)]
    with open(os.path.join(out, "manifest.json"), "w") as f:
        json.dump({
            "release": RELEASE,
            "model": "Meta Perception Encoder PE-Core-S16-384, converted to LiteRT by tools/describe_convert.py",
            "weights": {"url": WEIGHTS[0], "sha256": WEIGHTS[1]},
            "code": {"url": CODE[0], "sha256": CODE[1]},
            "files": files,
        }, f, indent=2)
    for x in files:
        print(f"{x['name']}: {x['size']:,} bytes, sha256 {x['sha256']}")

    print("\nClipTokenizerTest expectations (Meta's tokenizer, ids before the padding):")
    for s in TOKENIZER_CASES:
        ids = [tokenizer.sot_token_id] + tokenizer.encode(s) + [tokenizer.eot_token_id]
        ids = ids[:clip.context_length - 1] + [tokenizer.eot_token_id] if len(ids) > clip.context_length else ids
        assert ids + [0] * (clip.context_length - len(ids)) == tokenizer([s])[0].tolist()
        print(f"    {json.dumps(s, ensure_ascii=False)} to intArrayOf({', '.join(map(str, ids))}),")
    check(work, out)


def check(work, out):
    from importlib.metadata import version
    from ai_edge_litert.interpreter import Interpreter

    d = np.load(os.path.join(work, "check.npz"))

    def run(name, inputs, threads=4):
        it = Interpreter(model_path=os.path.join(out, name), num_threads=threads)
        it.allocate_tensors()
        i, o = it.get_input_details()[0]["index"], it.get_output_details()[0]["index"]
        result, ms = [], []
        for x in inputs:
            it.set_tensor(i, x[None].astype(it.get_input_details()[0]["dtype"]))
            t = time.perf_counter()
            it.invoke()
            ms.append((time.perf_counter() - t) * 1000)
            result.append(it.get_tensor(o)[0].copy())
        return np.array(result), float(np.median(ms[1:]))

    image, _ = run(IMAGE, d["pixels"])
    text, _ = run(TEXT, d["tokens"])
    ci, ct = (image * d["image"]).sum(1), (text * d["text"]).sum(1)
    print(f"\nai-edge-litert {version('ai-edge-litert')}")
    print(f"image: {len(ci)} photos, cosine to PyTorch min {ci.min():.4f} mean {ci.mean():.4f}")
    print(f"text: {len(ct)} captions and queries, cosine min {ct.min():.4f} mean {ct.mean():.4f}")

    n = len(CAPTIONS)

    def q8(v):  # as the app stores and compares vectors: int8 with one scale per vector
        s = np.abs(v).max(1, keepdims=True) / 127
        return np.round(v / s) * s

    ref = d["image"][:n] @ d["text"][:n].T
    ok = True
    for name, sim in (("LiteRT", image[:n] @ text[:n].T), ("LiteRT, int8 vectors", q8(image[:n]) @ q8(text[:n]).T)):
        right = (sim.argmax(1) == np.arange(n)).sum()
        same = (sim.argmax(1) == ref.argmax(1)).all() and (sim.argmax(0) == ref.argmax(0)).all()
        print(f"zero-shot, {name}: {right}/{n} photos pick their own caption; best caption per photo and best photo "
              f"per caption as in PyTorch: {'yes' if same else 'NO'}; largest score change {np.abs(sim - ref).max():.4f}")
        ok &= bool(same) and right == n
    ok &= bool(ci.min() >= 0.99 and ct.min() >= 0.99)

    # Floor study: which photos pass FLOOR and WINDOW for short queries, with the app's int8 vectors
    ids = list(d["ids"])
    texts = list(d["texts"])
    sims = q8(image) @ q8(text).T
    found = missed = wrong = absent = present = 0
    for q, show in QUERIES.items():
        s = sims[:, texts.index(q)]
        keep = (s >= FLOOR) & (s >= s.max() - WINDOW)
        yes = np.array([i in show for i in ids])
        if show:
            found += (keep & yes).sum()
            missed += (~keep & yes).sum()
            wrong += (keep & ~yes).sum()
            present += 1
        else:
            absent += keep.sum()
    print(f"floor {FLOOR} within {WINDOW} of the best: finds {found / (found + missed):.0%} of the labelled photos; "
          f"{wrong / present:.1f} other photos (of {len(ids)}) per query; {absent / (len(QUERIES) - present):.1f} per "
          f"query for things none of them shows")

    _, image_1 = run(IMAGE, d["pixels"][:6], threads=1)
    _, image_2 = run(IMAGE, d["pixels"][:6], threads=2)
    _, text_1 = run(TEXT, d["tokens"][:20], threads=1)
    print(f"latency on this computer: image {image_1:.0f} ms (1 thread), {image_2:.0f} ms (2 threads); text {text_1:.1f} ms")
    if not ok:
        sys.exit("check FAILED")
    print("check passed")


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--check":
        check(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 3:
        main(sys.argv[1], sys.argv[2])
    else:
        sys.exit(__doc__)
