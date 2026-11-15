package j3.voicekey

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    /** 小布全自动：发完一次后抑制，直到输入框失焦再重新聚焦才允许下一次自动触发 */
    private var autoSuppressed = false

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
        main.removeCallbacks(check)
        main.postDelayed(check, 150)
        maybeAutoListen(e)
    }

    /**
     * 小布全自动触发点：只在 Muse（com.facebook.aura）里触发，别的应用的输入框绝不自动听。
     * 聚焦或窗口切换时：已有可编辑输入框就直接听；还没聚焦就先让它聚焦（不行再点一下），
     * 点后的聚焦事件会再进来一次。框里已有用户自己打的字时不自动听，避免追加到半句话后面。
     */
    private fun maybeAutoListen(e: AccessibilityEvent) {
        if (!App.prefs.autoMuseFlow || !::session.isInitialized) return
        if (e.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return
        val pkg = e.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString()
        if (pkg != MUSE_PKG) {
            if (e.packageName != null) autoSuppressed = false
            return
        }
        if (session.active || session.recording) return
        val input = focusedInput()
        if (input == null) {
            // 失焦了：解除抑制，等下次重新聚焦；顺便找框聚焦（点后的聚焦事件会再进来）
            autoSuppressed = false
            rootInActiveWindow?.let { findEditable(it) }?.let { node ->
                if (!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            return
        }
        if (autoSuppressed) return
        val txt = input.text?.toString().orEmpty()
        if (txt.isNotEmpty() && !input.isShowingHintText) return
        session.beginAuto()
    }

    /** 窗口树里找第一个可见的可编辑节点（只找，不写） */
    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < 300) {
            val n = stack.removeLast()
            seen++
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /**
     * 小布全自动：上屏成功后等界面稳定一下（500ms，send 内部首试还有约 450ms，共约 1 秒）
     * 再点发送；同时抑制下一次自动触发，直到输入框失焦再重新聚焦。
     */
    fun autoSend(node: AccessibilityNodeInfo?) {
        autoSuppressed = true
        main.postDelayed({ send(node) }, 500)
    }

    /** 自动流程结束且无有效上屏（没听清/上屏失败/超时）：抑制到下一次失焦再聚焦，避免反复自动听 */
    fun suppressAutoFlow() {
        autoSuppressed = true
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
                    Log.w(TAG, "autoSend: send button not found, text kept in editor")
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
        /** 小布全自动只在 Muse 里触发 */
        private const val MUSE_PKG = "com.facebook.aura"
        private val SEND = listOf("发送", "Send")
        private val ACTIONS = setOf(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE)

        fun enabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return list.split(':').any { it.equals("${ctx.packageName}/${VoiceService::class.java.name}", true) }
        }
    }
}
