package com.xiefeihong.hypervolume.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「冷启动早期 provider 宿主未就绪」判定的纯函数单测。
 *
 * 该异常来自 `Settings.Global.getString` → `ActivityManager.getService()` 返回 null，
 * 消息固定点名 `IActivityManager.getContentProvider`；只有这种才降级成普通提示，
 * 其余异常（含其它 NPE）仍按真失败记录。
 */
class ProviderNotReadyTest {

    /** 真机上抓到的原始消息（`22:30:01.838 read global config failed`）。 */
    private val realMessage =
        "Attempt to invoke interface method 'android.app.ContentProviderHolder " +
            "android.app.IActivityManager.getContentProvider(android.app.IApplicationThread, " +
            "java.lang.String, java.lang.String, int, boolean)' on a null object reference"

    @Test
    fun activityManagerNpeIsRecognized() {
        assertTrue(isProviderHostNotReady(NullPointerException(realMessage)))
    }

    @Test
    fun otherNpeIsNotTreatedAsExpected() {
        assertFalse(isProviderHostNotReady(NullPointerException("another null deref")))
        assertFalse(isProviderHostNotReady(NullPointerException()))
    }

    @Test
    fun nonNpeIsNotTreatedAsExpected() {
        assertFalse(isProviderHostNotReady(IllegalStateException(realMessage)))
        assertFalse(isProviderHostNotReady(RuntimeException("IActivityManager getContentProvider")))
    }
}
