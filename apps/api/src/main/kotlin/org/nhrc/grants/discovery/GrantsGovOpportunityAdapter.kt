package org.nhrc.grants.discovery

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import java.util.concurrent.Executors

@Component
class GrantsGovOpportunityAdapter(private val mapper: ObjectMapper) : ExternalOpportunityAdapter {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val searchDate = DateTimeFormatter.ofPattern("MM/dd/yyyy")

    override fun supports(source: OpportunitySourceConfig) = source.adapterType == "GRANTS_GOV"

    override fun fetch(source: OpportunitySourceConfig): DiscoverySourceResult {
        val unique = LinkedHashMap<String, JsonNode>()
        val terms = source.terms().ifEmpty { listOf("health") }
        var fetched = 0

        for (term in terms) {
            if (unique.size >= source.fetchLimit) break
            val body = mapper.writeValueAsString(mapOf(
                "rows" to minOf(source.fetchLimit, 10),
                "keyword" to term,
                "oppStatuses" to "forecasted|posted",
                "startRecordNum" to 0
            ))
            val root = postJson(source.endpointUrl, body)
            if (root.path("errorcode").asInt(0) != 0) throw IllegalStateException("Grants.gov search error: " + root.path("msg").asText("unknown error"))
            val hits = root.path("data").path("oppHits")
            if (!hits.isArray) continue
            fetched += hits.size()
            for (hit in hits) {
                val id = hit.path("id").asText("").trim()
                if (id.isNotBlank()) unique.putIfAbsent(id, hit)
                if (unique.size >= source.fetchLimit) break
            }
        }

        val hits = unique.values.take(source.fetchLimit)
        val executor = Executors.newFixedThreadPool(minOf(5, maxOf(1, hits.size)))
        val normalized = try {
            hits.map { hit ->
                executor.submit<ExternalOpportunity> {
                    val id = hit.path("id").asText()
                    val details = source.detailEndpointUrl?.let { endpoint ->
                        runCatching { postJson(endpoint, mapper.writeValueAsString(mapOf("opportunityId" to id.toLong()))) }.getOrNull()
                    }
                    normalize(hit, details)
                }
            }.map { it.get() }
        } finally {
            executor.shutdown()
        }
        return DiscoverySourceResult(fetched, normalized)
    }

    private fun normalize(hit: JsonNode, details: JsonNode?): ExternalOpportunity {
        val id = hit.path("id").asText()
        val synopsis = details?.path("data")?.path("synopsis")
        val summary = synopsis?.path("synopsisDesc")?.asText(null)
        val floor = synopsis?.path("awardFloor")?.asText(null)?.toBigDecimalOrNull()
        val ceiling = synopsis?.path("awardCeiling")?.asText(null)?.toBigDecimalOrNull()
        val agency = hit.path("agencyName").asText(null)
            ?: details?.path("data")?.path("agencyDetails")?.path("agencyName")?.asText(null)
        return ExternalOpportunity(
            externalId = id,
            title = hit.path("title").asText("").trim(),
            funderName = agency,
            agencyCode = hit.path("agencyCode").asText(null),
            canonicalUrl = "https://www.grants.gov/search-results-detail/" + id,
            summary = summary,
            currency = if (floor != null || ceiling != null) "USD" else null,
            amountMin = floor,
            amountMax = ceiling,
            openAt = parseSearchDate(hit.path("openDate").asText(null)),
            closeAt = parseSearchDate(hit.path("closeDate").asText(null)),
            sourceStatus = hit.path("oppStatus").asText(null),
            rawPayload = mapper.writeValueAsString(mapOf("search" to hit, "detail" to details))
        )
    }

    private fun parseSearchDate(value: String?): java.time.OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(value, searchDate).atStartOfDay().atOffset(ZoneOffset.UTC) }.getOrNull()
    }

    private fun postJson(url: String, body: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "NHRC-Grants-Platform/0.1 opportunity-discovery")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Grants.gov returned HTTP " + response.statusCode())
        }
        return mapper.readTree(response.body())
    }
}
