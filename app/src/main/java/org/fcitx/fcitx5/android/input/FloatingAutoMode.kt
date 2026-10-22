/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * Auto floating keyboard rule: decides the initial floating state when the
 * input view is (re)created for a given orientation. Manual toggling via the
 * toolbar button always stays available afterwards.
 */
enum class FloatingAutoMode(override val stringRes: Int) : ManagedPreferenceEnum {
    Off(R.string.floating_auto_mode_off),
    Portrait(R.string.floating_auto_mode_portrait),
    Landscape(R.string.floating_auto_mode_landscape),
    Always(R.string.floating_auto_mode_always);
}
