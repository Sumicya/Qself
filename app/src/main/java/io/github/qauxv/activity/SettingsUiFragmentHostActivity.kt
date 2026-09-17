/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
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

package io.github.qauxv.activity

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.annotation.CallSuper
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import io.github.qauxv.util.ui.fling.SimpleFlingInterceptLayout
import com.google.android.material.appbar.AppBarLayout
import io.github.qauxv.R
import io.github.qauxv.fragment.BaseSettingFragment
import io.github.qauxv.fragment.SettingsMainFragment
import io.github.qauxv.ui.ModuleThemeManager
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.isInHostProcess
import sumicya.qself.ui.SettingsVisuals
import sumicya.qself.ui.SettingsAppearanceItem
import java.lang.Integer.max

open class SettingsUiFragmentHostActivity : BaseActivity(), SimpleFlingInterceptLayout.SimpleOnFlingHandler {

    private var dynamicColorSignature = 0

    private val FRAGMENT_TAG = "SettingsUiFragmentHostActivity.FRAGMENT_TAG"
    private val FRAGMENT_SAVED_STATE_KEY = "SettingsUiFragmentHostActivity.FRAGMENT_SAVED_STATE_KEY"
    private val FRAGMENT_CLASS_KEY = "SettingsUiFragmentHostActivity.FRAGMENT_CLASS_KEY"
    private val FRAGMENT_ARGS_KEY = "SettingsUiFragmentHostActivity.FRAGMENT_ARGS_KEY"

    private val mFragmentStack = ArrayList<BaseSettingFragment>(4)
    private var mTopVisibleFragment: BaseSettingFragment? = null
    private lateinit var mFlingLayout: SimpleFlingInterceptLayout
    private lateinit var mAppBarLayout: AppBarLayout
    private lateinit var mAppToolBar: androidx.appcompat.widget.Toolbar
    private var mAppBarLayoutHeight: Int = 0
    private val mPendingOnStartActions = ArrayList<Runnable>(4)
    private val mPendingOnResumeActions = ArrayList<Runnable>(4)
    private val mPendingActionsLock = Any()

    @CallSuper
    override fun doOnEarlyCreate(savedInstanceState: Bundle?, isInitializing: Boolean) {
        super.doOnEarlyCreate(savedInstanceState, isInitializing)
        setTheme(ModuleThemeManager.getCurrentThemeColorStyleId())
        theme.applyStyle(R.style.Theme_Qself_Expressive, true)
        sumicya.qself.ui.SettingsDynamicColors.apply(this)
        dynamicColorSignature = sumicya.qself.ui.SettingsDynamicColors.signature(this)
    }

    /**
     * Handle saved instance state ourselves
     */
    override fun shouldRetainActivitySavedInstanceState() = false

    override fun doOnCreate(savedInstanceState: Bundle?): Boolean {
        // we don't want the Fragment to be recreated
        super.doOnCreate(null)
        // The proxy ActivityInfo comes from QQ. Do not inherit a software-only
        // window for native components and local dialogs. This affects this window only.
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        setContentView(R.layout.activity_settings_ui_host)
        // update window background, I don't know why, but it's necessary
        val visualPalette = SettingsVisuals.palette(this, SettingsAppearanceItem.mode)
        window.setBackgroundDrawable(SettingsVisuals.backdrop(visualPalette))
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        )
        mAppBarLayout = findViewById(R.id.topAppBarLayout)
        mAppToolBar = findViewById(R.id.topAppBar)
        mAppBarLayout.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        mAppToolBar.setTitleTextColor(visualPalette.text)
        mAppToolBar.setSubtitleTextColor(visualPalette.secondary)
        setSupportActionBar(mAppToolBar)
        requestTranslucentStatusBar()
        mFlingLayout = findViewById(R.id.fragment_container)
        mFlingLayout.onFlingHandler = this
        mAppBarLayout.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            mAppBarLayoutHeight = bottom - top
            (mFlingLayout.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                topMargin = max(mAppBarLayoutHeight, statusBarLayoutInsect)
                mFlingLayout.layoutParams = this
            }
            for (fragment in mFragmentStack) {
                fragment.notifyLayoutPaddingsChanged()
            }
        }
        onBackPressedDispatcher.addCallback(this, mFragmentPopOnBackCallback)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back unwinds one level: an expanded row panel or an expanded category card,
                // most recently opened first. Only an empty stack leaves the screen.
                if (!sumicya.qself.ui.InlineSettings.back(this@SettingsUiFragmentHostActivity)) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        mAppBarLayout.doOnLayout {
            SyncUtils.postDelayed(0) {
                runOnStart {
                    initFragments(savedInstanceState)
                    savedInstanceState?.getBundle(sumicya.qself.ui.SettingsOptionSheet.TAG)?.let {
                        sumicya.qself.ui.SettingsOptionSheet.restore(this, it)
                    }
                }
            }
        }
        if (isInHostProcess) {
            val isThemeLightMode = resources.getBoolean(R.bool.is_not_night_mode)
            if (isThemeLightMode) {
                // QQ 8.9.78(4548)+
                // We need to tell system we are using a light title bar and we want a dark status bar text
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val insetsController = window.decorView.windowInsetsController
                    val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    insetsController?.setSystemBarsAppearance(flags, flags)
                } else {
                    window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            }
        }
        return true
    }

    private fun initFragments(savedInstanceState: Bundle?) {
        val fragmentBundle: Bundle? = savedInstanceState?.getBundle(FRAGMENT_TAG)
        if (fragmentBundle == null) {
            val intent = intent
            // check if we are requested to show a specific fragment
            val fragmentName: String? = intent.getStringExtra(TARGET_FRAGMENT_KEY)
            val startupFragment: BaseSettingFragment = if (fragmentName != null) {
                val clazz = Class.forName(fragmentName)
                val fragment = clazz.newInstance() as BaseSettingFragment
                val args: Bundle? = intent.getBundleExtra(TARGET_FRAGMENT_ARGS_KEY)
                if (args != null) {
                    fragment.arguments = args
                }
                fragment
            } else {
                // otherwise, show the default fragment
                SettingsMainFragment.newInstance(arrayOf())
            }
            // add the fragment to the stack
            presentFragment(startupFragment)
        } else {
            fragmentBundle.classLoader = this.javaClass.classLoader
            val classNames: ArrayList<String> = fragmentBundle.getStringArrayList(FRAGMENT_CLASS_KEY)!!
            val args: ArrayList<Bundle?> = fragmentBundle.getParcelableArrayList(FRAGMENT_ARGS_KEY)!!
            val states: ArrayList<Parcelable?> = fragmentBundle.getParcelableArrayList(FRAGMENT_SAVED_STATE_KEY)!!
            if (classNames.size != args.size || classNames.size != states.size) {
                throw IllegalStateException("Fragment class names, arguments and states do not match")
            }
            if (classNames.size == 0) {
                throw IllegalStateException("No fragments to restore")
            }
            val op = supportFragmentManager.beginTransaction()
            for (i in classNames.indices) {
                val clazz = Class.forName(classNames[i])
                val fragment = clazz.newInstance() as BaseSettingFragment
                fragment.arguments = args[i].also {
                    it?.classLoader = this.javaClass.classLoader
                }
                fragment.setInitialSavedState(states[i] as Fragment.SavedState?)
                mFragmentStack.add(fragment)
                op.apply {
                    add(R.id.fragment_container, fragment)
                    hide(fragment)
                }
            }
            op.commit()
            // find the top fragment
            val topFragment = mFragmentStack.last()
            mTopVisibleFragment = topFragment
            // show the top fragment
            supportFragmentManager.beginTransaction().apply {
                show(topFragment)
                commit()
            }
            // update back callback enabled state
            mFragmentPopOnBackCallback.isEnabled = mFragmentStack.size > 1
        }
    }

    fun presentFragment(fragment: BaseSettingFragment) {
        if (sumicya.qself.ui.InlineSettings.presentFragment(this, fragment)) return
        rtlAddFragmentToTop(fragment)
    }

    fun finishFragment(fragment: BaseSettingFragment) {
        if (sumicya.qself.ui.InlineSettings.finishFragment(fragment)) return
        rtlRemoveFragment(fragment)
    }

    fun popCurrentFragment() {
        val fragment = mFragmentStack.lastOrNull()
        if (fragment != null) {
            rtlRemoveFragment(fragment)
        } else {
            finish()
        }
    }

    private val mFragmentPopOnBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            popCurrentFragment()
        }
    }

    private fun saveFragmentInstanceState(): Bundle {
        val bundle = Bundle()
        val states = ArrayList<Fragment.SavedState?>(mFragmentStack.size)
        for (fragment in mFragmentStack) {
            val s = supportFragmentManager.saveFragmentInstanceState(fragment)
            states.add(s)
        }
        bundle.putParcelableArrayList(FRAGMENT_SAVED_STATE_KEY, states)
        val args = ArrayList<Bundle?>(mFragmentStack.size)
        for (fragment in mFragmentStack) {
            args.add(fragment.arguments)
        }
        bundle.putParcelableArrayList(FRAGMENT_ARGS_KEY, args)
        val classNames = ArrayList<String>(mFragmentStack.size)
        for (fragment in mFragmentStack) {
            classNames.add(fragment.javaClass.name)
        }
        bundle.putStringArrayList(FRAGMENT_CLASS_KEY, classNames)
        return bundle
    }

    override fun doOnSaveInstanceState(outState: Bundle) {
        super.doOnSaveInstanceState(outState)
        if (mFragmentStack.isNotEmpty()) {
            outState.putBundle(FRAGMENT_TAG, saveFragmentInstanceState())
        }
    }

    private fun updateTitle(fragment: BaseSettingFragment) {
        SyncUtils.postDelayed(1) {
            val text: String? = fragment.title
            val subtitle: String? = fragment.subtitle
            this.title = text
            supportActionBar?.let {
                it.title = text
                it.subtitle = subtitle
            }
            // Home draws its own centered header; hide the duplicated toolbar
            // and let only the status-bar inset remain above the content.
            mAppToolBar.visibility = if (fragment.ownsHeader()) View.GONE else View.VISIBLE
            mFlingLayout.isInterceptEnabled = fragment.isWrapContent
        }
    }

    open fun requestInvalidateActionBar() {
        if (mTopVisibleFragment != null) {
            updateTitle(mTopVisibleFragment!!)
        }
    }

    private fun rtlAddFragmentToTop(fragment: BaseSettingFragment) {
        if (mFragmentStack.isEmpty()) {
            // first fragment
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, fragment)
                .commit()
            mTopVisibleFragment = fragment
            mFragmentStack.add(fragment)
            updateTitle(fragment)
        } else {
            // replace the top fragment
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                .hide(mTopVisibleFragment!!)
                .add(R.id.fragment_container, fragment)
                .commit()
            mTopVisibleFragment = fragment
            mFragmentStack.add(fragment)
            updateTitle(fragment)
        }
        // update back callback enabled state
        mFragmentPopOnBackCallback.isEnabled = mFragmentStack.size > 1
    }

    private fun rtlRemoveFragment(fragment: BaseSettingFragment) {
        // check if we need to show the previous fragment
        if (fragment == mTopVisibleFragment) {
            // this is the visible fragment, so we need to show the previous one
            val transaction = supportFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                .hide(fragment)
            mFragmentStack.remove(fragment)
            mTopVisibleFragment = mFragmentStack.lastOrNull()
            if (mTopVisibleFragment == null) {
                finish()
            } else {
                transaction.show(mTopVisibleFragment!!).commit()
                updateTitle(mTopVisibleFragment!!)
                SyncUtils.postDelayed(300) {
                    // wait 300ms before remove the fragment to allow the animation to play
                    // I don't know why, but it works.
                    supportFragmentManager.beginTransaction().remove(fragment).commit()
                }
            }
        } else {
            // background fragment, just remove it
            supportFragmentManager.beginTransaction().remove(fragment).commit()
        }
        // update back callback enabled state
        mFragmentPopOnBackCallback.isEnabled = mFragmentStack.size > 1
    }

    open val layoutPaddingTop: Int
        get() = 0 // Content is laid out below the toolbar, not scrolled behind a transparent strip.

    open val layoutPaddingBottom: Int
        get() = navigationBarLayoutInsect

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // notifies the fragment that it is attached to the window
        for (fragment in mFragmentStack) {
            fragment.notifyLayoutPaddingsChanged()
        }
    }

    override fun doOnDestroy() {
        sumicya.qself.ui.InlineSettings.unregister(this)
        super.doOnDestroy()
        synchronized(mPendingActionsLock) {
            mPendingOnStartActions.clear()
            mPendingOnResumeActions.clear()
        }
    }

    override fun isWrapContent(): Boolean {
        return mTopVisibleFragment?.isWrapContent ?: true
    }

    override fun onFlingRightToLeft() = Unit

    override fun onFlingLeftToRight() {
        onBackPressedDispatcher.onBackPressed()
    }

    protected fun runOnStart(action: Runnable) {
        SyncUtils.runOnUiThread {
            if (isStarted) {
                action.run()
            } else {
                synchronized(mPendingActionsLock) {
                    mPendingOnStartActions.add(action)
                }
            }
        }
    }

    protected fun runOnResume(action: Runnable) {
        SyncUtils.runOnUiThread {
            if (isResumed2) {
                action.run()
            } else {
                synchronized(mPendingActionsLock) {
                    mPendingOnResumeActions.add(action)
                }
            }
        }
    }

    @CallSuper
    override fun doOnResume() {
        super.doOnResume()
        val colors = sumicya.qself.ui.SettingsDynamicColors.signature(this)
        if (dynamicColorSignature != colors) {
            dynamicColorSignature = colors
            recreate()
            return
        }
        synchronized(mPendingActionsLock) {
            // on start actions
            for (action in mPendingOnStartActions) {
                action.run()
            }
            mPendingOnStartActions.clear()
            // on resume actions
            for (action in mPendingOnResumeActions) {
                action.run()
            }
            mPendingOnResumeActions.clear()
        }
    }

    @CallSuper
    override fun doOnStart() {
        super.doOnStart()
        // on start actions
        synchronized(mPendingActionsLock) {
            for (action in mPendingOnStartActions) {
                action.run()
            }
            mPendingOnStartActions.clear()
        }
    }

    companion object {
        const val TARGET_FRAGMENT_KEY: String = "SettingsUiFragmentHostActivity.TARGET_FRAGMENT_KEY"
        const val TARGET_FRAGMENT_ARGS_KEY: String = "SettingsUiFragmentHostActivity.TARGET_FRAGMENT_ARGS_KEY"

        @JvmStatic
        @JvmOverloads
        fun startFragmentWithContext(
            context: Context,
            fragmentClass: Class<out BaseSettingFragment>,
            args: Bundle? = null
        ) {
            // check if we need to start a new activity
            if (context is SettingsUiFragmentHostActivity) {
                // just add the fragment to the top
                context.presentFragment(fragmentClass.newInstance().apply { arguments = args })
            } else {
                // start a new activity for the fragment
                startActivityForFragment(context, fragmentClass, args)
            }
        }

        @JvmStatic
        @JvmOverloads
        fun startActivityForFragment(
            context: Context,
            fragmentClass: Class<out BaseSettingFragment>,
            args: Bundle? = null
        ) {
            context.startActivity(createStartActivityForFragmentIntent(context, fragmentClass, args))
        }

        @JvmStatic
        @JvmOverloads
        fun createStartActivityForFragmentIntent(
            context: Context,
            fragmentClass: Class<out BaseSettingFragment>,
            args: Bundle? = null
        ): Intent {
            val intent = Intent(context, SettingsUiFragmentHostActivity::class.java)
            intent.putExtra(TARGET_FRAGMENT_KEY, fragmentClass.name)
            intent.putExtra(TARGET_FRAGMENT_ARGS_KEY, args)
            return intent
        }
    }
}
