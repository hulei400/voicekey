package j3.voicekey

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONObject

/** 1.13.6 悬浮栏自动触发“稳定 partial 先上屏”：停录后等多久再看 partial（让飞行中的 partial 落定） */
private const val PARTIAL_SETTLE_MS = 500L
/** 文字多久没变算“稳定整句” */
private const val PARTIAL_STABLE_MS = 400L
/** 没找到稳定文字时多久再看一次 */
private const val PARTIAL_RECHECK_MS = 300L

/** 一次说话：录音 → 一个或多个渠道识别 → 写入输入框。状态只在主线程读写 */
class Session(private val svc: VoiceService) {
    enum class State { Listen, Wait, Final, Error }

    class Row(val ch: Channel) {
        var text = ""
        var state = State.Listen
        var ms: Long? = null
        var handle = 0L
        /** 1.13.6：text 上次真正变化的时间（elapsedRealtime）；悬浮栏“稳定 partial 先上屏”用它判稳 */
        var lastTextMs: Long = 0L
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
    /** 1.13.6 悬浮栏自动触发“稳定 partial 先上屏”的定时器；close() 时取消 */
    private var partialRun: Runnable? = null
    /** 1.13.7 文件日志：logcat 缓冲会轮转，关键诊断改写文件（VoiceKey files 目录的 vk-session-trace.log） */
    private var sessionStartElapsed = 0L
    /** 1.13.7：上次音量超过 0.3 的时间（用户确实在说话）；PARTIAL 刷新 lastVoice 只认这个 */
    private var lastLoudMs: Long = 0L
    /** 1.13.7：lastVoice 上次是被谁刷新的（vol=音量，part=文字变化），诊断“还在听”用 */
    private var lastVoiceSrc = ""
    /** 1.13.7：上次 level() 回调音量是否超过 0.3（记 loud→quiet 过渡用） */
    private var wasLoud = false
    /** 1.13.7：音量已经落了之后、PARTIAL 还刷新 lastVoice 的次数（“还在听”的直接证据） */
    private var partRefreshAfterQuiet = 0

    /** 1.13.7 文件日志：每行带墙钟时间 + 本次会话开始后的毫秒，只记文字长度和前几个字，不记整句 */
    private fun logf(tag: String, msg: String) {
        try {
            val f = java.io.File(svc.filesDir, "vk-session-trace.log")
            if (f.exists() && f.length() > 512 * 1024) f.delete()
            val now = System.currentTimeMillis()
            val wall = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(now))
            val dt = SystemClock.elapsedRealtime() - sessionStartElapsed
            f.appendText("$wall dt=$dt [$tag] $msg\n")
        } catch (_: Exception) {
        }
    }

    /** 1.13.7：文字只记长度 + 前 4 个字，不把用户说的整句写进日志 */
    private fun shortText(t: String): String =
        if (t.length <= 4) "\"$t\" len=${t.length}" else "\"${t.take(4)}…\" len=${t.length}"

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
        logf("AUTO", "autoStartOverlay active=$active overlayAuto=$overlayAuto")
        return active
    }

    fun holdEnd() {
        if (!autoStop) end()
    }

    private fun begin(auto: Boolean) {
        sessionStartElapsed = SystemClock.elapsedRealtime()
        lastLoudMs = 0L
        lastVoiceSrc = ""
        wasLoud = false
        partRefreshAfterQuiet = 0
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
        lastVoiceSrc = "init"
        logf("BEGIN", "auto=$auto autoStop=$autoStop overlayFlow=$overlayFlow picking=$picking rows=${rows.size} silence=${App.prefs.silence} silenceStop=${App.prefs.silenceStop}")
        svc.onSessionChanged()
    }

    private fun level(v: Float) {
        svc.onLevel(v)
        if (!autoStop || !recording || !App.prefs.silenceStop) return
        val now = SystemClock.elapsedRealtime()
        if (v > 0.3f) {
            heard = true
            lastVoice = now
            lastLoudMs = now
            lastVoiceSrc = "vol"
            if (!wasLoud) {
                wasLoud = true
                logf("VOL", "loud v=${(v * 100).toInt()}")
            }
        } else {
            if (wasLoud) {
                wasLoud = false
                logf("VOL", "quiet v=${(v * 100).toInt()} lastVoiceAge=${now - lastVoice} lastLoudAge=${now - lastLoudMs}")
            }
            if (now - lastVoice > if (heard) (App.prefs.silence * 1000).toLong() else 8000L) {
                end()
            }
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
        // 1.13.6 悬浮栏自动触发：停录后不等 Final（双通道二次识别慢），
        // 用已稳定的流式整句先上屏；手动会话、手动点选不走这条
        val timer = if (overlayAuto && overlayFlow && !choosePending && !manual) schedulePartialCheck() else false
        // 1.13.7：聆听总时长、静音超时、lastVoice 最后是谁刷新的、音量落了之后 PARTIAL 还刷了几次
        val nowEnd = SystemClock.elapsedRealtime()
        logf(
            "END",
            "listenMs=${nowEnd - sessionStartElapsed} silence=${App.prefs.silence} silenceStop=${App.prefs.silenceStop} " +
                "heard=$heard lastVoiceSrc=$lastVoiceSrc lastVoiceAge=${nowEnd - lastVoice} " +
                "lastLoudAge=${if (lastLoudMs == 0L) -1 else nowEnd - lastLoudMs} " +
                "partRefreshAfterQuiet=$partRefreshAfterQuiet " +
                "overlayAuto=$overlayAuto overlayFlow=$overlayFlow choosePending=$choosePending manual=$manual timerScheduled=$timer"
        )
        svc.onSessionChanged()
    }

    private fun event(g: Int, row: Row, kind: Int, text: String) {
        if (g != gen || row !in rows || row.state == State.Final || row.state == State.Error) return
        when (kind) {
            Native.RECONNECT -> return svc.hud.info(text)
            Native.PARTIAL -> {
                // 1.13.7：文字变化只在最近 500ms 内音量曾经够大（用户确实在说话）时才刷新 lastVoice；
                // 否则（用户已经说完、音量落了，只剩服务端流式修正）不刷新，避免修正拖着不让停录。
                // 整场都没听到过够大的音量（小声说话）时仍刷新，避免把小声说话中途截断。
                if (recording && text != row.text) {
                    heard = true
                    val nowPart = SystemClock.elapsedRealtime()
                    val gated = lastLoudMs != 0L && nowPart - lastLoudMs > 500
                    if (!gated) {
                        lastVoice = nowPart
                        lastVoiceSrc = "part"
                        if (!wasLoud) partRefreshAfterQuiet++
                    }
                    logf("PART", "ch=${row.ch.short} ${shortText(text)} refreshLastVoice=${!gated} gated=$gated wasLoud=$wasLoud lastLoudAge=${if (lastLoudMs == 0L) -1 else nowPart - lastLoudMs}")
                }
                setRowText(row, text)
                if (typer?.update(text) == true) typed = true
            }
            Native.FINAL -> {
                if (text.isNotEmpty()) setRowText(row, text)
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
                    setRowText(row, text)
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
                logf("FINAL", "Final 路上屏 row=$it ch=${rows[it].ch.short} ${shortText(rows[it].text)}")
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

    private fun cancelPartialCheck() {
        partialRun?.let { main.removeCallbacks(it) }
        partialRun = null
    }

    /** 文字真的变了才更新，并记下变化时间（1.13.6 悬浮栏“稳定 partial 先上屏”用它判稳） */
    private fun setRowText(row: Row, text: String) {
        if (text != row.text) {
            row.text = text
            row.lastTextMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * 1.13.6 悬浮栏自动触发“第一个有效结果就上屏”：
     * 不等 Final——Final 要等 FinishSession 后服务端的二次识别（豆包等开着 twopass），
     * 实测要约 5 秒；流式 partial 在停录时其实已经是整句（用户说完了，不会再长出来），
     * 只要停录后这路的文字稳定了一会儿，就先上屏，不等其余渠道和 Final。
     * “有效整句”判定：已停录 + 文字非空 + 在 PARTIAL_STABLE_MS 内没变过（见 commitFirstStablePartial）。
     * 只在自动触发的悬浮栏会话里跑（overlayAuto），手动触发、手动点选都不受影响；
     * 找不到稳定文字时什么都不做，Final 那条快路（advancePick）仍是后备。
     */
    private fun schedulePartialCheck(): Boolean {
        if (!active || !overlayAuto || !overlayFlow || manual || choosePending || recording) {
            logf("TIMER", "not scheduled active=$active overlayAuto=$overlayAuto overlayFlow=$overlayFlow manual=$manual choosePending=$choosePending recording=$recording")
            return false
        }
        cancelPartialCheck()
        val g = gen
        val run = object : Runnable {
            override fun run() {
                if (g != gen) {
                    logf("TIMER", "run skipped gen changed g=$g gen=$gen")
                    return
                }
                partialRun = null
                logf("TIMER", "run rows=" + rows.joinToString(",") { "${it.ch.short}:${it.state}:len${it.text.length}:stable${SystemClock.elapsedRealtime() - it.lastTextMs}" })
                if (commitFirstStablePartial()) return
                // 还没有可上屏的稳定文字：只要还有路在识别，就再等一轮看看；
                // Final 到了会走快路、会话结束（close）会清掉这个定时器
                if (active && overlayAuto && overlayFlow && !manual && !choosePending && !recording &&
                    rows.any { it.state == State.Listen || it.state == State.Wait }) {
                    partialRun = this
                    main.postDelayed(this, PARTIAL_RECHECK_MS)
                } else {
                    logf("TIMER", "stop no rows in Listen/Wait")
                }
            }
        }
        partialRun = run
        main.postDelayed(run, PARTIAL_SETTLE_MS)
        logf("TIMER", "scheduled settleMs=$PARTIAL_SETTLE_MS")
        return true
    }

    /**
     * 找第一路（按 pickCount 排过的列表顺序）非空且稳定的文字上屏。
     * 上屏路径和 Final 快路一致：先 close（丢掉晚到的结果、收起面板、抑制下次自动触发），
     * 再走 insertForOverlay（它自带“框里已有字就不写”的上屏前检查兜底）。
     * 返回是否真的上屏了。
     */
    private fun commitFirstStablePartial(): Boolean {
        if (!active || !overlayAuto || !overlayFlow || manual || choosePending || recording) {
            logf("PCOMMIT", "guard fail active=$active overlayAuto=$overlayAuto overlayFlow=$overlayFlow manual=$manual choosePending=$choosePending recording=$recording")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        // 至少等 PARTIAL_SETTLE_MS，让停录前后飞行中的 partial 落定
        if (now - releasedAt < PARTIAL_SETTLE_MS) {
            logf("PCOMMIT", "too early sinceEndMs=${now - releasedAt}")
            return false
        }
        val idx = rows.indexOfFirst { r ->
            r.state != State.Error && r.text.isNotEmpty() &&
                now - r.lastTextMs >= PARTIAL_STABLE_MS
        }
        if (idx < 0) {
            logf("PCOMMIT", "no stable row rows=" + rows.joinToString(",") { "${it.ch.short}:${it.state}:len${it.text.length}:stable${now - it.lastTextMs}" })
            return false
        }
        val row = rows[idx]
        val node = target
        val text = row.text
        logf("PCOMMIT", "found row=$idx ch=${row.ch.short} ${shortText(text)} stableMs=${now - row.lastTextMs}")
        App.prefs.lastPick = row.ch
        if (picking) App.prefs.countPick(row.ch)
        close()
        val ok = insertForOverlay(node, text)
        logf("PCOMMIT", "committed row=$idx ok=$ok")
        return true
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
            logf("INSERT", "blocked box has user text ${shortText(text)}")
            return false
        }
        val ok = Inject.insert(svc, n, text)
        logf("INSERT", "ok=$ok ${shortText(text)}")
        return ok
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

    /** 1.13.7：从调用栈里找是哪个 Session 方法调的 close（定时器还是 Final 路），诊断用 */
    private fun closeCaller(): String {
        for (e in Thread.currentThread().stackTrace) {
            if (e.className.contains("Session") && e.methodName != "close" &&
                e.methodName != "closeCaller" &&
                e.methodName != "cancelPartialCheck" && e.methodName != "cancelPickGrace"
            ) return e.methodName
        }
        return "?"
    }

    private fun close() {
        logf("CLOSE", "caller=${closeCaller()} gen=$gen overlayAuto=$overlayAuto overlayFlow=$overlayFlow")
        cancelPickGrace()
        cancelPartialCheck()
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
