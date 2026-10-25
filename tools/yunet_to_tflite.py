#!/usr/bin/env python3
"""Builds People's face detector, app/src/main/assets/faces/yunet.tflite, from OpenCV Zoo's YuNet (MIT, see
licenses/yunet-MIT.txt), then checks it against the original on real photos.

    python3 -I tools/yunet_to_tflite.py ONNX PHOTOS_DIR

ONNX is face_detection_yunet_2026may.onnx (the 2023mar model with its height and width left free), from
https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet at commit eb9f4f2d4b79b58e5513b5d25d5f0243a8357bf4,
sha256 checked below. PHOTOS_DIR is any folder of photos with faces (the People evaluation set, tools/face_eval.py).

Run in a throwaway Python 3.12 venv with exactly: onnx2tf[tensorflow]==2.6.9, which pins onnx 1.20.1, onnxruntime
1.26.0, ai-edge-litert 2.1.2, numpy 2.2.6, opencv-python 4.13.0.92 (all from PyPI).

The result takes 1 x H x W x 3 (any H and W, multiples of 32), BGR, 0 to 255, no normalisation, like the original,
and gives the original's 12 outputs by name: cls, obj, bbox and kps at strides 8, 16 and 32 (float32 weights, 244 KB).
The check runs both models on every photo at 640 x 480, 480 x 640, 640 x 640 and 160 x 160 and fails unless every
output agrees within 1e-3.
"""
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

import cv2
import numpy as np

SHA256 = "ebafce4e3c118d6554634be5c27ab333b4c047a9a8c3faf1d7cf93101c22f0f0"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "faces", "yunet.tflite")
NAMES = [f"{kind}_{stride}" for kind in ("cls", "obj", "bbox", "kps") for stride in (8, 16, 32)]
SIZES = [(640, 480), (480, 640), (640, 640), (160, 160)]  # width, height


def main(onnx_path, photos):
    with open(onnx_path, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != SHA256:
            sys.exit("Not the expected face_detection_yunet_2026may.onnx")
    with tempfile.TemporaryDirectory() as work:
        source = os.path.join(work, "yunet.onnx")  # onnx2tf rewrites its input, so it gets a copy
        shutil.copyfile(onnx_path, source)
        onnx2tf = os.path.join(os.path.dirname(sys.executable), "onnx2tf")
        subprocess.run([onnx2tf, "-i", source, "-o", work, "-osd", "-n"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        shutil.copyfile(os.path.join(work, "yunet_float32.tflite"), OUT)
    check(onnx_path, OUT, photos)


def check(onnx_path, tflite_path, photos):
    import onnxruntime as ort
    from ai_edge_litert.interpreter import Interpreter

    session = ort.InferenceSession(onnx_path)
    lite = Interpreter(model_path=tflite_path)
    run = lite.get_signature_runner()
    worst = 0.0
    count = 0
    for name in sorted(os.listdir(photos)):
        bgr = cv2.imread(os.path.join(photos, name), cv2.IMREAD_COLOR)
        if bgr is None:
            continue
        for w, h in SIZES:
            x = cv2.resize(bgr, (w, h), interpolation=cv2.INTER_AREA).astype(np.float32)
            want = dict(zip(NAMES, session.run(NAMES, {"input": x.transpose(2, 0, 1)[None]})))
            got = run(input=x[None])
            worst = max(worst, max(float(np.abs(got[k] - want[k]).max()) for k in NAMES))
        count += 1
    print(f"{tflite_path}: {os.path.getsize(tflite_path):,} bytes, {count} photos x {len(SIZES)} sizes, largest difference to the original {worst:.2e}")
    if worst > 1e-3:
        sys.exit("The converted model does not match the original")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
