package j3.voicekey

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONObject

/** 一次说话：录音 → 一个或多个渠道识别 → 写入输入框。状态只在主线程读写 */
class Session(private val svc: VoiceService) {
    enum class State { Listen, Wait, Final, Error }

    class Row(val ch: Channel) {
        var text = ""
        var state = State.Listen
        var ms: Long? = null
        var handle = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private var gen = 0
    private var recorder: Recorder? = null
    private var target: AccessibilityNodeInfo? = null
    private var typer: Typer? = null
    private var typed = false
    private var autoStop = false
    private var heard = false
    private var lastVoice = 0L
    private var releasedAt = 0L
    private var userPicked = false
    private var choosePending = false
    private var chooseSend = false
    private var jevToken = 0
    private var pickGrace: Runnable? = null
    private var selectionTiming = JevSelectionTiming()
    /** 用户已动手选过（点选/换行），Jev 不再介入 */
    private var manual = false

    var rows: List<Row> = emptyList()
        private set
    /** Jev 评选进行中，候选框仍可手选 */
    var jevBusy = false
        private set
    var jevFailed = false
        private set
    var sel = 0
        private set
    var picking = false
        private set
    /** 1.13.3 悬浮栏上屏：本次会话的目标是 Muse 悬浮栏搜索框，结束只上屏、不发送；手动会话在别处恒为 false */
    var overlayFlow = false
        private set
    /** 本次会话是悬浮栏自动触发开始的（结束时抑制下一次自动触发）；手动会话恒为 false */
    private var overlayAuto = false
    val active get() = rows.isNotEmpty()
    val recording get() = recorder != null

    private fun channels(): List<Channel> = App.prefs.channel?.let { listOf(it) } ?: App.prefs.multi

    fun prewarm() {
        if (!active) channels().filter { it.ready && it.loggedIn }.forEach { Native.prewarm(it.id) }
    }

    /** 点按：没在说就开始（静音自动结束），在录就结束，候选框开着就取消 */
    fun tap() = when {
        recording -> end()
        active -> abort(null)
        else -> begin(true)
    }

    fun holdStart() {
        if (!active) begin(false)
    }

    /** 悬浮栏自动触发：不在会话中才按自动停止（静音自动结束）开始一次；返回是否真的开始了 */
    fun autoStartOverlay(): Boolean {
        if (active || recording) return false
        begin(true)
        overlayAuto = active
        return active
    }

    fun holdEnd() {
        if (!autoStop) end()
    }

    private fun begin(auto: Boolean) {
        val chs = channels()
        val ready = chs.filter { it.ready && it.loggedIn }
        if (ready.isEmpty()) {
            val blocked = chs.firstOrNull { it.needsLogin && !it.loggedIn }
            val msg = if (blocked != null) "${blocked.title}未登录，请在 VoiceKey 里登录" else "${chs.first().title}模型未下载，请在 VoiceKey 里下载"
            return svc.hud.error(msg)
        }
        MicService.start(svc)
        val g = ++gen
        selectionTiming = App.prefs.jevSelectionTiming()
        target = svc.focusedInput()
        // 1.13.4：手动触发永远不被门禁挡。悬浮栏认框只用来选上屏路径：
        // 开关开着、在 Muse 里、且当前聚焦框是悬浮栏搜索框 → 走 insertForOverlay
        // （上屏前再核一次框里有没有用户自己打的字，有就不写）；
        // 否则走普通上屏路径（1.13.1 的行为）。不拒绝、不弹提示。
        overlayFlow = App.prefs.museOverlayInsert && svc.isMuseForeground() &&
            (target?.let { svc.isMuseOverlaySearchBox(it) } == true)
        rows = (if (ready.size > 1) ready.sortedByDescending { App.prefs.pickCount(it) } else ready).map { Row(it) }
        picking = rows.size > 1
        userPicked = false
        choosePending = false
        chooseSend = false
        jevBusy = false
        jevFailed = false
        manual = false
        sel = 0
        if (picking) {
            rows.indexOfFirst { it.ch == App.prefs.lastPick }.takeIf { it >= 0 }?.let { sel = it; userPicked = true }
        }
        for (r in rows) {
            r.handle = Native.start(r.ch.id, Recorder.RATE) { kind, text -> main.post { event(g, r, kind, text) } }
            if (r.handle == 0L) {
                r.state = State.Error
                r.text = "渠道不可用"
            }
        }
        // 悬浮栏上屏不做边说边写：录完只以定稿一次性写入，避免中途文字干扰搜索框
        typer = if (!picking && App.prefs.streaming && !overlayFlow) target?.let(::Typer) else null
        typed = false
        val handles = rows.map { it.handle }.filter { it != 0L }.toLongArray()
        recorder = try {
            Recorder(
                onPcm = { buf, n -> for (h in handles) Native.push(h, buf, n) },
                onLevel = { v -> main.post { if (g == gen) level(v) } },
                onSilenced = { main.post { if (g == gen) abort("系统不允许后台录音，请打开一次 VoiceKey 再试") } },
            )
        } catch (e: Exception) {
            return abort(e.message ?: "麦克风不可用")
        }
        autoStop = auto
        heard = false
        lastVoice = SystemClock.elapsedRealtime()
        svc.onSessionChanged()
    }

    private fun level(v: Float) {
        svc.onLevel(v)
        if (!autoStop || !recording || !App.prefs.silenceStop) return
        val now = SystemClock.elapsedRealtime()
        if (v > 0.3f) {
            heard = true
            lastVoice = now
        } else if (now - lastVoice > if (heard) (App.prefs.silence * 1000).toLong() else 8000L) {
            end()
        }
    }

    fun end() {
        val r = recorder ?: return
        recorder = null
        r.stop()
        for (row in rows) {
            if (row.handle != 0L) Native.finish(row.handle)
            if (row.state == State.Listen) row.state = State.Wait
        }
        releasedAt = SystemClock.elapsedRealtime()
        val g = gen
        main.postDelayed({
            if (g == gen && rows.any { it.state == State.Wait }) {
                if (picking && rows.any { it.state == State.Final }) advancePick() else abort("识别超时")
            }
        }, 15_000)
        if (picking) advancePick()
        svc.onSessionChanged()
    }

    private fun event(g: Int, row: Row, kind: Int, text: String) {
        if (g != gen || row !in rows || row.state == State.Final || row.state == State.Error) return
        when (kind) {
            Native.RECONNECT -> return svc.hud.info(text)
            Native.PARTIAL -> {
                // 识别文字仍在变化即视为在说话，避免声音小时被静音判定中途截断
                if (recording && text != row.text) {
                    heard = true
                    lastVoice = SystemClock.elapsedRealtime()
                }
                row.text = text
                if (typer?.update(text) == true) typed = true
            }
            Native.FINAL -> {
                if (text.isNotEmpty()) row.text = text
                if (row.text.isEmpty()) {
                    row.text = "没有识别到内容"
                    row.state = State.Error
                } else {
                    row.state = State.Final
                }
                row.ms = if (recording) 0 else SystemClock.elapsedRealtime() - releasedAt
            }
            else -> {
                if (row.text.isEmpty()) {
                    row.text = text
                    row.state = State.Error
                } else {
                    row.state = State.Final
                    row.ms = if (recording) 0 else SystemClock.elapsedRealtime() - releasedAt
                }
            }
        }
        if (kind == Native.PARTIAL) return svc.onSessionChanged()
        if (!picking) return single(row)
        val cur = rows[sel].state
        if (row.state == State.Final) {
            val i = rows.indexOf(row)
            if (cur != State.Final && (!userPicked || cur == State.Error)) sel = i
        }
        advancePick()
        svc.onSessionChanged()
    }

    /**
     * 仅为有效 Jev 提前评选，避免失败或卡住的渠道拖住选择；停录后按本次录音快照的门槛推进，否则首个可用定稿开启一次配置时长的宽限。
     * 推进时保留慢渠道文字并释放句柄；关闭或无效的 Jev 保留候选及晚到结果，等待用户选择。
     * 显式等待上屏的选择保留原有宽限和回退逻辑，并可在录音期间沿原逻辑完成。
     *
     * Advance early only for valid Jev so failed or stalled channels cannot block selection. Without manual intervention,
     * the recording's threshold snapshot advances immediately after stop; otherwise the first usable final starts one configured grace period.
     * Slow rows retain text and release handles when advancing.
     * Disabled or invalid Jev preserves candidates and late results for manual selection. An explicit pending choice retains
     * its grace and fallback behavior, and may complete during recording as before.
     */
    private fun advancePick() {
        if (!picking) return
        // 悬浮栏上屏：不等用户按数字键选，有了第一条定稿就按列表顺序取最靠前的一条上屏（只上屏、不发送）；
        // 用户已动手（点选/换行/待选）或还在录音时不抢，避免打断说话和手动选择
        if (overlayFlow && !manual && !choosePending && !recording) {
            rows.indexOfFirst { it.state == State.Final }.takeIf { it >= 0 }?.let {
                choose(it, false)
                return
            }
        }
        var waiting = rows.any { it.state == State.Listen || it.state == State.Wait }
        val finals = rows.count { it.state == State.Final }
        val canAdvance = choosePending || canAutoPick()
        if (!recording && waiting && canAdvance && !choosePending && finals >= selectionTiming.threshold.required(rows.size)) {
            cancelPickGrace()
            finishSlowRows()
            waiting = false
        }
        if (recording || !waiting || !canAdvance) {
            cancelPickGrace()
        } else if (pickGrace == null && rows.any { it.state == State.Final }) {
            val g = gen
            val task = object : Runnable {
                override fun run() {
                    if (g != gen || !picking || recording || pickGrace !== this) return
                    pickGrace = null
                    if (choosePending || canAutoPick()) finishSlowRows()
                    advancePick()
                    svc.onSessionChanged()
                }
            }
            pickGrace = task
            main.postDelayed(task, selectionTiming.waitMs.toLong())
        }
        if (choosePending && rows[sel].state == State.Error) rows.indexOfFirst { it.state == State.Final }.takeIf { it >= 0 }?.let { sel = it }
        if (choosePending && rows[sel].state == State.Final) return choose(sel, chooseSend)
        if (!waiting) choosePending = false
        maybeJev()
    }

    private fun canAutoPick() = !manual && Native.jevCheck(App.prefs.jevConfig(selectionTiming)) == null

    private fun cancelPickGrace() {
        pickGrace?.let { main.removeCallbacks(it) }
        pickGrace = null
    }

    private fun finishSlowRows() {
        for (row in rows) {
            if (row.state != State.Listen && row.state != State.Wait) continue
            row.state = State.Error
            val handle = row.handle
            row.handle = 0L
            if (handle != 0L) Native.free(handle)
        }
    }

    /**
     * 仅在 Jev 配置有效时自动评选；单个定稿按 pickSend 自动上屏发送或只高亮，多个定稿交给 Jev 推荐。
     *
     * Automatic selection requires valid Jev configuration. A single final inserts and sends or only highlights according
     * to pickSend; multiple finals are passed to Jev for recommendation.
     */
    private fun maybeJev() {
        if (!picking || recording || choosePending || manual || jevBusy || jevFailed) return
        if (rows.any { it.state == State.Listen || it.state == State.Wait }) return
        val cfg = App.prefs.jevConfig(selectionTiming)
        if (Native.jevCheck(cfg) != null) return
        val idxs = rows.indices.filter { rows[it].state == State.Final }
        if (idxs.isEmpty()) return
        if (idxs.size == 1) {
            return if (App.prefs.pickSend) choose(idxs[0], true) else {
                sel = idxs[0]
                svc.onSessionChanged()
            }
        }
        val g = gen
        val t = ++jevToken
        jevBusy = true
        Native.jevPick(cfg, idxs.map { rows[it].text }.toTypedArray()) { kind, text ->
            main.post { onJev(g, t, idxs, kind, text) }
        }
    }

    /** 晚到（会话已换代或用户已手选）的结果一律丢弃 */
    private fun onJev(g: Int, t: Int, idxs: List<Int>, kind: Int, text: String) {
        if (g != gen || t != jevToken || !jevBusy) return
        jevBusy = false
        val i = if (kind == Native.FINAL) runCatching { idxs[JSONObject(text).getInt("index")] }.getOrNull() else null
        if (i != null && rows.getOrNull(i)?.state == State.Final) {
            Toast.makeText(svc, "Jev 已选 ${rows[i].ch.short}", Toast.LENGTH_SHORT).show()
            // 关闭自动选中时只把推荐项高亮出来，不上屏、不发送，等用户自己点
            if (!App.prefs.pickSend) {
                sel = i
                svc.onSessionChanged()
                return
            }
            return choose(i, true)
        }
        jevFailed = true
        svc.hud.error("智能选择失败，请手动选择")
        svc.onSessionChanged()
    }

    /** 用户手动选择：Jev 让位，进行中的结果作废 */
    private fun yieldToUser() {
        manual = true
        if (jevBusy) {
            jevToken++
            jevBusy = false
        }
    }

    /** 悬浮栏上屏：上屏前再核一次框里有没有用户自己打的字，有就不写 */
    private fun insertForOverlay(node: AccessibilityNodeInfo?, text: String): Boolean {
        val n = node?.takeIf { it.refresh() }
        if (n != null && !svc.nodeTextEmpty(n)) {
            svc.hud.info("搜索框里已有文字，未写入")
            return false
        }
        return Inject.insert(svc, n, text)
    }

    private fun single(row: Row) {
        val t = typer
        val node = target
        val overlay = overlayFlow
        close()
        when {
            row.state == State.Final -> {
                // 悬浮栏上屏只写字不发送；其余情况走原来的手动上屏路径
                if (overlay) insertForOverlay(node, row.text)
                else if (t?.update(row.text) != true) Inject.insert(svc, node, row.text)
            }
            typed -> {}
            else -> svc.hud.error(if (row.text == "没有识别到内容") row.text else "识别失败：${row.text}")
        }
    }

    /** 选一行上屏，send 时随后点发送；还在识别的行先结束录音，定稿后自动上屏 */
    fun choose(i: Int, send: Boolean = false) {
        val r = rows.getOrNull(i) ?: return
        yieldToUser()
        sel = i
        userPicked = true
        when (r.state) {
            State.Listen, State.Wait -> {
                choosePending = true
                chooseSend = send
                end()
                svc.onSessionChanged()
            }
            State.Final -> {
                App.prefs.lastPick = r.ch
                if (picking) App.prefs.countPick(r.ch)
                val node = target
                val overlay = overlayFlow
                close()
                // 悬浮栏上屏只写字，发送一律交给小布模块点绿箭头；其余情况 send 为真才点发送
                val ok = if (overlay) insertForOverlay(node, r.text) else Inject.insert(svc, node, r.text)
                if (ok && send && !overlay) svc.send(node)
            }
            State.Error -> svc.onSessionChanged()
        }
    }

    /** 候选框里换到下一条可上屏的行 */
    fun next() {
        if (!picking) return
        yieldToUser()
        val n = rows.size
        (1..n).map { (sel + it) % n }.firstOrNull { rows[it].state != State.Error }?.let {
            sel = it
            userPicked = true
            svc.onSessionChanged()
        }
    }

    fun abort(message: String?) {
        val row = if (picking) rows.getOrNull(sel) else rows.firstOrNull()
        val keep = if (message != null && row != null && row.state != State.Error) row.text.takeIf { it.isNotEmpty() } else null
        val t = typer
        val node = target
        val already = typed
        val overlay = overlayFlow
        close()
        if (keep != null) {
            if (overlay) insertForOverlay(node, keep)
            else if (t?.update(keep) != true && !already) Inject.insert(svc, node, keep)
            svc.hud.info("$message，已保留已识别的文字")
        } else {
            message?.let(svc.hud::error)
        }
    }

    private fun close() {
        cancelPickGrace()
        gen++
        // 悬浮栏自动触发的会话结束：抑制再触发，直到框失焦或悬浮栏关掉再重新聚焦
        if (overlayAuto) svc.suppressOverlayAuto()
        overlayAuto = false
        overlayFlow = false
        recorder?.stop()
        recorder = null
        for (r in rows) {
            val handle = r.handle
            r.handle = 0L
            if (handle != 0L) Native.free(handle)
        }
        rows = emptyList()
        picking = false
        typer = null
        target = null
        svc.onSessionChanged()
    }
}
