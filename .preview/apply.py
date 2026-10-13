from pathlib import Path
ROOT=Path('app/src/main/kotlin/dev/tqmane/befuck')
p=ROOT/'ui/SavedPostsScreen.kt'; s=p.read_text()
if 'previewSource: PreviewSource?' not in s:
    s=s.replace('execute: Executor = reads, clock: Clock = Clock.systemDefaultZone()): Dialog {', 'execute: Executor = reads, clock: Clock = Clock.systemDefaultZone(),\n             previewSource: PreviewSource? = null, previewActions: PostMediaActions? = null): Dialog {')
    s=s.replace('val scroll = ScrollView(activity)', 'val scroll = ScrollView(activity).apply { tag = "saved-post-scroll" }')
    pos=s.index('        fun tab(button:')
    s=s[:pos]+'''        val previews = SavedPreviewSession(activity, resources, root, ::active,
            previewSource ?: NetworkPreviewSource(activity) { previewCheck(active()) },
            previewActions ?: PostMediaActions(::active) { post, side, done ->
                FeedMediaDownloader.download(activity.applicationContext, post, side, Consumer { result -> main.post { done(result) } })
            })
'''+s[pos:]
    a=s.index('        fun save(post:'); b=s.index('        fun renderPosts()',a)
    s=s[:a]+s[b:]
    s=s.replace('            list.removeAllViews()', '            previews.clearRows()\n            list.removeAllViews()')
    a=s.index('                listOf(post.primary to FeedMediaSelection.PRIMARY');b=s.index('                card.addView(actions',a)
    s=s[:a]+'''                listOf(FeedMediaSelection.PRIMARY, FeedMediaSelection.SECONDARY).forEachIndexed { index, side ->
                    val params = if (actions.orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)
                    if (index > 0) { if (actions.orientation == LinearLayout.VERTICAL) params.topMargin = dp(activity, 12) else params.marginStart = dp(activity, 12) }
                    actions.addView(previews.tile(post, side), params)
                }
'''+s[b:]
    s=s.replace('all.setOnClickListener { save(post, FeedMediaSelection.ALL, all, cardStatus) }', '''all.setOnClickListener { previews.downloads.save(post, FeedMediaSelection.ALL) }
                previews.watch {
                    all.isEnabled = active() && !previews.downloads.busy(post.postId, FeedMediaSelection.ALL)
                    val state = previews.downloads.last?.takeIf { it.postId == post.postId }
                    cardStatus.visibility = if (state == null) View.GONE else View.VISIBLE
                    cardStatus.text = when {
                        state == null -> ""
                        state.result == null -> resources.getString(R.string.befuck_download_in_progress)
                        state.result.success -> resources.getString(R.string.befuck_download_complete, state.result.completedCount)
                        state.result.completedCount > 0 -> resources.getString(R.string.befuck_saved_partial, state.result.completedCount)
                        else -> resources.getString(R.string.befuck_download_failed)
                    }
                }''')
    s=s.replace('closed = true; ++generation; task?.cancel(true); main.removeCallbacks(renderLater)', 'closed = true; ++generation; task?.cancel(true); main.removeCallbacks(renderLater); previews.close()')
    s=s.replace('if (dialog.isShowing) { renderPosts(); load() }', 'if (dialog.isShowing) { renderPosts(); load(); previews.update() } else previews.close()')
    s=s.replace('        column.addView(list)', '''        column.addView(list)
        column.addView(ExpressiveUi.text(activity, resources.getString(R.string.befuck_preview_network_notice), 12f, ExpressiveUi.SECONDARY),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 12) })''')
    # Guard is not the last constructor argument: use a named argument rather than a trailing lambda.
    s=s.replace('NetworkPreviewSource(activity) { previewCheck(active()) }', 'NetworkPreviewSource(activity, guard = { previewCheck(active()) })')
    p.write_text(s)
p=ROOT/'ui/BeFuckGalleryUi.kt';s=p.read_text()
if 'SavedPreviewSession.resume(activity)' not in s:
    s=s.replace('        BeFakeLocationDialog.onActivityResume(activity)', '        BeFakeLocationDialog.onActivityResume(activity)\n        SavedPreviewSession.resume(activity)')
    s=s.replace('    fun onActivityPause(activity: Activity) {', '    fun onActivityPause(activity: Activity) {\n        SavedPreviewSession.pause(activity)')
    s=s.replace('    fun onActivityDestroy(activity: Activity) {', '    fun onActivityDestroy(activity: Activity) {\n        SavedPreviewSession.destroy(activity)')
    p.write_text(s)
p=ROOT/'ui/InlinePostPreview.kt';s=p.read_text().replace('status.setText(R.string.befuck_preview_loading) // Replaced with module-resolved text below at integration.', 'status.text = loadingText');p.write_text(s)
p=ROOT/'ui/PreviewTransport.kt';s=p.read_text().replace('override fun getSize(): Long { if (size < 0)', 'override fun getSize(): Long { previewCheck(!closed); ticket.check(); if (size < 0)')
s=s.replace('override fun close() { closed = true; ticket.cancel(); synchronized(this)', 'override fun close() { closed = true; synchronized(this)');p.write_text(s)
p=ROOT/'ui/PreviewSource.kt';s=p.read_text().replace('val handle = PreviewCancel { ticket.cancel(); task.cancel(true); worker.remove(task) }', 'lateinit var handle: PreviewCancel\n        handle = PreviewCancel { ticket.cancel(); task.cancel(true); worker.remove(task); requests.remove(handle) }');p.write_text(s)
p=ROOT/'ui/SavedPostViewer.kt';s=p.read_text().replace('change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT', 'change < 0');p.write_text(s)
p=ROOT/'archive/ArchiveRecord.kt';s=p.read_text()
old='''it.mimeType?.lowercase(java.util.Locale.ROOT)?.takeIf { mime -> mime.startsWith("image/") || mime.startsWith("video/") }'''
new='''(it.mimeType?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { mime -> mime.startsWith("image/") || mime.startsWith("video/") } ?: if (it.isVideo) "video/mp4" else null)'''
s=s.replace(old,new);p.write_text(s)
# Existing screenshots/tests must never start actual network I/O to their fixture URLs.
p=Path('app/src/test/kotlin/dev/tqmane/befuck/ui/CalendarScreenTest.kt');s=p.read_text()
s=s.replace(',direct,clock)', ',direct,clock,previewSource=FixturePreviewSource())').replace(',Executor { queued+=it },clock)', ',Executor { queued+=it },clock,previewSource=FixturePreviewSource())')
p.write_text(s)
print('Integrated lazy camera cards, full-screen pager, lifecycle and stable video classification')
