package j3.voicekey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后自启：把前台服务拉起来，无障碍被关过就写回开启列表。
 * 注意：划后台强行停止 App（force-stop）后系统不会再发 BOOT_COMPLETED，
 * 重启后的恢复只在 App 没被划掉的前提下生效；防划后台主要靠
 * MicService 的 stopWithTask=false + 系统里开自启动。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        Keep.restore(context)
        if (VoiceService.enabled(context)) {
            MicService.start(context)
            Watchdog.schedule(context)
        }
    }
}
