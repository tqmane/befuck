from pathlib import Path
p=Path('app/src/main/kotlin/dev/tqmane/befuck/ui/SavedPostViewer.kt');s=p.read_text()
old='''        val page = pages.current ?: return
        val ticket = generation
        play.isEnabled = false; retry.visibility = View.GONE'''
new='''        val page = pages.current ?: return
        // A slow/failed poster must not cover playback with a retry overlay or overwrite its status.
        imageRequest?.cancel(); imageRequest = null
        val ticket = ++generation
        play.isEnabled = false; retry.visibility = View.GONE'''
s=s.replace(old,new);p.write_text(s)
p=Path('app/src/test/kotlin/dev/tqmane/befuck/ui/SavedPreviewUiTest.kt');s=p.read_text()
if 'latePosterFailureCannotCoverAnExplicitVideoLoad' not in s:
    i=s.rfind('}')
    s=s[:i]+'''    @Test fun latePosterFailureCannotCoverAnExplicitVideoLoad() {
        val owner=Robolectric.buildActivity(Activity::class.java).setup();val activity=owner.get()
        val source=FixturePreviewSource(false);val actions=PostMediaActions({ true }) { _,_,_ -> }
        val viewer=SavedPostViewer(activity,activity.resources,post(true),FeedMediaSelection.PRIMARY,source,actions,{ true }) {}
        viewer.showViewer();assertEquals(1,source.images.size)
        view(viewer,"viewer-play").performClick()
        assertTrue(source.images[0].cancelled);assertEquals(1,source.videos.size)
        source.images[0].callback(Result.failure(java.io.IOException("Late poster failure")))
        assertEquals(View.GONE,view(viewer,"viewer-retry").visibility)
        assertTrue(view(viewer,"viewer-save-current").isEnabled)
        source.videos[0].callback(Result.failure(java.io.IOException("Synthetic playback failure")))
        assertTrue(view(viewer,"viewer-play").isEnabled)
        viewer.dismiss();source.close();owner.pause().stop().destroy()
    }
'''+s[i:];p.write_text(s)
print('Prevented delayed video poster callbacks from covering explicit playback')
