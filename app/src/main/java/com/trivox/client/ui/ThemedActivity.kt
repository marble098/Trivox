package com.trivox.client.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/** TRIVOX_V7_IMPORT_WIREGUARD
 * Shared lightweight motion. Theme changes use AppCompat's single recreation;
 * an explicit second recreate would cause flicker and unnecessary work.
 */
open class ThemedActivity : AppCompatActivity() {
    private var themeTransitionRunning = false

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!motionEnabled()) return
        val root = findViewById<View>(android.R.id.content) ?: window.decorView
        installPressMotion(root)
        root.alpha = 0.94f
        root.animate()
            .withLayer()
            .alpha(1f)
            .setDuration(145L)
            .setListener(null)
            .start()
    }

    protected fun applyNightModeWithMotion(mode: Int) {
        if (themeTransitionRunning) return
        if (!motionEnabled()) {
            AppCompatDelegate.setDefaultNightMode(mode)
            return
        }

        themeTransitionRunning = true
        val root = findViewById<View>(android.R.id.content) ?: window.decorView
        root.animate()
            .cancel()
        root.animate()
            .withLayer()
            .alpha(0.94f)
            .setDuration(90L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.animate().setListener(null)
                    AppCompatDelegate.setDefaultNightMode(mode)
                }

                override fun onAnimationCancel(animation: Animator) {
                    root.alpha = 1f
                    themeTransitionRunning = false
                }
            })
            .start()
    }

    private fun installPressMotion(view: View) {
        if (
            view.isClickable &&
            view.stateListAnimator == null &&
            view !is ViewGroup
        ) {
            view.stateListAnimator = StateListAnimator().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 0.975f),
                            ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 0.975f)
                        )
                        duration = 65L
                    }
                )
                addState(
                    intArrayOf(),
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(view, View.SCALE_X, 1f),
                            ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f)
                        )
                        duration = 95L
                    }
                )
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                installPressMotion(view.getChildAt(index))
            }
        }
    }

    private fun motionEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ValueAnimator.areAnimatorsEnabled()
}
