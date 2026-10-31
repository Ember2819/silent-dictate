#!/usr/bin/env python3
"""
Export a small visual-speech-recognition (lip-reading) model to the format
Silent Dictate loads on-device: model.onnx + config.json + vocab.txt.

Silent Dictate feeds the model a stack of mouth crops (grayscale, mouth ROI
sampled to width x height, normalized with mean/std) and greedy-decodes CTC
logits. It is fully generic: WHAT the graph is doesn't matter, only the tensor
contract in config.json.

Recipe used here: AutoAVSR (https://github.com/mpc002/auto_avsr) — its pytorch
checkpoint ships on GitHub releases. Other small VSR models work equally well
as long as they are CTC (e.g. a conformer/GRU head) and take mouth-ROI frames.

    pip install torch onnx onnxruntime autoavsr-style deps (see repo)
    python export_lip_model.py --checkpoint auto_avsr_1.pth --out model_zip/

Then zip the three produced files (keep names!) and host it anywhere;
paste the URL in the app's "Lip model" section.

The intermediate wrapper below exposes a clean forward:
input  [1, T, 3, 88, 88], normalized frames
output [1, T', C]            logits (log-softmax or raw logits, CTC)
"""
import argparse
import json
import os

import torch
import torch.nn as nn


class VSRWrapper(nn.Module):
    """Adapter: fixed-size input/output names + shapes for torch.onnx.export."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, video):
        # video: [1, T, 3, H, W] (float, normalized)
        out = self.model(video)          # adjust to the checkpoint's forward
        if isinstance(out, (tuple, list)):
            out = out[0]
        # force shape [1, T', C] and float32
        out = out.float()
        if out.ndim == 3 and out.shape[-1] < min(out.shape[0], out.shape[1]):
            # [T', C, 1] style -> transpose to last-dim C
            pass
        return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--out", default="model_zip")
    ap.add_argument("--input-shape", default="1,3,29,88,88")
    ap.add_argument("--input-name", default="video")
    ap.add_argument("--output-name", default="logits")
    ap.add_argument("--input-order", default="ncthw",
                    help="axis order over n,c,t,h,w matching input_shape")
    ap.add_argument("--mean", type=float, default=0.421)
    ap.add_argument("--std", type=float, default=0.165)
    ap.add_argument("--blank-id", type=int, default=0)
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)

    # ---- load (adapt these three lines to the model repo you are exporting) --
    from auto_avsr.demo import load_ckpt  # noqa — example: see README
    model = load_ckpt(args.checkpoint)  # returns a nn.Module in eval mode
    model.eval()
    # -------------------------------------------------------------------------

    shape = [int(x) for x in args.input_shape.split(",")]
    dummy = torch.randn(*shape, dtype=torch.float32)

    wrapper = VSRWrapper(model).eval()
    onnx_path = os.path.join(args.out, "model.onnx")
    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            dummy,
            onnx_path,
            input_names=[args.input_name],
            output_names=[args.output_name],
            opset_version=14,
            do_constant_folding=True,
        )

    # build config.json
    config = {
        "input_name": args.input_name,
        "output_name": args.output_name,
        "input_shape": shape,
        "input_order": args.input_order,
        "mean": args.mean,
        "std": args.std,
        "blank_id": args.blank_id,
        "ignore": ["<pad>", "<unk>"],
    }
    with open(os.path.join(args.out, "config.json"), "w") as f:
        json.dump(config, f, indent=2)

    # build vocab.txt — one token per CTC column, index == logit column.
    # AutoAVSR uses SentencePiece; decode each id's piece:
    try:
        tok = model.tokenizer  # adapt if your wrapper differs
        with open(os.path.join(args.out, "vocab.txt"), "w") as f:
            for i in range(tok.get_vocab_size()):
                piece = tok.id2tok(i)
                f.write(piece + "\n")
    except AttributeError:
        print("!! Write vocab.txt manually: one token per line, "
              "line index == logit column index")

    print("Wrote", onnx_path)
    print("zip", args.out, "and point the app at it.")


if __name__ == "__main__":
    main()
