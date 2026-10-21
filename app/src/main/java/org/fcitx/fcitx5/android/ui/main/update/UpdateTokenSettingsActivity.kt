/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.update

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor

/**
 * Settings page for the optional GitHub access token used by the update
 * checker, modeled after the hosts settings page.
 */
class UpdateTokenSettingsActivity : AppCompatActivity() {
    private lateinit var toolbar: Toolbar
    private lateinit var tokenInput: EditText
    private lateinit var saveButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_update_token)
        toolbar = findViewById(R.id.toolbar)
        toolbar.backgroundColor = styledColor(android.R.attr.colorPrimary)
        toolbar.elevation = dp(4f)
        tokenInput = findViewById(R.id.tokenInput)
        saveButton = findViewById(R.id.tokenSaveButton)
        statusText = findViewById(R.id.tokenStatusText)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.update_token_settings_title)
        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        tokenInput.setText(UpdatePrefs.loadGithubToken(this).orEmpty())
        renderStatus()

        saveButton.setOnClickListener {
            val token = tokenInput.text?.toString().orEmpty().trim()
            UpdateRepository.saveGithubToken(this, token)
            renderStatus()
            val message = if (token.isEmpty()) {
                getString(R.string.update_token_cleared)
            } else {
                getString(R.string.update_token_saved)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun renderStatus() {
        statusText.text = if (UpdatePrefs.loadGithubToken(this) == null) {
            getString(R.string.update_token_status_empty)
        } else {
            getString(R.string.update_token_status_set)
        }
    }
}
