package org.nhrc.grants.workbench

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.nio.ByteBuffer
import java.time.LocalDate
import java.util.concurrent.CompletionException
import java.util.concurrent.Flow

class FundingSourceClientTest {
    private val mapper=ObjectMapper()
    private val client=FundingSourceClient(mapper,MockEnvironment())
    @Test fun `Grants gov fixture preserves source reference and date without inventing a deadline time`() {
        val call=client.parse("GRANTS_GOV",mapper.readTree("""{"id":"219999","number":"TEST-CALL-001","title":"Public health research","agencyCode":"HHS","agencyName":"Health agency","closeDate":"10/11/2026","oppStatus":"posted"}"""))
        assertEquals("219999",call.externalId)
        assertEquals("TEST-CALL-001",call.reference)
        assertEquals(LocalDate.of(2026,10,11),call.closeDate)
        assertNull(call.summary)
        assertFalse(call.snapshot.containsKey("deadline_at"))
    }
    @Test fun `missing or ambiguous closing dates remain unknown and retain source text`() {
        val call=client.parse("GRANTS_GOV",mapper.readTree("""{"id":"219999","title":"A call","closeDate":"See call documentation"}"""))
        assertNull(call.closeDate)
        assertEquals("See call documentation",call.snapshot["published_close_date"])
        assertNull(client.parseDate("02/30/2026"))
    }
    @Test fun `Simpler flat and nested summaries are both supported`() {
        val flat=client.parse("SIMPLER_GRANTS_GOV",mapper.readTree("""{"opportunity_id":"12345678-1234-1234-1234-123456789012","opportunity_title":"A call","summary":"A source summary","close_date":"2026-11-01"}"""))
        val nested=client.parse("SIMPLER_GRANTS_GOV",mapper.readTree("""{"opportunity_id":"12345678-1234-1234-1234-123456789012","opportunity_title":"A call","summary":{"summary_description":"A source summary","close_date":"2026-11-01"}}"""))
        assertEquals(flat.summary,nested.summary)
        assertEquals(flat.closeDate,nested.closeDate)
    }
    @Test fun `source identifiers and required titles are validated`() {
        assertThrows(IllegalArgumentException::class.java) { client.parse("GRANTS_GOV",mapper.readTree("""{"id":"unexpected/path","title":"A call"}""")) }
        assertThrows(IllegalArgumentException::class.java) { client.parse("GRANTS_GOV",mapper.readTree("""{"id":"219999"}""")) }
        assertFalse(client.simplerConfigured())
    }
    @Test fun `oversized source streams are cancelled before buffering the full response`() {
        var cancelled=false
        val subscriber=LimitedFundingBody(4)
        subscriber.onSubscribe(object:Flow.Subscription { override fun request(n:Long){};override fun cancel(){cancelled=true} })
        subscriber.onNext(listOf(ByteBuffer.wrap(ByteArray(5))))
        assertTrue(cancelled)
        assertThrows(CompletionException::class.java) { subscriber.body.toCompletableFuture().join() }
    }
    @Test fun `bounded source streams return their complete bytes`() {
        val subscriber=LimitedFundingBody(10)
        subscriber.onSubscribe(object:Flow.Subscription {override fun request(n:Long){};override fun cancel(){}})
        subscriber.onNext(listOf(ByteBuffer.wrap("abc".toByteArray())))
        subscriber.onComplete()
        assertArrayEquals("abc".toByteArray(),subscriber.body.toCompletableFuture().join())
    }
}
