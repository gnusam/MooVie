package fr.moovie.tv.data.subtitles

import fr.moovie.tv.core.subtitles.port.SubtitleTranslator
import fr.moovie.tv.data.net.clientRest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Traduit des répliques par un appel à l'API Messages de Claude.
 *
 * ## Pourquoi du HTTP écrit à la main plutôt que le SDK
 *
 * Anthropic publie un SDK Java, qui conviendrait à Android et au desktop. Il ne
 * conviendrait pas à iOS : ce module est du Kotlin Multiplatform et vise aussi
 * Kotlin/Native, où aucune dépendance JVM n'existe. C'est exactement la raison
 * pour laquelle `TmdbApi` a quitté Retrofit — voir son en-tête. Le projet a donc
 * déjà sa réponse : Ktor, en code commun, pour toutes les cibles.
 *
 * ## La sortie structurée n'est pas un raffinement
 *
 * `output_config.format` impose un objet dont **chaque numéro de réplique est
 * une clé obligatoire**, et qui n'en accepte aucune autre. Le compte juste
 * devient donc une propriété de la requête au lieu d'un espoir vérifié après
 * coup, et c'est la parade directe au décalage d'un cran que documente
 * `translateCues`. La vérification côté domaine reste, parce qu'une garantie
 * qu'on ne contrôle pas n'en est pas une.
 *
 * Les numéros sont **locaux au lot** (1..n) et non ceux du film : un lot qui
 * commencerait à 641 apprendrait au modèle un décompte dont il n'a aucun besoin,
 * et allongerait le schéma sans rien apporter.
 *
 * ## Réglages, et ce qu'ils coûtent
 *
 * `effort: low` — traduire n'est pas raisonner. C'est un travail de volume, à
 * mille répliques par film, et le palier bas est celui que la documentation
 * recommande pour ce genre de route.
 *
 * Modèle : **Haiku**, choix de Sam le 09/10/2026 (« des modèles plus légers et
 * suffisants pour traduire »). Mesuré sur 40 vraies répliques de Fullmetal
 * Alchemist : Opus 5 11,8 s, Sonnet 5.5 8,1 s, Haiku 5.5 5,5 s, pour une
 * traduction correcte, un rien plus littérale. La vitesse compte plus que la
 * nuance : c'est elle qui décide si le français arrive avant la réplique.
 *
 * Pas de `fallbacks` : Haiku n'a pas de repli serveur sur refus. Un lot refusé
 * rend null, la bissection le recoupe, et une réplique qui résiste garde son
 * texte anglais — un défaut local, jamais un fichier éteint.
 */
class ClaudeSubtitleTranslator(
    private val apiKey: String = TraductionConfig.ANTHROPIC_API_KEY,
    private val langueCible: String = "français",
    private val model: String = MODELE,
) : SubtitleTranslator {

    val configured: Boolean get() = apiKey.isNotBlank()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun translate(textes: List<String>): List<String>? {
        if (!configured || textes.isEmpty()) return null

        val reponse = runCatching {
            clientRest.post(URL) {
                // Mesuré le 09/10/2026 : 12,2 s pour un lot de 40 répliques.
                // Le délai de lecture par défaut d'OkHttp (10 s) coupait chaque
                // lot ; la bissection les recoupait jusqu'à l'unité, et la
                // traduction restait figée à « 0 sur 333 », sans un mot dans
                // les journaux puisque l'échec rend null.
                timeout {
                    requestTimeoutMillis = DELAI_MS
                    socketTimeoutMillis = DELAI_MS
                }
                header("x-api-key", apiKey)
                header("anthropic-version", VERSION)
                contentType(ContentType.Application.Json)
                setBody(corps(textes).toString())
            }.bodyAsText()
        }.getOrNull() ?: return null

        return lire(reponse, textes.size)
    }

    private fun corps(textes: List<String>): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", 16000)
        putJsonObject("output_config") {
            put("effort", "low")
            putJsonObject("format") {
                put("type", "json_schema")
                put("schema", schema(textes.size))
            }
        }
        putJsonArray("messages") {
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", consigne(textes))
                },
            )
        }
    }

    /**
     * Un objet aux clés « 1 », « 2 »… toutes obligatoires, et rien d'autre.
     * `additionalProperties: false` est ce qui interdit au modèle d'inventer une
     * réplique ; `required` est ce qui l'empêche d'en sauter une.
     */
    private fun schema(taille: Int): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (i in 1..taille) {
                putJsonObject(i.toString()) { put("type", "string") }
            }
        }
        put("required", buildJsonArray { for (i in 1..taille) add(JsonPrimitive(i.toString())) })
        put("additionalProperties", false)
    }

    /**
     * La consigne, dont la dernière phrase est la seule qui compte vraiment.
     *
     * Elle vient d'une mesure faite sur le script terminal : un prompt qui
     * demande une bonne traduction en obtient une, **et déplace du texte d'une
     * réplique à sa voisine** — le début d'une phrase reste sur un plan, la fin
     * part sur le suivant. Le résultat se lit bien sur le papier et se regarde
     * mal à l'écran. La règle d'indépendance est ce qui a rendu les deux
     * compatibles, et elle est reprise mot pour mot.
     */
    private fun consigne(textes: List<String>): String = buildString {
        append("Traduis en ").append(langueCible)
        append(" les sous-titres numérotés ci-dessous.\n\n")
        append("RÈGLE ABSOLUE : chaque ligne numérotée est un sous-titre affiché à un ")
        append("instant précis. Traduis chaque numéro INDÉPENDAMMENT, sans jamais ")
        append("déplacer, fusionner ni répartir du contenu d'un numéro vers un autre.\n\n")
        append("Garde les retours à la ligne internes, la ponctuation et les balises. ")
        append("N'ajoute aucun commentaire.\n\n")
        textes.forEachIndexed { i, texte ->
            append(i + 1).append(". ").append(texte).append('\n')
        }
    }

    /**
     * Lit la réponse, et refuse tout ce qui n'est pas exactement ce qu'on a
     * demandé.
     *
     * `stop_reason` est consulté **avant** le contenu : un refus rend un HTTP 200
     * dont `content` peut être vide, et le lire d'abord donnerait une erreur de
     * décodage là où il y a en fait une décision du service.
     */
    private fun lire(reponse: String, taille: Int): List<String>? {
        val racine = runCatching { json.parseToJsonElement(reponse).jsonObject }.getOrNull()
            ?: return null
        if (racine["stop_reason"]?.jsonPrimitive?.content == "refusal") return null

        val texte = runCatching {
            racine["content"]?.jsonArray
                ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
        }.getOrNull() ?: return null

        val objet = runCatching { json.parseToJsonElement(texte).jsonObject }.getOrNull()
            ?: return null

        // Reconstruit dans l'ordre des numéros, pas dans celui des clés : rien
        // ne garantit l'ordre d'un objet JSON, et un tri par clé textuelle
        // mettrait « 10 » avant « 2 ».
        val sortie = ArrayList<String>(taille)
        for (i in 1..taille) {
            val v = objet[i.toString()]?.jsonPrimitive?.contentOrNull() ?: return null
            sortie += v
        }
        return sortie
    }

    private fun JsonPrimitive.contentOrNull(): String? = content.takeIf { it != "null" }

    private companion object {
        const val URL = "https://api.anthropic.com/v1/messages"
        const val VERSION = "2023-06-01"

        /** Un lot entier, réponse comprise : large devant les 12 s mesurées. */
        const val DELAI_MS = 180_000L

        /** Le Haiku courant : voir la documentation de la classe. */
        const val MODELE = "claude-haiku-5-5"
    }
}
