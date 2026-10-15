package kr.c40.floor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.view.View
import kr.c40.floor.core.Pose2D
import org.json.JSONObject
import java.io.File

/** 지도 원점 정보. 이 값이 있어야 기준 위치를 지도 위 점으로 표시할 수 있다. */
data class MapGeo(val originX: Double, val originY: Double, val resolution: Double)

/** 잘라 낸 미리보기. [cropX]/[cropY] 는 원본 지도에서 잘라 낸 위치, [fullHeight] 는 원본 높이(px). */
class MapThumb(val bitmap: Bitmap, val cropX: Int, val cropY: Int, val fullHeight: Int, val geo: MapGeo?) {
    /** ROS 좌표 → 미리보기 픽셀. 원점 정보가 없으면 null. */
    fun toPixel(p: Pose2D): Pair<Float, Float>? {
        val g = geo ?: return null
        val px = (p.x - g.originX) / g.resolution - cropX
        val py = fullHeight - (p.y - g.originY) / g.resolution - cropY
        return px.toFloat() to py.toFloat()
    }
}

/**
 * 층 지도 미리보기. 지도 id 별로 PNG 를 찾는다.
 * 1) 앱 저장소 캐시 (청소 앱이 그 층 지도를 불러왔을 때 `getCurrentLoadMapInfo` 의 `pic` 을 저장한 것)
 * 2) 앱에 들어 있는 지도 (2026-10-15 "공대 3호" 지도)
 * 지도를 새로 만들면 id 가 바뀌므로 옛 그림이 새 지도로 잘못 보이지 않는다.
 */
class MapThumbs(private val context: Context) {
    private val cacheDir = File(context.filesDir, "maps").apply { mkdirs() }
    private val memo = HashMap<String, MapThumb?>()

    private val bundledGeo: Map<String, MapGeo> by lazy {
        try {
            val j = JSONObject(context.assets.open("maps/maps.json").bufferedReader().readText())
            j.keys().asSequence().associateWith { id ->
                val o = j.getJSONObject(id)
                MapGeo(o.getDouble("originx"), o.getDouble("originy"), o.getDouble("resolution"))
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun has(mapId: String) = File(cacheDir, "$mapId.png").exists()

    /** `data:image/png;base64,...` 를 그 지도 id 로 저장한다. */
    fun storePic(mapId: String, pic: String) {
        val b64 = pic.substringAfter("base64,", pic)
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        File(cacheDir, "$mapId.png").writeBytes(bytes)
        synchronized(memo) { memo.remove(mapId) }
    }

    /** 백그라운드 스레드에서 부른다. */
    fun load(mapId: String): MapThumb? {
        synchronized(memo) { if (memo.containsKey(mapId)) return memo[mapId] }
        val cached = File(cacheDir, "$mapId.png")
        val raw = when {
            cached.exists() -> BitmapFactory.decodeFile(cached.path)
            else -> try {
                context.assets.open("maps/$mapId.png").use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
        }
        val thumb = raw?.let { process(it, bundledGeo[mapId]) }
        synchronized(memo) { memo[mapId] = thumb }
        return thumb
    }

    /** 빈 공간은 옅은 회색, 벽은 굵게(2px 팽창) 진하게, 미탐색 영역은 투명하게 바꾸고 내용 영역만 잘라 낸다. */
    private fun process(src: Bitmap, geo: MapGeo?): MapThumb {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val kind = ByteArray(w * h)   // 0 미탐색, 1 빈 공간, 2 벽
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c); val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            val k = when {
                a < 128 -> 0
                r > 240 && g > 240 && b > 240 -> 1
                r < 120 -> 2
                else -> 0
            }
            kind[i] = k.toByte()
            if (k != 0) {
                val x = i % w; val y = i / w
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
        }
        if (x1 < 0) { x0 = 0; y0 = 0; x1 = w - 1; y1 = h - 1 }
        val pad = 12
        x0 = maxOf(0, x0 - pad); y0 = maxOf(0, y0 - pad)
        x1 = minOf(w - 1, x1 + pad); y1 = minOf(h - 1, y1 + pad)
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1
        val out = IntArray(cw * ch)
        val free = Color.rgb(0xEE, 0xF1, 0xEC)
        val wall = Color.rgb(0x15, 0x18, 0x1B)
        for (y in 0 until ch) for (x in 0 until cw) {
            if (kind[(y + y0) * w + (x + x0)].toInt() == 1) out[y * cw + x] = free
        }
        val rad = 2
        for (y in 0 until ch) for (x in 0 until cw) {
            if (kind[(y + y0) * w + (x + x0)].toInt() != 2) continue
            for (dy in -rad..rad) for (dx in -rad..rad) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until cw && ny in 0 until ch) out[ny * cw + nx] = wall
            }
        }
        val bmp = Bitmap.createBitmap(out, cw, ch, Bitmap.Config.ARGB_8888)
        return MapThumb(bmp, x0, y0, h, geo)
    }
}

/** 지도 미리보기와 기준 위치 점을 그린다. */
class MapThumbView(context: Context) : View(context) {
    var thumb: MapThumb? = null
        set(v) { field = v; invalidate() }
    var spot: Pose2D? = null
        set(v) { field = v; invalidate() }
    var dotColor: Int = Color.rgb(0x0F, 0x62, 0xA8)
    var emptyText: String = "지도 미리보기 없음"

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x8A, 0x91, 0x97); textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        val t = thumb
        if (t == null) {
            textPaint.textSize = 15f * resources.displayMetrics.scaledDensity
            canvas.drawText(emptyText, width / 2f, height / 2f, textPaint)
            return
        }
        val bw = t.bitmap.width.toFloat()
        val bh = t.bitmap.height.toFloat()
        val s = minOf(width / bw, height / bh)
        val left = (width - bw * s) / 2
        val top = (height - bh * s) / 2
        canvas.drawBitmap(t.bitmap, null, RectF(left, top, left + bw * s, top + bh * s), bmpPaint)

        val p = spot?.let { t.toPixel(it) } ?: return
        val cx = left + p.first * s
        val cy = top + p.second * s
        val d = resources.displayMetrics.density
        dotPaint.color = Color.argb(64, Color.red(dotColor), Color.green(dotColor), Color.blue(dotColor))
        canvas.drawCircle(cx, cy, 13 * d, dotPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 8 * d, dotPaint)
        dotPaint.color = dotColor
        canvas.drawCircle(cx, cy, 6 * d, dotPaint)
    }
}
