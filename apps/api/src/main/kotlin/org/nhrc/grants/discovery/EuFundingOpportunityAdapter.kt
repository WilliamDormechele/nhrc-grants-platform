package org.nhrc.grants.discovery

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.LinkedHashMap
import java.util.UUID

@Component
class EuFundingOpportunityAdapter(private val mapper:ObjectMapper) : ExternalOpportunityAdapter {
    private val http=HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun supports(source:OpportunitySourceConfig)=source.adapterType=="EU_FUNDING_TENDERS"

    override fun fetch(source:OpportunitySourceConfig):DiscoverySourceResult{
        val unique=LinkedHashMap<String,JsonNode>()
        val terms=source.terms().ifEmpty { listOf("health") }
        var fetched=0
        for(term in terms){
            if(unique.size>=source.fetchLimit) break
            val root=search(source.endpointUrl,term,minOf(15,source.fetchLimit))
            val hits=root.path("results")
            if(!hits.isArray) continue
            fetched += hits.size()
            for(hit in hits){
                val id=one(hit.path("metadata"),"identifier") ?: hit.path("reference").asText("").trim()
                if(id.isNotBlank()) unique.putIfAbsent(id,hit)
                if(unique.size>=source.fetchLimit) break
            }
        }
        return DiscoverySourceResult(fetched,unique.values.take(source.fetchLimit).map(::normalize))
    }

    private fun normalize(hit:JsonNode):ExternalOpportunity{
        val meta=hit.path("metadata")
        val id=one(meta,"identifier") ?: hit.path("reference").asText("")
        val statusCode=one(meta,"status")
        val status=when(statusCode){
            "31094501" -> "UPCOMING"
            "31094502" -> "OPEN"
            "31094503" -> "CLOSED"
            else -> statusCode
        }
        val rawSummary=one(meta,"descriptionByte") ?: hit.path("summary").asText(null) ?: hit.path("content").asText(null)
        return ExternalOpportunity(
            externalId=id,
            title=one(meta,"title") ?: hit.path("reference").asText(id),
            funderName="European Commission / EU Funding & Tenders Portal",
            agencyCode=one(meta,"frameworkProgramme"),
            canonicalUrl=one(meta,"url") ?: hit.path("url").asText(null),
            summary=stripHtml(rawSummary),
            currency="EUR",
            amountMin=null,
            amountMax=null,
            openAt=parseDate(one(meta,"startDate")),
            closeAt=parseDate(one(meta,"deadlineDate")),
            sourceStatus=status,
            rawPayload=mapper.writeValueAsString(hit)
        )
    }

    private fun search(endpoint:String,term:String,limit:Int):JsonNode{
        val encoded=URLEncoder.encode(term,StandardCharsets.UTF_8)
        val separator=if(endpoint.contains("?")) "&" else "?"
        val url=endpoint + separator + "apiKey=SEDIA&text=" + encoded + "&pageSize=" + limit + "&pageNumber=1"
        val boundary="----NHRCGrants" + UUID.randomUUID().toString().replace("-","")
        val query=mapper.writeValueAsString(mapOf("bool" to mapOf("must" to listOf(
            mapOf("terms" to mapOf("type" to listOf("1"))),
            mapOf("terms" to mapOf("status" to listOf("31094501","31094502")))
        ))))
        val body=multipart(boundary,listOf(
            "query" to query,
            "languages" to mapper.writeValueAsString(listOf("en")),
            "sort" to mapper.writeValueAsString(mapOf("field" to "deadlineDate","order" to "DESC"))
        ))
        val request=HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept","application/json")
            .header("Content-Type","multipart/form-data; boundary=" + boundary)
            .header("User-Agent","NHRC-Grants-Platform/0.1 opportunity-discovery")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response=http.send(request,HttpResponse.BodyHandlers.ofString())
        if(response.statusCode() !in 200..299) throw IllegalStateException("EU Funding & Tenders returned HTTP " + response.statusCode())
        val root=mapper.readTree(response.body())
        if(root.path("type").asText("")=="throwable") throw IllegalStateException("EU Funding & Tenders search error: " + root.path("message").asText("unknown error"))
        return root
    }

    private fun multipart(boundary:String,parts:List<Pair<String,String>>):String{
        val out=StringBuilder()
        for((name,value) in parts){
            out.append("--").append(boundary).append("\r\n")
            out.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n")
            out.append("Content-Type: application/json\r\n\r\n")
            out.append(value).append("\r\n")
        }
        out.append("--").append(boundary).append("--\r\n")
        return out.toString()
    }

    private fun one(meta:JsonNode,key:String):String?{
        val value=meta.path(key)
        if(value.isMissingNode||value.isNull) return null
        if(value.isArray) return value.firstOrNull()?.asText(null)
        return value.asText(null)
    }

    private fun parseDate(v:String?):OffsetDateTime?{
        if(v.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(v) }.getOrElse {
            runCatching { java.time.LocalDate.parse(v).atStartOfDay().atOffset(ZoneOffset.UTC) }.getOrNull()
        }
    }

    private fun stripHtml(v:String?):String?{
        if(v.isNullOrBlank()) return null
        return v.replace(Regex("<[^>]+>")," ")
            .replace("&nbsp;"," ").replace("&amp;","&")
            .replace(Regex("\\s+")," ").trim().takeIf { it.isNotBlank() }
    }
}
