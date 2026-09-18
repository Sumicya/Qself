/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

/**
 * AGSL programs shared by the pill and the droplet renderers.
 *
 * <p>The refraction shader derives from Kyant0/AndroidLiquidGlass (android
 * branch), Copyright 2025 Kyant, Apache License 2.0. The signed-distance
 * helpers are the renderer-internal contract between the two surfaces.
 */
final class GlassShader {

    private GlassShader() {
    }

    /** Signed-distance helpers reused by every glass program. */
    static final String SDF_SOURCE = ""
            + "float radiusAt(float2 p, float4 radii) {\n"
            + "    if (p.x < 0.0) return p.y > 0.0 ? radii.w : radii.x;\n"
            + "    return p.y > 0.0 ? radii.z : radii.y;\n"
            + "}\n"
            + "float sdRoundedRect(float2 p, float2 halfExtent, float radius) {\n"
            + "    float2 q = abs(p) - halfExtent + radius;\n"
            + "    float outside = length(max(q, 0.0)) - radius;\n"
            + "    float inside = min(max(q.x, q.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 p, float2 halfExtent, float radius) {\n"
            + "    float2 q = abs(p) - halfExtent + radius;\n"
            + "    float2 corner = max(q, 0.0);\n"
            + "    if (dot(corner, corner) > 0.0) return sign(p) * normalize(corner);\n"
            + "    float2 g = sign(p);\n"
            + "    if (q.x >= q.y) { g.y = 0.0; } else { g.x = 0.0; }\n"
            + "    return g;\n"
            + "}\n";

    /** Rim-only refraction lens: the centre passes through untouched. */
    static final String LENS_PROGRAM = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + SDF_SOURCE
            + "half4 main(float2 coord) {\n"
            + "    float2 halfExtent = size * 0.5;\n"
            + "    float2 local = (coord + offset) - halfExtent;\n"
            + "    float radius = radiusAt(coord, cornerRadii);\n"
            + "    float dist = sdRoundedRect(local, halfExtent, radius);\n"
            + "    if (-dist >= refractionHeight) return content.eval(coord);\n"
            + "    float rim = clamp(-dist / refractionHeight, 0.0, 1.0);\n"
            + "    float bend = 1.0 - sqrt(max(0.0, 1.0 - rim * rim));\n"
            + "    float gradLimit = min(radius * 1.5, min(halfExtent.x, halfExtent.y));\n"
            + "    float2 normal = normalize(gradSdRoundedRect(local, halfExtent, gradLimit)\n"
            + "                             + depthEffect * normalize(local));\n"
            + "    return content.eval(coord + bend * refractionAmount * normal);\n"
            + "}\n";
}
