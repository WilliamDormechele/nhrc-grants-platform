package org.nhrc.grants.procurement

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/procurement")
class ProcurementReadController(private val jdbc:JdbcTemplate){
 @GetMapping("/plans") fun plans(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select p.*,a.reference award_reference from procurement_plans p join awards a on a.id=p.award_id where (?='' or p.status=?) order by p.planned_date nulls last limit 300",s,s) }
 @GetMapping("/purchase-orders") fun purchaseOrders(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select po.*,s.name supplier_name,r.reference requisition_reference from purchase_orders po left join suppliers s on s.id=po.supplier_id join procurement_requisitions r on r.id=po.requisition_id where (?='' or po.status=?) order by po.expected_delivery_date nulls last limit 300",s,s) }
 @GetMapping("/assets") fun assets(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select x.*,a.reference award_reference from assets x left join awards a on a.id=x.award_id where (?='' or x.status=?) order by x.created_at desc limit 300",s,s) }
}
