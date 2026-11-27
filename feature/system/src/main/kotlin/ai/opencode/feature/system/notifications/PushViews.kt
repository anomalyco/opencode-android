package ai.opencode.feature.system.notifications

import ai.opencode.feature.system.R
import android.content.Context
import android.view.View
import android.widget.RemoteViews

/**
 * A permission's expanded layout, drawn inside Android's own notification header and buttons.
 * Colours come from the notification's theme, so they follow dark mode and the wallpaper.
 */
internal object PushViews {
    fun detail(context: Context, detail: PushDetail, title: String): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.push_permission)
        views.setTextViewText(R.id.push_heading, title)
        views.setTextViewText(R.id.push_detail, detail.text)
        if (detail.cut) {
            views.setTextViewText(R.id.push_detail_more, context.getString(R.string.system_push_detail_more))
            views.setViewVisibility(R.id.push_detail_more, View.VISIBLE)
        }
        return views
    }
}
