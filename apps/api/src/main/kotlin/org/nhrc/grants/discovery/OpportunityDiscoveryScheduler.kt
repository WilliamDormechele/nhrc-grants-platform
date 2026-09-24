package org.nhrc.grants.discovery

import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OpportunityDiscoveryScheduler(
    private val service:OpportunityDiscoveryService,
    @Value("\${nhrc.discovery.enabled:true}") private val enabled:Boolean
){
 @Scheduled(cron="\${nhrc.discovery.cron:0 15 6 * * *}",zone="\${nhrc.discovery.zone:UTC}")
 fun scheduledDiscovery(){
   if(enabled) service.runScheduled()
 }
}
