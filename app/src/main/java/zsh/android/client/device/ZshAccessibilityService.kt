package zsh.android.client.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 无障碍设备能力（对齐 DSHA：读屏、点按、输入、滚动、返回）。
 * 仅响应本机桥接请求，不订阅系统数据、不上传任何内容。
 */
class ZshAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------- 读屏

    fun dumpActiveWindow(): JSONObject? {
        val root = rootInActiveWindow ?: return null
        val obj = JSONObject()
        obj.put("package", root.packageName ?: JSONObject.NULL)
        obj.put("nodes", JSONArray(dumpNode(root, depth = 0)))
        root.recycle()
        return obj
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int): List<JSONObject> {
        if (depth > 25) return emptyList()
        val list = mutableListOf<JSONObject>()
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        list += JSONObject()
            .put("class", node.className ?: JSONObject.NULL)
            .put("text", node.text ?: JSONObject.NULL)
            .put("desc", node.contentDescription ?: JSONObject.NULL)
            .put("viewId", node.viewIdResourceName ?: JSONObject.NULL)
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("scrollable", node.isScrollable)
            .put("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                list += dumpNode(child, depth + 1)
                child.recycle()
            }
        }
        return list
    }

    // ---------------------------------------------------------------- 操作

    /** 在当前窗口里按包含文本查找可点击节点并点击（找不到节点本身时向上找最近的可点击祖先）。 */
    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targets = root.findAccessibilityNodeInfosByText(text)
        val result = targets.firstOrNull { it.isClickable || it.findClickableAncestor() != null }?.let { node ->
            val clickTarget = if (node.isClickable) node else node.findClickableAncestor()
            clickTarget?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        } ?: false
        targets.forEach { it.recycle() }
        root.recycle()
        return result
    }

    private fun AccessibilityNodeInfo.findClickableAncestor(): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = this
        repeat(6) {
            cur = cur?.parent ?: return null
            if (cur?.isClickable == true) return cur
        }
        return null
    }

    fun typeIntoFocused(text: String): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun performGlobal(action: Int): Boolean = performGlobalAction(action)

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        var found = false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && !found) {
            val node = queue.removeFirst()
            if (node.isScrollable) {
                found = node.performAction(
                    if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
                )
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        root.recycle()
        return found
    }

    companion object {
        const val GLOBAL_BACK = GLOBAL_ACTION_BACK
        const val GLOBAL_HOME = GLOBAL_ACTION_HOME

        @Volatile
        var instance: ZshAccessibilityService? = null
            private set
    }

    init {
        instance = this
    }
}
