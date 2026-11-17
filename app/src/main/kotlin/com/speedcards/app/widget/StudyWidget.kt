package com.speedcards.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews
import com.speedcards.app.AppStartup
import com.speedcards.app.MainActivity
import com.speedcards.app.R
import com.speedcards.app.quickadd.QuickAddActivity
import com.speedcards.app.ui.stats.heatLevel
import com.speedcards.app.ui.stats.heatShade
import com.speedcards.data.db.DeckKind
import com.speedcards.data.repo.DeckRepository
import com.speedcards.data.repo.StatsRepository
import com.speedcards.data.repo.StudyRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

data class DueSummary(
    val due: Int,
    val streakDays: Int,
    val studiedToday: Boolean,
    val heatmap: Map<LocalDate, Int>,
    val today: LocalDate,
)

/** Cards due today across all decks (with each deck's daily limits), the streak and recent reviews. */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val startup: AppStartup,
    private val decks: DeckRepository,
    private val study: StudyRepository,
    private val stats: StatsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun summary(): DueSummary {
        startup.ready()
        val roots = decks.all().filter { it.parentId == null && it.kind == DeckKind.NORMAL }
        val due = roots.sumOf { study.countsFor(it.id).total }
        val s = stats.streak()
        val recent = stats.stats(null, heatmapDays = HEATMAP_WEEKS * 7)
        return DueSummary(due, s, recent.reviewsToday > 0, recent.heatmap, recent.today)
    }

    /** Redraws every Speed Cards widget on the home screen. */
    fun update() {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, StudyWidgetProvider::class.java))
        if (ids.isEmpty()) return
        scope.launch {
            val summary = runCatching { summary() }.getOrNull() ?: return@launch
            ids.forEach { manager.updateAppWidget(it, views(context, summary)) }
        }
    }

    companion object {
        private const val HEATMAP_WEEKS = 12
        private const val EMPTY_CELL = 0x40808080

        fun views(context: Context, s: DueSummary) = RemoteViews(context.packageName, R.layout.widget_due).apply {
            setTextViewText(R.id.widget_study, if (s.due == 0) "All caught up" else "Study · ${s.due}")
            setContentDescription(R.id.widget_study, if (s.due == 0) "All caught up" else "Study ${s.due} ${if (s.due == 1) "card" else "cards"} due")
            setImageViewBitmap(R.id.widget_heatmap, heatmap(context, s.heatmap, s.today))
            val open = PendingIntent.getActivity(
                context, 10, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val add = PendingIntent.getActivity(
                context, 11, Intent(context, QuickAddActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            setOnClickPendingIntent(R.id.widget_root, open)
            setOnClickPendingIntent(R.id.widget_study, open)
            setOnClickPendingIntent(R.id.widget_add, add)
        }

        /**
         * The stats screen's heatmap, last [HEATMAP_WEEKS] weeks with Monday on top, in the same
         * per-weekday rainbow, shaded by review count. Empty cells are translucent gray so they read on light and dark backgrounds.
         */
        private fun heatmap(context: Context, counts: Map<LocalDate, Int>, today: LocalDate): Bitmap {
            val density = context.resources.displayMetrics.density
            val cell = 9 * density
            val gap = 2 * density
            val radius = 2 * density
            val weeks = HEATMAP_WEEKS
            val bitmap = Bitmap.createBitmap(
                (weeks * (cell + gap) - gap).toInt(), (7 * (cell + gap) - gap).toInt(), Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bitmap)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = context.getColor(R.color.widget_accent) }
            val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
            val max = counts.filterKeys { !it.isBefore(start) }.values.maxOrNull() ?: 0
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            fun color(d: Int, n: Int): Int {
                val l = heatLevel(n, max)
                return if (l < 0) EMPTY_CELL else heatShade(d, l, dark)
            }
            for (w in 0 until weeks) for (d in 0..6) {
                val day = start.plusWeeks(w.toLong()).plusDays(d.toLong())
                if (day.isAfter(today)) continue
                val rect = RectF(w * (cell + gap), d * (cell + gap), w * (cell + gap) + cell, d * (cell + gap) + cell)
                fill.color = color(d, counts[day] ?: 0)
                canvas.drawRoundRect(rect, radius, radius, fill)
                if (day == today) {
                    rect.inset(ring.strokeWidth / 2, ring.strokeWidth / 2)
                    canvas.drawRoundRect(rect, radius, radius, ring)
                }
            }
            return bitmap
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetUpdater(): WidgetUpdater
}

class StudyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetUpdater().update()
    }
}
