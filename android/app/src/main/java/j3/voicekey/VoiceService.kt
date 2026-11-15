package j3.voicekey

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo

/** 不占用输入法：无障碍服务找到当前输入框写入文字，提供悬浮球与长按按键（默认音量下键，可自定义组合）触发 */
class VoiceService : AccessibilityService() {
    lateinit var session: Session
        private set
    lateinit var hud: Hud
        private set
    private lateinit var bubble: Bubble
    private val main = Handler(Looper.getMainLooper())
    private val check = Runnable { refresh() }
    private var chordKeys = App.prefs.holdKeys
    private var chord = Chord(chordKeys)
    /** 1.13.4 悬浮栏自动触发：自动会话结束后抑制再触发，直到框失焦或悬浮栏关掉再重新聚焦 */
    private var overlayAutoSuppressed = false
    /**
     * 1.13.5 前台 Activity 跟踪：TYPE_WINDOW_STATE_CHANGED 事件更新，认悬浮栏用。
     * 只记类名含 "Activity" 的窗口（输入法窗口等非 Activity 窗口不覆盖），避免键盘弹出时
     * 把前台 Activity 冲掉。初始为 null（服务重连后悬浮栏已在前台时，等下一次窗口切换再认）。
     */
    private var foregroundActivity: String? = null
    private var foregroundActivityPkg: String? = null
    /** 1.13.8 Muse 语音自动播放：上次扫描时间（500ms 防抖）。
     * 1.13.10-1.13.12 一路试的都是“数可见气泡个数”：bounds 随滚动变、
     * 序号跨窗口撞号、未播个数随播放状态震荡、全部个数自己抖（3↔2↔1）、
     * 历史最大个数被视口容量卡死（新语音来一个、旧的就被顶出视口，个数
     * 永远到不了 4）——可见个数是视口的属性，不是对话的属性，这条路走不通。
     * 1.13.13：按文字认语音，不数个数。每条语音气泡正上方都有一段文字
     * （消息文本转写，随滚动不变，2026-11-15 dump 实测：主聊天和悬浮栏
     * 都是“文字在上、气泡在下”，文字底边离播放按钮顶边约 66-90px）——
     * 用它当身份。按窗口记上次放过的那条的文字（lastPlayedTranscript）：
     * 最下面那条的文字变了、并且列表已滑到底、并且按钮是“播放”，才点一次；
     * 发文字消息、滚动、重组、播完变回“播放”都不会换文字，都不点。
     * key 是前台 Activity 类名（悬浮栏 HatchAssistOverlayActivity 和主聊天
     * AuraMainActivity 各记各的），切窗口不会互相干扰；某窗口第一次见时只记
     * 文字、不点，避免把旧气泡当成新的放出来。 */
    private var lastVoiceScanMs = 0L
    /** 1.13.13 该窗口上次放过的语音的文字（身份）。 */
    private val lastPlayedTranscriptByWindow = mutableMapOf<String, String>()
    /** 1.13.13 上次扫描时该窗口可见的全部语音文字（新旧判断用：文字不在上一
     * 次可见集合里，说明它刚进视口；滚动回旧语音时文字早在集合里，不点）。 */
    private val lastScanTranscriptsByWindow = mutableMapOf<String, Set<String>>()
    /** 1.13.12 上一次确定的前台 Activity（取不到前台 Activity 时沿用它，别拿
     * "?" 当新窗口；从没确定过就跳过这次扫描）。 */
    private var lastKnownVoiceWindow: String? = null
    /** 1.13.13 最近一次滚动事件（TYPE_VIEW_SCROLLED，在 Muse 包里才记）：
     * 列表滑到底的判断。LazyColumn 滚动事件是否报真实总数（itemCount），
     * 实现时没法在盒子上验——把它写进日志，真机上能从日志反推；拿不到
     * 总数时走“文字是否刚进视口”的退路。 */
    private var lastScrollToIndex = -1
    private var lastScrollItemCount = -1
    /** 1.13.13 最近一次内容变化事件的时间（elapsedRealtime，诊断用）。 */
    private var lastContentChangeMs = 0L

    /** 设置页录制长按组合：非空时接管按键，录完回调结果；null 表示取消 */
    var recorder: ((List<Int>?) -> Unit)? = null
        set(v) {
            field = v
            recHeld.clear()
            recPeak.clear()
            chord.reset()
        }
    private val recHeld = mutableSetOf<Int>()
    private val recPeak = mutableListOf<Int>()

    override fun onServiceConnected() {
        session = Session(this)
        hud = Hud(this)
        bubble = Bubble(this)
        instance = this
        MicService.start(this)
        refresh()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        // 1.13.5：窗口切换时记下前台 Activity（认悬浮栏用），在评估之前更新，
        // 同一个窗口切换事件的评估就能用上最新的 Activity。
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = e.className?.toString()
            if (cls != null && cls.contains("Activity")) {
                foregroundActivityPkg = e.packageName?.toString()
                foregroundActivity = cls
            }
        }
        main.removeCallbacks(check)
        main.postDelayed(check, 150)
        maybeAutoListenOverlay(e)
        maybeAutoPlayVoice(e)
    }

    /**
     * 1.13.8 Muse 聊天语音消息自动播放。开关 museVoiceAutoPlay 是唯一的门（默认关，
     * 关着时直接返回、什么都不做）；只在 Muse 包里工作，无障碍服务只能看到前台窗口
     * 的节点，Muse 不在前台时自然不触发，不用另加前台判断。
     * 新消息出现（内容变化/滚动）时扫描一次，带 500ms 防抖。输入框、发送箭头、
     * 悬浮栏那条路都不碰。
     * 每次扫描记一行 vk-autoplay-trace.log（时间/窗口/可见个数/动作与理由），
     * Log.d 不进 logcat，决策不能再是黑盒。日志里只记文字的长度 + 前 4 字，
     * 不记全文。
     * 1.13.13：不再数个数——1.13.10-1.13.12 数可见个数栽了五回（bounds 随滚动变、
     * 序号跨窗口撞号、未播个数随播放状态震荡、全部个数自己抖 3↔2↔1、高水位被
     * 视口容量 3 卡死、新语音再也超不过去）。改成按文字认语音：每条语音气泡
     * 正上方都有一段文字（消息文本转写，随滚动不变；主聊天和悬浮栏都一样），
     * 拿它当身份。按窗口记上次放过的文字（lastPlayedTranscript）；每次扫描
     * 找最下面那条语音 + 它的文字，文字变了、并且列表已滑到底（滚动事件
     * toIndex >= itemCount - 1；拿不到总数时退路是“文字刚进视口”——
     * 这条文字不在上次扫描可见的文字集合里）、并且按钮是“播放”，才点一次。
     * 文字没变（发文字消息、滚动、重组、播完变回“播放”）就不点。某窗口
     * 第一次见只记文字、不点。找不到文字的气泡跳过不点、记跳过理由。
     * 两条语音文字一字不差会撞号（第二条不放）——极少见，且是漏放不是重播。
     */
    private fun maybeAutoPlayVoice(e: AccessibilityEvent) {
        // 1.13.13：先记滚动/内容变化信号（不管这次事件会不会触发扫描），滑到底
        // 的判断和退路都需要它们。只记 Muse 包里的事件，别被别的 App 冲掉。
        val now = SystemClock.elapsedRealtime()
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && e.packageName?.toString() == MUSE_PKG) {
            lastScrollToIndex = e.toIndex
            lastScrollItemCount = e.itemCount
        }
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && e.packageName?.toString() == MUSE_PKG) {
            lastContentChangeMs = now
        }
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) return
        if (now - lastVoiceScanMs < VOICE_SCAN_DEBOUNCE_MS) return
        lastVoiceScanMs = now
        // 1.13.12：取不到前台 Activity 时沿用上一次确定的窗口，别拿 "?" 当新窗口
        // （否则状态会被拆到 "?" 和真窗口两个 key 下）；从来没确定过就跳过这次扫描。
        val activity = foregroundActivity ?: lastKnownVoiceWindow
        if (activity == null) {
            logAutoplay("?", -1, -1, "skip no-activity")
            return
        }
        lastKnownVoiceWindow = activity
        if (!App.prefs.museVoiceAutoPlay) {
            logAutoplay(activity, -1, -1, "skip switch-off")
            return
        }
        val pkg = foregroundPackage()
        if (pkg != MUSE_PKG) {
            logAutoplay(activity, -1, -1, "skip not-muse pkg=$pkg")
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            logAutoplay(activity, -1, -1, "skip no-root")
            return
        }
        val bubbles = findVoiceBubbles(root)
        val count = bubbles.size
        // 退路用的上一轮可见文字集合：先读出来，再记这一轮的。
        val prevVisible = lastScanTranscriptsByWindow[activity]
        lastScanTranscriptsByWindow[activity] = bubbles.mapNotNull { it.second }.toSet()
        if (bubbles.isEmpty()) {
            logAutoplay(activity, count, -1, "skip no-voice-bubbles")
            return
        }
        val bottom = bubbles.last()
        val transcript = bottom.second
        if (transcript == null) {
            logAutoplay(activity, count, -1, "skip no-transcript")
            return
        }
        val tag = transcriptTag(transcript)
        // 1.13.13 按文字认语音：第一次见某窗口只记文字、不点。
        val lastPlayed = lastPlayedTranscriptByWindow[activity]
        if (lastPlayed == null) {
            lastPlayedTranscriptByWindow[activity] = transcript
            logAutoplay(activity, count, -1, "baseline first-scan-in-window $tag")
            return
        }
        if (transcript == lastPlayed) {
            logAutoplay(activity, count, -1, "skip same-transcript $tag")
            return
        }
        // 滑到底的判断：滚动事件 toIndex >= itemCount - 1。LazyColumn 滚动事件
        // 是否报真实总数（itemCount）实现时没法在盒子上验——拿不到就走退路。
        val atBottom: Boolean? =
            if (lastScrollItemCount > 0 && lastScrollToIndex >= 0) lastScrollToIndex >= lastScrollItemCount - 1 else null
        if (atBottom == false) {
            logAutoplay(activity, count, -1, "skip not-at-bottom to=$lastScrollToIndex total=$lastScrollItemCount $tag")
            return
        }
        // 退路：拿不到总数时，光靠“文字变了”还不够——用户往上翻、把一条旧语音翻
        // 到视口底部时文字也会变。要求这条文字不在上次扫描可见的集合里（刚进
        // 视口的新语音才满足）；拿得到总数且已滑到底时不用这道闸。
        if (atBottom == null && prevVisible != null && transcript in prevVisible) {
            logAutoplay(activity, count, -1, "skip transcript-already-visible $tag")
            return
        }
        val play = bottom.first
        if (play.contentDescription?.toString()?.trim() != PLAY_DESC) {
            // 已经在播了（用户手动点了、或之前放过）：记成已放，播完变回“播放”
            // 后别再点它一遍。
            lastPlayedTranscriptByWindow[activity] = transcript
            logAutoplay(activity, count, -1, "skip bottom-most-already-playing $tag")
            return
        }
        val clicked = play.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) lastPlayedTranscriptByWindow[activity] = transcript
        logAutoplay(activity, count, -1, "click bottom-most clicked=$clicked atBottom=$atBottom $tag")
        if (clicked) Log.d(TAG, "muse voice autoplay: clicked play button $tag activity=$activity")
    }

    /** 1.13.13 日志用标签：只记文字长度 + 前 4 字，不记全文。 */
    private fun transcriptTag(t: String): String =
        "len=${t.length} head=${t.take(4).replace('\n', ' ').replace('\r', ' ')}"

    /** 1.13.10 自动播放文件日志：Log.d 不进 logcat，决策写文件（VoiceKey files 目录的
     * vk-autoplay-trace.log，和 vk-session-trace.log 分开），每次扫描一行——时间、
     * 窗口、可见语音气泡数、动作与跳过理由（1.13.13 起决策看文字不是个数，只记
     * 文字长度 + 前 4 字，不记全文）。下次再报“没放”直接读这个日志定。 */
    private fun logAutoplay(activity: String, count: Int, prev: Int, msg: String) {
        try {
            val f = java.io.File(filesDir, "vk-autoplay-trace.log")
            if (f.exists() && f.length() > 512 * 1024) f.delete()
            val wall = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(System.currentTimeMillis()))
            f.appendText("$wall activity=$activity count=$count prev=$prev $msg\n")
        } catch (_: Exception) {
        }
    }

    /**
     * 找全部语音气泡（播放/暂停按钮 + 每条正上方的文字转写），按从上到下排好
     * （最下面那条在最后），返回 (按钮, 文字?)——文字找不到时为 null。
     * 语音三件套（2026-11-15 uiautomator dump 实测，Muse 聊天独有，链接卡片没有）：
     * Button 内容描述“播放”或“暂停” + 同气泡里进度控件内容描述“音频进度” +
     * 时长 TextView（m:ss 格式，如 0:00）。气泡本身不一定是
     * hatch-message-assistant-latest（最新文本消息占着它），所以扫描全部助手气泡；
     * 多条语音在屏上时取最下面那条。
     * 1.13.11：按钮“播放”和“暂停”都算——只数未播的（只认“播放”）会随播放状态
     * 震荡（播着变“暂停”不算、播完变回“播放”又算，0→1 被误当成新消息，同一条
     * 53 秒被点 9 次）。数全部气泡，个数才不跟播放状态走。
     * 1.13.13：每条气泡正上方都有一段文字（消息文本转写，随滚动不变；主聊天
     * 和悬浮栏都一样，文字底边离播放按钮顶边约 66-90px）。文字是最靠近按钮
     * 上方的 TextView（助手文字左对齐 x≈96-144，用户文字右对齐 x≥354，
     * 所以只认左边缘 <= 300 的；时长 0:00、输入框提示、标题都不在候选里）。
     */
    private fun findVoiceBubbles(root: AccessibilityNodeInfo): List<Pair<AccessibilityNodeInfo, String?>> {
        val nodes = collectNodes(root)
        val b = Rect()
        // 候选文字：非空 TextView，去掉时长（m:ss）、只认左对齐的助手文字。
        val texts = nodes.mapNotNull { n ->
            if (n.className?.toString() != TEXT_VIEW_CLASS) return@mapNotNull null
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.isEmpty() || DURATION_RE.matches(t)) return@mapNotNull null
            n.getBoundsInScreen(b)
            if (b.left > TRANSCRIPT_LEFT_MAX) return@mapNotNull null
            Triple(t, b.bottom, b.left)
        }
        val found = mutableListOf<Triple<AccessibilityNodeInfo, String?, Int>>()
        for (n in nodes) {
            if (n.className?.toString() != BUTTON_CLASS) continue
            val desc = n.contentDescription?.toString()?.trim()
            if (desc != PLAY_DESC && desc != PAUSE_DESC) continue
            if (!isVoiceBubble(n)) continue
            n.getBoundsInScreen(b)
            val top = b.top
            // 文字：最靠近按钮上方的那段（底边在按钮顶边之上、且离得近），
            // 找不到就当没有（上层跳过不点）。
            val transcript = texts
                .filter { it.second <= top && top - it.second <= TRANSCRIPT_MAX_GAP }
                .maxByOrNull { it.second }
                ?.first
            found.add(Triple(n, transcript, top))
        }
        return found.sortedBy { it.third }.map { it.first to it.second }
    }

    /** 该播放/暂停按钮所在的气泡里是否同时有“音频进度”进度控件和 m:ss 时长文本 */
    private fun isVoiceBubble(button: AccessibilityNodeInfo): Boolean {
        var anc = button.parent
        var depth = 0
        while (anc != null && depth < 6) {
            if (subtreeHasVoiceSignature(anc)) return true
            anc = anc.parent
            depth++
        }
        return false
    }

    /** 子树里是否同时有内容描述“音频进度”的进度控件和 m:ss 格式的 TextView。
     * 1.13.11：进度控件只按 content-desc 认，别写死类名——未播时是 ProgressBar、
     * 播着时是 SeekBar（SeekBar 是 ProgressBar 的子类），只认类名会把播着的
     * 气泡漏掉。 */
    private fun subtreeHasVoiceSignature(root: AccessibilityNodeInfo): Boolean {
        var hasProgress = false
        var hasDuration = false
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < 200) {
            val n = stack.removeLast()
            seen++
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            val cls = n.className?.toString()
            if (n.contentDescription?.toString()?.trim() == PROGRESS_DESC) hasProgress = true
            if (cls == TEXT_VIEW_CLASS && DURATION_RE.matches(n.text?.toString().orEmpty())) hasDuration = true
            if (hasProgress && hasDuration) return true
        }
        return false
    }

    /**
     * 1.13.5 悬浮栏自动触发：只限 Muse 悬浮栏搜索框。聚焦或窗口切换时，如果当前聚焦框
     * 是悬浮栏搜索框，就自动开始听，说完自动上屏进这个框，不用按音量键、不用点悬浮球。
     * 认框改按前台 Activity（见 isMuseOverlaySearchBox）：聊天输入框在主聊天 Activity 里，
     * Activity 不同，天然排除——聊天里只手动，永不自动触发。框里已有用户自己打的字时不触发；
     * 自动会话结束后抑制再触发，直到框失焦或悬浮栏关掉再重新聚焦。开关 museOverlayInsert
     * 关着时不自动触发。
     * 注意：只负责自动开始听，不负责给框聚焦——点搜索框聚焦由小布模块做。
     * 1.13.5 起每次评估都记一行 `muse overlay check`（不管匹不匹配、开关开不开），
     * 这样日志里能直接看到它有没有跑、看到了什么，不用再猜。
     */
    private fun maybeAutoListenOverlay(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return
        if (!::session.isInitialized) return
        val pkg = e.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString()
        if (pkg != MUSE_PKG) {
            if (e.packageName != null) overlayAutoSuppressed = false
            return
        }
        val input = focusedInput()
        val matched = input != null && isMuseOverlaySearchBox(input)
        val action = focusedEditorAction()
        Log.d(TAG, "muse overlay check: activity=$foregroundActivity pkg=$pkg nodeClass=${input?.className} inputType=${input?.inputType} hint=${input?.hintText} desc=${input?.contentDescription} imeAction=$action editable=${input?.isEditable} matched=$matched suppressed=$overlayAutoSuppressed switch=${App.prefs.museOverlayInsert} active=${session.active} recording=${session.recording}")
        if (!App.prefs.museOverlayInsert) return
        if (session.active || session.recording) return
        if (input == null) {
            // 失焦 / 悬浮栏关掉：解除抑制，等下次重新聚焦
            overlayAutoSuppressed = false
            return
        }
        if (overlayAutoSuppressed) return
        // 明确限制：聊天输入框（IME 动作 SEND）永不自动触发（Activity 已把聊天排除，这是第二道）
        if (action == EditorInfo.IME_ACTION_SEND) return
        if (!nodeTextEmpty(input)) return
        if (!matched) return
        session.autoStartOverlay()
    }

    /** 自动触发的会话结束（上屏/失败/超时）后抑制再触发，直到框失焦或悬浮栏关掉 */
    fun suppressOverlayAuto() {
        overlayAutoSuppressed = true
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        if (::session.isInitialized) {
            session.abort(null)
            bubble.show(false)
            hud.detach()
        }
        super.onDestroy()
    }

    fun focusedInput(): AccessibilityNodeInfo? = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    /** 当前活动窗口的包名（用于判断是不是在 Muse 里） */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun isMuseForeground(): Boolean = foregroundPackage() == MUSE_PKG

    /**
     * Muse 悬浮栏搜索框识别（与聊天界面的输入框区分）。
     *
     * 1.13.5 起按前台 Activity 认，不再靠 IME 动作 / hint：
     * ① 前台 Activity 是 Muse 悬浮栏（类名含 HatchAssistOverlayActivity）；
     * ② 节点在 Muse 包里、类名是 android.widget.EditText、isEditable。
     * 聊天输入框在主聊天 Activity（AuraMainActivity）里，Activity 不同，天然排除。
     * IME 动作 / hint 只作加分信号（记在 maybeAutoListenOverlay 的 `muse overlay check`
     * 日志里，方便核对），不再是必要条件——实测这个框的 a11y 节点 inputType=0、hint 为空，
     * 1.13.4 那两条信号永远匹配不上，自动触发根本启动不了。
     * 1.13.4 起只用来选上屏路径（手动触发）与自动触发的认框，不再拒绝启动。
     */
    fun isMuseOverlaySearchBox(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEditable || !node.isVisibleToUser) return false
        if (node.packageName?.toString() != MUSE_PKG) return false
        if (node.className?.toString() != EDIT_TEXT_CLASS) return false
        val activity = foregroundActivity ?: return false
        if (foregroundActivityPkg != MUSE_PKG) return false
        return activity.contains(OVERLAY_ACTIVITY)
    }

    /** 当前聚焦的编辑框在输入法侧的动作；未聚焦或无输入连接时返回 -1 */
    private fun focusedEditorAction(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return -1
        val im = inputMethod ?: return -1
        if (!im.currentInputStarted) return -1
        return (im.currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
    }

    /** 框里是否没有用户自己打的字（空框、只显示占位提示，或输入连接里的正文为空） */
    fun nodeTextEmpty(node: AccessibilityNodeInfo): Boolean {
        val raw = node.text?.toString().orEmpty()
        return raw.isEmpty() || node.isShowingHintText || raw == node.hintText?.toString() || editorEmpty()
    }

    /** 微信等会对无障碍隐藏控件树，拿不到输入框时以键盘是否弹出为准 */
    fun typing(): Boolean = focusedInput() != null || windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    /** 微信等拿不到输入框节点时，经系统给无障碍服务的输入连接提交文字（Android 13+） */
    fun commit(text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val im = inputMethod ?: return false
        if (!im.currentInputStarted) return false
        val ic = im.currentInputConnection ?: return false
        ic.commitText(text, 1, null)
        return true
    }

    fun editorEmpty(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val im = inputMethod ?: return false
        if (!im.currentInputStarted) return false
        return im.currentInputConnection?.getSurroundingText(1, 1, 0)?.text?.isEmpty() == true
    }

    /** 上屏后发送：发送按钮常在有字后才出现，稍等重试；找不到再走输入框的发送动作或回车 */
    fun send(node: AccessibilityNodeInfo?, tries: Int = 3) {
        main.postDelayed({
            when {
                clickSend(node) -> {}
                tries > 1 -> send(node, tries - 1)
                !editorSend() -> {
                    Log.w(TAG, "send: send button not found, text kept in editor")
                    hud.info("没找到发送按钮，已上屏")
                }
            }
        }, 150L * (4 - tries))
    }

    /**
     * 找发送按钮，三层后路：① 内容描述含“发送” ② 文本等于“发送” ③ 输入框附近的 ImageButton；
     * 命中后沿父级找可点的节点再点。都找不到返回 false，由上层走输入法发送动作/回车。
     */
    private fun clickSend(node: AccessibilityNodeInfo?): Boolean {
        val root = node?.window?.root ?: rootInActiveWindow ?: return false
        val hit = findSendByDesc(root)
            ?: findSendByText(root)
            ?: findSendNearInput(root, node)
            ?: return false
        var n: AccessibilityNodeInfo? = hit
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    /** ① 内容描述含“发送”/“Send” */
    private fun findSendByDesc(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        collectNodes(root).firstOrNull { n ->
            SEND.any { w -> n.contentDescription?.toString()?.contains(w, ignoreCase = true) == true }
        }

    /** ② 文本等于“发送”/“Send” */
    private fun findSendByText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        SEND.firstNotNullOfOrNull { w ->
            root.findAccessibilityNodeInfosByText(w).firstOrNull { n ->
                n.isVisibleToUser && n.isEnabled && !n.isEditable &&
                    listOf(n.text, n.contentDescription).any { it?.toString()?.trim().equals(w, ignoreCase = true) }
            }
        }

    /** ③ 输入框附近（上下 200px 内）的 ImageButton/ImageView，取离输入框最近的一个 */
    private fun findSendNearInput(root: AccessibilityNodeInfo, input: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val r = Rect()
        input?.getBoundsInScreen(r) ?: return null
        val b = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE
        for (n in collectNodes(root)) {
            val cls = n.className?.toString() ?: continue
            if (cls != "android.widget.ImageButton" && cls != "android.widget.ImageView") continue
            n.getBoundsInScreen(b)
            val vGap = when {
                b.top > r.bottom -> b.top - r.bottom
                r.top > b.bottom -> r.top - b.bottom
                else -> 0
            }
            if (vGap > 200) continue
            val hGap = if (b.left >= r.right) b.left - r.right else if (b.right <= r.left) r.left - b.right else 0
            val score = vGap * 10 + hGap
            if (score < bestScore) {
                bestScore = score
                best = n
            }
        }
        return best
    }

    /** 收集窗口树里可见、可用、不可编辑的节点（上限 500，避免大树耗时） */
    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < 500) {
            val n = stack.removeLast()
            seen++
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            if (n.isVisibleToUser && n.isEnabled && !n.isEditable) out.add(n)
        }
        return out
    }

    private fun editorSend(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val im = inputMethod ?: return false
        if (!im.currentInputStarted) return false
        val ic = im.currentInputConnection ?: return false
        val action = (im.currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action in ACTIONS) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        return true
    }

    fun refresh() {
        if (instance != this) return
        bubble.show(App.prefs.bubble && (App.prefs.bubbleAlways || session.active || typing()))
    }

    fun onSessionChanged() {
        bubble.active = session.recording
        hud.update()
        refresh()
    }

    fun onLevel(v: Float) {
        bubble.level = v
        hud.level(v)
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        recorder?.let { return record(it, e) }
        if (!App.prefs.volumeKey) return false
        val keys = App.prefs.holdKeys
        if (keys != chordKeys) {
            chordKeys = keys
            chord = Chord(keys)
        }
        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP && e.keyCode !in keys) {
            if (!session.picking || session.recording) return false
            if (e.action == KeyEvent.ACTION_UP) session.next()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN && e.action != KeyEvent.ACTION_UP) return false
        val c = chord
        return c.key(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.repeatCount > 0, { session.active || typing() }) { out ->
            when (out) {
                Chord.Arm -> {
                    session.prewarm()
                    val g = c.gen
                    main.postDelayed({ if (c.fire(g)) session.holdStart() }, 300)
                }
                Chord.Disarm -> {}
                Chord.Release -> session.holdEnd()
                is Chord.Short -> when {
                    out.act && session.picking -> session.choose(session.sel)
                    out.act && session.active -> session.tap()
                    else -> out.replay.forEach {
                        getSystemService(AudioManager::class.java).adjustSuggestedStreamVolume(
                            if (Keys.volume[it] == 1) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                            AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI,
                        )
                    }
                }
            }
        }
    }

    /** 按住一组键，松开任一个时记录本轮按过的全部键；不合法则继续等待。返回键交给系统并取消录制 */
    private fun record(done: (List<Int>?) -> Unit, e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code in Keys.system) {
            if (code == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP && recHeld.isEmpty()) finishRecord(done, null)
            return false
        }
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0 && recHeld.add(code) && code !in recPeak) recPeak += code
            KeyEvent.ACTION_UP -> {
                recHeld -= code
                val keys = recPeak.sortedBy { it !in Keys.modifiers }
                val map = KeyCharacterMap.load(e.deviceId)
                if (Keys.valid(keys) { it !in Keys.modifiers && map.isPrintingKey(it) }) finishRecord(done, keys)
                else recPeak.retainAll(recHeld)
            }
        }
        return true
    }

    private fun finishRecord(done: (List<Int>?) -> Unit, keys: List<Int>?) {
        recorder = null
        if (keys != null) App.prefs.holdKeys = keys
        done(keys)
    }

    companion object {
        var instance: VoiceService? = null
            private set
        private const val TAG = "VoiceKey"
        /** Muse 包名：悬浮栏搜索框与聊天输入框同属此包，区分靠 isMuseOverlaySearchBox 的前台 Activity */
        private const val MUSE_PKG = "com.facebook.aura"
        /** Muse 语音会话悬浮栏 Activity 的类名片段：前台是它才认作悬浮栏搜索框 */
        private const val OVERLAY_ACTIVITY = "HatchAssistOverlayActivity"
        /** 悬浮栏搜索框在无障碍层暴露的类名（小布模块 12:40 实测） */
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        /** 1.13.8 语音自动播放：播放按钮与进度条的类名/内容描述（2026-11-15 dump 实测的固定字面串） */
        private const val BUTTON_CLASS = "android.widget.Button"
        private const val PLAY_DESC = "播放"
        private const val PAUSE_DESC = "暂停"
        private const val PROGRESS_DESC = "音频进度"
        private const val TEXT_VIEW_CLASS = "android.widget.TextView"
        private val DURATION_RE = Regex("^\\d+:\\d{2}$")
        private const val VOICE_SCAN_DEBOUNCE_MS = 500L
        /** 1.13.13 转写文字离播放按钮顶边的最大距离（px；实测约 66-90px，留足余量）。 */
        private const val TRANSCRIPT_MAX_GAP = 500
        /** 1.13.13 转写文字的左边缘上限（px；助手文字 x≈96-144，用户文字右对齐 x≥354）。 */
        private const val TRANSCRIPT_LEFT_MAX = 300
        private val SEND = listOf("发送", "Send")
        private val ACTIONS = setOf(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE)

        fun enabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.equals("${ctx.packageName}/${VoiceService::class.java.name}", true) }
        }
    }
}
