package org.nhrc.grants.workflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class FlywayMigrationSafetyTest {
    @Test
    fun `Flyway versions are unique`() {
        val root = File("src/main/resources/db/migration")
        assertTrue(root.isDirectory, "Flyway migration directory is missing")
        val files = root.listFiles()?.filter { it.isFile && Regex("""V\d+__.+\.sql""").matches(it.name) }.orEmpty()
        val versions = files.groupBy { Regex("""^V(\d+)__""").find(it.name)!!.groupValues[1].toInt() }
        val duplicates = versions.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "Duplicate Flyway versions: " + duplicates.mapValues { e -> e.value.map { it.name } })
    }

    @Test
    fun `repeatable convergence migration is present`() {
        val repeatable = File("src/main/resources/db/migration/R__converge_nhrc_pregrant_schema.sql")
        assertTrue(repeatable.isFile, "Repeatable pre-grant convergence migration is missing")
    }

    @Test
    fun `base migrations are retained and new migrations follow the deployed baseline`() {
        val root = File("src/main/resources/db/migration")
        val versions = root.listFiles()?.filter { it.isFile && Regex("""V\d+__.+\.sql""").matches(it.name) }
            ?.map { Regex("""^V(\d+)__""").find(it.name)!!.groupValues[1].toInt() }?.sorted().orEmpty()
        assertTrue(versions.isNotEmpty(), "No Flyway migrations found")
        assertTrue(versions.containsAll((1..11).toList()), "An original base migration is missing")
        // UAT has no migration 12. Exact legacy versions 13-16 were restored from Git and checksum-verified.
        // Version gaps are valid in Flyway. Never fabricate historical scripts or reuse those versions.
        assertTrue(versions.containsAll((13..16).toList()) && versions.filter { it > 11 }.all { it >= 13 }, "Retain the recovered legacy migration baseline without introducing version 12")
    }
}
