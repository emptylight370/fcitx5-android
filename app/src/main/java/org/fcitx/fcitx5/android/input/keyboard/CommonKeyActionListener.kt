/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dialog.AddMoreInputMethodsPrompt
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Reset
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Selection
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Stopped
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.QuickPhraseAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.UnicodeAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.VoiceInputHoldEnd
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.voice.VoiceInputAudioCapture
import org.fcitx.fcitx5.android.input.voice.VoiceInputProviderManager
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.switchToNextIME
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler() {

    enum class BackspaceSwipeState {
        Stopped, Selection, Reset
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior
    private val preferredVoiceInput by kbdPrefs.preferredVoiceInput

    private var backspaceSwipeState = Stopped

    private var voiceHoldActive = false

    /**
     * "Switch to voice input": jump to a dedicated (system) voice IME.
     * Always uses the system path — [InputMethodUtil.findVoiceSubtype] prefers
     * the system IME selected in "Preferred voice input" (a plugin id simply
     * doesn't match any IME and falls back to the first voice IME).
     */
    private fun switchToVoiceIme(): Boolean {
        val isPasswordField = service.currentInputEditorInfo?.let {
            CapabilityFlags.fromEditorInfo(it).has(CapabilityFlag.Password)
        } ?: false
        if (isPasswordField) return false
        val (id, subtype) = InputMethodUtil.findVoiceSubtype(preferredVoiceInput) ?: return false
        InputMethodUtil.switchInputMethod(service, id, subtype)
        return true
    }

    /**
     * "Hold to talk": record inside this IME and feed audio to the AIDL plugin
     * provider selected in "Preferred voice input". Requires a plugin provider;
     * the system voice IME cannot support press-and-hold start/stop.
     */
    private fun startPluginVoiceInput(): Boolean {
        val isPasswordField = service.currentInputEditorInfo?.let {
            CapabilityFlags.fromEditorInfo(it).has(CapabilityFlag.Password)
        } ?: false
        if (isPasswordField) return false
        if (!VoiceInputProviderManager.isProviderId(preferredVoiceInput)) {
            Toast.makeText(
                service,
                service.getString(R.string.voice_hold_requires_plugin),
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        // RECORD_AUDIO is a runtime permission; the IME process cannot show
        // the grant dialog itself, so guide the user to the app info page.
        if (!VoiceInputAudioCapture.hasPermission(service)) {
            Toast.makeText(
                service,
                service.getString(R.string.voice_error_no_permission),
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                service.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${service.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        return VoiceInputProviderManager.toggle(
            service = service,
            id = preferredVoiceInput,
            onReady = {
                // Feedback: distinguish "plugin session started" from
                // "switched to a system voice IME"
                Toast.makeText(
                    service,
                    service.getString(R.string.voice_status_listening),
                    Toast.LENGTH_SHORT
                ).show()
                VoiceInputProviderManager.voiceReadyCallback?.invoke()
            },
            onPartialResult = {},
            onError = { msg ->
                // Fallback toast: voiceErrorCallback may be unset when the
                // session is triggered from space long-press (no status bar UI)
                Toast.makeText(service, msg, Toast.LENGTH_SHORT).show()
                VoiceInputProviderManager.voiceErrorCallback?.invoke(msg)
            },
            onLevel = { rms ->
                VoiceInputProviderManager.voiceLevelCallback?.invoke(rms)
            },
            onFinished = {
                VoiceInputProviderManager.voiceFinishedCallback?.invoke()
            },
            onStatus = { status ->
                VoiceInputProviderManager.voiceStatusCallback?.invoke(status)
            },
        )
    }

    // there should be a new fcitx API for this
    private suspend fun FcitxAPI.commitAndReset() {
        if (inputMethodEntryCached.languageCode.startsWith("zh")) {
            // Chinese: select 1st candidate, except prediction candidates
            if (clientPreeditCached.isNotEmpty() || inputPanelCached.preedit.isNotEmpty()) {
                // preedit not empty, maybe there are candidates to select ...
                select(0)
            }
        } else {
            // Other languages: commit preedit as-is
            service.finishComposing()
        }
        reset()
    }

    private fun showInputMethodPicker() {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context))
            }
        }
    }

    val listener by lazy {
        KeyActionListener { action, _ ->
            when (action) {
                is FcitxKeyAction -> service.postFcitxJob {
                    sendKey(action.act, action.states.states, action.code)
                }
                is SymAction -> service.postFcitxJob {
                    sendKey(action.sym, action.states)
                }
                is CommitAction -> service.postFcitxJob {
                    commitAndReset()
                    service.lifecycleScope.launch { service.commitText(action.text) }
                }
                is QuickPhraseAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerQuickPhrase()
                }
                is UnicodeAction -> service.postFcitxJob {
                    commitAndReset()
                    triggerUnicode()
                }
                is LangSwitchAction -> {
                    when (langSwitchKeyBehavior) {
                        LangSwitchBehavior.Enumerate -> {
                            service.postFcitxJob {
                                if (enabledIme().size < 2) {
                                    service.lifecycleScope.launch {
                                        service.showDialog(AddMoreInputMethodsPrompt.build(context))
                                    }
                                } else {
                                    enumerateIme()
                                }
                            }
                        }
                        LangSwitchBehavior.ToggleActivate -> {
                            service.postFcitxJob {
                                toggleIme()
                            }
                        }
                        LangSwitchBehavior.NextInputMethodApp -> {
                            service.switchToNextIME()
                        }
                    }
                }
                is ShowInputMethodPickerAction -> showInputMethodPicker()
                is MoveSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {
                            backspaceSwipeState = if (
                                preeditState.isEmpty &&
                                horizontalCandidate.adapter.total <= 0 // total is -1 on initialization
                            ) {
                                service.applySelectionOffset(action.start, action.end)
                                Selection
                            } else {
                                Reset
                            }
                        }
                        Selection -> {
                            service.applySelectionOffset(action.start, action.end)
                        }
                        Reset -> {}
                    }
                }
                is DeleteSelectionAction -> {
                    when (backspaceSwipeState) {
                        Stopped -> {}
                        Selection -> service.deleteSelection()
                        Reset -> if (action.totalCnt < 0) { // swipe left
                            service.postFcitxJob { reset() }
                        }
                    }
                    backspaceSwipeState = Stopped
                }
                is PickerSwitchAction -> {
                    // update lastSymbolType only when specified explicitly
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    when (spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                        SpaceLongPressBehavior.VoiceInput -> { switchToVoiceIme() }
                        SpaceLongPressBehavior.VoiceInputHold -> {
                            voiceHoldActive = startPluginVoiceInput()
                        }
                    }
                }
                is VoiceInputHoldEnd -> {
                    if (voiceHoldActive) {
                        // Gracefully end the session (drain feed queue + endStream)
                        // so the provider finalizes and commits the last segment;
                        // stop() would abort the session without any result.
                        VoiceInputProviderManager.endSession(service)
                        // Feedback: session released, provider is finalizing
                        Toast.makeText(
                            service,
                            service.getString(R.string.voice_status_recognizing),
                            Toast.LENGTH_SHORT
                        ).show()
                        voiceHoldActive = false
                    }
                }
                else -> {}
            }
        }
    }
}
