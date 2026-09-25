package com.tvplayer.universal.ui

import android.os.Handler
import android.view.View
import android.widget.TextView

/**
 * OSD + 控制条的显隐与自动收起（原 PlayerActivity 内的 showOsd / showOsdPermanent /
 * hideOsd）。
 *
 * 控制条（@id/controls）与 OSD 同进同出：没有操作一会儿就一起收起来，按任意键再唤出。
 * 收起时必须把焦点从按钮上摘掉 —— 焦点留在看不见的按钮上，左右键就变成"移动焦点"，
 * 用户要的"左右直接快退/快进"正是这样被吃掉的。摘焦点、复位 barFocused 属于按键分发
 * 的状态，通过 [onHide] 回调交给 Activity 处理，不留在本类。
 */
class OsdController(
    private val osd: View,
    private val controls: View,
    private val hintView: TextView,
    private val handler: Handler,
    private val isPlaying: () -> Boolean,
    /** show 时刷新播放/暂停图标（图标状态外部可见，每拍 tick 也会刷） */
    private val onShown: () -> Unit = {},
    /** hide 时摘掉焦点、复位"主动进控制条"标记 */
    private val onHide: () -> Unit = {}
) {
    private val hideAction = Runnable { hide() }

    fun show(hint: String? = null) {
        osd.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        if (hint != null) hintView.text = hint
        onShown()
        handler.removeCallbacks(hideAction)
        // 暂停时留久一点（画面冻住，控制条要能看出是暂停）；正常播放 6 秒收起
        handler.postDelayed(hideAction, if (isPlaying()) HIDE_MS else HIDE_MS * 3)
    }

    /** 诊断信息不能被自动隐藏掉 */
    fun showPermanent(hint: String) {
        osd.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        hintView.text = hint
        handler.removeCallbacks(hideAction)
    }

    fun hide() {
        onHide()
        osd.visibility = View.GONE
        controls.visibility = View.GONE
    }

    val isVisible: Boolean get() = osd.visibility == View.VISIBLE

    companion object {
        /** 没有操作多久收起 OSD + 控制条（暂停时长三倍，见 show） */
        const val HIDE_MS = 6_000L
    }
}
