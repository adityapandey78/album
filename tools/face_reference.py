#!/usr/bin/env python3
"""The golden check for People's face pipeline: Album's faces (from FaceEvalTest's faces.tsv) against the same steps
done by the original tools, on the same photos.

    python3 -I tools/face_reference.py CHECKPOINT YUNET_ONNX PHOTOS_DIR FACES_TSV [ALIGNED_DIR]

CHECKPOINT and YUNET_ONNX are the original models (see tools/edgeface_to_tflite.py and tools/yunet_to_tflite.py; their
sha256 is checked there), PHOTOS_DIR the photos FaceEvalTest read (adb pull /sdcard/Pictures/PeopleEval), FACES_TSV
the faces.tsv it wrote (adb pull /sdcard/Android/data/com.aditya.album.eval/files/faces.tsv). Same venv as
tools/edgeface_to_tflite.py. Optionally writes the reference's aligned 112 x 112 faces to ALIGNED_DIR, for that
script's own check.

Two checks, each per face Album kept:
1. Landmarks: OpenCV's FaceDetectorYN (the original ONNX) on the photo fitted into 640 x 640 the way Faces.kt does it;
   the face matching Album's box, and how far apart the two sets of five landmarks are (full-size pixels).
2. Embedding: from Album's landmarks, the face cut and embedded the way Faces.kt does it, but with OpenCV and the
   original PyTorch EdgeFace-S: similarity onto the template, the region at the same power-of-two sampling (INTER_AREA,
   where Album's decoder scales the JPEG), bilinear warp into the 160 x 160 view, its middle 112 x 112, RGB,
   (x / 255 - 0.5) / 0.5. Cosine similarity to Album's embedding. This checks the cut (region, sampling, orientation,
   warp, pixel centres, colour order), the normalisation and the converted model together.
Positions are measured from pixel corners as in Faces.kt (OpenCV counts from pixel centres: -0.5 on the way in).
Photos are assumed upright as stored (FaceEvalTest's are; Faces.kt's EXIF handling has its own unit test).
"""
import os
import sys

import cv2
import numpy as np
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I leaves the script's folder out; edgeface_to_tflite is ours
from edgeface_to_tflite import EdgeFace  # noqa: E402

DETECT, VIEW, INPUT = 640, 160, 112
PAD = (VIEW - INPUT) // 2
TEMPLATE = np.array([38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041]).reshape(5, 2)
TEMPLATE_IN_VIEW = TEMPLATE + PAD + 0.5


def similarity(src, dst):
    """Faces.kt's similarity(): least squares scale, rotation and shift (no reflection), as a 2 x 3 matrix."""
    ms, md = src.mean(0), dst.mean(0)
    s, d = src - ms, dst - md
    norm = (s ** 2).sum()
    a = (s[:, 0] * d[:, 0] + s[:, 1] * d[:, 1]).sum() / norm
    b = (s[:, 0] * d[:, 1] - s[:, 1] * d[:, 0]).sum() / norm
    return np.array([[a, -b, md[0] - (a * ms[0] - b * ms[1])], [b, a, md[1] - (b * ms[0] + a * ms[1])]])


def then(first, second):
    """2 x 3 affine maps: [first], then [second]."""
    return (np.vstack([second, [0, 0, 1]]) @ np.vstack([first, [0, 0, 1]]))[:2]


def landmarks(det, bgr):
    """YuNet as Faces.kt runs it: the photo fitted into DETECT x DETECT (INTER_AREA here), placed top-left, rest black."""
    h, w = bgr.shape[:2]
    scale = min(1.0, DETECT / max(h, w))
    small = cv2.resize(bgr, (max(1, round(w * scale)), max(1, round(h * scale))), interpolation=cv2.INTER_AREA) if scale < 1 else bgr
    canvas = np.zeros((DETECT, DETECT, 3), np.uint8)
    canvas[:small.shape[0], :small.shape[1]] = small
    det.setInputSize((DETECT, DETECT))
    _, found = det.detect(canvas)
    if found is None:
        return []
    sx, sy = w / small.shape[1], h / small.shape[0]
    out = []
    for f in found:
        box = ((f[0] + 0.5) * sx / w, (f[1] + 0.5) * sy / h, (f[0] + f[2] + 0.5) * sx / w, (f[1] + f[3] + 0.5) * sy / h)
        out.append((box, (f[4:14].reshape(5, 2) + 0.5) * [sx, sy]))
    return out


def view(rgb, points):
    """FullSize.cut(): the region the view needs, at Album's sampling, warped into VIEW x VIEW."""
    h, w = rgb.shape[:2]
    to_view = similarity(points, TEMPLATE_IN_VIEW)
    back = cv2.invertAffineTransform(to_view)
    corners = np.array([[0, 0], [VIEW, 0], [0, VIEW], [VIEW, VIEW]], float) @ back[:, :2].T + back[:, 2]
    per_view_pixel = 1 / np.hypot(to_view[0, 0], to_view[1, 0])
    k = 1 << (max(1, int(per_view_pixel)).bit_length() - 1)  # Integer.highestOneBit
    left, top = max(0, int(np.floor((corners[:, 0].min() - 1) / k)) * k), max(0, int(np.floor((corners[:, 1].min() - 1) / k)) * k)
    right, bottom = min(w, int(np.ceil((corners[:, 0].max() + 1) / k)) * k), min(h, int(np.ceil((corners[:, 1].max() + 1) / k)) * k)
    piece = rgb[top:bottom, left:right]
    if k > 1:
        piece = cv2.resize(piece, (-(-piece.shape[1] // k), -(-piece.shape[0] // k)), interpolation=cv2.INTER_AREA)
    to_stored = np.array([[(right - left) / piece.shape[1], 0, left], [0, (bottom - top) / piece.shape[0], top]])
    m = then(to_stored, to_view)
    # Pixel corners (Album) to pixel centres (OpenCV) on both sides
    m_cv = then(then(np.array([[1, 0, 0.5], [0, 1, 0.5]]), m), np.array([[1, 0, -0.5], [0, 1, -0.5]]))
    return cv2.warpAffine(piece, m_cv, (VIEW, VIEW), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)


def iou(a, b):
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    if w <= 0 or h <= 0:
        return 0.0
    return w * h / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - w * h)


def main(checkpoint, onnx, photos, tsv, aligned_dir=None):
    model = EdgeFace()
    model.load_state_dict(torch.load(checkpoint, map_location="cpu", weights_only=True), strict=True)
    model.eval()
    det = cv2.FaceDetectorYN.create(onnx, "", (DETECT, DETECT), 0.6, 0.3, 5000)
    rows = [line.rstrip("\n").split("\t") for line in open(tsv) if line.strip()]
    cosines, gaps, unmatched = [], [], 0
    detected = {}
    for i, (name, truth, quality, weak, cluster, box, points, embedding) in enumerate(rows):
        path = next(os.path.join(photos, f) for f in os.listdir(photos) if os.path.splitext(f)[0] == name)
        bgr = cv2.imread(path, cv2.IMREAD_COLOR)
        rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
        points = np.array(points.split(","), float).reshape(5, 2)
        box = tuple(map(float, box.split(",")))
        if name not in detected:
            detected[name] = landmarks(det, bgr)
        match = max(detected[name], key=lambda d: iou(d[0], box), default=None)
        if match is not None and iou(match[0], box) >= 0.5:
            gaps.append(float(np.abs(match[1] - points).max()))
        else:
            unmatched += 1
        face = view(rgb, points)[PAD:PAD + INPUT, PAD:PAD + INPUT]
        if aligned_dir:
            cv2.imwrite(os.path.join(aligned_dir, f"{i:04d}.png"), cv2.cvtColor(face, cv2.COLOR_RGB2BGR))
        with torch.no_grad():
            want = model(torch.from_numpy(face.astype(np.float32) / 127.5 - 1).permute(2, 0, 1)[None])[0].numpy()
        got = np.array(embedding.split(","), float)
        cosines.append(float(got @ want / np.linalg.norm(got) / np.linalg.norm(want)))
    c, g = np.array(cosines), np.array(gaps)
    print(f"{len(rows)} faces. Embedding from Album's landmarks, cosine to the reference: min {c.min():.4f}, 5th percentile "
          f"{np.percentile(c, 5):.4f}, median {np.median(c):.4f}; {np.mean(c >= 0.99):.1%} at 0.99 or more")
    print(f"Landmarks, Album against OpenCV's YuNet ({len(g)} faces found by both, {unmatched} not): largest gap per face "
          f"median {np.median(g):.2f} px, 95th percentile {np.percentile(g, 95):.2f} px, max {g.max():.2f} px (full size)")
    worst = np.argsort(c)[:5]
    print("lowest cosines: " + ", ".join(f"{rows[j][0]} ({c[j]:.4f})" for j in worst))


if __name__ == "__main__":
    if len(sys.argv) not in (5, 6):
        sys.exit(__doc__)
    main(*sys.argv[1:])
