from pathlib import Path
ROOT=Path('app/src/main/kotlin/dev/tqmane/befuck')
p=ROOT/'ui/PreviewGestures.kt';s=p.read_text().replace('internal class PreviewPhotoView(context: Context) : ImageView(context)', 'internal class PreviewPhotoView(context: Context) : androidx.appcompat.widget.AppCompatImageView(context)')
s=s.replace('bitmap = value; setImageBitmap(value); fit()', 'bitmap = value; if (value == null) setImageDrawable(null) else setImageBitmap(value); fit()');p.write_text(s)
p=ROOT/'ui/SavedPostViewer.kt';s=p.read_text().replace('PreviewPhotoView(activity)', 'PreviewPhotoView(ModuleUiContext(activity, labels))')
if 'viewer-footer-scroll' not in s:
    s=s.replace('        root.addView(footer)', '''        val footerScroll = object : android.widget.ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) activity.resources.displayMetrics.heightPixels else MeasureSpec.getSize(heightMeasureSpec)
                val limit = (available * .60f).toInt().coerceAtLeast(dp(96))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
            }
        }.apply { tag = "viewer-footer-scroll"; addView(footer) }
        val boundedFooter = WidthLimitedFrame(activity, 720).apply { addView(footerScroll, FrameLayout.LayoutParams(-1, -2)) }
        root.addView(boundedFooter, LinearLayout.LayoutParams(-1, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })''')
p.write_text(s)
p=ROOT/'ui/InlinePostPreview.kt';s=p.read_text()
if 'override fun dispatchDraw' not in s:
    s=s.replace('        val frame = object : FrameLayout(activity) {', '''        val frame = object : FrameLayout(activity) {
            private val clip = android.graphics.Path()
            override fun dispatchDraw(canvas: android.graphics.Canvas) {
                clip.reset()
                val radius = ExpressiveUi.dp(context, 20).toFloat()
                clip.addRoundRect(android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, android.graphics.Path.Direction.CW)
                val save = canvas.save(); canvas.clipPath(clip); super.dispatchDraw(canvas); canvas.restoreToCount(save)
            }''')
p.write_text(s)
for p in [ROOT/'ui/SavedPostsScreen.kt',Path('app/src/test/kotlin/dev/tqmane/befuck/ui/SavedPreviewUiTest.kt')]:
    s=p.read_text().replace('saved-post-scroll','saved-media-scroll');p.write_text(s)
p=Path('app/src/test/kotlin/dev/tqmane/befuck/download/FeedDownloadScopeTest.kt');s=p.read_text().replace('val post=post(', 'val snapshot=post(').replace('one.key(post,','one.key(snapshot,').replace('two.key(post,','two.key(snapshot,');p.write_text(s)
p=Path('app/src/test/kotlin/dev/tqmane/befuck/ui/SavedPreviewUiTest.kt');s=p.read_text()
s=s.replace('    @Test fun imageDecoderBoundsOutput', '    @Test @Config(sdk=[29,33]) fun imageDecoderBoundsOutput')
if 'smallLandscapeAndLargeTypeLeaveAnImageAreaAndScrollableActions' not in s:
    i=s.rfind('}')
    s=s[:i]+'''    @Test fun smallLandscapeAndLargeTypeLeaveAnImageAreaAndScrollableActions() {
        for ((width,height,scale) in listOf(Triple(800,360,1f),Triple(320,800,1.8f),Triple(1440,1000,1f))) {
            RuntimeEnvironment.setQualifiers("ja-rJP-w${width}dp-h${height}dp-mdpi")
            RuntimeEnvironment.setFontScale(scale)
            val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get()
            val source=FixturePreviewSource();val actions=PostMediaActions({ true }) { _,_,_ -> }
            val viewer=SavedPostViewer(activity,activity.resources,post(true),FeedMediaSelection.PRIMARY,source,actions,{ true }) {}
            viewer.showViewer();layout(root(viewer),width,height)
            assertTrue("No media area at $width x $height",view(viewer,"viewer-swipe").height>0)
            val footer=view(viewer,"viewer-footer-scroll") as ScrollView
            assertTrue(footer.width<=720)
            assertTrue(view(viewer,"viewer-save-current").isEnabled)
            footer.fullScroll(View.FOCUS_DOWN)
            assertTrue(view(viewer,"viewer-save-all").height>=48)
            viewer.dismiss();source.close();owner.pause().stop().destroy()
        }
        RuntimeEnvironment.setFontScale(1f)
    }
'''+s[i:];p.write_text(s)
print('Corrected empty image teardown, AppCompat context, rounded thumbnail clipping and constrained viewer controls')
