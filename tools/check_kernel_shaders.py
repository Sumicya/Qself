#!/usr/bin/env python3
"""Skia CPU 编译检查；不代表 Android GPU 或 QQ 设备验收。需要 skia-python==138.0。"""
import re
from pathlib import Path

NAMES = ('ROUNDED_RECT_REFRACTION_SHADER', 'ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER',
         'BLOOM_STROKE_SHADER_DUAL', 'COLOR_CONTROLS_SHADER', 'INTERACTIVE_HIGHLIGHT_SHADER')


def shaders():
    source = (Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/sumicya/qself/KernelBar.kt').read_text()
    constants = dict(re.findall(r'private const val (\w+) = """(.*?)"""', source, re.S))
    result = {}
    for name in NAMES:
        value = constants[name]
        for key, replacement in constants.items():
            value = value.replace('$' + key, replacement)
        assert '$' not in value, f'未展开常量: {name}'
        result[name] = value
    return result


if __name__ == '__main__':
    import skia
    for name, shader in shaders().items():
        assert skia.RuntimeEffect.MakeForShader(shader) is not None, name
        print(f'Skia {skia.__version__}: {name} 编译通过（非设备验证）')

    print(f"::notice title=KernelSU shader compilation::Skia 138 CPU compiled all {len(NAMES)} ported shaders; Android GPU/device verification is not performed.")
