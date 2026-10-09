package fr.moovie.tv.shared

import kotlin.time.TimeSource

/** Écrit une ligne de chronométrage dans le journal de la plateforme. */
expect fun journalChrono(ligne: String)

/**
 * Chronométrage du démarrage et des chargements, lisible dans le journal.
 *
 * Sam, 09/10/2026 : « rajoute de la télémétrie pour qu'on comprenne pourquoi il
 * est si lent à s'ouvrir et si lent à se charger ». Rien ne sort de l'appareil :
 * les lignes vont au journal système, et se lisent en ADB avec
 * `adb logcat -s System.out | grep MoovieChrono`.
 *
 * Deux formes : [repere] date une étape depuis le premier appel (le démarrage du
 * processus applicatif), [debut] / [Mesure.fin] mesure une durée. Une mesure est
 * un objet qu'on garde, pas une clé dans une table partagée : aucun verrou, et
 * deux chargements simultanés ne se mélangent pas.
 */
object Chrono {
    private val origine = TimeSource.Monotonic.markNow()

    fun repere(etape: String) =
        journalChrono("T+${origine.elapsedNow().inWholeMilliseconds} ms  $etape")

    fun debut(etape: String) = Mesure(etape)

    class Mesure internal constructor(private val etape: String) {
        private val depart = TimeSource.Monotonic.markNow()

        fun fin(detail: String = "") = journalChrono(
            "$etape : ${depart.elapsedNow().inWholeMilliseconds} ms" +
                if (detail.isBlank()) "" else "  ($detail)",
        )
    }
}
