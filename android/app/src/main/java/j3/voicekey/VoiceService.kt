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
    /** 1.13.8 Muse 语音自动播放：上次扫描时间（500ms 防抖）与已放过的语音气泡序号集合；
     * 1.13.9 起序号是 key（从上往下 0 起），不用 bounds——bounds 会随聊天滚动变，播完
     * 变回“播放”后对不上旧位置就重播；序号不随滚动变。 */
    private var lastVoiceScanMs = 0L
    private val playedVoiceIndex = LinkedHashSet<Int>()

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
     * 新消息出现（内容变化/滚动）时扫描一次，带 500ms 防抖；找到未播过的语音气泡
     * 就点它的播放按钮。只认未播状态（内容描述恰好是“播放”，播放中大概率变“暂停”
     * 就不认）；播完变回“播放”时靠已放集合不再重播；新气泡（序号更大）才再放。
     * 1.13.9：已放集合的 key 是语音气泡在当前全部语音气泡里的序号（从上往下 0 起），
     * 不用 bounds——bounds 会随聊天滚动变，播完对不上旧位置就重播；序号不随滚动变。
     * 输入框、发送箭头、悬浮栏那条路都不碰。
     */
    private fun maybeAutoPlayVoice(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) return
        if (!App.prefs.museVoiceAutoPlay) return
        if (foregroundPackage() != MUSE_PKG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastVoiceScanMs < VOICE_SCAN_DEBOUNCE_MS) return
        lastVoiceScanMs = now
        val root = rootInActiveWindow ?: return
        val plays = findVoicePlayButtons(root)
        if (plays.isEmpty()) return
        val index = plays.size - 1
        if (index in playedVoiceIndex) return
        val play = plays[index]
        if (play.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            playedVoiceIndex.add(index)
            while (playedVoiceIndex.size > VOICE_PLAYED_CAP) playedVoiceIndex.remove(playedVoiceIndex.first())
            Log.d(TAG, "muse voice autoplay: clicked play button index=$index count=${plays.size}")
        }
    }

    /**
     * 找全部语音气泡的播放按钮，按从上到下排好（序号 0 起，最下面那条序号最大）。
     * 语音三件套（2026-11-15 uiautomator dump 实测，Muse 聊天独有，链接卡片没有）：
     * Button 内容描述“播放” + 同气泡里 ProgressBar 内容描述“音频进度” +
     * 时长 TextView（m:ss 格式，如 0:00）。气泡本身不一定是
     * hatch-message-assistant-latest（最新文本消息占着它），所以扫描全部助手气泡；
     * 多条未播语音在屏上时取最下面那条（序号最大）。
     * 1.13.9：序号当已放集合的 key——序号不随聊天滚动变（新消息进来旧气泡序号不变；
     * 新语音来了它是序号最大的那条），bounds 当 key 会随滚动变、播完变回“播放”后
     * 对不上旧位置就重播。
     */
    private fun findVoicePlayButtons(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        val b = Rect()
        for (n in collectNodes(root)) {
            if (n.className?.toString() != BUTTON_CLASS) continue
            if (n.contentDescription?.toString()?.trim() != PLAY_DESC) continue
            if (!isVoiceBubble(n)) continue
            n.getBoundsInScreen(b)
            found.add(n to b.top)
        }
        return found.sortedBy { it.second }.map { it.first }
    }

    /** 该播放按钮所在的气泡里是否同时有“音频进度”进度条和 m:ss 时长文本 */
    private fun isVoiceBubble(play: AccessibilityNodeInfo): Boolean {
        var anc = play.parent
        var depth = 0
        while (anc != null && depth < 6) {
            if (subtreeHasVoiceSignature(anc)) return true
            anc = anc.parent
            depth++
        }
        return false
    }

    /** 子树里是否同时有内容描述“音频进度”的 ProgressBar 和 m:ss 格式的 TextView */
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
            if (cls == PROGRESS_CLASS && n.contentDescription?.toString()?.trim() == PROGRESS_DESC) hasProgress = true
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
        private const val PROGRESS_CLASS = "android.widget.ProgressBar"
        private const val PROGRESS_DESC = "音频进度"
        private const val TEXT_VIEW_CLASS = "android.widget.TextView"
        private val DURATION_RE = Regex("^\\d+:\\d{2}$")
        private const val VOICE_SCAN_DEBOUNCE_MS = 500L
        private const val VOICE_PLAYED_CAP = 100
        private val SEND = listOf("发送", "Send")
        private val ACTIONS = setOf(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE)

        fun enabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.equals("${ctx.packageName}/${VoiceService::class.java.name}", true) }
        }
    }
}
