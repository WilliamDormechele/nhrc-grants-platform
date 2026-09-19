package org.nhrc.grants.workbench

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.DateTimeException

class GrantRulesTest {
    @Test fun `catalogue identifiers are an explicit safe allow list`() {
        val identifier=Regex("^[a-z_]+$")
        for(resource in WorkbenchCatalogue.resources.values) {
            assertTrue(identifier.matches(resource.table))
            assertEquals(resource.fields.size,resource.fields.map { it.name }.toSet().size)
            resource.fields.forEach { assertTrue(identifier.matches(it.name),it.name) }
            assertFalse(resource.writers.contains("SUPERADMIN"))
            assertFalse(resource.writers.contains("IT_ADMIN"))
        }
    }
    @Test fun `application lifecycle writes the stage column not a fictional status column`() {
        assertEquals("stage",WorkbenchCatalogue.resources.getValue("applications").statusColumn)
    }
    @Test fun `every authorised approver can read their queue`() {
        for(resource in WorkbenchCatalogue.resources.values) for(action in resource.transitions) assertTrue(resource.readers.containsAll(action.roles),"${resource.key}: ${action.code}")
    }
    @Test fun `all form fields and status columns exist in additive migration history`() {
        val files=Files.list(Path.of("src/main/resources/db/migration")).use { paths -> paths.filter { it.toString().endsWith(".sql") }.sorted().toList() }
        val sql=files.joinToString("\n") { Files.readString(it) }
        for(resource in WorkbenchCatalogue.resources.values) {
            val table=Regex("CREATE TABLE\\s+(?:IF NOT EXISTS\\s+)?${resource.table}\\s*\\((.*?)\\);",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)).find(sql)?.groupValues?.get(1)
            assertNotNull(table,"Missing table ${resource.table}")
            val columns=resource.fields.map { it.name }+listOfNotNull(resource.statusColumn,resource.creatorColumn)
            for(column in columns) {
                val inCreate=Regex("(?:^|,)\\s*$column\\s+",setOf(RegexOption.IGNORE_CASE)).containsMatchIn(table.orEmpty())
                val inAlter=Regex("ALTER TABLE\\s+${resource.table}\\s+ADD COLUMN\\s+(?:IF NOT EXISTS\\s+)?$column\\s+",RegexOption.IGNORE_CASE).containsMatchIn(sql)
                assertTrue(inCreate||inAlter,"${resource.table}.$column is not in the migration contract")
            }
        }
    }
    @Test fun `protected fields cannot be supplied through a form`() {
        assertThrows(IllegalArgumentException::class.java) { GrantRules.validate(listOf(WorkbenchCatalogue.text("title","Title",true)),mapOf("title" to "A proposal","stage" to "AWARDED")) }
    }
    @Test fun `missing required values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { GrantRules.validate(listOf(WorkbenchCatalogue.text("title","Title",true)),emptyMap()) }
    }
    @Test fun `unknown funding amounts remain unknown rather than becoming zero`() {
        val fields=WorkbenchCatalogue.resources.getValue("opportunities").fields.filter { it.name in setOf("amount_min","amount_max") }
        val values=GrantRules.validate(fields,emptyMap())
        assertNull(values["amount_min"]);assertNull(values["amount_max"])
    }
    @Test fun `an expected instalment does not acquire a fictitious received amount`() {
        val field=WorkbenchCatalogue.resources.getValue("receipts").fields.single { it.name=="received_amount" }
        assertNull(GrantRules.validate(listOf(field),emptyMap())["received_amount"])
    }
    @Test fun `money rejects negative amounts excessive precision and scientific overflow`() {
        listOf("-1","1.001","1e1000","NaN").forEach { value -> assertThrows(IllegalArgumentException::class.java) { GrantRules.amount(value) } }
        assertEquals(BigDecimal("123.40"),GrantRules.amount("123.4"))
    }
    @Test fun `budget funding sources must balance to the rounded line cost`() {
        assertEquals(BigDecimal("125.00"),GrantRules.budgetTotal(BigDecimal("2.5"),BigDecimal("50"),BigDecimal("100"),BigDecimal("20"),BigDecimal("5")))
        assertThrows(IllegalArgumentException::class.java) { GrantRules.budgetTotal(BigDecimal.ONE,BigDecimal("120000"),BigDecimal("120000"),BigDecimal("10000"),BigDecimal.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { GrantRules.budgetTotal(BigDecimal.ZERO,BigDecimal("50"),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO) }
    }
    @Test fun `ORCID validation includes its check digit`() {
        assertTrue(GrantRules.validOrcid("0000-0002-1825-0097"))
        assertFalse(GrantRules.validOrcid("0000-0002-1825-0098"))
        assertFalse(GrantRules.validOrcid("0000-0000-0000-0000"))
    }
    @Test fun `evidence links cannot use executable schemes or embedded credentials`() {
        assertEquals("https://example.org/evidence",GrantRules.safeUrl("https://example.org/evidence"))
        listOf("javascript:alert(1)","file:///private/file","https://username@example.org/evidence").forEach { value -> assertThrows(IllegalArgumentException::class.java) { GrantRules.safeUrl(value) } }
    }
    @Test fun `exact deadlines require a timezone`() {
        val field=GrantField("deadline_at","Deadline","datetime")
        assertThrows(DateTimeException::class.java) { GrantRules.validate(listOf(field),mapOf("deadline_at" to "2026-10-01T15:00:00")) }
        assertNotNull(GrantRules.validate(listOf(field),mapOf("deadline_at" to "2026-10-01T15:00:00+01:00"))["deadline_at"])
    }
    @Test fun `readiness is calculated from saved sections and differs by submission type`() {
        assertEquals(0,GrantRules.readiness("SOI",emptyMap()))
        val soi=WorkbenchCatalogue.requiredNarrative("SOI").associateWith { "Completed draft text" }
        assertEquals(100,GrantRules.readiness("SOI",soi))
        assertTrue(GrantRules.readiness("FULL_PROPOSAL",soi)<100)
    }
    @Test fun `stage actions cannot skip gates or award technical administrators business power`() {
        assertThrows(IllegalArgumentException::class.java) { GrantRules.requireAction("ASSIGN","DISCOVERED",setOf("GRANTS_OFFICER")) }
        assertThrows(IllegalArgumentException::class.java) { GrantRules.requireAction("APPROVE","INSTITUTIONAL_APPROVAL",setOf("SUPERADMIN")) }
        assertThrows(IllegalArgumentException::class.java) { GrantRules.requireAction("RECORD_OUTCOME","PREPARATION",setOf("GRANTS_OFFICER")) }
        assertEquals("SUBMITTED",GrantRules.requireAction("RECORD_SUBMISSION","APPROVED_FOR_SUBMISSION",setOf("GRANTS_OFFICER")).to)
    }
    @Test fun `researcher matching uses visible phrase overlap not an inferred success score`() {
        assertEquals(listOf("data science"),GrantRules.matchedTerms(listOf("data science","malaria"),"Data science for antimalarial research","",emptyList()))
        assertEquals(emptyList<String>(),GrantRules.matchedTerms(emptyList(),"Health","",emptyList()))
    }
    @Test fun `tag lists are bounded and deduplicated without case sensitivity`() {
        assertEquals(listOf("Health"),GrantRules.validate(listOf(WorkbenchCatalogue.tags("themes","Themes")),mapOf("themes" to listOf("Health","health")))["themes"])
        assertThrows(IllegalArgumentException::class.java) { GrantRules.validate(listOf(WorkbenchCatalogue.tags("themes","Themes")),mapOf("themes" to (1..51).map { "Theme $it" })) }
    }
    @Test fun `reversed date periods are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { GrantRules.validateDates(mapOf("start" to "2026-11-10","end" to "2026-10-10"),"start","end") }
    }
}
