package com.tvplayer.universal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.tvplayer.universal.subtitle.Cue
import kotlin.math.max

/**
 * 自绘字幕层：描边+阴影两遍绘制，StaticLayout 处理多行与中文断行。
 * 不依赖任何额外字体库（系统思源黑体已含 CJK；自定义字体子集为后续可选项）。
 */
class SubtitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * 画笔对齐必须留在默认的 LEFT：StaticLayout 已经按 ALIGN_CENTER 算好了每行的起点，
     * 画笔再居中会在某些行上叠加两次偏移。
     */
    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }
    private val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE6000000.toInt()
    }

    /**
     * 占屏高百分比，由设置页驱动。上限必须是**屏高**而不是画笔字号：
     * 这一层在 activity_player.xml 里是 match_parent，画笔字号本来就按屏高算，
     * 拿字号当上限会让下一次赋值把 size 卡在"上限"上 —— 设置页往上调没反应。
     */
    var sizePercent: Float = 4.5f
        set(v) {
            field = v.coerceIn(MIN_SIZE_PERCENT, MAX_SIZE_PERCENT)
            refreshTextSize()
        }

    var delayMs: Int = 0

    private var cues: List<Cue> = emptyList()
    private var current: Cue? = null
    private var layoutStroke: StaticLayout? = null
    private var layoutFill: StaticLayout? = null

    fun setCues(cues: List<Cue>) {
        this.cues = cues
        current = null
        layoutStroke = null
        layoutFill = null
        invalidate()
    }

    fun clear() = setCues(emptyList())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        refreshTextSize()
    }

    private fun refreshTextSize() {
        if (height == 0) return
        val px = height * sizePercent / 100f
        fill.textSize = px
        stroke.textSize = px
        stroke.strokeWidth = max(2f, px / 12f)
        current?.let { buildLayout(it) }
    }

    /** 播放器每 tick 调用（~200ms），二分找覆盖 pos 的 cue */
    fun updatePosition(positionMs: Long) {
        if (cues.isEmpty()) return
        val pos = positionMs + delayMs
        val idx = binaryFind(pos)
        val hit = if (idx >= 0) cues[idx] else null
        val active = if (hit != null && pos in hit.startMs..hit.endMs) hit else null
        if (active !== current) {
            current = active
            if (active == null) {
                layoutStroke = null
                layoutFill = null
            } else {
                buildLayout(active)
            }
            invalidate()
        }
    }

    private var layoutWidth: Int = 0

    private fun buildLayout(cue: Cue) {
        // 版心：左右各留 4%，正文在这个宽度内折行。宽度还没量到就先不建 ——
        // 拿 0/1px 去建 StaticLayout 会把一条字幕折成几十行一个字一行，竖着堆满屏幕。
        if (width == 0) {
            layoutStroke = null
            layoutFill = null
            return
        }
        val w = (width * 0.92f).toInt().coerceAtLeast(1)
        layoutWidth = w
        val text = clampToMaxLines(breakLongLines(cue.text, w, fill), w)
        // StaticLayout.Builder 是 API 23+，minSdk 22 用旧构造器
        layoutStroke = StaticLayout(text, stroke, w, Layout.Alignment.ALIGN_CENTER, 0f, 0f, false)
        layoutFill = StaticLayout(text, fill, w, Layout.Alignment.ALIGN_CENTER, 0f, 0f, false)
    }

    /**
     * 主动断行。中文一行 20 字在电视上就已经读不动，而 92% 版心按默认字号容得下 36 字，
     * 只靠 StaticLayout 的自然折行等于不折（用户举的"我们尚不了解外星人在书写上是否有断词的概念"
     * 21 字就是这么整行上屏的）。所以先把超过版心一半的长句切开，断点取最靠近该行宽度中点的
     * 标点；切在标点之后，英文单词和数字内部不切。
     * 逐段用 subSequence 拼回，ASS 的颜色是 span，转成 String 会把颜色丢掉。
     */
    private fun breakLongLines(text: CharSequence, boxW: Int, paint: TextPaint): CharSequence {
        val limit = (boxW * MAX_LINE_FILL).toInt()
        if (paint.measureText(text, 0, text.length) <= limit) return text
        val out = SpannableStringBuilder()
        var from = 0
        while (true) {
            val nl = text.indexOf('\n', from)
            appendPieces(out, text, from, if (nl < 0) text.length else nl, limit, paint)
            if (nl < 0) return out
            from = nl + 1
        }
    }

    private fun appendPieces(
        out: SpannableStringBuilder,
        text: CharSequence,
        from: Int,
        to: Int,
        limit: Int,
        paint: TextPaint
    ) {
        var start = from
        while (start < to) {
            val end = if (paint.measureText(text, start, to) > limit)
                breakAt(text, start, to, limit, paint) else to
            if (out.isNotEmpty()) out.append('\n')
            out.append(text, start, end)
            start = end
            while (start < to && text[start] == ' ') start++
        }
    }

    /** 返回这一行该在哪个下标处断开（断点左侧留在当前行） */
    private fun breakAt(text: CharSequence, from: Int, to: Int, limit: Int, paint: TextPaint): Int {
        val total = paint.measureText(text, from, to)
        // 目标点：句中，但不许超过上限 —— 一行 3 倍宽的长句要能切成 3 段而不是 2 段
        val target = minOf(total / 2f, limit * 0.95f)
        var best = -1
        var bestErr = Float.MAX_VALUE
        for (i in from + 1 until to) {
            if (BREAK_AFTER.indexOf(text[i - 1]) < 0 && BREAK_BEFORE.indexOf(text[i]) < 0) continue
            if (text[i - 1].isLetterOrDigit() && text[i].isLetterOrDigit()) continue
            val left = paint.measureText(text, from, i)
            if (left < target * 0.5f || left > limit) continue
            val err = Math.abs(left - target)
            if (err < bestErr) {
                bestErr = err
                best = i
            }
        }
        if (best > from) return best
        // 整行没有一个可用标点：按宽度硬切到目标点
        var j = from + 1
        while (j < to - 1 && paint.measureText(text, from, j + 1) <= target) j++
        return j
    }

    /**
     * 折行本身交给 StaticLayout（按版心宽度断行，中文逐字可断，超长英文单词也会切开），
     * 这里只负责把超出 MAX_VISUAL_LINES 的部分截掉：用同一支画笔探量一次，
     * 截断点取第 N 行的行首偏移 —— 和真正绘制时的断行结果一致。
     * 截断走 subSequence 不走 toString：ASS 的颜色是 span，toString 会把颜色丢掉。
     */
    private fun clampToMaxLines(text: CharSequence, w: Int): CharSequence {
        val probe = StaticLayout(text, stroke, w, Layout.Alignment.ALIGN_CENTER, 0f, 0f, false)
        return if (probe.lineCount <= MAX_VISUAL_LINES) text
        else text.subSequence(0, probe.getLineStart(MAX_VISUAL_LINES))
    }

    companion object {
        /** 字号占屏高百分比的合法范围：设置页与本 View 共用，避免"假保存" */
        const val MIN_SIZE_PERCENT = 2f
        const val MAX_SIZE_PERCENT = 6f

        const val MAX_VISUAL_LINES = 4

        /** 单行宽度超过版心的这个比例就主动断开。0.5 在 1080 屏默认字号下约合 18 个汉字 */
        const val MAX_LINE_FILL = 0.5f

        // 可以断在其后的字符（断点右侧另起一行）
        const val BREAK_AFTER = "，。、；：？！…—）】」』》,;:)-] }"

        // 可以断在其前的字符（引号/括号跟着下一行走，不会被甩在行尾）
        const val BREAK_BEFORE = "（【「『《(["
    }

    private fun binaryFind(pos: Long): Int {
        var lo = 0
        var hi = cues.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= pos) {
                ans = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        // 字幕轨偶有交叠：回看一条
        if (ans >= 0 && pos <= cues[ans].endMs) return ans
        if (ans > 0 && pos >= cues[ans - 1].startMs && pos <= cues[ans - 1].endMs) return ans - 1
        return if (ans >= 0 && pos <= cues[ans].endMs) ans else -1
    }

    override fun onDraw(canvas: Canvas) {
        val ls = layoutStroke
        val lf = layoutFill
        if (ls == null || lf == null || visibility != VISIBLE) return
        val textH = ls.height.toFloat()
        // 版心水平居中：StaticLayout 的绘制原点就是版心左上角，所以平移量是两侧留白，
        // 不是屏宽的一半（那样会把整块版心推到右侧，真机上出现过字幕贴右下角）
        val x = (width - layoutWidth) / 2f
        val y = height - height * 0.06f - textH
        canvas.save()
        canvas.translate(x, y)
        ls.draw(canvas)
        canvas.translate(0f, 1.5f)
        lf.draw(canvas)
        canvas.restore()
    }
}
