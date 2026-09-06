package me.excuse.app.strict

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager

/**
 * Recents 动画期间直接启动 HOME 可能只给已 resumed 的 launcher 送 onNewIntent，
 * 目标 app 甚至还没有 PAUSED。先用独立的不透明 task 接管一次前台，再回桌面，才能结束
 * 这段重叠生命周期；之后从卡片重进目标会重新 RESUMED，而不是永久漏拦。
 */
class ReturnHomeActivity : Activity() {
    private var homeRequested = false
    private var readyToReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_SECURE,
        )
        val cover = View(this)
        setContentView(cover)
        // 透明 1px 页立即 finish 仍可与 Recents/目标同时 resumed，不能证明真的退出。
        // 让不透明页先交出一帧；此时原 overlay 仍在最上面，用户不会看到额外页面。
        cover.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var scheduled = false
            override fun onDraw() {
                if (scheduled) return
                scheduled = true
                cover.post {
                    cover.viewTreeObserver.removeOnDrawListener(this)
                    // 先让 presenter 校验代际并让出 overlay 焦点，再从这个已绘制的普通
                    // Activity 请求 HOME。遮挡仍留着，直到 Home 交接完成才允许点下层。
                    val commandReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            if (resultCode == COMMAND_RETURN_HOME) {
                                readyToReturn = true
                                if (hasWindowFocus()) requestHome()
                            } else if (!isFinishing && !isDestroyed) {
                                finish()
                            }
                        }
                    }
                    reportResult(RESULT_COVER_READY, Bundle().apply {
                        putParcelable(EXTRA_COMMAND_RECEIVER, commandReceiver)
                    })
                }
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 首帧不等于 Recents 动画交权。本页真正拿到焦点后，HOME
        // 才会作为一次新的前台导航处理，而不是被仍在进行的最近任务切换覆盖。
        if (hasFocus && readyToReturn) requestHome()
    }

    @Suppress("DEPRECATION")
    private fun requestHome() {
        if (homeRequested || isFinishing || isDestroyed) return
        // 必须等接管完成；在 service 中连续 startActivity(bridge) / startActivity(HOME)
        // 仍会让 HOME 先撞上尚未结束的 Recents 动画，等同于原来的 no-op。
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
            homeRequested = true
        } catch (error: Exception) {
            Log.e("ReasonMonitor", "return-home activity failed", error)
            reportResult(RESULT_HOME_FAILED)
            finish()
        }
        overridePendingTransition(0, 0)
    }

    override fun onStop() {
        super.onStop()
        // 不提前 finish：Home 真正盖住本页后才收掉 overlay，避免把取消做成新的点击空档。
        reportResult(if (homeRequested) RESULT_HOME_FINISHED else RESULT_HOME_FAILED)
        if (!isFinishing) finish()
    }

    private fun reportResult(result: Int, data: Bundle = Bundle.EMPTY) {
        val receiver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_RECEIVER)
        }
        receiver?.send(result, data)
    }

    companion object {
        const val EXTRA_RESULT_RECEIVER = "me.excuse.app.extra.HOME_RESULT_RECEIVER"
        const val EXTRA_COMMAND_RECEIVER = "me.excuse.app.extra.HOME_COMMAND_RECEIVER"
        const val RESULT_COVER_READY = 0
        const val RESULT_HOME_FINISHED = 1
        const val RESULT_HOME_FAILED = 2
        const val COMMAND_RETURN_HOME = 1
        const val COMMAND_CANCEL = 2
    }
}
