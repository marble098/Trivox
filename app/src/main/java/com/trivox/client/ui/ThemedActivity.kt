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

open class ThemedActivity : AppCompatActivity() {
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (!motionEnabled()) return
        val root = window.decorView
        installPressMotion(root)
        root.alpha = 0.97f
        root.animate()
            .alpha(1f)
            .setDuration(120L)
            .start()
    }

    protected fun recreateWithMotion() {
        if (!motionEnabled()) {
            recreate()
            return
        }
        val root = window.decorView
        root.animate()
            .alpha(0.92f)
            .setDuration(90L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.animate().setListener(null)
                    recreate()
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
