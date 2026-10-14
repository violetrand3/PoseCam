package com.posecam

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.posecam.core.cloud.AuthValidation
import com.posecam.core.cloud.UploadStats
import com.posecam.core.cloud.UploadStatsCache
import com.posecam.core.cloud.UploadStatsText
import com.posecam.core.sync.CloudSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * The account screen ("My uploads"): who is logged in (with Log out and Delete account), how many recordings this
 * collector has uploaded, in total and day by day, and the way on to My campaigns and to the website, where review results
 * and earnings are. A day is one short row (its count and how it splits across campaigns), because at a hundred recordings a day a flat list of recordings is unreadable:
 * tapping a day, or picking a date, opens that day's recordings ([DayUploadsActivity]).
 *
 * The numbers come from the server, which counts a recording once it is fully uploaded, so deleting recordings from the
 * phone (by hand, with "Delete synced", or by storage cleanup) never lowers them. The last answer is kept for when there
 * is no signal.
 */
class UploadStatsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sync: CloudSync
    private lateinit var cache: UploadStatsCache

    private lateinit var avatar: TextView
    private lateinit var name: TextView
    private lateinit var account: TextView
    private lateinit var total: TextView
    private lateinit var today: TextView
    private lateinit var note: TextView
    private lateinit var days: ListView
    private lateinit var empty: TextView
    private var firstDay: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload_stats)
        showBackArrow()
        keepBelowSystemBars()
        sync = CloudSync.get(this)
        cache = UploadStatsCache(this)
        avatar = findViewById(R.id.statsAvatar)
        name = findViewById(R.id.statsName)
        account = findViewById(R.id.statsAccount)
        total = findViewById(R.id.statsTotal)
        today = findViewById(R.id.statsToday)
        note = findViewById(R.id.statsNote)
        days = findViewById(R.id.statsDays)
        empty = findViewById(R.id.statsEmpty)
        findViewById<Button>(R.id.statsSignOut).setOnClickListener { confirmLogOut() }
        findViewById<Button>(R.id.statsPickDate).setOnClickListener { pickDate() }
        findViewById<Button>(R.id.statsCampaigns).setOnClickListener { startActivity(Intent(this, MyCampaignsActivity::class.java)) }
        findViewById<Button>(R.id.statsWeb).setOnClickListener { openWebLink(sync.meta.current().webAppUrl) }
        findViewById<Button>(R.id.statsDelete).setOnClickListener { explainDeleteAccount() }
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
        val session = sync.auth.current().session
        if (session == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }
        showAccount(session.name, session.email)
        // The website's links (opened by the buttons) come from GET /v1/meta; cached, so this only keeps them current.
        scope.launch { runCatching { sync.refreshMeta() } }
        cache.load(session.email)?.let { show(it.stats, "Saved copy \u00b7 ${UploadStatsText.age(it.savedAtMs, System.currentTimeMillis()) ?: "just now"}") }
            ?: run { showNote("Loading\u2026") }
        scope.launch {
            try {
                val offset = UploadStatsText.tzOffsetMinutes()
                val stats = withContext(Dispatchers.IO) { sync.accounts.myStats(offset) }
                cache.save(stats, System.currentTimeMillis())
                show(stats, null)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                // A refresh token the server refused has already signed the collector out (see TokenRefresher).
                if (!sync.auth.isSignedIn) {
                    startActivity(Intent(this@UploadStatsActivity, AuthActivity::class.java))
                    finish()
                    return@launch
                }
                val saved = cache.load(session.email)
                showNote(
                    if (saved != null) {
                        "Offline \u00b7 saved copy from ${UploadStatsText.age(saved.savedAtMs, System.currentTimeMillis()) ?: "a moment ago"}"
                    } else {
                        AuthValidation.messageFor(failure)
                    },
                )
            }
        }
    }

    /** The collector's name is the heading; the e-mail sits under it, or is the heading for an account with no name. */
    private fun showAccount(displayName: String?, email: String) {
        val title = displayName?.trim().takeUnless { it.isNullOrEmpty() } ?: email
        name.text = title
        account.text = email
        account.visibility = if (title == email) View.GONE else View.VISIBLE
        avatar.text = title.first().uppercase()
    }

    private fun showNote(text: String?) {
        note.text = text
        note.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun show(stats: UploadStats, footnote: String?) {
        total.text = stats.totalRecordings.toString()
        today.text = stats.uploadedToday.toString()
        showNote(footnote)
        firstDay = stats.days.lastOrNull()?.date
        val account = sync.recordingAccountId()
        val rows = UploadStatsText.dayRows(stats, UploadStatsText.today()) { id -> sync.campaigns.title(account, id) }
        days.adapter = DayAdapter(rows)
        days.setOnItemClickListener { _, _, position, _ -> openDay(rows[position].date) }
        days.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openDay(date: String) {
        startActivity(Intent(this, DayUploadsActivity::class.java).putExtra(DayUploadsActivity.EXTRA_DATE, date))
    }

    /** Jump to any date, including one with nothing on it (the day's screen then says so). */
    private fun pickDate() {
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            openDay("%04d-%02d-%02d".format(Locale.US, year, month + 1, day))
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = now.timeInMillis
            firstDay?.let { first ->
                val parts = first.split("-").mapNotNull { it.toIntOrNull() }
                if (parts.size == 3) datePicker.minDate = Calendar.getInstance().apply { set(parts[0], parts[1] - 1, parts[2], 0, 0, 0) }.timeInMillis
            }
        }.show()
    }

    /** Day heading and count on one line, and its campaign split under it. */
    private inner class DayAdapter(private val rows: List<UploadStatsText.DayRow>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val root = (convertView as? LinearLayout) ?: LinearLayout(this@UploadStatsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(TextView(context).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD); tag = "title" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(context).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD); tag = "count" })
                        addView(TextView(context).apply { text = "  \u203A"; textSize = 16f; alpha = 0.4f })
                    },
                )
                addView(TextView(context).apply { textSize = 12f; alpha = 0.65f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; tag = "campaigns" })
            }
            val row = rows[position]
            root.findViewWithTag<TextView>("title").text = row.title
            root.findViewWithTag<TextView>("count").text = row.count.toString()
            root.findViewWithTag<TextView>("campaigns").text = row.campaigns
            return root
        }
    }

    private fun confirmLogOut() {
        AlertDialog.Builder(this)
            .setTitle("Log out?")
            .setMessage(
                "Recordings not yet uploaded stay on this phone. They upload when you log in to this account again; " +
                    "another account never uploads them.",
            )
            .setPositiveButton("Log out") { _, _ ->
                scope.launch {
                    // Ends the login on the server too (best effort: offline is fine), then forgets the tokens here.
                    withContext(Dispatchers.IO) { runCatching { sync.logOut() } }
                    startActivity(Intent(this@UploadStatsActivity, AuthActivity::class.java))
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Deleting an account (needed by the app stores) is done on the website, where the password is asked for. */
    private fun explainDeleteAccount() {
        AlertDialog.Builder(this)
            .setTitle("Delete account")
            .setMessage(
                "Your account is deleted on the website, after you sign in there. It anonymizes your account and logs " +
                    "you out everywhere. It can't be done while a payout is pending.\n\nRecordings still on this phone stay here.",
            )
            .setPositiveButton("Open the website") { _, _ -> openWebLink(sync.meta.current().webAppUrl) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
