package top.rootu.dddplayer.data

import android.content.Context
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences("global_prefs", Context.MODE_PRIVATE)

    @Before
    fun resetSingletons() {
        // Синглтоны живут между тестами, а приложение Robolectric для каждого теста новое
        listOf(SettingsRepository::class.java, AppDatabase::class.java).forEach { cls ->
            cls.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
    }

    private fun repo() = SettingsRepository.getInstance(context)

    private fun SettingsRepository.order() = getAudioTrackStats().map { it.signature }

    @Test
    fun `audio tracks are ordered by count, recent first on equal count`() {
        val repo = repo()
        repo.recordAudioTrackChoice("A", "Track A")
        repo.recordAudioTrackChoice("B", "Track B")
        repo.recordAudioTrackChoice("B", "Track B")
        repo.recordAudioTrackChoice("C", "Track C")

        assertEquals(listOf("B", "C", "A"), repo.order())
        assertEquals(listOf(2, 1, 1), repo.getAudioTrackStats().map { it.count })
    }

    @Test
    fun `moved tracks keep their place regardless of counts`() {
        val repo = repo()
        repo.recordAudioTrackChoice("A", "A")
        repo.recordAudioTrackChoice("B", "B")
        repo.recordAudioTrackChoice("B", "B")
        repo.recordAudioTrackChoice("C", "C") // B, C, A

        repo.moveAudioTrackStat(1, 0) // C, B закреплены
        assertEquals(listOf("C", "B", "A"), repo.order())

        repeat(5) { repo.recordAudioTrackChoice("A", "A") }
        repo.recordAudioTrackChoice("D", "D")
        // Незакрепленные - после закрепленных, по счетчику
        assertEquals(listOf("C", "B", "A", "D"), repo.order())
        assertEquals(listOf(true, true, false, false), repo.getAudioTrackStats().map { it.pinned })
    }

    @Test
    fun `move down and remove`() {
        val repo = repo()
        listOf("A", "B", "C").forEach { repo.recordAudioTrackChoice(it, it) } // C, B, A

        repo.moveAudioTrackStat(0, 1)
        assertEquals(listOf("B", "C", "A"), repo.order())

        repo.removeAudioTrackStat(0)
        assertEquals(listOf("C", "A"), repo.order())
    }

    @Test
    fun `list keeps the most recent tracks when full`() {
        val repo = repo()
        repeat(25) { repo.recordAudioTrackChoice("t$it", "t$it") }

        val order = repo.order()
        assertEquals(SettingsRepository.MAX_AUDIO_TRACK_STATS, order.size)
        assertEquals("t24", order.first())
        assertFalse("t0" in order)
    }

    @Test
    fun `dolby vision switch is migrated to the mode`() {
        prefs.edit { putBoolean("map_dv_to_hevc", false) }
        assertEquals(SettingsRepository.DV_MODE_OFF, repo().getDvMode())
        assertFalse(prefs.contains("map_dv_to_hevc"))
    }

    @Test
    fun `dolby vision compatibility stays on by default`() {
        assertEquals(SettingsRepository.DV_MODE_ALWAYS, repo().getDvMode())
        assertTrue(prefs.contains("dv_mode"))
    }

    @Test
    fun `clock switch is migrated to transparency`() {
        prefs.edit { putBoolean("show_clock", true) }
        assertEquals(0, repo().getClockTransparency())
        assertFalse(prefs.contains("show_clock"))
    }

    @Test
    fun `defaults keep the previous behaviour`() {
        val repo = repo()
        assertEquals(SettingsRepository.CLOCK_OFF, repo.getClockTransparency())
        assertEquals(SettingsRepository.UP_ACTION_MENU, repo.getUpButtonAction())
        assertEquals(SettingsRepository.AUDIO_MEMORY_OFF, repo.getAudioTrackMemoryMode())
        assertEquals(SettingsRepository.EXTERNAL_POSITION_APPLY, repo.getExternalPositionMode())
        assertEquals(SettingsRepository.BUFFER_SIZE_AUTO, repo.getTargetBufferMB())
        assertFalse(repo.isLiveModeEnabled())
        assertFalse(repo.isAutoPipEnabled())
        assertTrue(repo.isConfirmExitEnabled())
        assertTrue(repo.isVolumeBrightnessGesturesEnabled())
    }
}
