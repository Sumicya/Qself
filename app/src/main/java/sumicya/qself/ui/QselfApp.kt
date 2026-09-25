// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class QselfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Framework.service.value = service
            }

            override fun onServiceDied(service: XposedService) {
                Framework.service.value = null
            }
        })
    }
}

object Framework {
    val service = mutableStateOf<XposedService?>(null)
}
