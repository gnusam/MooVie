package fr.moovie.tv.data.sources

import fr.moovie.tv.core.sources.port.HttpRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Le délai posé par l'appelant doit être celui qu'il subit.
 *
 * Reproduit la mesure du 10/10/2026 sur le téléviseur : un serveur qui accepte
 * la connexion et ne répond jamais. Avec `execute()` sous `withContext`, le
 * `withTimeoutOrNull` de 300 ms ne rendait la main qu'au `readTimeout` du
 * client — dix secondes par défaut.
 */
class OkHttpGatewayCancellationTest {

    @Test
    fun `annuler la coroutine rend la main sans attendre le serveur`() {
        ServerSocket(0).use { serveur ->
            // Accepte, puis se tait : la lecture côté client attend pour rien.
            val muet = thread(isDaemon = true) {
                runCatching { serveur.accept().use { Thread.sleep(5_000) } }
            }
            val gateway = OkHttpGateway(
                OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build(),
            )
            var resultat: Any? = Unit
            val duree = measureTime {
                resultat = runBlocking {
                    withTimeoutOrNull(300) {
                        gateway.fetch(HttpRequest("http://127.0.0.1:${serveur.localPort}/lent"))
                    }
                }
            }
            assertNull(resultat, "un délai dépassé rend null")
            assertTrue(
                duree.inWholeMilliseconds < 3_000,
                "la main doit revenir au délai de l'appelant, pas à celui du client (${duree.inWholeMilliseconds} ms)",
            )
            muet.interrupt()
        }
    }

    @Test
    fun `une reponse normale est materialisee`() {
        ServerSocket(0).use { serveur ->
            thread(isDaemon = true) {
                runCatching {
                    serveur.accept().use { socket ->
                        // Lire la requête jusqu'à la ligne vide avant de répondre.
                        val entree = socket.getInputStream().bufferedReader()
                        while (true) {
                            val ligne = entree.readLine() ?: break
                            if (ligne.isEmpty()) break
                        }
                        socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray(),
                        )
                        socket.getOutputStream().flush()
                    }
                }
            }
            val gateway = OkHttpGateway(OkHttpClient())
            val reponse = runBlocking {
                gateway.fetch(HttpRequest("http://127.0.0.1:${serveur.localPort}/ok"))
            }
            assertEquals(200, reponse?.status)
            assertEquals("ok", reponse?.body)
        }
    }
}
