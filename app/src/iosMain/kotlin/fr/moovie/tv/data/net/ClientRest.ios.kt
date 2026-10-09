package fr.moovie.tv.data.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.engine.darwin.Darwin

actual val clientRest: HttpClient = HttpClient(Darwin) {
    expectSuccess = false
    // Sans valeur par défaut : seul un appel qui le demande change ses délais
    // (la traduction des sous-titres, plus lente que les 10 s d'OkHttp).
    install(HttpTimeout)
}
