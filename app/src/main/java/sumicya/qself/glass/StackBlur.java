/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.graphics.Bitmap;

/**
 * CPU fallback blur for hosts without a hardware blur path: three rounds of
 * separable box blur approximate a Gaussian at a fraction of the cost. Each
 * pass uses a sliding window, so the work per pixel is independent of the
 * radius. Operates in place on the given bitmap.
 */
final class StackBlur {

    private static final int PASSES = 3;

    private StackBlur() {
    }

    static void blur(Bitmap bitmap, int radius) {
        if (bitmap == null || radius < 1) {
            return;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        int[] work = new int[pixels.length];
        for (int pass = 0; pass < PASSES; pass++) {
            horizontalWindow(pixels, work, width, height, radius);
            verticalWindow(work, pixels, width, height, radius);
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    }

    /** Box window along rows, edge pixels clamp instead of bleeding black. */
    private static void horizontalWindow(int[] src, int[] dst, int w, int h, int r) {
        final int window = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int x = -r; x <= r; x++) {
                int p = src[row + clamp(x, 0, w - 1)];
                a += (p >>> 24);
                rr += (p >> 16) & 0xFF;
                gg += (p >> 8) & 0xFF;
                bb += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = ((a / window) << 24) | ((rr / window) << 16)
                        | ((gg / window) << 8) | (bb / window);
                int leave = clamp(x - r, 0, w - 1);
                int enter = clamp(x + r + 1, 0, w - 1);
                int pOut = src[row + leave];
                int pIn = src[row + enter];
                a += (pIn >>> 24) - (pOut >>> 24);
                rr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                gg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                bb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static void verticalWindow(int[] src, int[] dst, int w, int h, int r) {
        final int window = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int y = -r; y <= r; y++) {
                int p = src[clamp(y, 0, h - 1) * w + x];
                a += (p >>> 24);
                rr += (p >> 16) & 0xFF;
                gg += (p >> 8) & 0xFF;
                bb += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = ((a / window) << 24) | ((rr / window) << 16)
                        | ((gg / window) << 8) | (bb / window);
                int leave = clamp(y - r, 0, h - 1);
                int enter = clamp(y + r + 1, 0, h - 1);
                int pOut = src[leave * w + x];
                int pIn = src[enter * w + x];
                a += (pIn >>> 24) - (pOut >>> 24);
                rr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                gg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                bb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static int clamp(int v, int low, int high) {
        return v < low ? low : (Math.min(v, high));
    }
}
