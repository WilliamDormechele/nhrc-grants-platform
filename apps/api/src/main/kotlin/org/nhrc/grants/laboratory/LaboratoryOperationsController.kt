package org.nhrc.grants.laboratory

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/laboratory")
class LaboratoryOperationsController(private val jdbc:JdbcTemplate){
 @GetMapping("/maintenance") fun maintenance(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select m.*,l.name item_name,l.catalogue_or_asset_ref from laboratory_maintenance m join laboratory_items l on l.id=m.laboratory_item_id where (?='' or m.status=?) order by m.scheduled_date limit 300",s,s) }
 @GetMapping("/compliance") fun compliance():List<Map<String,Any?>> = jdbc.queryForList("select l.id,l.name,l.item_type,l.expiry_date,l.calibration_due_date,l.maintenance_due_date,l.status,a.reference award_reference from laboratory_items l left join awards a on a.id=l.award_id where l.expiry_date<=current_date+60 or l.calibration_due_date<=current_date+60 or l.maintenance_due_date<=current_date+60 order by least(coalesce(l.expiry_date,'9999-12-31'),coalesce(l.calibration_due_date,'9999-12-31'),coalesce(l.maintenance_due_date,'9999-12-31')) limit 300")
}
