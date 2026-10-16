package org.nhrc.grants.workflow
import org.nhrc.grants.config.LegacyBaselineBootstrap
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
class LegacyBaselineBootstrapTest {
 private fun context(version:String):Context {val context=mock(Context::class.java);val info=mock(MigrationInfo::class.java);`when`(context.migrationInfo).thenReturn(info);`when`(info.version).thenReturn(MigrationVersion.fromVersion(version));return context}
 @Test fun `Flyway callback discovery accepts a null preparation context`(){assertTrue(LegacyBaselineBootstrap().supports(Event.BEFORE_EACH_MIGRATE,null));assertFalse(LegacyBaselineBootstrap().supports(Event.BEFORE_MIGRATE,null))}
 @Test fun `prepare only immediately before the recovered version thirteen`(){assertTrue(LegacyBaselineBootstrap().supports(Event.BEFORE_EACH_MIGRATE,context("013")))}
 @Test fun `existing installations beyond the legacy baseline are untouched`(){assertFalse(LegacyBaselineBootstrap().supports(Event.BEFORE_EACH_MIGRATE,context("022")));assertFalse(LegacyBaselineBootstrap().supports(Event.BEFORE_EACH_MIGRATE,context("014")))}
 @Test fun `other callback events do not execute bootstrap`(){assertFalse(LegacyBaselineBootstrap().supports(Event.AFTER_EACH_MIGRATE,context("013")))}
}
