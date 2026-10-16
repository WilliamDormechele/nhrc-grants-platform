package org.nhrc.grants.config

import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/** Fresh installations need dependencies that preceded the recovered immutable V013.
 * Existing installations beyond V013 are untouched. No historical version is invented.
 */
@Component
class LegacyBaselineBootstrap:Callback {
 override fun supports(event:Event,context:Context?)=event==Event.BEFORE_EACH_MIGRATE && (context==null || context.migrationInfo?.version?.toString()?.toIntOrNull()==13)
 override fun canHandleInTransaction(event:Event,context:Context?)=true
 override fun getCallbackName()="prepare-recovered-legacy-dependencies"
 override fun handle(event:Event,context:Context?) {
  if(context==null || !supports(event,context))return
  context.connection.createStatement().use { statement ->
   for(path in listOf("db/migration/R__converge_nhrc_pregrant_schema.sql","db/bootstrap/legacy-v013-dependencies.sql")) {
    val sql=ClassPathResource(path).inputStream.bufferedReader(Charsets.UTF_8).use{it.readText()}
    statement.execute(sql)
   }
  }
 }
}
