#!/usr/bin/env python3
"""
Build a tiny SYNTHETIC model zip to test Silent Dictate's install and inference
plumbing (file import, validation, loading, CTC decoding) without a real
lip-reading checkpoint. It does NOT read lips: its logits are just a function
of average frame brightness.

    pip install onnx numpy
    python tools/make_test_model.py        # writes synthetic_model.zip
    adb push synthetic_model.zip /sdcard/Download/
    # then: app -> Lip model -> Import .zip
"""
import json
import zipfile

import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper

T, C = 29, 5
inp = helper.make_tensor_value_info("video", TensorProto.FLOAT, [1, 3, T, 88, 88])
out = helper.make_tensor_value_info("logits", TensorProto.FLOAT, [1, T, C])
# mean over c,h,w -> [1,1,T,1,1] -> reshape [1,T,1] -> * weights [1,1,C] (toy logits)
axes = numpy_helper.from_array(np.array([1, 3, 4], dtype=np.int64), "axes")
shape = numpy_helper.from_array(np.array([1, T, 1], dtype=np.int64), "shape")
w = numpy_helper.from_array(np.array([[[0.0, 1.0, -1.0, 0.5, -0.5]]], dtype=np.float32), "w")
b = numpy_helper.from_array(np.array([[[0.1, 0.0, 0.2, 0.0, 0.0]]], dtype=np.float32), "b")
nodes = [
    helper.make_node("ReduceMean", ["video", "axes"], ["m"], keepdims=1),
    helper.make_node("Reshape", ["m", "shape"], ["r"]),
    helper.make_node("Mul", ["r", "w"], ["s"]),
    helper.make_node("Add", ["s", "b"], ["logits"]),
]
g = helper.make_graph(nodes, "synth", [inp], [out], initializer=[axes, shape, w, b])
m = helper.make_model(g, opset_imports=[helper.make_opsetid("", 18)])
m.ir_version = 8
onnx.checker.check_model(m)
onnx.save(m, "model.onnx")
json.dump({"input_name": "video", "output_name": "logits", "input_shape": [1, 3, T, 88, 88],
           "input_order": "ncthw", "mean": 0.421, "std": 0.165, "blank_id": 0,
           "ignore": ["<pad>", "<unk>"]}, open("config.json", "w"))
open("vocab.txt", "w").write("<blank>\nh\ni\n▁\nx\n")
with zipfile.ZipFile("synthetic_model.zip", "w") as z:
    for f in ("model.onnx", "config.json", "vocab.txt"): z.write(f)
print("ok")
