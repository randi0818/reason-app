package me.excuse.app.strict

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.view.Gravity
import android.view.WindowManager

/**
 * 1px 透明 Activity，只在新的温和拦截提示即将展示时短暂取得前台。
 *
 * 它不渲染内容、不接收触摸，也不改变「确认 / 我不用了 / 继续用 +5 分钟」的既有选择；
 * 唯一作用是先让目标 app 进入 onPause，给紧随其后的 overlay 一个稳定的首帧窗口。
 */
class StealForegroundActivity : Activity() {

    private var readyReported = false

    // launchMode=singleInstance：正在 finish 的实例仍可能接住下一次 startActivity。
    // 不接这个回调的话新 intent 不会替换旧的，readyReported 也还是 true —— receiver 永远
    // 收不到通知，那一轮 prompt 只能靠 service 侧的 120ms 兜底出现。
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { setIntent(it) }
        readyReported = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setGravity(Gravity.TOP or Gravity.START)
        window.attributes = window.attributes.apply {
            width = 1
            height = 1
            x = 0
            y = 0
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        // 到这里目标 app 已收到 onPause，Activity 自己的冷启动工作也已经结束。通知 service
        // 再开始 overlay 首绘，避免两套窗口初始化挤在同一个可见动画帧里。
        if (!readyReported) {
            readyReported = true
            val receiver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_READY_RECEIVER, ResultReceiver::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_READY_RECEIVER)
            }
            receiver?.send(RESULT_FOREGROUND_READY, Bundle.EMPTY)
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_READY_RECEIVER = "me.excuse.app.extra.FOREGROUND_READY_RECEIVER"
        const val RESULT_FOREGROUND_READY = 1
    }
}
