package org.nhrc.grants.discovery

import org.springframework.stereotype.Component
import org.w3c.dom.Element
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

@Component
class RssOpportunityAdapter : ExternalOpportunityAdapter {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun supports(source: OpportunitySourceConfig) = source.adapterType == "RSS"

    override fun fetch(source: OpportunitySourceConfig): DiscoverySourceResult {
        val request = HttpRequest.newBuilder(URI.create(source.endpointUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/rss+xml, application/xml, text/xml")
            .header("User-Agent", "NHRC-Grants-Platform/0.1 opportunity-discovery")
            .GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw IllegalStateException(source.name + " returned HTTP " + response.statusCode())

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false

        val doc = factory.newDocumentBuilder().parse(response.body().byteInputStream())
        val nodes = doc.getElementsByTagName("item")
        val terms = source.terms().map { it.lowercase() }
        val items = mutableListOf<ExternalOpportunity>()
        for (i in 0 until nodes.length) {
            if (items.size >= source.fetchLimit) break
            val item = nodes.item(i) as? Element ?: continue
            val title = text(item, "title")?.trim().orEmpty()
            val link = text(item, "link")?.trim()
            val descriptionRaw = text(item, "description").orEmpty()
            val description = stripHtml(descriptionRaw)
            val searchable = (title + " " + description).lowercase()
            if (terms.isNotEmpty() && terms.none { searchable.contains(it) }) continue
            val guid = text(item, "guid")?.trim().takeUnless { it.isNullOrBlank() } ?: link ?: title
            if (title.isBlank() || guid.isBlank()) continue
            val pubDate = parseRssDate(text(item, "pubDate"))
            val closeDate = parseUkriDate(description, "Closing date")
            val openDate = parseUkriDate(description, "Opening date") ?: pubDate
            items += ExternalOpportunity(
                externalId = guid,
                title = title,
                funderName = inferFunder(description),
                agencyCode = null,
                canonicalUrl = link,
                summary = description.takeIf { it.isNotBlank() },
                currency = if (description.contains("£")) "GBP" else null,
                amountMin = null,
                amountMax = null,
                openAt = openDate,
                closeAt = closeDate,
                sourceStatus = inferStatus(description),
                rawPayload = descriptionRaw
            )
        }
        return DiscoverySourceResult(nodes.length, items)
    }

    private fun text(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0).textContent else null
    }

    private fun stripHtml(v: String) = v.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()

    private fun parseRssDate(v: String?): OffsetDateTime? {
        if (v.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()
    }

    private fun parseUkriDate(text: String, label: String): OffsetDateTime? {
        val escaped = Regex.escape(label)
        val match = Regex(escaped + "\\s*:?\\s*(\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4})", RegexOption.IGNORE_CASE).find(text) ?: return null
        return runCatching {
            java.time.LocalDate.parse(match.groupValues[1], DateTimeFormatter.ofPattern("d MMMM yyyy"))
                .atStartOfDay().atOffset(ZoneOffset.UTC)
        }.getOrNull()
    }

    private fun inferFunder(text: String): String? {
        val m = Regex("Funders?\\s*:?\\s*([^.;]{2,160})", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1)?.trim()
    }

    private fun inferStatus(text: String): String =
        when {
            Regex("Opportunity status\\s*:?\\s*Upcoming", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "UPCOMING"
            Regex("Opportunity status\\s*:?\\s*Open", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "OPEN"
            else -> "OPEN_OR_UPCOMING"
        }
}
