package com.trivox.client.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.trivox.client.R
import com.trivox.client.data.SettingsRepository
import com.trivox.client.data.ThemeMode

open class ThemedActivity : AppCompatActivity() {
    private var neon = false
    override fun onCreate(savedInstanceState: Bundle?) {
        neon = SettingsRepository(this).load().themeMode == ThemeMode.NEON
        if (neon) setTheme(R.style.Theme_Trivox_Neon)
        super.onCreate(savedInstanceState)
    }
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (neon) styleNeon(window.decorView)
    }
    private fun styleNeon(view: View) {
        val cyan = ContextCompat.getColor(this, R.color.neon_cyan)
        val magenta = ContextCompat.getColor(this, R.color.neon_magenta)
        when (view) {
            is Toolbar -> { view.setTitleTextColor(cyan); view.setSubtitleTextColor(magenta) }
            is CompoundButton -> view.buttonTintList = ColorStateList.valueOf(cyan)
            is EditText -> ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(cyan))
            is Button -> if (view.id != R.id.connectButton) ViewCompat.setBackgroundTintList(view, ColorStateList.valueOf(magenta))
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) styleNeon(view.getChildAt(i))
    }
}
