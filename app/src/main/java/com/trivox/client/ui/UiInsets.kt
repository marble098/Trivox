package com.trivox.client.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

object UiInsets {
    fun apply(activity: Activity) {
        val window = activity.window

        WindowCompat.enableEdgeToEdge(
            window
        )

        val night =
            (
                activity.resources
                    .configuration
                    .uiMode and
                    Configuration
                        .UI_MODE_NIGHT_MASK
            ) ==
                Configuration
                    .UI_MODE_NIGHT_YES

        WindowCompat
            .getInsetsController(
                window,
                window.decorView
            )
            .apply {
                isAppearanceLightStatusBars =
                    !night
                isAppearanceLightNavigationBars =
                    !night
            }

        val content =
            activity.findViewById<View>(
                android.R.id.content
            )

        val initialLeft =
            content.paddingLeft
        val initialTop =
            content.paddingTop
        val initialRight =
            content.paddingRight
        val initialBottom =
            content.paddingBottom

        ViewCompat
            .setOnApplyWindowInsetsListener(
                content
            ) { view, insets ->
                val systemBars =
                    insets.getInsets(
                        WindowInsetsCompat
                            .Type
                            .systemBars()
                    )

                val cutout =
                    insets.getInsets(
                        WindowInsetsCompat
                            .Type
                            .displayCutout()
                    )

                val safe = Insets.of(
                    maxOf(
                        systemBars.left,
                        cutout.left
                    ),
                    maxOf(
                        systemBars.top,
                        cutout.top
                    ),
                    maxOf(
                        systemBars.right,
                        cutout.right
                    ),
                    maxOf(
                        systemBars.bottom,
                        cutout.bottom
                    )
                )

                view.updatePadding(
                    left =
                        initialLeft +
                            safe.left,
                    top =
                        initialTop +
                            safe.top,
                    right =
                        initialRight +
                            safe.right,
                    bottom =
                        initialBottom +
                            safe.bottom
                )

                insets
            }

        ViewCompat.requestApplyInsets(
            content
        )
    }
}
