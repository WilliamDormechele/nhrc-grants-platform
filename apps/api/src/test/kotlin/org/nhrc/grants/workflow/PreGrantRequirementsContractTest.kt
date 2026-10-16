package org.nhrc.grants.workflow

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreGrantRequirementsContractTest {
    @Test
    fun `NHRC pre-grant migration contains required operational controls`() {
        val sql = requireNotNull(javaClass.classLoader.getResource("db/migration/V011__nhrc_pregrant_operations.sql")).readText()
        listOf(
            "pregrant_expressions_of_interest",
            "application_team_members",
            "application_requirements",
            "funder_portal_records",
            "application_quality_checks",
            "funder_communications",
            "award_handovers"
        ).forEach { assertTrue(sql.contains(it), "Missing pre-grant control: $it") }
    }

    @Test
    fun `quality gate includes NHRC submission readiness controls`() {
        val sql = requireNotNull(javaClass.classLoader.getResource("db/migration/V011__nhrc_pregrant_operations.sql")).readText()
        listOf("COMPLETENESS","CONSISTENCY","FUNDER_COMPLIANCE","ATTACHMENTS","APPROVALS","SUBMISSION_READY")
            .forEach { assertTrue(sql.contains(it), "Missing quality check: $it") }
    }
}
