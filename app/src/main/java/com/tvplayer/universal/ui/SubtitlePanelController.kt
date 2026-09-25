package com.tvplayer.universal.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tvplayer.universal.R
import com.tvplayer.universal.databinding.ItemSubtitleBinding
import com.tvplayer.universal.databinding.PanelSubtitleSearchBinding
import com.tvplayer.universal.subtitle.SubCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 字幕搜索面板（原 PlayerActivity 内的 toggleSubtitlePanel/focusPanelRow/
 * closeSubtitlePanel/searchSubtitles/pickSubtitle/sourceLabel 及面板适配器）。
 *
 * 刻意是播放页内的覆盖层而不是独立 Activity：盖界面会销毁 SurfaceView 的 surface，
 * 回来没有画面；本类只切一个 View 的可见性，播放不中断。
 *
 * 搜索与下载的字幕领域工作通过 [search] / [fetch] 回调 [SubtitleController]；
 * 面板开关牵动按键层状态（barFocused、焦点停放），通过 [onOpened] / [onClosed] 回调 Activity。
 */
class SubtitlePanelController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val panel: PanelSubtitleSearchBinding,
    private val videoName: String,
    private val search: suspend () -> com.tvplayer.universal.subtitle.SubtitleMatcher.Report?,
    private val fetch: suspend (SubCandidate) -> FetchResult,
    /** 面板内切换字幕显隐时回调，Activity 用来弹 OSD 提示 */
    private val onToggleVisibility: (Boolean) -> Unit,
    /** 面板打开：按键层复位"主动进控制条"标记 */
    private val onOpened: () -> Unit = {},
    /** 面板关闭：摘掉面板列表焦点并复位 barFocused（实现里调 parkFocus） */
    private val onClosed: () -> Unit = {}
) {
    /** 候选（带最终分）；面板每次打开都重搜一遍，旧结果先当占位 */
    private val rows = mutableListOf<Pair<SubCandidate, Int>>()
    private var searching = false

    val isOpen: Boolean get() = panel.root.visibility == View.VISIBLE

    /** 面板内（含列表各行）是否持有着焦点；false 说明焦点被 park 到了根布局 */
    val hasFocus: Boolean get() = panel.root.hasFocus()

    private fun toggleLabel(): String =
        context.getString(
            if (isSubtitleVisible()) R.string.subtitle_toggle_on
            else R.string.subtitle_toggle_off
        )

    private fun isSubtitleVisible(): Boolean =
        subtitleView?.visibility == View.VISIBLE

    private var subtitleView: SubtitleView? = null

    fun setSubtitleView(view: SubtitleView) {
        subtitleView = view
    }

    fun toggle() {
        if (isOpen) close() else open()
    }

    fun open() {
        panel.root.visibility = View.VISIBLE
        onOpened()
        panel.subtitleInfo.text = videoName
        panel.list.adapter = adapter
        // 上一次搜索留下的结果还在，先把焦点送进列表：不这么做的话焦点可能还停在
        // 控制条的播放键上，此时上下键是在控制条里打转，看着就像"面板不能用"
        if (rows.isNotEmpty()) focusRow(0)
        runSearch()
    }

    fun close() {
        panel.root.visibility = View.GONE
        // 不把焦点还给播放/暂停键：用户要"平时焦点不要在控制条上"，那样左右键才是快退快进。
        // 要按那三个按钮就按下键主动进控制条（见 onKeyDown 的焦点优先规则）。
        // 面板自己的列表也带着焦点，必须一起摘掉，否则关完面板左右键还在给列表选行。
        onClosed()
    }

    /**
     * 按键层在"面板开着但焦点已丢（被 park 到根布局）"时调用：把焦点救回列表，
     * 否则按上下键落在根布局上、什么都不发生（实测搜索完成的时序不同会偶发）。
     */
    fun requestListFocus() {
        val lm = panel.list.layoutManager
        val position = if (lm != null && lm.childCount > 0) {
            val target = lm.getFocusedChild() ?: lm.getChildAt(0)
            panel.list.getChildAdapterPosition(target!!)
        } else 0
        focusRow(if (position == RecyclerView.NO_POSITION) 0 else position)
    }

    private fun focusRow(position: Int, retries: Int = 8) {
        // notifyDataSetChanged 后行不一定已完成布局：直接 post 时 ViewHolder 可能还不存在，
        // 焦点送不进去就永远停在根布局。延时重试，直到拿到该位置的行（或重试耗尽）。
        panel.list.postDelayed({
            val holder = panel.list.findViewHolderForAdapterPosition(position)
            if (holder != null) holder.itemView.requestFocus()
            else if (retries > 0) focusRow(position, retries - 1)
        }, 16)
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) =
            if (position == 0) TYPE_TOGGLE else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_TOGGLE) {
                val tv = TextView(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        parent.context.resources.getDimensionPixelSize(R.dimen.row_height)
                    )
                    setBackgroundResource(R.drawable.bg_item_focus)
                    isClickable = true
                    isFocusable = true
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(48, 0, 48, 0)
                    setTextColor(Color.WHITE)
                    textSize = 18f
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            return PanelRow(
                ItemSubtitleBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }

        override fun getItemCount() = 1 + rows.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) {
                val tv = h.itemView as TextView
                tv.text = toggleLabel()
                tv.setOnClickListener {
                    val nowVisible = isSubtitleVisible()
                    val newVisible = !nowVisible
                    subtitleView?.visibility =
                        if (newVisible) View.VISIBLE else View.GONE
                    onToggleVisibility(newVisible)
                    tv.text = if (newVisible)
                        context.getString(R.string.subtitle_toggle_on)
                    else
                        context.getString(R.string.subtitle_toggle_off)
                }
                return
            }
            val row = position - 1
            val (c, score) = rows[row]
            val b = (h as PanelRow).b
            b.name.text = c.title
            b.caption.text = context.getString(
                R.string.subtitle_row_caption, sourceLabel(c.sourceId), score
            )
            b.root.setOnClickListener {
                val i = h.bindingAdapterPosition
                if (i != RecyclerView.NO_POSITION) pick(i - 1)
            }
        }
    }

    class PanelRow(val b: ItemSubtitleBinding) : RecyclerView.ViewHolder(b.root)

    private fun runSearch() {
        if (searching) return
        searching = true
        // 不清空旧结果：上一次的面板结果一边当占位一边等新的回来，清空会让面板空一格，
        // 焦点也就没地方落（"打开面板却不能按上下"的另一半原因）
        panel.searchStatus.setText(R.string.subtitle_panel_searching)
        scope.launch(Dispatchers.IO) {
            val report = search()
            withContext(Dispatchers.Main) {
                searching = false
                if (report == null) {
                    panel.searchStatus.setText(R.string.subtitle_panel_failed)
                    return@withContext
                }
                rows.clear()
                rows += report.ranked
                adapter.notifyDataSetChanged()
                panel.searchStatus.text = context.getString(
                    R.string.subtitle_panel_status, report.counts, report.ranked.size
                )
                focusRow(1)
            }
        }
    }

    private fun pick(index: Int) {
        val (candidate, _) = rows.getOrNull(index) ?: return
        panel.searchStatus.text =
            context.getString(R.string.subtitle_panel_fetching, candidate.title)
        scope.launch(Dispatchers.IO) {
            val result = fetch(candidate)
            withContext(Dispatchers.Main) {
                when (result) {
                    // 设计：选中后不关闭面板，便于连续试多个字幕；只再按字幕检索键（菜单键）才关。
                    is FetchResult.Applied -> {
                        panel.searchStatus.text = context.getString(
                            R.string.subtitle_panel_applied, candidate.title, result.cueCount
                        )
                        // 下载是异步的，期间焦点可能已被摘走，把焦点送回所按行，上下键才不会失灵
                        focusRow(index + 1)
                    }
                    is FetchResult.Failed -> panel.searchStatus.text = context.getString(
                        R.string.subtitle_panel_failed_reason, result.reasonLabel
                    )
                }
            }
        }
    }

    private fun sourceLabel(sourceId: String) = when (sourceId) {
        "assrt" -> context.getString(R.string.subtitle_source_assrt)
        "opensub" -> context.getString(R.string.subtitle_source_opensub)
        else -> context.getString(R.string.subtitle_source_fakesub)
    }

    companion object {
        private const val TYPE_TOGGLE = 0
        private const val TYPE_ROW = 1
    }
}
