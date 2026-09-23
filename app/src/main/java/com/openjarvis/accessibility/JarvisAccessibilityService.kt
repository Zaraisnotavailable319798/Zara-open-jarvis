package com.openjarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

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
        // Events are currently handled on demand.
    }

    override fun onInterrupt() {
        // Required by AccessibilityService.
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /*
     * OPEN APP BY PACKAGE
     */
    fun openAppByPackage(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
                ?: return false

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)

            true
        } catch (e: Exception) {
            false
        }
    }

    /*
     * OPEN APP BY LABEL
     */
    fun openAppByLabel(label: String): Boolean {
        return try {
            val pm = packageManager

            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val apps = pm.queryIntentActivities(intent, 0)
            val normalizedLabel = label.lowercase().trim()

            for (app in apps) {
                val appLabel =
                    app.loadLabel(pm).toString().lowercase().trim()

                if (
                    appLabel.contains(normalizedLabel) ||
                    normalizedLabel.contains(appLabel)
                ) {
                    return openAppByPackage(
                        app.activityInfo.packageName
                    )
                }
            }

            false
        } catch (e: Exception) {
            false
        }
    }

    /*
     * TAP BY TEXT
     */
    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false

        return try {
            val node = findNodeByText(root, text)

            if (node != null) {
                performClickOnNode(node)
            } else {
                false
            }
        } finally {
            root.recycle()
        }
    }

    /*
     * TAP BY CONTENT DESCRIPTION
     */
    fun tapByContentDescription(description: String): Boolean {
        val root = rootInActiveWindow ?: return false

        return try {
            val node = findNodeByContentDescription(
                root,
                description
            )

            if (node != null) {
                performClickOnNode(node)
            } else {
                false
            }
        } finally {
            root.recycle()
        }
    }

    /*
     * FOCUS INPUT BY TEXT / HINT / DESCRIPTION
     */
    fun focusInputByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false

        return try {
            val node = findInputByText(root, text)

            if (node != null) {
                focusInputNode(node)
            } else {
                false
            }
        } finally {
            root.recycle()
        }
    }

    /*
     * FOCUS FIRST AVAILABLE INPUT
     */
    fun focusFirstInput(): Boolean {
        val root = rootInActiveWindow ?: return false

        return try {
            val input = findFirstInputNode(root)

            if (input != null) {
                focusInputNode(input)
            } else {
                false
            }
        } finally {
            root.recycle()
        }
    }

    /*
     * TYPE TEXT
     *
     * Tries the currently focused input first.
     * Then searches for an editable field.
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false

        return try {
            var target =
                root.findFocus(
                    AccessibilityNodeInfo.FOCUS_INPUT
                )

            if (target == null) {
                target = findFirstInputNode(root)
            }

            if (target == null) {
                false
            } else {
                typeIntoNode(target, text)
            }
        } finally {
            root.recycle()
        }
    }

    /*
     * TYPE TEXT INTO SPECIFIC INPUT
     */
    fun typeTextIntoInput(
        hintOrText: String,
        text: String
    ): Boolean {
        val root = rootInActiveWindow ?: return false

        return try {
            val node = findInputByText(
                root,
                hintOrText
            )

            if (node != null) {
                typeIntoNode(node, text)
            } else {
                false
            }
        } finally {
            root.recycle()
        }
    }

    /*
     * TYPE INTO NODE
     *
     * First tries click + focus.
     * Then attempts ACTION_SET_TEXT.
     */
    private fun typeIntoNode(
        node: AccessibilityNodeInfo,
        text: String
    ): Boolean {

        return try {

            if (!node.isVisibleToUser || !node.isEnabled) {
                return false
            }

            /*
             * Some apps require the field to be clicked
             * before it accepts focus/text.
             */
            if (node.isClickable) {
                node.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                )
            }

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

            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                arguments
            )
        } finally {
            node.recycle()
        }
    }

    /*
     * PRESS ENTER
     */
    fun pressEnter(): Boolean {
        val root = rootInActiveWindow ?: return false

        return try {
            val input = root.findFocus(
                AccessibilityNodeInfo.FOCUS_INPUT
            )

            if (input != null) {
                try {
                    input.performAction(
                        AccessibilityNodeInfo.ACTION_IME_ENTER
                    )
                } finally {
                    input.recycle()
                }
            } else {
                false
            }
        } finally {
            root.recycle()
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
     * If the matched node itself is not clickable,
     * walks upward to find a clickable parent.
     */
    private fun performClickOnNode(
        node: AccessibilityNodeInfo
    ): Boolean {

        try {
            if (
                node.isClickable &&
                node.isVisibleToUser
            ) {
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
                    return parent.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                }

                parent = parent.parent
            }

            return false

        } finally {
            node.recycle()
        }
    }

    /*
     * FIND TEXT
     */
    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {

        return findNodeByTextAnywhere(
            root,
            text,
            true
        )
    }

    /*
     * FIND TEXT / CONTENT DESCRIPTION
     *
     * Uses a fresh accessibility snapshot traversal.
     *
     * Important:
     * We do NOT recycle nodes while they are still
     * needed by the traversal.
     */
    private fun findNodeByTextAnywhere(
        root: AccessibilityNodeInfo,
        text: String,
        requireClickable: Boolean = false
    ): AccessibilityNodeInfo? {

        val normalizedText =
            text.lowercase().trim()

        val queue =
            ArrayDeque<AccessibilityNodeInfo>()

        queue.add(root)

        while (queue.isNotEmpty()) {

            val node = queue.removeFirst()

            val nodeText =
                node.text
                    ?.toString()
                    ?.lowercase()
                    ?.trim()

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
                node.getChild(i)?.let { child ->
                    queue.add(child)
                }
            }

            /*
             * Don't recycle here.
             *
             * The returned node and queued nodes may still
             * be referenced during traversal.
             */
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
        }

        return null
    }

    /*
     * FIND FIRST INPUT
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
                node.className
                    ?.toString()
                    ?.contains(
                        "EditText",
                        ignoreCase = true
                    ) == true

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
                node.text
                    ?.toString()
                    ?.lowercase()
                    ?.trim()

            val hint =
                node.hintText
                    ?.toString()
                    ?.lowercase()
                    ?.trim()

            val description =
                node.contentDescription
                    ?.toString()
                    ?.lowercase()
                    ?.trim()

            val isInput =
                node.isEditable ||
                node.className
                    ?.toString()
                    ?.contains(
                        "EditText",
                        ignoreCase = true
                    ) == true

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
        }

        return null
    }

    /*
     * FOCUS INPUT NODE
     */
    private fun focusInputNode(
        node: AccessibilityNodeInfo
    ): Boolean {

        return try {

            if (
                !node.isVisibleToUser ||
                !node.isEnabled
            ) {
                false
            } else {

                /*
                 * Click first if possible.
                 */
                if (node.isClickable) {
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                }

                node.performAction(
                    AccessibilityNodeInfo.ACTION_FOCUS
                )
            }

        } finally {
            node.recycle()
        }
    }

    companion object {

        var instance: JarvisAccessibilityService? = null
            private set
    }
}
