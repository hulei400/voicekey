package j3.voicekey

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 看门狗：无障碍开着的情况下，每 15 分钟检查一次——无障碍被关过就写回
 * 开启列表，前台服务没跑就拉起来，免得用户长期不打开 App 就悄悄失效。
 *
 * 注意：划后台强行停止 App（force-stop）会清空闹钟，看门狗防不住那一下；
 * 防划后台主要靠 MicService 的 stopWithTask=false + 系统里开自启动。
 * 看门狗只在无障碍已开启时才排闹钟，没启用时不产生任何后台唤醒。
 */
object Watchdog {
    private const val INTERVAL_MS = 15 * 60 * 1000L
    private const val REQUEST = 1

    fun schedule(ctx: Context) {
        if (!VoiceService.enabled(ctx)) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, REQUEST, Intent(ctx, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // setAndAllowWhileIdle 不需要 SCHEDULE_EXACT_ALARM，Doze 下也会跑
        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS, pi,
        )
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Keep.restore(context)
        if (VoiceService.enabled(context)) {
            MicService.start(context)
            Watchdog.schedule(context)
        }
    }
}
