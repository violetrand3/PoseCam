package com.posecam

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.posecam.core.cloud.AuthValidation
import com.posecam.core.cloud.UploadStatsCache
import com.posecam.core.cloud.UploadStatsText
import com.posecam.core.cloud.UploadedRecording
import com.posecam.core.sync.CloudSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One day of "My uploads": every recording uploaded that day with its campaign and status ("Uploaded", and under it the
 * marketplace's view -- "In review", "Approved", ... -- when the backend says). A day can hold a hundred, so the campaign
 * chips narrow the list, and the summary line over it always describes the whole day.
 */
class DayUploadsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sync: CloudSync
    private lateinit var cache: UploadStatsCache

    private lateinit var summary: TextView
    private lateinit var chips: LinearLayout
    private lateinit var note: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView

    private lateinit var date: String
    private var recordings: List<UploadedRecording> = emptyList()
    private var filter: Filter = Filter.All
    private var campaignName: (String) -> String? = { null }

    private sealed interface Filter {
        data object All : Filter
        data class Campaign(val id: String) : Filter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_day_uploads)
        showBackArrow()
        keepBelowSystemBars()
        sync = CloudSync.get(this)
        cache = UploadStatsCache(this)
        summary = findViewById(R.id.daySummary)
        chips = findViewById(R.id.dayChips)
        note = findViewById(R.id.dayNote)
        list = findViewById(R.id.dayList)
        empty = findViewById(R.id.dayEmpty)
        date = intent.getStringExtra(EXTRA_DATE) ?: run { finish(); return }
        title = UploadStatsText.dayLabel(date, UploadStatsText.today())
        val account = sync.recordingAccountId()
        campaignName = { id -> sync.campaigns.title(account, id) }
        findViewById<Button>(R.id.dayWeb).setOnClickListener { openWebLink(sync.meta.current().webAppUrl) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = handleBackArrow(item) || super.onOptionsItemSelected(item)

    override fun onStart() {
        super.onStart()
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load() {
        val session = sync.auth.current().session ?: run { finish(); return }
        cache.loadDay(session.email, date)?.let { show(it, "Saved copy") } ?: showNote("Loading\u2026")
        scope.launch {
            try {
                val offset = UploadStatsText.tzOffsetMinutes()
                val fresh = withContext(Dispatchers.IO) { sync.accounts.uploadsOn(date, offset) }
                cache.saveDay(session.email, date, fresh)
                show(fresh.sortedByDescending { it.sessionId }, null)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (!sync.auth.isSignedIn) {
                    finish()
                    return@launch
                }
                if (recordings.isEmpty()) showNote(AuthValidation.messageFor(failure)) else showNote("Offline \u00b7 saved copy")
            }
        }
    }

    private fun showNote(text: String?) {
        note.text = text
        note.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun show(all: List<UploadedRecording>, footnote: String?) {
        recordings = all
        showNote(footnote)
        summary.text = if (all.isEmpty()) "Nothing uploaded this day" else UploadStatsText.daySummary(all)
        if (filter is Filter.Campaign && all.none { it.campaignId == (filter as Filter.Campaign).id }) filter = Filter.All
        renderChips()
        renderList()
    }

    private fun renderChips() {
        chips.removeAllViews()
        (chips.parent as View).visibility = if (recordings.size < 2) View.GONE else View.VISIBLE
        chip("All ${recordings.size}", Filter.All)
        val titles = recordings.associate { it.campaignId to it.campaignTitle }
        recordings.groupingBy { it.campaignId }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .forEach { (id, count) -> chip("${campaignName(id) ?: titles[id]?.ifBlank { null } ?: id} $count", Filter.Campaign(id)) }
    }

    private fun chip(label: String, target: Filter) {
        val density = resources.displayMetrics.density
        val selected = filter == target
        chips.addView(
            TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(if (selected) Color.WHITE else 0xFF37474F.toInt())
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 100f
                    setColor(if (selected) 0xFF37474F.toInt() else 0xFFECEFF1.toInt())
                }
                setOnClickListener {
                    filter = target
                    renderChips()
                    renderList()
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * density).toInt() },
        )
    }

    private fun renderList() {
        val shown = when (val f = filter) {
            Filter.All -> recordings
            is Filter.Campaign -> recordings.filter { it.campaignId == f.id }
        }
        list.adapter = RecordingAdapter(shown)
        list.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class RecordingAdapter(private val items: List<UploadedRecording>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val density = resources.displayMetrics.density
            val root = (convertView as? LinearLayout) ?: LinearLayout(this@DayUploadsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                addView(TextView(context).apply { textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "name" })
                addView(TextView(context).apply { textSize = 12f; alpha = 0.65f; tag = "detail" })
                addView(TextView(context).apply { textSize = 12f; setTextColor(0xFF00796B.toInt()); tag = "review" })
            }
            val item = items[position]
            root.findViewWithTag<TextView>("name").text = UploadStatsText.recordingName(item.sessionId)
            root.findViewWithTag<TextView>("detail").text = UploadStatsText.recordingDetail(item, campaignName)
            root.findViewWithTag<TextView>("review").apply {
                val line = UploadStatsText.reviewLine(item)
                text = line
                visibility = if (line == null) View.GONE else View.VISIBLE
            }
            return root
        }
    }

    companion object {
        const val EXTRA_DATE = "date"
    }
}
