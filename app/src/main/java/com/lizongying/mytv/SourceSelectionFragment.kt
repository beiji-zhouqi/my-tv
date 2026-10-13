package com.lizongying.mytv

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.lizongying.mytv.models.TVListViewModel
import com.lizongying.mytv.playlist.SourceQualityResolver

/** A focusable source picker that leaves the live picture visible above it. */
class SourceSelectionFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val content = LayoutInflater.from(dialog.context).inflate(R.layout.source_selection, null)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)

        val channel = ViewModelProvider(requireActivity())[TVListViewModel::class.java]
            .getTVViewModelCurrent()
        val urls = channel?.videoUrl?.value.orEmpty().toList()
        val current = channel?.videoIndex?.value ?: 0
        val qualities = SourceQualityResolver.cachedRanking(requireContext(), urls).qualities
        content.findViewById<TextView>(R.id.source_channel_name).text =
            if (urls.isEmpty()) "当前频道暂无可选来源" else channel?.getTV()?.title
        content.findViewById<TextView>(R.id.source_selection_hint).text =
            if ((activity as? MainActivity)?.isTelevision == true)
                "左右键选择，中间键确认；返回键关闭"
            else "点击来源切换；点击面板外部或返回键关闭"
        val choices = content.findViewById<LinearLayout>(R.id.source_choices)
        urls.indices.forEach { index ->
            val quality = qualities[index]
            val measured = if (index == current) (activity as? MainActivity)?.playbackQuality() else null
            val actual = measured?.takeIf { it.width > 0 && it.height > 0 } ?: quality
            val description = when {
                actual.width > 0 && actual.height > 0 -> "${actual.width} × ${actual.height}" +
                    (if (index == current && measured === actual) " · 实时" else " · 检测值")
                quality.failed -> "上次未能播放，可重试"
                else -> "分辨率待检测"
            }
            val name = channel?.getTV()?.sourceNames?.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: "直播源"
            val button = TextView(requireContext()).apply {
                text = "$name · ${index + 1}" + (if (index == current) " · 当前播放" else "") +
                    "\n" + description
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                minWidth = dp(170)
                isFocusable = true
                isClickable = true
                isSelected = index == current
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(0xFF0097A7.toInt()))
                    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(0xFF0097A7.toInt()))
                    addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(0xFF455A64.toInt()))
                    addState(intArrayOf(), ColorDrawable(0xFF333333.toInt()))
                }
                setOnClickListener {
                    val active = ViewModelProvider(requireActivity())[TVListViewModel::class.java]
                        .getTVViewModelCurrent()
                    val sourceIndex = active?.videoUrl?.value?.indexOf(urls[index]) ?: -1
                    if (active === channel && sourceIndex >= 0 && active != null) {
                        (activity as? MainActivity)?.selectSource(active.getTV().id, sourceIndex)
                    } else {
                        Toast.makeText(context, "频道或来源已更新，请重新打开选源", Toast.LENGTH_SHORT).show()
                    }
                    dismiss()
                }
            }
            choices.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(12) })
            if (index == current) button.post { button.requestFocus() }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "SourceSelectionFragment"
    }
}
