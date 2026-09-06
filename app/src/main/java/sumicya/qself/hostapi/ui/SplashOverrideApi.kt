/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */
package sumicya.qself.hostapi.ui

import java.io.InputStream

/**
 * RFC-03 §13 batch-7 port: overriding the host splash screen with user
 * supplied images.
 *
 * Three interception sites, all "decide synchronously inside the hook body":
 *  - the framework-level `AssetManager.open(String, int)` (mandatory): host
 *    splash assets resolve to a caller supplied stream, logo assets are
 *    blanked with a transparent PNG;
 *  - `SplashWidget.setSplashDrawable(Drawable, boolean)` (optional class):
 *    the second argument is forced to false;
 *  - a synthetic static `Map (int)` accessor of `ThemeSplashHelper`
 *    (optional class): forced to return null.
 *
 * Failure contract — faithful to the original: a missing mandatory hook
 * point throws; optional classes are simply skipped.
 */
interface SplashOverrideApi {

    /**
     * Installs all three sites for the running host.
     *
     * @param classLoader host class loader
     * @param resolveOverride asked for a replacement stream for a splash
     *        asset under the current mode; null means "do not intercept"
     * @param isEnabled guard evaluated on every invocation (original
     *        semantics: enabled + common hooks licensed)
     * @param onError error sink; the adapter rethrows after reporting
     * @return true once installed (the original initOnce returned true
     *         unconditionally); absence of mandatory points throws
     */
    fun installSplashOverride(
        classLoader: ClassLoader,
        resolveOverride: (assetName: String, isDark: Boolean) -> InputStream?,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean
}
