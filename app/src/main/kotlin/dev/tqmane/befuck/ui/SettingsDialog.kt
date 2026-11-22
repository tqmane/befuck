package dev.tqmane.befuck.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import dev.tqmane.befuck.R
import dev.tqmane.befuck.runtime.DailySync
import dev.tqmane.befuck.runtime.RuntimeKnowledge
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Module settings, background sync interval configuration, recovery test/log, and repair rules editor. */
internal object SettingsDialog {
    fun open(activity: Activity, resources: Resources, backgroundExecutor: Executor) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ExpressiveUi.BACKGROUND)
        }
        val top = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        }
        top.addView(
            ExpressiveUi.text(activity, resources.getString(R.string.befuck_settings_title), 26f, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        top.addView(
            ExpressiveUi.iconButton(activity, TablerIcon.X, resources.getString(R.string.befuck_close)) { dialog.dismiss() },
            LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)),
        )
        root.addView(top)

        val scroll = ScrollView(activity)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 28))
        }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val summary = ExpressiveUi.text(activity, "", 14f, ExpressiveUi.SECONDARY).apply {
            background = ExpressiveUi.shape(activity)
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18))
            setLineSpacing(dp(activity, 4).toFloat(), 1f)
        }
        column.addView(summary, gapParams(activity, 0, 0, 0, 24))

        val toggles = linkedMapOf<String, MaterialSwitch>()
        var refreshing = false
        fun refresh() {
            summary.text = resources.getString(
                R.string.befuck_settings_summary,
                RuntimeKnowledge.versionName.orEmpty(),
                RuntimeKnowledge.versionCode,
                resources.getString(
                    if (RuntimeKnowledge.loadSymbols() != null) R.string.befuck_settings_saved else R.string.befuck_settings_empty,
                ),
                RuntimeKnowledge.repairs().size,
            )
            refreshing = true
            try {
                toggles.forEach { (key, view) -> view.isChecked = RuntimeKnowledge.enabled(key) }
            } finally {
                refreshing = false
            }
        }

        fun section(title: Int) {
            column.addView(
                ExpressiveUi.text(activity, resources.getString(title), 17f, bold = true),
                gapParams(activity, 4, 0, 0, 12),
            )
        }

        fun grouped(view: View, index: Int, count: Int): FrameLayout {
            val inner = dp(activity, 6).toFloat()
            val outer = dp(activity, 24).toFloat()
            return FrameLayout(activity).apply {
                background = GradientDrawable().apply {
                    setColor(ExpressiveUi.SURFACE)
                    val topRadius = if (index == 0) outer else inner
                    val bottomRadius = if (index == count - 1) outer else inner
                    cornerRadii = floatArrayOf(
                        topRadius, topRadius, topRadius, topRadius,
                        bottomRadius, bottomRadius, bottomRadius, bottomRadius,
                    )
                }
                addView(view, FrameLayout.LayoutParams(-1, -2))
            }
        }

        section(R.string.befuck_settings_title)
        listOf(
            "prefer_preset" to R.string.befuck_settings_prefer_preset,
            "auto_repair" to R.string.befuck_settings_auto_repair,
            "diagnostics" to R.string.befuck_settings_diagnostics,
        ).forEachIndexed { index, (key, labelRes) ->
            val control = MaterialControls.switch(activity, resources).apply {
                tag = "setting-$key"
                text = resources.getString(labelRes)
                minHeight = dp(activity, 72)
                setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16))
                isChecked = RuntimeKnowledge.enabled(key)
                setOnCheckedChangeListener { _, checked -> if (!refreshing) RuntimeKnowledge.setEnabled(key, checked) }
            }
            toggles[key] = control
            column.addView(grouped(control, index, 3), gapParams(activity, 0, 0, 0, 4))
        }

        section(R.string.befuck_daily_sync_section)
        val dailySyncToggle = MaterialControls.switch(activity, resources).apply {
            tag = "setting-daily-sync"
            text = resources.getString(R.string.befuck_daily_sync_toggle)
            minHeight = dp(activity, 72)
            setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16))
            isChecked = DailySync.enabled(activity)
            setOnCheckedChangeListener { _, checked ->
                if (refreshing) return@setOnCheckedChangeListener
                if (!DailySync.configure(activity, checked)) {
                    refreshing = true
                    try {
                        isChecked = DailySync.enabled(activity)
                    } finally {
                        refreshing = false
                    }
                    Toast.makeText(activity, resources.getString(R.string.befuck_daily_sync_error), Toast.LENGTH_LONG).show()
                }
            }
        }
        column.addView(grouped(dailySyncToggle, 0, 1), gapParams(activity, 0, 0, 0, 4))

        fun intervalText() = resources.getString(
            R.string.befuck_sync_interval,
            resources.getString(R.string.befuck_sync_hours, DailySync.intervalHours(activity)),
        )
        val intervalButton = MaterialControls.button(activity, resources, intervalText(), icon = TablerIcon.REFRESH) {}.apply {
            tag = "setting-sync-interval"
            setOnClickListener { anchor ->
                PopupMenu(ModuleUiContext(activity, resources), anchor).apply {
                    DailySync.INTERVALS.forEachIndexed { index, hours ->
                        menu.add(0, index, index, resources.getString(R.string.befuck_sync_hours, hours)).apply {
                            isCheckable = true
                            isChecked = hours == DailySync.intervalHours(activity)
                        }
                    }
                    setOnMenuItemClickListener { item ->
                        if (!DailySync.setIntervalHours(activity, DailySync.INTERVALS[item.itemId])) {
                            Toast.makeText(activity, resources.getString(R.string.befuck_daily_sync_error), Toast.LENGTH_LONG).show()
                        }
                        text = intervalText()
                        true
                    }
                    show()
                }
            }
        }
        column.addView(intervalButton, gapParams(activity, 0, 0, 0, 4))
        column.addView(hintLabel(activity, resources.getString(R.string.befuck_daily_sync_hint)), gapParams(activity, 4, 8, 4, 28))
        column.addView(hintLabel(activity, resources.getString(R.string.befuck_settings_recovery_hint)), gapParams(activity, 4, 10, 4, 28))

        section(R.string.befuck_settings_recovery_section)
        fun item(labelRes: Int, icon: TablerIcon, index: Int, count: Int, action: () -> Unit) {
            val row = ExpressiveUi.row(activity, resources.getString(labelRes), icon, action = action).apply {
                background = ExpressiveUi.ripple(activity, Color.TRANSPARENT, 6f)
            }
            column.addView(grouped(row, index, count), gapParams(activity, 0, 0, 0, 4))
        }

        item(R.string.befuck_settings_add_rule, TablerIcon.PLUS, 0, 4) { editRepair(activity, resources) { refresh() } }
        item(R.string.befuck_settings_rules, TablerIcon.LIST_SEARCH, 1, 4) { openRepairRules(activity, resources) { refresh() } }
        var testingRecovery = false
        item(R.string.befuck_settings_test_recovery, TablerIcon.SHIELD_CHECK, 2, 4) {
            if (testingRecovery) return@item
            testingRecovery = true
            Toast.makeText(activity, resources.getString(R.string.befuck_settings_test_running), Toast.LENGTH_SHORT).show()
            backgroundExecutor.execute {
                val passed = runCatching { RuntimeKnowledge.verifyRecovery() }
                    .onFailure { RuntimeKnowledge.recordFailure(it) }
                    .getOrDefault(false)
                activity.runOnUiThread {
                    testingRecovery = false
                    if (!activity.isDestroyed && !activity.isFinishing && dialog.isShowing) {
                        refresh()
                        val msgRes = if (passed) R.string.befuck_settings_test_passed else R.string.befuck_settings_test_failed
                        Toast.makeText(activity, resources.getString(msgRes), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        item(R.string.befuck_settings_view_log, TablerIcon.FILE_TEXT, 3, 4) {
            val text = runCatching {
                RuntimeKnowledge.logFile()?.takeIf(File::isFile)?.useLines { lines ->
                    val tail = ArrayDeque<String>()
                    lines.forEach { line ->
                        if (tail.size == 40) tail.removeFirst()
                        tail.addLast(line.take(4096))
                    }
                    tail.joinToString("\n")
                }
            }.getOrNull() ?: resources.getString(R.string.befuck_settings_no_log)
            val log = ExpressiveUi.text(activity, text, 12f).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            ExpressiveDialogs.form(
                activity,
                resources.getString(R.string.befuck_settings_view_log),
                null,
                log,
                resources.getString(R.string.befuck_close),
                null,
            ) { true }
        }
        column.addView(
            hintLabel(
                activity,
                resources.getString(R.string.befuck_settings_log_path, RuntimeKnowledge.logFile()?.absolutePath.orEmpty()),
            ).apply { textSize = 12f },
            gapParams(activity, 4, 12, 4, 28),
        )

        section(R.string.befuck_settings_storage_section)
        listOf(
            R.string.befuck_settings_clear_symbols to { RuntimeKnowledge.clearSymbols() },
            R.string.befuck_settings_clear_repairs to { RuntimeKnowledge.clearRepairs() },
            R.string.befuck_settings_clear_all to { RuntimeKnowledge.clearPreferences() },
        ).forEachIndexed { index, (labelRes, clear) ->
            item(labelRes, TablerIcon.TRASH, index, 3) {
                ExpressiveDialogs.confirm(
                    activity,
                    resources.getString(labelRes),
                    resources.getString(R.string.befuck_settings_clear_hint),
                    resources.getString(R.string.befuck_settings_clear),
                    resources.getString(R.string.befuck_close),
                ) {
                    clear()
                    refresh()
                }
            }
        }
        refresh()
        BoundedScreen.show(activity, dialog, root, 720)
    }

    private fun openRepairRules(activity: Activity, resources: Resources, changed: () -> Unit) {
        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ExpressiveUi.BACKGROUND)
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 20))
        }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            ExpressiveUi.text(activity, resources.getString(R.string.befuck_settings_rules), 22f, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        header.addView(
            ExpressiveUi.iconButton(activity, TablerIcon.X, resources.getString(R.string.befuck_close)) { dialog.dismiss() },
            LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)),
        )
        root.addView(header)

        val searchRow = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ExpressiveUi.shape(activity, ExpressiveUi.CONTAINER)
        }
        searchRow.addView(
            ImageView(activity).apply {
                setImageDrawable(TablerIconDrawable(TablerIcon.SEARCH, dp(activity, 20), ExpressiveUi.SECONDARY))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20)).apply { marginStart = dp(activity, 16) },
        )
        val search = EditText(activity).apply {
            hint = resources.getString(R.string.befuck_settings_rule_search)
            contentDescription = hint
            setTextColor(Color.WHITE)
            setHintTextColor(ExpressiveUi.SECONDARY)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            background = null
            minimumHeight = dp(activity, 48)
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 8), dp(activity, 10))
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        ExpressiveUi.attachClearButton(searchRow, search, resources.getString(R.string.befuck_search_clear))
        root.addView(searchRow, gapParams(activity, 0, 8, 0, 0))
        root.addView(
            hintLabel(activity, resources.getString(R.string.befuck_settings_rule_edit_hint)),
            gapParams(activity, 4, 10, 4, 10),
        )
        val scroll = ScrollView(activity)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun render() {
            list.removeAllViews()
            val query = search.text.toString().trim()
            val rules = RuntimeKnowledge.repairRules().filter {
                query.isEmpty() || "${it.key}\n${it.value}\n${it.reason}".contains(query, ignoreCase = true)
            }
            list.addView(
                hintLabel(activity, resources.getString(R.string.befuck_settings_rule_count, rules.size)),
                gapParams(activity, 4, 4, 4, 12),
            )
            if (rules.isEmpty()) {
                list.addView(
                    hintLabel(activity, resources.getString(R.string.befuck_settings_rule_empty)).apply {
                        background = ExpressiveUi.shape(activity, ExpressiveUi.SURFACE, 18f)
                        setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 20))
                        gravity = Gravity.CENTER
                    },
                    gapParams(activity, 0, 4, 0, 8),
                )
                return
            }
            val formatter = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss").withZone(ZoneId.systemDefault())
            for (rule in rules) {
                val source = resources.getString(
                    when (rule.source) {
                        "manual" -> R.string.befuck_settings_rule_manual
                        "learned" -> R.string.befuck_settings_rule_learned
                        "preset" -> R.string.befuck_settings_rule_preset
                        else -> R.string.befuck_settings_rule_saved
                    },
                )
                val at = rule.updatedAt?.let { text -> runCatching { formatter.format(Instant.parse(text)) }.getOrNull() }
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
                    background = ExpressiveUi.ripple(activity, ExpressiveUi.SURFACE, 18f)
                }
                row.addView(hintLabel(activity, listOfNotNull(source, at).joinToString(" · ")).apply { textSize = 11f })
                row.addView(
                    hintLabel(activity, rule.key.replace("#", "\n")).apply {
                        typeface = Typeface.MONOSPACE
                        textSize = 12f
                        setTextColor(Color.WHITE)
                    },
                    gapParams(activity, 0, 6, 0, 4),
                )
                row.addView(hintLabel(activity, rule.value.take(180)).apply { maxLines = 4 })
                if (rule.reason.isNotBlank()) {
                    row.addView(hintLabel(activity, rule.reason).apply { textSize = 11f }, gapParams(activity, 0, 6, 0, 0))
                }
                row.setOnClickListener { editRepair(activity, resources, rule.key) { render(); changed() } }
                list.addView(row, gapParams(activity, 0, 0, 0, 8))
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render()
        BoundedScreen.show(activity, dialog, root, 720)
    }

    private fun editRepair(activity: Activity, resources: Resources, ruleKey: String? = null, saved: () -> Unit) {
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun input(hintRes: Int, multiline: Boolean = false) = EditText(activity).apply {
            hint = resources.getString(hintRes)
            contentDescription = hint
            setTextColor(Color.WHITE)
            setHintTextColor(ExpressiveUi.SECONDARY)
            textSize = 14f
            background = ExpressiveUi.shape(activity, ExpressiveUi.CONTAINER, 18f)
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            if (multiline) {
                minLines = 2
                maxLines = 6
            } else {
                isSingleLine = true
            }
            column.addView(this, gapParams(activity, 0, 0, 0, 10))
        }
        val owner = input(R.string.befuck_settings_rule_class)
        val name = input(R.string.befuck_settings_rule_field)
        val value = input(R.string.befuck_settings_rule_value, multiline = true)
        if (ruleKey != null) {
            owner.setText(ruleKey.substringBeforeLast('#'))
            name.setText(ruleKey.substringAfterLast('#'))
            value.setText(RuntimeKnowledge.repairs()[ruleKey].orEmpty())
            owner.isEnabled = false
            name.isEnabled = false
            owner.alpha = 0.6f
            name.alpha = 0.6f
        }
        val status = hintLabel(activity, "").apply {
            setTextColor(ExpressiveUi.ERROR)
            visibility = View.GONE
        }
        column.addView(status, gapParams(activity, 4, 2, 4, 0))
        ExpressiveDialogs.form(
            activity = activity,
            title = resources.getString(if (ruleKey == null) R.string.befuck_settings_add_rule else R.string.befuck_settings_edit_rule),
            message = null,
            body = column,
            confirm = resources.getString(R.string.befuck_settings_save),
            cancel = resources.getString(R.string.befuck_close),
        ) {
            runCatching { RuntimeKnowledge.registerRepair(owner.text.toString().trim(), name.text.toString().trim(), value.text.toString()) }
                .onSuccess { saved() }
                .onFailure {
                    status.text = resources.getString(R.string.befuck_settings_rule_error, it.javaClass.simpleName)
                    status.visibility = View.VISIBLE
                }
                .isSuccess
        }
    }

    private fun hintLabel(activity: Activity, text: String) = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setLineSpacing(0f, 1.15f)
        setTextColor(ExpressiveUi.SECONDARY)
    }

    private fun gapParams(activity: Activity, left: Int, top: Int, right: Int, bottom: Int) =
        LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(activity, left), dp(activity, top), dp(activity, right), dp(activity, bottom))
        }

    private fun dp(activity: Activity, value: Int): Int = ExpressiveUi.dp(activity, value)
}
