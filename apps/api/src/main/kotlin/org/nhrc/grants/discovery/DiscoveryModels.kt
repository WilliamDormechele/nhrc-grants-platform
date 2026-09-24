package org.nhrc.grants.discovery

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class OpportunitySourceConfig(
    val id: UUID,
    val code: String,
    val name: String,
    val adapterType: String,
    val endpointUrl: String,
    val detailEndpointUrl: String?,
    val enabled: Boolean,
    val scheduleEnabled: Boolean,
    val queryTerms: String?,
    val fetchLimit: Int,
    val trustLevel: String
) {
    fun terms(): List<String> = queryTerms?.split("|")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
}

data class ExternalOpportunity(
    val externalId: String,
    val title: String,
    val funderName: String?,
    val agencyCode: String?,
    val canonicalUrl: String?,
    val summary: String?,
    val currency: String?,
    val amountMin: BigDecimal?,
    val amountMax: BigDecimal?,
    val openAt: OffsetDateTime?,
    val closeAt: OffsetDateTime?,
    val sourceStatus: String?,
    val rawPayload: String
)

data class DiscoverySourceResult(
    val fetched: Int,
    val normalized: List<ExternalOpportunity>
)

data class DiscoveryRunSummary(
    val runId: UUID,
    val sourceCode: String,
    val status: String,
    val fetched: Int,
    val normalized: Int,
    val created: Int,
    val updated: Int,
    val duplicates: Int,
    val rejected: Int,
    val error: String? = null
)

interface ExternalOpportunityAdapter {
    fun supports(source: OpportunitySourceConfig): Boolean
    fun fetch(source: OpportunitySourceConfig): DiscoverySourceResult
}
