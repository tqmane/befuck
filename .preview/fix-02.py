from pathlib import Path
ROOT=Path('app/src/main/kotlin/dev/tqmane/befuck')
p=ROOT/'download/FeedMediaDownloader.kt';s=p.read_text()
if 'val scope = FeedDownloadScope()' not in s:
    s=s.replace('private class UnauthorizedTrustedHost(val host: String)', 'private class UnauthorizedTrustedHost(val host: String, val origin: String)')
    s=s.replace('        val key = "${post.postId}:$selection:$realMoji"', '        val scope = FeedDownloadScope()\n        val key = scope.key(post, selection, realMoji)')
    s=s.replace('            val result = try {\n                val selected', '            val result = try {\n                scope.check()\n                val selected')
    s=s.replace('for ((kind, item) in selected) {\n                    val metadata', 'for ((kind, item) in selected) {\n                    scope.check()\n                    val metadata')
    s=s.replace('if (realMoji) "realmoji" else kind, item)', 'if (realMoji) "realmoji" else kind, item, scope)')
    s=s.replace('Log.e(TAG, "Media download failed after $completed item(s)", failure)', 'Log.e(TAG, "Media download failed after $completed item(s): ${failure.javaClass.simpleName}")')
    s=s.replace('private fun downloadOne(context: Context, post: FeedPostMedia, kind: String, media: FeedMediaItem): Long {', 'private fun downloadOne(context: Context, post: FeedPostMedia, kind: String, media: FeedMediaItem, scope: FeedDownloadScope): Long {\n        scope.check()')
    s=s.replace('copyUrlToOutput(media.url) {', 'copyUrlToOutput(media.url, scope) {')
    s=s.replace('            val updated = context.contentResolver.update', '            scope.check()\n            val updated = context.contentResolver.update')
    s=s.replace('private fun copyUrlToOutput(mediaUrl: String, openOutput:', 'private fun copyUrlToOutput(mediaUrl: String, scope: FeedDownloadScope, openOutput:')
    s=s.replace('copyWithRedirects(mediaUrl, openOutput, authorization = null)', 'copyWithRedirects(mediaUrl, openOutput, scope, authorization = null, authOrigin = null)')
    s=s.replace('BeFakeAuthHeaders.snapshot()["Authorization"]', 'scope.authorization')
    s=s.replace('copyWithRedirects(mediaUrl, openOutput, authorization)', 'copyWithRedirects(mediaUrl, openOutput, scope, authorization, unauthorized.origin)')
    s=s.replace('        authorization: String?,\n    ): DownloadedMedia', '        scope: FeedDownloadScope,\n        authorization: String?,\n        authOrigin: String?,\n    ): DownloadedMedia')
    s=s.replace('        while (true) {\n            val currentUri', '        while (true) {\n            scope.check()\n            val currentUri')
    s=s.replace('authorization != null && isTrustedBeRealHost(current.host)', 'authorization != null && isTrustedBeRealHost(current.host) && dev.tqmane.befuck.ui.PreviewTransport.origin(current) == authOrigin')
    s=s.replace('throw UnauthorizedTrustedHost(current.host)', 'throw UnauthorizedTrustedHost(current.host, dev.tqmane.befuck.ui.PreviewTransport.origin(current))')
    s=s.replace('val copied = MediaResponseCopy.copy(connection, MAX_MEDIA_BYTES, openOutput)', '''val copied = MediaResponseCopy.copy(connection, MAX_MEDIA_BYTES) {
                    scope.check()
                    object : java.io.FilterOutputStream(openOutput()) {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) { scope.check(); out.write(bytes, offset, length) }
                        override fun write(value: Int) { scope.check(); out.write(value) }
                    }
                }
                scope.check()''')
    p.write_text(s)
# Don't tie shared download code to a UI helper. Origin normalization is tiny and local to the redirect policy.
p=ROOT/'download/FeedMediaDownloader.kt';s=p.read_text().replace('dev.tqmane.befuck.ui.PreviewTransport.origin(current)', '"https://${current.host.lowercase(Locale.ROOT)}:${if (current.port == -1) 443 else current.port}"');p.write_text(s)
# Explicit screenshot descriptions from the previous iteration are superseded by visible lazy previews.
p=Path('docs/calendar-ui.md');s=p.read_text().replace('Browsing it does not fetch remote image previews.', 'Visible front/back previews now load on demand from the saved URLs; see [media previews](saved-media-preview.md). The backup remains URL/metadata-only.')
p.write_text(s)
additions={
'README.md': '\n## Saved media previews\n\nSaved posts show small back/front previews. Tap a preview for a full-screen viewer; swipe between the two cameras of that post, pinch/double-tap photos to zoom, and save either from the card or inside the viewer. Videos show a poster and load playback only after Play. The black/white Material/Tabler UI, calendar and SQLite backup are retained. Preview downloads are temporary and not included in backups. [Behavior and verification](docs/saved-media-preview.md).\n',
'README.ja.md': '\n## 保存メディアのプレビュー\n\n保存した投稿に内カメ・外カメの小さいプレビューを表示します。タップで全画面、左右スワイプで同じ投稿の内外カメラを切り替え、写真はピンチ／ダブルタップで拡大できます。カード上・全画面のどちらからも保存できます。動画は静止画プレビューを表示し、再生を押してから本体を読み込みます。黒白のMaterial/Tabler UI、カレンダー、SQLiteバックアップは維持しています。プレビューの一時データはバックアップに含みません。[仕様と検証](docs/saved-media-preview.md)。\n',
'README.zh-CN.md': '\n## 已保存媒体预览\n\n已保存帖子显示前后摄像头的小预览。点击全屏查看，左右滑动切换同一帖子的两个镜头；照片支持双指或双击缩放。列表卡片与全屏均可下载。视频先显示静态预览，按播放后才加载视频。保留黑白 Material/Tabler 界面、日历和 SQLite 备份；临时预览媒体不进入备份。[说明与验证](docs/saved-media-preview.md)。\n',
'README.ko.md': '\n## 저장 미디어 미리보기\n\n저장한 게시물에 전면/후면 카메라 미리보기를 표시합니다. 탭하여 전체 화면으로 보고, 좌우 스와이프로 같은 게시물의 카메라를 전환합니다. 사진은 핀치/더블 탭으로 확대하며 카드와 전체 화면 양쪽에서 저장할 수 있습니다. 동영상은 정지 미리보기만 자동 표시하고 재생을 누르면 로드합니다. 흑백 Material/Tabler UI, 달력, SQLite 백업을 유지하며 임시 미디어는 백업에 포함하지 않습니다. [사양과 검증](docs/saved-media-preview.md).\n'}
for name,extra in additions.items():
    p=Path(name);s=p.read_text()
    if 'docs/saved-media-preview.md' not in s:p.write_text(s+extra)
print('Scoped downloads to the original account and synchronized preview documentation')
