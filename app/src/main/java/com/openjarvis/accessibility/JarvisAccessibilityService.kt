package com.openjarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY

            notificationTimeout = 100
        }

        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /*
     * OPEN APP
     */
    fun openAppByPackage(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /*
     * OPEN APP BY LABEL
     */
    fun openAppByLabel(label: String): Boolean {
        val pm = packageManager

        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps = pm.queryIntentActivities(intent, 0)
        val normalizedLabel = label.lowercase().trim()

        for (app in apps) {
            val appLabel = app.loadLabel(pm).toString().lowercase()

            if (
                appLabel.contains(normalizedLabel) ||
                normalizedLabel.contains(appLabel)
            ) {
                return openAppByPackage(
                    app.activityInfo.packageName
                )
            }
        }

        return false
    }

    /*
     * TAP BY TEXT
     */
    fun tapByText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        return try {
            val node = findNodeByText(rootNode, text)

            if (node != null) {
                val result = performClickOnNode(node)

                node.recycle()
                result
            } else {
                false
            }
        } finally {
            rootNode.recycle()
        }
    }

    /*
     * TAP BY CONTENT DESCRIPTION
     */
    fun tapByContentDescription(description: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        return try {
            val node = findNodeByContentDescription(
                rootNode,
                description
            )

            if (node != null) {
                val result = performClickOnNode(node)

                node.recycle()
                result
            } else {
                false
            }
        } finally {
            rootNode.recycle()
        }
    }

    /*
     * FIND + FOCUS INPUT FIELD
     *
     * This is important for Copilot.
     */
    fun focusInputByText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        return try {
            val node = findNodeByTextAnywhere(rootNode, text)

            if (node != null) {
                val focused = focusInputNode(node)

                node.recycle()
                focused
            } else {
                false
            }
        } finally {
            rootNode.recycle()
        }
    }

    /*
     * FOCUS FIRST AVAILABLE INPUT
     */
    fun focusFirstInput(): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        return try {
            val input = findFirstInputNode(rootNode)

            if (input != null) {
                val result = input.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS
                )

                input.recycle()
                result
            } else {
                false
            }
        } finally {
            rootNode.recycle()
        }
    }

    /*
     * TYPE TEXT
     *
     * First tries the currently focused input.
     * If none exists, tries to find an editable field.
     */
    fun typeText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        return try {

            var target =
                rootNode.findFocus(
                    AccessibilityNodeInfo.FOCUS_INPUT
                )

            if (target == null) {
                target = findFirstInputNode(rootNode)
            }

            if (target == null) {
                false
            } else {

                target.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS
                )

                val arguments = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo
                            .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }

                val result = target.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    arguments
                )

                target.recycle()

                result
            }

        } finally {
            rootNode.recycle()
        }
    }

    /*
     * TYPE TEXT INTO A SPECIFIC INPUT
     */
    fun typeTextIntoInput(
        hintOrText: String,
        text: String
    ): Boolean {

        val rootNode = rootInActiveWindow ?: return false

        return try {

            val node = findInputByText(
                rootNode,
                hintOrText
            )

            if (node == null) {
                false
            } else {

                node.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS
                )

                val arguments = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo
                            .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }

                val result = node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    arguments
                )

                node.recycle()

                result
            }

        } finally {
            rootNode.recycle()
        }
    }

    /*
     * PRESS ENTER
     *
     * Useful as a fallback when an AI app submits
     * a message through the keyboard.
     */
    fun pressEnter(): Boolean {
        return try {
            val rootNode = rootInActiveWindow ?: return false

            val input = rootNode.findFocus(
                AccessibilityNodeInfo.FOCUS_INPUT
            )

            if (input != null) {
                val result = input.performAction(
                    AccessibilityNodeInfo.ACTION_IME_ENTER
                )

                input.recycle()
                rootNode.recycle()

                result
            } else {
                rootNode.recycle()
                false
            }

        } catch (e: Exception) {
            false
        }
    }

    /*
     * GLOBAL NAVIGATION
     */
    fun pressBack(): Boolean =
        performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean =
        performGlobalAction(GLOBAL_ACTION_HOME)

    fun pressRecents(): Boolean =
        performGlobalAction(GLOBAL_ACTION_RECENTS)

    /*
     * CLICK HELPER
     *
     * Some UI elements are not directly clickable.
     * We walk upward through parents until we find
     * a clickable container.
     */
    private fun performClickOnNode(
        node: AccessibilityNodeInfo
    ): Boolean {

        if (node.isClickable && node.isVisibleToUser) {
            return node.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            )
        }

        var parent = node.parent

        while (parent != null) {

            if (
                parent.isClickable &&
                parent.isVisibleToUser
            ) {

                val result = parent.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )

                parent.recycle()

                return result
            }

            val next = parent.parent

            parent.recycle()

            parent = next
        }

        return false
    }

    /*
     * FIND TEXT
     */
    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {

        val normalizedText = text.lowercase().trim()

        return findNodeByTextAnywhere(
            root,
            normalizedText,
            true
        )
    }

    /*
     * FIND TEXT ANYWHERE
     */
    private fun findNodeByTextAnywhere(
        root: AccessibilityNodeInfo,
        text: String,
        requireClickable: Boolean = false
    ): AccessibilityNodeInfo? {

        val normalizedText = text.lowercase().trim()

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(root)

        while (queue.isNotEmpty()) {

            val node = queue.removeFirst()

            val nodeText =
                node.text?.toString()?.lowercase()?.trim()

            val contentDescription =
                node.contentDescription
                    ?.toString()
                    ?.lowercase()
                    ?.trim()

            val matches =
                nodeText?.contains(normalizedText) == true ||
                contentDescription?.contains(normalizedText) == true

            if (
                matches &&
                node.isVisibleToUser &&
                (!requireClickable || node.isClickable)
            ) {
                return node
            }

            for (i in 0 until node.childCount) {

                val child = node.getChild(i)

                if (child != null) {
                    queue.add(child)
                }
            }

            if (node !== root) {
                node.recycle()
            }
        }

        return null
    }

    /*
     * FIND CONTENT DESCRIPTION
     */
    private fun findNodeByContentDescription(
        root: AccessibilityNodeInfo,
        description: String
    ): AccessibilityNodeInfo? {

        val normalized =
            description.lowercase().trim()

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(root)

        while (queue.isNotEmpty()) {

            val node = queue.removeFirst()

            val desc =
                node.contentDescription
                    ?.toString()
                    ?.lowercase()
                    ?.trim()

            if (
                desc?.contains(normalized) == true &&
                node.isVisibleToUser
            ) {
                return node
            }

            for (i in 0 until node.childCount) {

                node.getChild(i)?.let { child ->
                    queue.add(child)
                }
            }

            if (node !== root) {
                node.recycle()
            }
        }

        return null
    }

    /*
     * FIND INPUT FIELD
     */
    private fun findFirstInputNode(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(root)

        while (queue.isNotEmpty()) {

            val node = queue.removeFirst()

            val isInput =
                node.isEditable ||
                node.className?.toString()
                    ?.contains("EditText", true) == true

            if (
                isInput &&
                node.isVisibleToUser &&
                node.isEnabled
            ) {
                return node
            }

            for (i in 0 until node.childCount) {

                node.getChild(i)?.let { child ->
                    queue.add(child)
                }
            }

            if (node !== root) {
                node.recycle()
            }
        }

        return null
    }

    /*
     * FIND INPUT BY HINT / TEXT / DESCRIPTION
     */
    private fun findInputByText(
        root: AccessibilityNodeInfo,
        searchText: String
    ): AccessibilityNodeInfo? {

        val normalized =
            searchText.lowercase().trim()

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(root)

        while (queue.isNotEmpty()) {

            val node = queue.removeFirst()

            val text =
                node.text?.toString()
                    ?.lowercase()
                    ?.trim()

            val hint =
                node.hintText?.toString()
                    ?.lowercase()
                    ?.trim()

            val description =
                node.contentDescription
                    ?.toString()
                    ?.lowercase()
                    ?.trim()

            val isInput =
                node.isEditable ||
                node.className?.toString()
                    ?.contains("EditText", true) == true

            val matches =
                text?.contains(normalized) == true ||
                hint?.contains(normalized) == true ||
                description?.contains(normalized) == true

            if (
                isInput &&
                matches &&
                node.isVisibleToUser &&
                node.isEnabled
            ) {
                return node
            }

            for (i in 0 until node.childCount) {

                node.getChild(i)?.let { child ->
                    queue.add(child)
                }
            }

            if (node !== root) {
                node.recycle()
            }
        }

        return null
    }

    /*
     * FOCUS INPUT NODE
     */
    private fun focusInputNode(
        node: AccessibilityNodeInfo
    ): Boolean {

        if (
            !node.isVisibleToUser ||
            !node.isEnabled
        ) {
            return false
        }

        return node.performAction(
            AccessibilityNodeInfo.ACTION_FOCUS
        )
    }

    companion object {

        var instance: JarvisAccessibilityService? = null
            private set
    }
}
