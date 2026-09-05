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
package io.github.qauxv.feature.experimental

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Parcelable
import android.view.View
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.qauxv.activity.SettingsUiFragmentHostActivity
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.ConfigItems
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.dsl.FunctionEntryRouter.Locations.Auxiliary
import io.github.qauxv.fragment.FakeBatteryConfigFragment
import io.github.qauxv.host.HostInfo
import io.github.qauxv.hook.CommonConfigFunctionHook
import io.github.qauxv.util.Initiator.load
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.dexkit.CDialogUtil
import io.github.qauxv.util.dexkit.DexKitTarget
import io.github.qauxv.util.Initiator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlowKt
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 自定义电量 Hook
 * 
 * 重构说明：
 * - 迁移至新包结构 io.github.qauxv.feature.experimental
 * - 继承 CommonConfigFunctionHook 替代 BaseFunctionHook
 * - 保持原有功能逻辑不变
 */
@FunctionHookEntry
@UiItemAgentEntry
class FakeBatteryHook private constructor() : CommonConfigFunctionHook(
    null, 
    false, 
    arrayOf(CDialogUtil.INSTANCE)
), InvocationHandler, SyncUtils.BroadcastListener {

    companion object {
        val INSTANCE = FakeBatteryHook()

        private const val ACTION_UPDATE_BATTERY_STATUS = "io.github.qauxv.ACTION_UPDATE_BATTERY_STATUS"
        private const val _FLAG_MANUAL_CALL = "flag_manual_call"
    }

    private var mBatteryLevelRecvRef: WeakReference<BroadcastReceiver>? = null
    private var mBatteryStatusRecvRef: WeakReference<BroadcastReceiver>? = null
    private var origRegistrar: Any? = null
    private var origStatus: Any? = null
    private var lastFakeLevel = -1
    private var lastFakeStatus = -1
    private var mBatteryStateFlow: MutableStateFlow<String>? = null

    private fun doPostReceiveEvent(recv: BroadcastReceiver?, ctx: Context, intent: Intent) {
        SyncUtils.post {
            SyncUtils.setTlsFlag(_FLAG_MANUAL_CALL)
            try {
                recv?.onReceive(ctx, intent)
            } catch (e: Throwable) {
                INSTANCE.traceError(e)
            }
            SyncUtils.clearTlsFlag(_FLAG_MANUAL_CALL)
        }
    }

    private fun batteryPropertySetLong(prop: Parcelable?, value: Long) {
        if (prop == null) return
        try {
            val field: Field = prop.javaClass.getDeclaredField("mValueLong")
            field.isAccessible = true
            field.set(prop, value)
        } catch (e: Throwable) {
            INSTANCE.traceError(e)
        }
    }

    @SuppressLint("SoonBlockedPrivateApi")
    override fun initOnce(): Boolean {
        updateSettingsUiState()
        
        // for :MSF
        var mGetSendBatteryStatus: Method? = null
        for (m in load("com/tencent/mobileqq/msf/sdk/MsfSdkUtils")?.methods ?: emptyArray()) {
            if (m.name == "getSendBatteryStatus" && m.returnType == Int::class.javaPrimitiveType) {
                mGetSendBatteryStatus = m
                break
            }
        }
        
        XposedBridge.hookMethod(mGetSendBatteryStatus, object : XC_MethodHook(49) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isEnabled) return
                param.result = fakeBatteryStatus
            }
        })
        
        val cBatteryBroadcastReceiver = load("com.tencent.mobileqq.app.BatteryBroadcastReceiver")
        if (cBatteryBroadcastReceiver != null) {
            XposedHelpers.findAndHookMethod(
                cBatteryBroadcastReceiver,
                "onReceive",
                Context::class.java,
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (SyncUtils.hasTlsFlag(_FLAG_MANUAL_CALL)) return
                        
                        val intent = param.args[1] as Intent
                        val action = intent.action
                        
                        when (action) {
                            "android.intent.action.ACTION_POWER_CONNECTED",
                            "android.intent.action.ACTION_POWER_DISCONNECTED" -> {
                                if (mBatteryStatusRecvRef == null || mBatteryStatusRecvRef!!.get() != param.thisObject) {
                                    mBatteryStatusRecvRef = WeakReference(param.thisObject as BroadcastReceiver)
                                }
                            }
                            "android.intent.action.BATTERY_CHANGED" -> {
                                if (mBatteryLevelRecvRef == null || mBatteryLevelRecvRef!!.get() != param.thisObject) {
                                    mBatteryLevelRecvRef = WeakReference(param.thisObject as BroadcastReceiver)
                                }
                            }
                        }
                        
                        if (!isEnabled) return
                        
                        when (action) {
                            "android.intent.action.ACTION_POWER_CONNECTED",
                            "android.intent.action.ACTION_POWER_DISCONNECTED" -> {
                                if (isFakeBatteryCharging) {
                                    lastFakeStatus = BatteryManager.BATTERY_STATUS_CHARGING
                                    intent.action = "android.intent.action.ACTION_POWER_CONNECTED"
                                } else {
                                    lastFakeStatus = BatteryManager.BATTERY_STATUS_DISCHARGING
                                    intent.action = "android.intent.action.ACTION_POWER_DISCONNECTED"
                                }
                            }
                            "android.intent.action.BATTERY_CHANGED" -> {
                                intent.putExtra(BatteryManager.EXTRA_LEVEL, lastFakeLevel = fakeBatteryCapacity)
                                intent.putExtra(BatteryManager.EXTRA_SCALE, 100)
                                if (isFakeBatteryCharging) {
                                    intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
                                    intent.putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
                                } else {
                                    intent.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                                    intent.putExtra(BatteryManager.EXTRA_PLUGGED, 0)
                                }
                            }
                        }
                    }
                }
            )
        }
        
        // @MainProcess
        // 接下去是 UI stuff, 给自己看的
        // 本来还想用反射魔改 Binder/ActivityThread$ApplicationThread 实现 Xposed-less 拦截广播 onReceive 的，太肝了，就不搞了
        val batmgr = HostInfo.application.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (batmgr == null) {
            Log.e("Wtf, init FakeBatteryHook but BatteryManager is null!")
            return false
        }
        
        if (Build.VERSION.SDK_INT < 23) {
            // make a call to init mBatteryStats, so we don't care about the result
            batmgr.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }
        
        val fBatteryPropertiesRegistrar: Field = BatteryManager::class.java.getDeclaredField("mBatteryPropertiesRegistrar")
        fBatteryPropertiesRegistrar.isAccessible = true
        origRegistrar = fBatteryPropertiesRegistrar.get(batmgr)
        val cIBatteryPropertiesRegistrar = fBatteryPropertiesRegistrar.type
        
        if (origRegistrar == null) {
            Log.e("Error! mBatteryPropertiesRegistrar(original) got null")
            return false
        }
        
        var cIBatteryStatus: Class<*>? = null
        var fBatteryStatus: Field? = null
        try {
            fBatteryStatus = BatteryManager::class.java.getDeclaredField("mBatteryStats")
            fBatteryStatus.isAccessible = true
            origStatus = fBatteryStatus.get(batmgr)
            cIBatteryStatus = fBatteryStatus.type
            if (origStatus == null) {
                Log.e("FakeBatteryHook/W Field mBatteryStats found, but instance got null")
            }
        } catch (e: NoSuchFieldException) {
            if (Build.VERSION.SDK_INT >= 23) {
                traceError(e)
                Log.e("FakeBatteryHook/W Field mBatteryStats not found, but SDK_INT is ${Build.VERSION.SDK_INT}")
            }
        }
        
        val proxy: Any
        if (origStatus != null && cIBatteryStatus != null) {
            proxy = Proxy.newProxyInstance(
                Initiator.pluginClassLoader,
                arrayOf(cIBatteryPropertiesRegistrar, cIBatteryStatus),
                this
            )
            fBatteryPropertiesRegistrar.set(batmgr, proxy)
            fBatteryStatus?.set(batmgr, proxy)
        } else {
            proxy = Proxy.newProxyInstance(
                Initiator.pluginClassLoader,
                arrayOf(cIBatteryPropertiesRegistrar),
                this
            )
            fBatteryPropertiesRegistrar.set(batmgr, proxy)
        }
        
        SyncUtils.addBroadcastListener(this)
        return true
    }

    private fun scheduleReceiveBatteryLevel() {
        val recv: BroadcastReceiver? = mBatteryLevelRecvRef?.get() ?: mBatteryStatusRecvRef?.get() ?: return
        
        val intent = Intent("android.intent.action.BATTERY_CHANGED").apply {
            putExtra(BatteryManager.EXTRA_LEVEL, lastFakeLevel = fakeBatteryCapacity)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_PRESENT, true)
            putExtra(BatteryManager.EXTRA_TECHNOLOGY, "Li-ion")
            if (isFakeBatteryCharging) {
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
                putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
            } else {
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                putExtra(BatteryManager.EXTRA_PLUGGED, 0)
            }
        }
        
        doPostReceiveEvent(recv, HostInfo.application, intent)
    }

    private fun scheduleReceiveBatteryStatus() {
        val recv: BroadcastReceiver? = mBatteryStatusRecvRef?.get() ?: mBatteryLevelRecvRef?.get() ?: return
        
        val act = if (isFakeBatteryCharging) {
            "android.intent.action.ACTION_POWER_CONNECTED"
        } else {
            "android.intent.action.ACTION_POWER_DISCONNECTED"
        }
        
        val intent = Intent(act).apply {
            putExtra(BatteryManager.EXTRA_LEVEL, fakeBatteryCapacity)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_PRESENT, true)
            putExtra(BatteryManager.EXTRA_TECHNOLOGY, "Li-ion")
            if (isFakeBatteryCharging) {
                putExtra(BatteryManager.EXTRA_STATUS, lastFakeStatus = BatteryManager.BATTERY_STATUS_CHARGING)
                putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
            } else {
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
                putExtra(BatteryManager.EXTRA_PLUGGED, 0)
            }
        }
        
        doPostReceiveEvent(recv, HostInfo.application, intent)
    }

    override fun onReceive(context: Context, intent: Intent): Boolean {
        if (ACTION_UPDATE_BATTERY_STATUS == intent.action) {
            if (isInitialized && isEnabled) {
                if (lastFakeLevel != fakeBatteryCapacity) {
                    scheduleReceiveBatteryLevel()
                }
                if (lastFakeStatus == -1 || lastFakeStatus == BatteryManager.BATTERY_STATUS_DISCHARGING == isFakeBatteryCharging) {
                    scheduleReceiveBatteryStatus()
                }
            }
            return true
        }
        return false
    }

    @Throws(Throwable::class)
    override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
        return try {
            if (isEnabled) {
                if (method.name == "getProperty" && args?.size == 2) {
                    val id = args[0] as Int
                    val prop = args[1] as Parcelable
                    when (id) {
                        BatteryManager.BATTERY_PROPERTY_STATUS -> {
                            if (isFakeBatteryCharging) {
                                batteryPropertySetLong(prop, BatteryManager.BATTERY_STATUS_CHARGING)
                            } else {
                                batteryPropertySetLong(prop, BatteryManager.BATTERY_STATUS_DISCHARGING)
                            }
                            return 0
                        }
                        BatteryManager.BATTERY_PROPERTY_CAPACITY -> {
                            batteryPropertySetLong(prop, fakeBatteryCapacity.toLong())
                            return 0
                        }
                    }
                } else if (method.name == "isCharging" && (args == null || args.isEmpty())) {
                    return isFakeBatteryCharging
                }
            }
            
            try {
                val className = method.declaringClass.name
                when {
                    className.endsWith("IBatteryPropertiesRegistrar") -> method.invoke(origRegistrar, *args.orEmpty())
                    className.endsWith("IBatteryStats") -> method.invoke(origStatus, *args.orEmpty())
                    className.endsWith("Object") -> when (method.name) {
                        "toString" -> "a.a.a.a\$Stub\$Proxy@${Integer.toHexString(hashCode())}"
                        "equals" -> args?.get(0) == proxy
                        "hashCode" -> hashCode()
                        else -> null
                    }
                    else -> {
                        Log.e("Panic, unexpected method $method")
                        null
                    }
                }
            } catch (ite: InvocationTargetException) {
                traceError(ite)
                throw ite.cause!!
            }
        } catch (e: Exception) {
            traceError(e)
            null
        }
    }

    fun setFakeSendBatteryStatus(value: Int) {
        val cfg = ConfigManager.getDefaultConfig()
        cfg.putInt(ConfigItems.qn_fake_bat_expr, value)
        cfg.save()
        val intent = Intent(ACTION_UPDATE_BATTERY_STATUS)
        SyncUtils.sendGenericBroadcast(intent)
        updateSettingsUiState()
    }

    val fakeBatteryStatus: Int
        get() {
            val value = ConfigManager.getDefaultConfig().getIntOrDefault(ConfigItems.qn_fake_bat_expr, -1)
            return maxOf(value, 0) // safe value
        }

    val isFakeBatteryCharging: Boolean
        get() = (fakeBatteryStatus and 128) > 0

    val fakeBatteryCapacity: Int
        get() = fakeBatteryStatus and 127

    @NonNull
    override fun getUiItemLocation(): Array<String> = Auxiliary.EXPERIMENTAL_CATEGORY

    override fun getTargetProcesses(): Int = SyncUtils.PROC_MAIN or SyncUtils.PROC_MSF

    override val uiItemAgent: IUiItemAgent by lazy { createUiItemAgent() }

    private fun createUiItemAgent(): IUiItemAgent {
        return object : IUiItemAgent {
            @NonNull
            override fun getTitleProvider() = { _: IUiItemAgent -> "自定义电量" }

            @Nullable
            override fun getSummaryProvider() = null

            @NonNull
            override fun getValueState(): MutableStateFlow<String> {
                if (mBatteryStateFlow == null) {
                    updateSettingsUiState()
                }
                return mBatteryStateFlow!!
            }

            @Nullable
            override fun getValidator() = null

            @Nullable
            override fun getSwitchProvider() = null

            override fun getOnClickListener() = 
                { _: IUiItemAgent, activity: Activity, _: View ->
                    onItemClicked(activity)
                }

            @Nullable
            override fun getExtraSearchKeywordProvider() = null
        }
    }

    private fun generateValueString(): String =
        if (isEnabled) "${fakeBatteryCapacity}%" + if (isFakeBatteryCharging) "+" else ""
        else "禁用"

    private fun updateSettingsUiState() {
        val value = generateValueString()
        if (mBatteryStateFlow == null) {
            mBatteryStateFlow = StateFlowKt.MutableStateFlow(value)
        } else {
            mBatteryStateFlow!!.value = value
        }
    }

    companion object {
        @JvmStatic
        fun onItemClicked(activity: Activity) {
            SettingsUiFragmentHostActivity.startFragmentWithContext(activity, FakeBatteryConfigFragment::class.java, null)
        }
    }
}
