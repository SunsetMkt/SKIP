package com.android.skip.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.android.skip.SKIPApp
import com.android.skip.SKIP_LAYOUT_INSPECT
import com.android.skip.SKIP_PERMIT_NOTICE
import com.android.skip.handler.BoundsHandler
import com.android.skip.handler.IdNodeHandler
import com.android.skip.handler.TextNodeHandler
import com.android.skip.manager.AnalyticsManager
import com.android.skip.manager.ToastManager
import com.android.skip.manager.WhitelistManager
import com.android.skip.utils.AccessibilityUtils
import com.android.skip.utils.Constants
import com.android.skip.utils.DataStoreUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.ServiceUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

data class MyNode(val node: AccessibilityNodeInfo, val depth: Int)

data class MyNodeChild(val depth: Int, val childCount: Int, var className: String? = null, var text: String?=null, var viewIdResourceName: String?=null)

class MyAccessibilityService : AccessibilityService() {
    private val textNodeHandler = TextNodeHandler()
    private val idNodeHandler = IdNodeHandler()
    private val boundsHandler = BoundsHandler()
    private var isLayoutInspect = false
    private var layoutInspectClassName: String? = null
    private val foregroundAccessibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.FOREGROUND_ACCESSIBILITY_RECEIVER_ACTION) {
                val enabled = intent.getBooleanExtra(Constants.FOREGROUND_ACCESSIBILITY_RECEIVER_ENABLED, false)
                if (enabled) {
                    ServiceUtils.startService(MyForegroundService::class.java)
                } else {
                    ServiceUtils.stopService(MyForegroundService::class.java)
                }
            }
        }
    }

    init {
        textNodeHandler.setNextHandler(idNodeHandler).setNextHandler(boundsHandler)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            event ?: return

            val rootNode = getCurrentRootNode()

            val className = event.className
            if (className != null) {
                if (!AccessibilityUtils.isSystemClass(className.toString())) {
                    layoutInspectClassName = className.toString()
                }
                if (isLayoutInspect) {
                    isLayoutInspect = false
                    LogUtils.d("layout inspect className: $layoutInspectClassName")
                    bfsTraverse(rootNode)
                }
            }

            if (!AnalyticsManager.isPerformScan(rootNode.packageName.toString())) return

            if (WhitelistManager.isInWhitelist(rootNode.packageName.toString())) return

            val listOfRect = textNodeHandler.handle(rootNode)
            for (rect in listOfRect) {
                click(this, rect)
            }
        } catch (e: Exception) {
//            LogUtils.e(e)
        } finally {
            AnalyticsManager.increaseScanCount()
        }
    }

    private fun getCurrentRootNode(): AccessibilityNodeInfo {
        return rootInActiveWindow ?: throw IllegalStateException("No valid root node available")
    }

    override fun onInterrupt() {}

    private fun click(accessibilityService: AccessibilityService, rect: Rect) {
        val path = Path()
        path.moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
        path.lineTo(rect.centerX().toFloat(), rect.centerY().toFloat())

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
            .build()

        accessibilityService.dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    super.onCompleted(gestureDescription)

                    if (AnalyticsManager.isShowToast()) {
                        if (DataStoreUtils.getSyncData(SKIP_PERMIT_NOTICE, false)) {
                            ToastManager.showToast(accessibilityService, "已为您跳过广告")
                        }
                        AnalyticsManager.setShowToastCount()
                    }

                }
            },
            null
        )
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null
            && event.action == KeyEvent.ACTION_DOWN
            && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            && DataStoreUtils.getSyncData(SKIP_LAYOUT_INSPECT, false)
        ) {
            val intent = Intent(Constants.SKIP_KEY_EVENT_VOLUME_DOWN)
            intent.setPackage(packageName)
            sendBroadcast(intent)

            isLayoutInspect = true
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun bfsTraverse(root: AccessibilityNodeInfo) {
        val queue: MutableList<MyNode> = mutableListOf(MyNode(root, 0))
        val temp: MutableList<MyNodeChild> = mutableListOf()

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeAt(0)
            processNode(node, temp, depth)

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(MyNode(it, depth + 1)) }
            }
        }

        val gson = Gson()
        val jsonStr = gson.toJson(temp)
        val file = File(SKIPApp.context.filesDir, "temp.json")
        file.writeText(jsonStr)
    }

    private fun processNode(node: AccessibilityNodeInfo, temp: MutableList<MyNodeChild>, depth: Int) {
        val myNodeChild = MyNodeChild(depth, node.childCount)

        node.className?.let {
            myNodeChild.className = it.toString()
        }

        node.text?.let {
            myNodeChild.text = it.toString()
        }

        node.viewIdResourceName?.let {
            myNodeChild.viewIdResourceName = it
        }

        temp.add(myNodeChild)
    }



    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onServiceConnected() {
        super.onServiceConnected()

        val intentFilter = IntentFilter(Constants.FOREGROUND_ACCESSIBILITY_RECEIVER_ACTION)
        registerReceiver(foregroundAccessibilityReceiver, intentFilter, RECEIVER_NOT_EXPORTED)

        if (DataStoreUtils.getSyncData(Constants.SKIP_FOREGROUND_ACCESSIBILITY, false)) {
            ServiceUtils.startService(MyForegroundService::class.java)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(foregroundAccessibilityReceiver)
    }
}