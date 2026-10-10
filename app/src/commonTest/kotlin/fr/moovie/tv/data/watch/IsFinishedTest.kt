package fr.moovie.tv.data.watch

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Le seuil « terminé », et son plancher contre les cartons de Torrentio. */
class IsFinishedTest {

    private val episode = 42 * 60_000L

    @Test
    fun `les derniers huit pour cent valent terminé`() {
        assertTrue(isFinished(positionMs = (episode * 0.93).toLong(), durationMs = episode))
        assertTrue(isFinished(positionMs = episode - 5_000, durationMs = episode))
        assertFalse(isFinished(positionMs = (episode * 0.80).toLong(), durationMs = episode))
    }

    @Test
    fun `un carton de trente secondes joué jusqu'au bout ne termine rien`() {
        val carton = 30_000L
        assertFalse(isFinished(positionMs = carton, durationMs = carton))
        assertFalse(isFinished(positionMs = carton - 1_000, durationMs = carton))
    }

    @Test
    fun `une durée inconnue ne termine rien`() {
        assertFalse(isFinished(positionMs = 10_000, durationMs = 0))
    }

    @Test
    fun `le plancher est a deux minutes`() {
        assertFalse(isFinished(positionMs = FINISHED_MIN_DURATION_MS - 1, durationMs = FINISHED_MIN_DURATION_MS - 1))
        assertTrue(isFinished(positionMs = FINISHED_MIN_DURATION_MS, durationMs = FINISHED_MIN_DURATION_MS))
    }
}
