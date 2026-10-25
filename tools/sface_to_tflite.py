#!/usr/bin/env python3
"""Builds the People face-embedding model from OpenCV Zoo's SFace (Apache 2.0, see licenses/sface-Apache-2.0.txt).

    python3 tools/sface_to_tflite.py ONNX     # face_recognition_sface_2021dec.onnx, downloaded from
        https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface (sha256 checked below)

Needs Python 3.12 with tensorflow 2.19, tf_keras 2.19, onnx, onnx2tf 1.28, onnx_graphsurgeon, sng4onnx, ai_edge_litert
and onnxruntime. Writes app/src/main/assets/faces/sface.tflite: input 1 x 112 x 112 x 3 RGB (0 to 255, the model
normalises itself), output 1 x 128, weights int8 (dynamic-range quantisation: 9.9 MB instead of 38.6 MB).
Then checks the result against the ONNX original on random inputs and prints the lowest cosine similarity.
"""
import hashlib
import os
import shutil
import sys
import tempfile

import numpy as np

SHA256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "faces", "sface.tflite")


def main(onnx_path):
    with open(onnx_path, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != SHA256:
            sys.exit("Not the expected face_recognition_sface_2021dec.onnx")

    import onnx2tf.onnx2tf as o
    import tensorflow as tf

    # onnx2tf's own sample data is a pickled download; random data is enough for its shape checks.
    o.download_test_image_data = lambda: np.random.default_rng(0).random((20, 112, 112, 3), dtype=np.float32)
    with tempfile.TemporaryDirectory() as work:
        # onnx2tf rewrites its input in place (names, simplification), so it gets a copy
        source = os.path.join(work, "sface.onnx")
        shutil.copyfile(onnx_path, source)
        o.convert(input_onnx_file_path=source, output_folder_path=work, output_signaturedefs=True, non_verbose=True)
        converter = tf.lite.TFLiteConverter.from_saved_model(work)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]  # int8 weights, float activations
        model = converter.convert()
    with open(OUT, "wb") as f:
        f.write(model)
    check(onnx_path, OUT)


def check(onnx_path, tflite_path):
    import onnxruntime as ort
    from ai_edge_litert.interpreter import Interpreter

    session = ort.InferenceSession(onnx_path)
    lite = Interpreter(model_path=tflite_path)
    lite.allocate_tensors()
    worst = 1.0
    for x in np.random.default_rng(1).random((8, 112, 112, 3), dtype=np.float32) * 255:
        a = session.run(None, {"data": x.transpose(2, 0, 1)[None]})[0][0]
        lite.set_tensor(lite.get_input_details()[0]["index"], x[None])
        lite.invoke()
        b = lite.get_tensor(lite.get_output_details()[0]["index"])[0]
        worst = min(worst, float(a @ b / np.linalg.norm(a) / np.linalg.norm(b)))
    print(f"{tflite_path}: {os.path.getsize(tflite_path):,} bytes, lowest cosine similarity to the original {worst:.4f}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
