package org.nhrc.grants.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.nhrc.grants.discovery.EuFundingOpportunityAdapter
import org.nhrc.grants.discovery.GrantsGovOpportunityAdapter
import org.nhrc.grants.discovery.OpportunitySourceConfig
import org.nhrc.grants.discovery.RssOpportunityAdapter
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID

class OpportunityAdapterTest {
    @Test
    fun grantsGovAdapterNormalizesSearchAndDetailResponses() {
        val server = HttpServer.create(InetSocketAddress(0),0)
        server.createContext("/search") { x ->
            respond(x, """{"errorcode":0,"msg":"ok","data":{"oppHits":[{"id":"123","title":"Global Health Systems Call","agencyCode":"HHS","agencyName":"Health and Human Services","openDate":"09/01/2026","closeDate":"12/15/2026","oppStatus":"posted"}]}}""")
        }
        server.createContext("/detail") { x ->
            respond(x, """{"errorcode":0,"data":{"synopsis":{"synopsisDesc":"<p>Health systems research</p>","awardFloor":"10000","awardCeiling":"50000"},"agencyDetails":{"agencyName":"Health and Human Services"}}}""")
        }
        server.start()
        try {
            val base="http://127.0.0.1:"+server.address.port
            val source=source("GRANTS_GOV","GRANTS_GOV",base+"/search",base+"/detail","global health",10)
            val result=GrantsGovOpportunityAdapter(ObjectMapper()).fetch(source)
            assertEquals(1,result.normalized.size)
            val item=result.normalized.first()
            assertEquals("123",item.externalId)
            assertEquals("Global Health Systems Call",item.title)
            assertEquals("USD",item.currency)
            assertEquals("Health systems research",item.summary)
            assertNotNull(item.closeAt)
        } finally { server.stop(0) }
    }

    @Test
    fun rssAdapterFiltersAndNormalizesRelevantCalls() {
        val server=HttpServer.create(InetSocketAddress(0),0)
        server.createContext("/feed") { x ->
            respond(x,"""<?xml version="1.0"?><rss version="2.0"><channel><item>
                <title>Global health implementation call</title>
                <link>https://example.org/call</link><guid>ukri-1</guid>
                <description><![CDATA[Opportunity status: Open. Funders: Medical Research Council. Opening date: 1 September 2026. Closing date: 15 December 2026.]]></description>
                <pubDate>Tue, 01 Sep 2026 09:00:00 GMT</pubDate>
            </item><item><title>Unrelated astronomy call</title><guid>other</guid><description>space only</description></item></channel></rss>""","application/rss+xml")
        }
        server.start()
        try{
            val source=source("UKRI","RSS","http://127.0.0.1:"+server.address.port+"/feed",null,"global health|medical",20)
            val result=RssOpportunityAdapter().fetch(source)
            assertEquals(1,result.normalized.size)
            val item=result.normalized.first()
            assertEquals("ukri-1",item.externalId)
            assertEquals("OPEN",item.sourceStatus)
            assertNotNull(item.closeAt)
        } finally { server.stop(0) }
    }

    @Test
    fun euAdapterNormalizesOpenFundingTopic() {
        val server=HttpServer.create(InetSocketAddress(0),0)
        server.createContext("/search") { x ->
            respond(x,"""{"results":[{"metadata":{"identifier":["EU-HEALTH-1"],"title":["Population health data call"],"status":["31094502"],"startDate":["2026-09-01"],"deadlineDate":["2026-12-20"],"url":["https://example.eu/topic"],"descriptionByte":["<p>Digital health and population research</p>"],"frameworkProgramme":["HORIZON"]}}]}""")
        }
        server.start()
        try{
            val source=source("EU","EU_FUNDING_TENDERS","http://127.0.0.1:"+server.address.port+"/search",null,"health",10)
            val result=EuFundingOpportunityAdapter(ObjectMapper()).fetch(source)
            assertEquals(1,result.normalized.size)
            val item=result.normalized.first()
            assertEquals("EU-HEALTH-1",item.externalId)
            assertEquals("OPEN",item.sourceStatus)
            assertEquals("EUR",item.currency)
            assertNotNull(item.closeAt)
        } finally { server.stop(0) }
    }

    private fun source(code:String,type:String,url:String,detail:String?,terms:String,limit:Int)=OpportunitySourceConfig(
        UUID.randomUUID(),code,code,type,url,detail,true,true,terms,limit,"OFFICIAL"
    )

    private fun respond(exchange:HttpExchange,body:String,contentType:String="application/json"){
        val bytes=body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type",contentType)
        exchange.sendResponseHeaders(200,bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
