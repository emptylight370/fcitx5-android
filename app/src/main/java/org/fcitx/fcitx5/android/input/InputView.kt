/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.DisplayMetrics
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.RealSize
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.fcitx.fcitx5.android.utils.windowManager
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import timber.log.Timber

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val advancedPrefs = AppPrefs.getInstance().advanced
    private val keyboardHeightPercentBase = advancedPrefs.keyboardHeightPercentBase

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        keyboardHeightPercentBase,
        keyboardPrefs.floatingKeyboardWidthPercentPortrait,
        keyboardPrefs.floatingKeyboardWidthPercentLandscape,
    )

    // ---- Floating keyboard state (ported from fx, simplified) ----

    private val internalPrefs = AppPrefs.getInstance().internal

    var isFloating = false
        private set

    private val isLandscapeOrientation: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private var lastFloatingTouchX = 0f
    private var lastFloatingTouchY = 0f

    /**
     * Small round handle shown above the floating keyboard; dragging it moves
     * the keyboard around. Replaces fx's edge resize handles (size is now
     * configured in settings).
     */
    private val floatingMoveHandle: View by lazy {
        view(::View) {
            visibility = GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.accentKeyBackgroundColor)
            }
            setOnTouchListener { v, event ->
                if (!isFloating) return@setOnTouchListener false
                v.parent?.requestDisallowInterceptTouchEvent(true)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastFloatingTouchX = event.rawX
                        lastFloatingTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        keyboardView.translationX += event.rawX - lastFloatingTouchX
                        keyboardView.translationY += event.rawY - lastFloatingTouchY
                        clampFloatingPosition()
                        preedit.ui.root.translationX = keyboardView.translationX
                        preedit.ui.root.translationY = keyboardView.translationY
                        updateFloatingMoveHandle()
                        lastFloatingTouchX = event.rawX
                        lastFloatingTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        saveFloatingPosition(
                            keyboardView.translationX.toInt(),
                            keyboardView.translationY.toInt()
                        )
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private val keyboardHeightPx: Int
        get() {
            val baseType = keyboardHeightPercentBase.getValue()
            val base = when (baseType) {
                DisplayMetrics -> resources.displayMetrics.heightPixels
                RealSize -> Point().also {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.display
                    } else {
                        context.windowManager.defaultDisplay
                    }.getRealSize(it)
                }.y
            }
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            Timber.d("keyboardHeightPx get(): baseType=${baseType}, base=${base}, percent=${percent}")
            return base * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
    }

    val keyboardView: View

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        updateKeyboardSize()

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(keyboardView)
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })
        add(floatingMoveHandle, lParams(dp(24), dp(24)) {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        })

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        advancedPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)

        // Restore floating state: the auto-floating rule decides the initial
        // state per orientation; the last manual state only applies when the
        // rule is Off
        val shouldFloat = when (keyboardPrefs.floatingAutoKeyboardMode.getValue()) {
            FloatingAutoMode.Always -> true
            FloatingAutoMode.Portrait -> !isLandscapeOrientation
            FloatingAutoMode.Landscape -> isLandscapeOrientation
            FloatingAutoMode.Off -> internalPrefs.floatingModeEnabled.getValue()
        }
        if (shouldFloat) {
            isFloating = true
            updateFloatingState()
            updateKeyboardSize()
        }
    }

    private fun updateKeyboardSize() {
        // Only the key area scales; the toolbar keeps its fixed height so its
        // buttons stay usable
        val heightPx: Int
        if (isFloating) {
            // Scale relative to the PORTRAIT docked keyboard's aspect ratio,
            // so landscape floating keyboards keep a usable key height
            val dm = resources.displayMetrics
            val portraitScreenWidth = minOf(dm.widthPixels, dm.heightPixels)
            val portraitScreenHeight = maxOf(dm.widthPixels, dm.heightPixels)
            val base = when (keyboardHeightPercentBase.getValue()) {
                DisplayMetrics -> portraitScreenHeight
                RealSize -> Point().also {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.display
                    } else {
                        context.windowManager.defaultDisplay
                    }.getRealSize(it)
                }.let { maxOf(it.x, it.y) }
            }
            val portraitKeyHeight = base * keyboardHeightPercent.getValue() / 100
            heightPx = (resolveFloatingWidth().toFloat() * portraitKeyHeight / portraitScreenWidth)
                .toInt()
        } else {
            heightPx = keyboardHeightPx
        }
        windowManager.view.updateLayoutParams {
            height = heightPx
        }
        kawaiiBar.view.updateLayoutParams {
            height = dp(KawaiiBarComponent.HEIGHT)
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        if (isFloating) {
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            bottomPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    // ---- Floating keyboard (ported from fx, simplified) ----

    private val minFloatingWidthPx: Int
        get() = dp(180).coerceAtMost(resources.displayMetrics.widthPixels)

    private fun floatingWidthPercentPref() =
        if (isLandscapeOrientation) keyboardPrefs.floatingKeyboardWidthPercentLandscape
        else keyboardPrefs.floatingKeyboardWidthPercentPortrait

    private fun floatingXRatioPref() =
        if (isLandscapeOrientation) internalPrefs.floatingKeyboardXLandscapeRatio
        else internalPrefs.floatingKeyboardXPortraitRatio

    private fun floatingYRatioPref() =
        if (isLandscapeOrientation) internalPrefs.floatingKeyboardYLandscapeRatio
        else internalPrefs.floatingKeyboardYPortraitRatio

    private fun resolveFloatingWidth(): Int =
        (resources.displayMetrics.widthPixels * floatingWidthPercentPref().getValue() / 100f)
            .toInt().coerceIn(minFloatingWidthPx, resources.displayMetrics.widthPixels)

    private fun getStoredFloatingPosition(): Pair<Int, Int> {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val xRatio = floatingXRatioPref().getValue()
        val yRatio = floatingYRatioPref().getValue()
        return if (xRatio >= 0f && yRatio >= 0f && w > 0 && h > 0) {
            (w * xRatio).toInt() to (h * yRatio).toInt()
        } else -1 to -1
    }

    private fun saveFloatingPosition(x: Int, y: Int) {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        if (w <= 0 || h <= 0) return
        floatingXRatioPref().setValue(x.toFloat() / w)
        floatingYRatioPref().setValue(y.toFloat() / h)
    }

    private fun applyDockedKeyboardState() {
        keyboardView.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = matchParent
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = unset
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        }
        keyboardView.translationX = 0f
        keyboardView.translationY = 0f
        preedit.ui.root.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = matchParent
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToTop = keyboardView.id
            topToBottom = unset
        }
        preedit.ui.root.translationX = 0f
        preedit.ui.root.translationY = 0f
        keyboardView.invalidateOutline()
        requestLayout()
    }

    private fun updateFloatingState() {
        val params = keyboardView.layoutParams as ConstraintLayout.LayoutParams
        if (isFloating) {
            params.width = resolveFloatingWidth()
            params.bottomToBottom = params.unset
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = params.unset
            val (storedX, storedY) = getStoredFloatingPosition()
            if (storedX != -1 && storedY != -1) {
                keyboardView.translationX = storedX.toFloat()
                keyboardView.translationY = storedY.toFloat()
            } else if (keyboardView.translationX == 0f && keyboardView.translationY == 0f) {
                keyboardView.translationX = resources.displayMetrics.widthPixels * 0.1f
                keyboardView.translationY = resources.displayMetrics.heightPixels * 0.6f
            }
            preedit.ui.root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                width = 0 // match constraints
                startToStart = keyboardView.id
                endToEnd = keyboardView.id
                bottomToTop = keyboardView.id
                topToBottom = unset
            }
            preedit.ui.root.translationX = keyboardView.translationX
            preedit.ui.root.translationY = keyboardView.translationY
        } else {
            applyDockedKeyboardState()
        }
        updateFloatingMoveHandle()
        keyboardView.layoutParams = params
        keyboardView.invalidateOutline()
        requestLayout()
        keyboardView.post {
            clampFloatingPosition()
            updateFloatingMoveHandle()
        }
    }

    internal fun toggleFloatingMode() {
        popup.dismissAll()
        if (isFloating) {
            saveFloatingPosition(
                keyboardView.translationX.toInt(),
                keyboardView.translationY.toInt()
            )
        }
        isFloating = !isFloating
        internalPrefs.floatingModeEnabled.setValue(isFloating)
        updateFloatingState()
        updateKeyboardSize()
        requestLayout()
        service.window.window?.decorView?.requestLayout()
    }

    /**
     * Touchable region of the floating keyboard (keyboard + preedit + move
     * handle), used by [FcitxInputMethodService.onComputeInsets] with
     * TOUCHABLE_INSETS_REGION so touches outside the floating keyboard fall
     * through to the app underneath.
     */
    fun getFloatingKeyboardRegion(outRegion: Region) {
        if (!isFloating) return
        val rect = Rect()
        keyboardView.getHitRect(rect)
        if (preedit.ui.root.visibility == View.VISIBLE) {
            val preeditRect = Rect()
            preedit.ui.root.getHitRect(preeditRect)
            rect.union(preeditRect)
        }
        if (floatingMoveHandle.visibility == View.VISIBLE) {
            val handleRect = Rect()
            floatingMoveHandle.getHitRect(handleRect)
            rect.union(handleRect)
        }
        outRegion.set(rect)
    }

    private fun clampFloatingPosition() {
        if (!isFloating) return
        val containerWidth = if (width > 0) width else resources.displayMetrics.widthPixels
        val containerHeight = if (height > 0) height else resources.displayMetrics.heightPixels
        val keyboardWidth = if (keyboardView.width > 0) keyboardView.width else resolveFloatingWidth()
        val keyboardHeight = if (keyboardView.height > 0) keyboardView.height else keyboardHeightPx
        val maxX = (containerWidth - keyboardWidth).coerceAtLeast(0)
        val maxY = (containerHeight - keyboardHeight).coerceAtLeast(0)
        keyboardView.translationX = keyboardView.translationX.coerceIn(0f, maxX.toFloat())
        keyboardView.translationY = keyboardView.translationY.coerceIn(0f, maxY.toFloat())
    }

    private fun updateFloatingMoveHandle() {
        if (!isFloating) {
            floatingMoveHandle.visibility = GONE
            return
        }
        val size = dp(24)
        val keyboardWidth = if (keyboardView.width > 0) keyboardView.width else resolveFloatingWidth()
        floatingMoveHandle.visibility = VISIBLE
        floatingMoveHandle.translationX =
            keyboardView.translationX + keyboardWidth / 2f - size / 2f
        floatingMoveHandle.translationY = keyboardView.translationY - size - dp(8)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        advancedPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
