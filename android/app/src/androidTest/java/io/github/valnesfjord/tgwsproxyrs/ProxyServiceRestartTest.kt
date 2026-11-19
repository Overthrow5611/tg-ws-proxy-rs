package io.github.valnesfjord.tgwsproxyrs

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ProxyService]'s own recovery: a run that reached its listener and then died
 * is restarted with backoff, and a Stop is final.
 *
 * Starting a foreground service from an instrumentation process counts as a
 * background start on Android 12+, so the app is put on the device-idle
 * allowlist first, which is one of the exemptions.
 */
@RunWith(AndroidJUnit4::class)
class ProxyServiceRestartTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        shell("cmd deviceidle whitelist +${context.packageName}")
        stopAndWait()
        resetBridge()
    }

    @After
    fun tearDown() {
        Thread.sleep(500)
        ProxyService.stop(context)
        runBlocking { withTimeout(SHUTDOWN_TIMEOUT_MS) { ProxyBridge.restarting.first { !it } } }
        stopAndWait()
        resetBridge()
        context.getSharedPreferences(ProxyService.PREFS, 0).edit(commit = true) { clear() }
    }

    @Test
    fun runThatDiesAfterListeningIsRestarted() {
        ProxyService.start(context, OFFLINE_ARGS)
        awaitLink()
        // Let the service finish its start bookkeeping, as a real run would
        // before it dies.
        Thread.sleep(1_000)

        // A death the service did not ask for.
        NativeProxy.nativeStop()
        runBlocking { withTimeout(SHUTDOWN_TIMEOUT_MS) { ProxyBridge.running.first { !it } } }

        runBlocking { withTimeout(SHUTDOWN_TIMEOUT_MS) { ProxyBridge.running.first { it } } }
        assertTrue(NativeProxy.nativeIsRunning())
        assertFalse(ProxyBridge.restarting.value)
    }

    @Test
    fun stopIsFinal() {
        ProxyService.start(context, OFFLINE_ARGS)
        awaitLink()
        Thread.sleep(1_000)

        ProxyService.stop(context)
        runBlocking { withTimeout(SHUTDOWN_TIMEOUT_MS) { ProxyBridge.running.first { !it } } }
        // Longer than the first restart delay (2 s).
        Thread.sleep(4_000)

        assertFalse(ProxyBridge.running.value)
        assertFalse(ProxyBridge.restarting.value)
        assertFalse(NativeProxy.nativeIsRunning())
        val prefs = context.getSharedPreferences(ProxyService.PREFS, 0)
        assertFalse(prefs.getBoolean(ProxyService.PREF_WAS_RUNNING, true))
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
    }
}
