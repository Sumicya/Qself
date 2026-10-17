#!/bin/bash
# 仅用于构建环境的 CPU 着色器编译工具；不进入模块，不替代 Android GPU 验收。
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd)
if [[ ${GITHUB_ACTIONS:-false} == true ]]; then
    sudo apt-get update -qq
    sudo apt-get install -y -qq --no-install-recommends libegl1 libgl1
fi
T=${RUNNER_TEMP:-$HOME/.cache}/qself-skia-138
python3 -m venv "$T"
"$T/bin/python" -m pip install --disable-pip-version-check --quiet skia-python==138.0 numpy==2.2.6
"$T/bin/python" "$R/tools/check_kernel_shaders.py"
