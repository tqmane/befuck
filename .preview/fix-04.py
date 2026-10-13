from pathlib import Path
p=Path('app/src/main/kotlin/dev/tqmane/befuck/ui/PreviewGestures.kt');s=p.read_text()
s=s.replace('if (!detector.scaleFactor.isFinite()) return false','if (bitmap == null || !detector.scaleFactor.isFinite()) return false')
s=s.replace('override fun onDoubleTap(event: MotionEvent): Boolean {\n            if (isZoomed)', 'override fun onDoubleTap(event: MotionEvent): Boolean {\n            if (bitmap == null) return true\n            if (isZoomed)')
s=s.replace('''val factor = minOf(width.toFloat() / it.width, height.toFloat() / it.height)
                transform.setScale(factor, factor)
                transform.postTranslate((width - it.width * factor) / 2, (height - it.height * factor) / 2)''','''val dw = drawable?.intrinsicWidth?.takeIf { value -> value > 0 } ?: it.width
                val dh = drawable?.intrinsicHeight?.takeIf { value -> value > 0 } ?: it.height
                val factor = minOf(width.toFloat() / dw, height.toFloat() / dh)
                transform.setScale(factor, factor)
                transform.postTranslate((width - dw * factor) / 2, (height - dh * factor) / 2)''')
s=s.replace('''val bounds = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()).also { transform.mapRect(it) }''','''val dw = drawable?.intrinsicWidth?.takeIf { it > 0 } ?: image.width
        val dh = drawable?.intrinsicHeight?.takeIf { it > 0 } ?: image.height
        val bounds = RectF(0f, 0f, dw.toFloat(), dh.toFloat()).also { transform.mapRect(it) }''')
p.write_text(s)
print('Made fit/zoom independent of bitmap density and prevented zoom locking an empty image')
