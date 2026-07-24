package com.firebolt9907.tangentbrowser.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.firebolt9907.tangentbrowser.MainActivity
import com.firebolt9907.tangentbrowser.R
import com.firebolt9907.tangentbrowser.state.AppShortcutSignals

/**
 * Home-screen search widget: a pill that opens a new tab ready to type, plus a mic that starts voice
 * search. Both taps launch [MainActivity] with an [AppShortcutSignals] action, reusing the exact
 * cold/warm-start signal path the launcher app-shortcuts already use — no widget-specific browser
 * logic. Static content, so [onUpdate] just (re)binds the two click intents.
 */
class SearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_search).apply {
                setOnClickPendingIntent(
                    R.id.widget_search_area,
                    activityIntent(context, AppShortcutSignals.ACTION_NEW_TAB, requestCode = 1),
                )
                setOnClickPendingIntent(
                    R.id.widget_mic,
                    activityIntent(context, AppShortcutSignals.ACTION_VOICE_SEARCH, requestCode = 2),
                )
            }
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    private fun activityIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
