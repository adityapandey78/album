#!/usr/bin/env python3
"""Builds People's face recognition model, app/src/main/assets/faces/edgeface_s.tflite, from Idiap's EdgeFace-S
(gamma 0.5), then checks it against the original PyTorch model on real faces.

    python3 -I tools/edgeface_to_tflite.py CHECKPOINT FACES_DIR [RECIPE]

CHECKPOINT is edgeface_s_gamma_05.pt from https://huggingface.co/Idiap/EdgeFace-S-GAMMA (revision
a1c171036159e94bd4c766088370a37d0899d31f; the same bytes as checkpoints/ in https://github.com/otroshi/edgeface at
ce86851cfc37979a9cd2558598d0e9bc592cbba3; Idiap's MD5 b5bfe661b3575110b108d72aa66b8923), sha256 checked below.
The weights are CC BY-NC-SA 4.0 (licenses/edgeface-CC-BY-NC-SA-4.0.txt): fine while Album stays free, and the
converted file keeps that licence. FACES_DIR holds aligned 112 x 112 face crops (PNG or JPEG, any names), such as the
ones tools/face_reference.py writes. RECIPE: fp16 (weights stored as float16, maths in float32; the default: 7.9 MB
and within cosine 0.9999 of PyTorch), fp32 (15 MB), wi8 (int8 weights, 4.5 MB, but only cosine 0.991 on the worst
face) or dynamic (int8 weights and activations quantised on the fly: cosine 0.84, unusable).

Run in a throwaway Python 3.12 venv with exactly these PyPI packages: litert-torch==0.10.0, torch==2.14.1,
timm==1.0.30, opencv-python==5.0.0.93 (litert-torch brings ai-edge-litert 2.3.0 and ai-edge-quantizer 0.10.0).

The model is built the way the authors' code builds it (backbones/ in their repository, BSD 3-Clause, Copyright (c)
2024 Anjith George, Christophe Ecabert, Hatef Otroshi Shahreza, Ketan Kotwal, Sebastien Marcel, Idiap Research
Institute): timm's EdgeNeXt-S with a 512-wide head, every linear layer but the head's container split into two of rank
half the smaller side. The checkpoint must load with no missing or unexpected weights. The result takes
1 x 112 x 112 x 3 RGB scaled to -1..1 ((x / 255 - 0.5) / 0.5, the authors' transform) and gives the raw 512-wide
embedding. The check fails unless every face's embedding is within cosine 0.99 of PyTorch's.
"""
import hashlib
import os
import sys
import tempfile

import cv2
import numpy as np
import timm
import torch
import torch.nn as nn

SHA256 = "dc59abda2e8580399fd115a1eeb07e1f21156196db604b884407bcf0f17efb07"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "faces", "edgeface_s.tflite")
MIN_COSINE = 0.99


class LoRaLin(nn.Module):
    """A linear layer as two of lower rank (EdgeFace's backbones/timmfr.py)."""

    def __init__(self, in_features, out_features, rank, bias=True):
        super().__init__()
        self.linear1 = nn.Linear(in_features, rank, bias=False)
        self.linear2 = nn.Linear(rank, out_features, bias=bias)

    def forward(self, x):
        return self.linear2(self.linear1(x))


def low_rank(module, ratio):
    """EdgeFace's replace_linear_with_lowrank_2: every nn.Linear whose own name lacks "head"."""
    for name, child in module.named_children():
        if isinstance(child, nn.Linear) and "head" not in name:
            rank = max(2, int(min(child.in_features, child.out_features) * ratio))
            setattr(module, name, LoRaLin(child.in_features, child.out_features, rank, child.bias is not None))
        else:
            low_rank(child, ratio)


class EdgeFace(nn.Module):
    """EdgeFace's TimmFRWrapperV2 for edgeface_s_gamma_05."""

    def __init__(self):
        super().__init__()
        self.model = timm.create_model("edgenext_small")
        self.model.reset_classifier(512)
        low_rank(self, 0.5)

    def forward(self, x):
        return self.model(x)


def load(checkpoint):
    with open(checkpoint, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != SHA256:
            sys.exit("Not the expected edgeface_s_gamma_05.pt")
    model = EdgeFace()
    # weights_only: the checkpoint is plain tensors, so nothing in it can run code while loading
    model.load_state_dict(torch.load(checkpoint, map_location="cpu", weights_only=True), strict=True)
    return model.eval()


def faces_in(folder):
    out = []
    for name in sorted(os.listdir(folder)):
        bgr = cv2.imread(os.path.join(folder, name), cv2.IMREAD_COLOR)
        if bgr is not None:
            out.append(cv2.resize(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB), (112, 112), interpolation=cv2.INTER_AREA))
    return np.stack(out).astype(np.float32) / 127.5 - 1  # (x / 255 - 0.5) / 0.5


def convert(model, recipe):
    import litert_torch

    channel_last = litert_torch.to_channel_last_io(model, args=[0]).eval()
    lite = litert_torch.convert(channel_last, (torch.zeros(1, 112, 112, 3),))
    with tempfile.TemporaryDirectory() as work:
        path = os.path.join(work, "edgeface.tflite")
        lite.export(path)
        if recipe != "fp32":
            from ai_edge_quantizer import algorithm_manager, qtyping, quantizer, recipe as recipes, recipe_manager

            if recipe == "fp16":
                manager = recipe_manager.RecipeManager()
                manager.add_weight_only_config(".*", qtyping.TFLOperationName.ALL_SUPPORTED, 16, algorithm_key=algorithm_manager.AlgorithmName.FLOAT_CASTING)
                chosen = manager.get_quantization_recipe()
            else:
                chosen = recipes.weight_only_wi8_afp32() if recipe == "wi8" else recipes.dynamic_wi8_afp32()
            q = quantizer.Quantizer(path)
            q.load_quantization_recipe(chosen)
            q.quantize().export_model(OUT, overwrite=True)
        else:
            with open(path, "rb") as src, open(OUT, "wb") as dst:
                dst.write(src.read())


def check(model, faces):
    from ai_edge_litert.interpreter import Interpreter

    with torch.no_grad():
        want = model(torch.from_numpy(faces).permute(0, 3, 1, 2)).numpy()
    lite = Interpreter(model_path=OUT)
    lite.allocate_tensors()
    inp, out = lite.get_input_details()[0]["index"], lite.get_output_details()[0]["index"]
    cos = []
    for x, w in zip(faces, want):
        lite.set_tensor(inp, x[None])
        lite.invoke()
        g = lite.get_tensor(out)[0]
        cos.append(float(g @ w / np.linalg.norm(g) / np.linalg.norm(w)))
    print(f"{OUT}: {os.path.getsize(OUT):,} bytes, {len(faces)} faces, cosine to PyTorch min {min(cos):.4f} median {np.median(cos):.4f}")
    if min(cos) < MIN_COSINE:
        sys.exit(f"The converted model is further than cosine {MIN_COSINE} from the original")


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    model = load(sys.argv[1])
    convert(model, sys.argv[3] if len(sys.argv) == 4 else "fp16")
    check(model, faces_in(sys.argv[2]))
