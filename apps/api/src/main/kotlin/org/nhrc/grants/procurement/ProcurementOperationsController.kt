package org.nhrc.grants.procurement

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/procurement")
class ProcurementOperationsController(private val jdbc:JdbcTemplate){
 @GetMapping("/suppliers") fun suppliers(@RequestParam(required=false) q:String?):List<Map<String,Any?>> { val t="%${q?:""}%";return jdbc.queryForList("select * from suppliers where lower(name) like lower(?) order by name limit 300",t) }
 @GetMapping("/contracts") fun contracts(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:"";return jdbc.queryForList("select c.*,sp.name supplier_name,po.reference purchase_order_reference from procurement_contracts c left join suppliers sp on sp.id=c.supplier_id left join purchase_orders po on po.id=c.purchase_order_id where (?='' or c.status=?) order by c.end_date nulls last limit 300",s,s) }
}
