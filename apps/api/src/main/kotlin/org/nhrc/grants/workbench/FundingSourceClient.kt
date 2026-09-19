package org.nhrc.grants.workbench

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit

/** Source data is untrusted. Fixed hosts, no redirects, bounded bodies and no automatic link retrieval. */
data class FundingCall(val externalId: String,val reference: String?,val title: String,val agency: String?,val agencyCode: String?,val url: String,val summary: String?,val closeDate: LocalDate?,val snapshot: Map<String,Any?>)
data class FundingBatch(val calls: List<FundingCall>,val available: Int,val skipped: Int,val truncated: Boolean)

@Component
class FundingSourceClient(private val mapper: ObjectMapper,private val environment: Environment) {
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val grantsEndpoint=URI("https://api.grants.gov/v1/api/search2")
    private val detailEndpoint=URI("https://api.grants.gov/v1/api/fetchOpportunity")
    private val simplerEndpoint=URI("https://api.simpler.grants.gov/v1/opportunities/search")
    fun simplerConfigured() = !environment.getProperty("SIMPLER_GRANTS_API_KEY","").isBlank()
    private fun post(uri: URI,payload: Any,source: String): JsonNode {
        require(uri in setOf(grantsEndpoint,detailEndpoint,simplerEndpoint)) { "Unapproved funding source" }
        val builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(35)).header("Content-Type","application/json").header("Accept","application/json").header("User-Agent","NHRC-Grants-Discovery/1.0").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
        if(source=="SIMPLER_GRANTS_GOV") {
            val key=environment.getProperty("SIMPLER_GRANTS_API_KEY","")
            require(key.isNotBlank()) { "Simpler.Grants.gov is not configured. Set its API key in the server environment" }
            builder.header("X-API-Key",key)
        }
        val future=client.sendAsync(builder.build(),HttpResponse.BodyHandler<ByteArray> { LimitedFundingBody(4*1024*1024) })
        val response=try { future.get(40,TimeUnit.SECONDS) } catch(ex: Exception) {
            future.cancel(true)
            throw IllegalStateException("The funding source could not be reached or its response exceeded the time or size limit")
        }
        if(response.statusCode()==429) throw IllegalStateException("The funding source is rate-limiting requests. No partial import was committed")
        if(response.statusCode() !in 200..299) throw IllegalStateException("The funding source returned HTTP ${response.statusCode()}. No partial import was committed")
        return mapper.readTree(response.body())
    }
    fun search(source: String,term: String): FundingBatch {
        require(source in setOf("GRANTS_GOV","SIMPLER_GRANTS_GOV")) { "Unknown funding source" }
        require(term.isNotBlank() && term.length<=100) { "Enter a search of 1 to 100 characters" }
        val calls=linkedMapOf<String,FundingCall>();var available=0;var skipped=0;var ended=false
        for(page in 0..3) {
            val payload: Any = if(source=="GRANTS_GOV") mapOf("rows" to 25,"startRecordNum" to page*25,"keyword" to term,"oppStatuses" to "forecasted|posted")
                else mapOf("query" to term,"filters" to mapOf("opportunity_status" to mapOf("one_of" to listOf("posted","forecasted"))),"pagination" to mapOf("page_offset" to page+1,"page_size" to 25,"sort_order" to listOf(mapOf("order_by" to "opportunity_id","sort_direction" to "ascending"))))
            val root=post(if(source=="GRANTS_GOV") grantsEndpoint else simplerEndpoint,payload,source)
            val hits: JsonNode
            if(source=="GRANTS_GOV") {
                require(root.has("errorcode") && root.path("errorcode").asInt(-1)==0) { "Grants.gov returned an unsuccessful search response" }
                hits=root.path("data").path("oppHits");available=root.path("data").path("hitCount").asInt(0)
            } else { hits=root.path("data");available=root.path("pagination_info").path("total_records").asInt(0) }
            require(hits.isArray) { "The funding source returned an unexpected search format" }
            val previous=calls.size
            hits.forEach { hit ->
                try { val call=parse(source,hit);calls.putIfAbsent(call.externalId,call) } catch(_: IllegalArgumentException) { skipped++ }
            }
            if(hits.size()<25 || (page+1)*25>=available) { ended=true;break }
            if(calls.size==previous) break
        }
        return FundingBatch(calls.values.toList(),available,skipped,!ended || calls.size+skipped<available)
    }
    fun detail(externalId: String): Map<String,Any?> {
        require(Regex("^[0-9]{1,12}$").matches(externalId)) { "Invalid Grants.gov opportunity identifier" }
        val root=post(detailEndpoint,mapOf("opportunityId" to externalId.toLong()),"GRANTS_GOV")
        require(root.path("errorcode").asInt(-1)==0 && root.path("data").isObject) { "Grants.gov did not return a valid opportunity detail" }
        val data=root.path("data");val synopsis=data.path("synopsis")
        return mapOf(
            "opportunity_number" to text(data,"opportunityNumber",255),"opportunity_title" to text(data,"opportunityTitle",1000),
            "agency_name" to text(synopsis,"agencyName",255),"agency_code" to text(data,"owningAgencyCode",255),
            "summary" to text(synopsis,"synopsisDesc",60000),"award_floor" to synopsis.path("awardFloor").takeIf { it.isNumber }?.decimalValue(),
            "award_ceiling" to synopsis.path("awardCeiling").takeIf { it.isNumber }?.decimalValue(),
            "cost_sharing" to synopsis.path("costSharing").takeIf { it.isBoolean }?.booleanValue(),
            "source_url" to "https://www.grants.gov/search-results-detail/$externalId"
        )
    }
    fun parse(source: String,hit: JsonNode): FundingCall {
        val id=text(hit,if(source=="GRANTS_GOV") "id" else "opportunity_id",255) ?: throw IllegalArgumentException("Missing source identifier")
        if(source=="GRANTS_GOV") require(Regex("^[0-9]{1,12}$").matches(id)) { "Invalid source identifier" }
        else java.util.UUID.fromString(id)
        val reference=text(hit,if(source=="GRANTS_GOV") "number" else "opportunity_number",255)
        val title=text(hit,if(source=="GRANTS_GOV") "title" else "opportunity_title",1000) ?: throw IllegalArgumentException("Missing call title")
        val agency=text(hit,if(source=="GRANTS_GOV") "agencyName" else "agency_name",255)
        val agencyCode=text(hit,if(source=="GRANTS_GOV") "agencyCode" else "agency_code",255)
        val summaryNode=hit.path("summary")
        val summary=if(summaryNode.isTextual) summaryNode.asText().take(24000) else text(summaryNode,"summary_description",24000)
        val dateText=text(hit,if(source=="GRANTS_GOV") "closeDate" else "close_date",80) ?: text(summaryNode,"close_date",80)
        val closing=dateText?.let { parseDate(it) }
        val url=if(source=="GRANTS_GOV") "https://www.grants.gov/search-results-detail/$id" else "https://simpler.grants.gov/opportunity/$id"
        val snapshot=mapOf("external_id" to id,"reference" to reference,"title" to title,"agency" to agency,"agency_code" to agencyCode,"summary" to summary,"published_close_date" to dateText,"parsed_close_date" to closing?.toString(),"source_url" to url,"source_status" to text(hit,if(source=="GRANTS_GOV") "oppStatus" else "opportunity_status",80))
        return FundingCall(id,reference,title,agency,agencyCode,url,summary,closing,snapshot)
    }
    fun parseDate(value: String): LocalDate? = try { LocalDate.parse(value) } catch(_: Exception) {
        try { LocalDate.parse(value,DateTimeFormatter.ofPattern("MM/dd/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)) } catch(_: Exception) { null }
    }
    private fun text(node: JsonNode,key: String,limit: Int): String? {
        val field=node.path(key)
        if(!field.isTextual && !field.isIntegralNumber) return null
        return field.asText().trim().takeIf { it.isNotBlank() }?.take(limit)
    }
}

/** Cancels the upstream stream instead of buffering an unbounded response. */
internal class LimitedFundingBody(private val maximum: Int): HttpResponse.BodySubscriber<ByteArray> {
    private val output=ByteArrayOutputStream()
    private val result=CompletableFuture<ByteArray>()
    private var subscription: Flow.Subscription?=null
    override fun getBody(): CompletionStage<ByteArray> = result
    override fun onSubscribe(value: Flow.Subscription) { subscription=value;value.request(1) }
    override fun onNext(items: List<ByteBuffer>) {
        try {
            for(buffer in items) {
                require(output.size()+buffer.remaining()<=maximum) { "Funding response exceeds the permitted size" }
                val bytes=ByteArray(buffer.remaining());buffer.get(bytes);output.write(bytes)
            }
            subscription?.request(1)
        } catch(ex: Exception) { subscription?.cancel();result.completeExceptionally(ex) }
    }
    override fun onError(error: Throwable) { result.completeExceptionally(error) }
    override fun onComplete() { result.complete(output.toByteArray()) }
}
