package com.codexrm.mobile

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal object Ui {
    var dark = true
        private set
    var BACKGROUND = 0xff151515.toInt(); private set
    var SIDEBAR = 0xff101010.toInt(); private set
    var SURFACE = 0xff202020.toInt(); private set
    var SURFACE_ALT = 0xff292929.toInt(); private set
    var TEXT = 0xffececec.toInt(); private set
    var MUTED = 0xffa3a3a3.toInt(); private set
    var DIM = 0xff919191.toInt(); private set
    var BORDER = 0xff2b2b2b.toInt(); private set
    var ACCENT = 0xffd7e7dc.toInt(); private set
    var ACCENT_TEXT = 0xff14271b.toInt(); private set
    var GREEN = 0xff75bd92.toInt(); private set
    var DANGER = 0xffed9c96.toInt(); private set

    fun configure(activity: Activity, dark: Boolean) {
        this.dark = dark
        if (dark) {
            BACKGROUND = 0xff151515.toInt(); SIDEBAR = 0xff101010.toInt(); SURFACE = 0xff202020.toInt()
            SURFACE_ALT = 0xff292929.toInt(); TEXT = 0xffececec.toInt(); MUTED = 0xffa3a3a3.toInt()
            DIM = 0xff919191.toInt(); BORDER = 0xff2b2b2b.toInt(); ACCENT = 0xffd7e7dc.toInt()
            ACCENT_TEXT = 0xff14271b.toInt(); GREEN = 0xff75bd92.toInt(); DANGER = 0xffed9c96.toInt()
        } else {
            BACKGROUND = Color.WHITE; SIDEBAR = 0xfff7f7f8.toInt(); SURFACE = 0xfff3f3f4.toInt()
            SURFACE_ALT = 0xffebebee.toInt(); TEXT = 0xff242426.toInt(); MUTED = 0xff64646a.toInt()
            DIM = 0xff696970.toInt(); BORDER = 0xffe5e5e8.toInt(); ACCENT = 0xff244c37.toInt()
            ACCENT_TEXT = Color.WHITE; GREEN = 0xff36815a.toInt(); DANGER = 0xffbd443d.toInt()
        }
        configureSystemBars(activity, dark)
    }

    fun configureSystemBars(activity: Activity, dark: Boolean, background: Int = BACKGROUND) {
        if (Build.VERSION.SDK_INT >= 30) configureSystemBars30(activity, dark, background)
        else configureSystemBarsLegacy(activity, dark, background)
    }

    @TargetApi(30)
    @Suppress("DEPRECATION")
    private fun configureSystemBars30(activity: Activity, dark: Boolean, background: Int) {
        activity.window.setDecorFitsSystemWindows(false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        activity.window.isNavigationBarContrastEnforced = false
        activity.window.decorView.setBackgroundColor(background)
        activity.window.decorView.post {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            val appearance = if (dark) 0 else mask
            activity.window.insetsController?.setSystemBarsAppearance(appearance, mask)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBarsLegacy(activity: Activity, dark: Boolean, background: Int) {
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        activity.window.decorView.setBackgroundColor(background)
        var visibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (!dark) {
            visibility = visibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        activity.window.decorView.systemUiVisibility = visibility
    }

    fun applyWindowInsets(view: View) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        if (Build.VERSION.SDK_INT >= 30) applyWindowInsets30(view, left, top, right, bottom)
        else applyWindowInsetsLegacy(view, left, top, right, bottom)
        view.post { view.requestApplyInsets() }
    }

    @TargetApi(30)
    private fun applyWindowInsets30(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            target.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
    }

    @Suppress("DEPRECATION")
    private fun applyWindowInsetsLegacy(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            val cutout = insets.displayCutout
            val safeLeft = maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0)
            val safeTop = maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0)
            val safeRight = maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0)
            val safeBottom = maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0)
            target.setPadding(left + safeLeft, top + safeTop, right + safeRight, bottom + safeBottom)
            insets
        }
    }

    fun dp(context: Context, value: Number): Int = (value.toFloat() * context.resources.displayMetrics.density + 0.5f).toInt()
    fun column(context: Context, vertical: Boolean = true): LinearLayout = LinearLayout(context).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    }
    fun frame(context: Context): FrameLayout = FrameLayout(context)

    fun text(context: Context, value: CharSequence = "", size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value; textSize = size; setTextColor(color)
            typeface = Typeface.create("sans", if (bold) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }

    fun button(context: Context, value: String, primary: Boolean = false, onClick: (View) -> Unit): Button = Button(context).apply {
        text = value; textSize = 12f; isAllCaps = false
        setTextColor(if (primary) ACCENT_TEXT else TEXT)
        minHeight = dp(context, 48); minimumHeight = dp(context, 48); minWidth = 0; minimumWidth = 0
        setPadding(dp(context, 14), 0, dp(context, 14), 0)
        background = ripple(if (primary) ACCENT else SURFACE_ALT, dp(context, 8).toFloat())
        setOnClickListener(onClick)
    }

    fun iconButton(context: Context, icon: String, description: String, size: Int = 48, onClick: (View) -> Unit): FrameLayout =
        FrameLayout(context).apply {
            contentDescription = description; isClickable = true; isFocusable = true
            background = ripple(Color.TRANSPARENT, dp(context, 8).toFloat())
            addView(IconView(context, icon, MUTED), FrameLayout.LayoutParams(dp(context, 20), dp(context, 20), Gravity.CENTER))
            setOnClickListener(onClick)
            val target = dp(context, maxOf(48, size))
            layoutParams = LinearLayout.LayoutParams(target, target)
        }

    fun input(context: Context, hint: String, multiLine: Boolean = false): EditText = EditText(context).apply {
        this.hint = hint; setHintTextColor(MUTED); setTextColor(TEXT); textSize = 16f
        backgroundTintList = ColorStateList.valueOf(BORDER)
        minHeight = dp(context, 48); minimumHeight = dp(context, 48)
        setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 8))
        if (multiLine) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1; maxLines = 6; gravity = Gravity.TOP or Gravity.START
            background = null
        } else setSingleLine(true)
    }

    fun boxInput(context: Context, hint: String): EditText = input(context, hint).apply {
        background = rounded(SURFACE, dp(context, 8).toFloat(), BORDER)
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
    }

    fun rounded(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke != Color.TRANSPARENT) setStroke(1, stroke)
    }
    fun ripple(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(if (dark) 0x22ffffff else 0x18000000), rounded(color, radius, stroke), null,
    )
    fun dot(context: Context, color: Int, size: Int = 6): View = View(context).apply { background = rounded(color, dp(context, size / 2).toFloat()) }

    fun params(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height, weight)
}
