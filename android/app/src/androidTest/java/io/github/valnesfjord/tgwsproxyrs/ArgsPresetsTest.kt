package io.github.valnesfjord.tgwsproxyrs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The pure helpers behind the mode chips, the detailed-log switch and the update check. */
@RunWith(AndroidJUnit4::class)
class ArgsPresetsTest {
    @Test
    fun quietIsRecognisedInBothSpellings() {
        assertFalse(ProxyViewModel.isVerbose("--quiet --pool-size 1"))
        assertFalse(ProxyViewModel.isVerbose("--pool-size 1 -q"))
        assertTrue(ProxyViewModel.isVerbose("--pool-size 1"))
        assertTrue(ProxyViewModel.isVerbose("--quietness 1"))
    }

    @Test
    fun withoutQuietDropsOnlyTheFlag() {
        val stripped = ProxyViewModel.withoutQuiet("--dc-ip 2:1.2.3.4\n-q --quiet --pool-size 1")
        assertFalse(stripped.contains("-q"))
        assertFalse(stripped.contains("--quiet"))
        assertTrue(stripped.contains("--dc-ip 2:1.2.3.4"))
        assertTrue(stripped.contains("--pool-size 1"))
        assertTrue(stripped.contains("\n"))
    }

    @Test
    fun presetsMatchRegardlessOfQuietAndSpacing() {
        ProxyViewModel.PRESETS.forEach { preset ->
            assertTrue(ProxyViewModel.matches(preset.args, preset))
            assertTrue(ProxyViewModel.matches(ProxyViewModel.withoutQuiet(preset.args), preset))
            assertTrue(ProxyViewModel.matches(preset.args.replace(" ", "\n"), preset))
            assertFalse(ProxyViewModel.matches(preset.args + " --pool-size 4", preset))
        }
        val (direct, cloudflare) = ProxyViewModel.PRESETS
        assertFalse(ProxyViewModel.matches(direct.args, cloudflare))
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(ProxyViewModel.isNewer("2.7.10", "2.7.9"))
        assertTrue(ProxyViewModel.isNewer("2.8.0", "2.7.99"))
        assertFalse(ProxyViewModel.isNewer("2.7.5", "2.7.5"))
        assertFalse(ProxyViewModel.isNewer("2.7.4", "2.7.5"))
    }
}
