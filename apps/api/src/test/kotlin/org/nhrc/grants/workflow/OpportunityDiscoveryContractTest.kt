package org.nhrc.grants.workflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.nhrc.grants.discovery.OpportunitySourceConfig
import java.util.UUID

class OpportunityDiscoveryContractTest {
    @Test
    fun `discovery schema contains provenance run and deduplication controls`() {
        val sql = requireNotNull(javaClass.classLoader.getResource("db/migration/R__converge_nhrc_pregrant_schema.sql")).readText()
        listOf(
            "opportunity_sources",
            "opportunity_discovery_runs",
            "opportunity_source_evidence",
            "opportunity_discovery_keys",
            "discovery_review_status",
            "GRANTS_GOV",
            "UKRI_FUNDING_FINDER"
        ).forEach { assertTrue(sql.contains(it), "Missing discovery contract: $it") }
    }

    @Test
    fun `source query terms are normalized from governed configuration`() {
        val source = OpportunitySourceConfig(
            UUID.randomUUID(),"TEST","Test","RSS","https://example.org/feed",null,
            true,true,"global health| data science | |implementation",25,"OFFICIAL"
        )
        assertEquals(listOf("global health","data science","implementation"), source.terms())
    }
}
