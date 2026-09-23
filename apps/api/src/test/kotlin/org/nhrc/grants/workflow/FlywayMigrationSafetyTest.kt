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
    fun `Flyway versions are contiguous through current schema`() {
        val root = File("src/main/resources/db/migration")
        val versions = root.listFiles()?.filter { it.isFile && Regex("""V\d+__.+\.sql""").matches(it.name) }
            ?.map { Regex("""^V(\d+)__""").find(it.name)!!.groupValues[1].toInt() }?.sorted().orEmpty()
        assertTrue(versions.isNotEmpty(), "No Flyway migrations found")
        assertEquals((1..versions.last()).toList(), versions, "Flyway versions contain a gap or duplicate")
    }
}
