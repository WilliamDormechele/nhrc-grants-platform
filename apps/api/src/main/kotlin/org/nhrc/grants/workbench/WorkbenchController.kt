package org.nhrc.grants.workbench

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@RestController
@RequestMapping("/api/workbench")
class WorkbenchController(private val auth: WorkbenchAuth,private val records: WorkbenchRecords,private val applications: WorkbenchApplications,private val discovery: FundingDiscoveryService,private val oversight: WorkbenchOversight,private val operations: WorkbenchOperations,private val dashboard: WorkbenchDashboard) {
    @GetMapping("/identity") fun identity(request: HttpServletRequest)=auth.identity(request)
    @GetMapping("/catalogue") fun catalogue(request: HttpServletRequest)=records.catalogue(auth.actor(request))
    @GetMapping("/records/{key}") fun list(@PathVariable key: String,@RequestParam(defaultValue="") q: String,@RequestParam(defaultValue="0") page: Int,@RequestParam(defaultValue="50") size: Int,request: HttpServletRequest)=records.list(key,auth.actor(request),q,page,size)
    @GetMapping("/records/{key}/{id}") fun record(@PathVariable key: String,@PathVariable id: UUID,request: HttpServletRequest)=records.get(key,id,auth.actor(request))
    @PostMapping("/records/{key}") fun create(@PathVariable key: String,@RequestBody input: GrantRecordInput,request: HttpServletRequest)=records.save(key,null,input,auth.actor(request))
    @PatchMapping("/records/{key}/{id}") fun update(@PathVariable key: String,@PathVariable id: UUID,@RequestBody input: GrantRecordInput,request: HttpServletRequest)=records.save(key,id,input,auth.actor(request))
    @PostMapping("/records/{key}/{id}/actions") fun action(@PathVariable key: String,@PathVariable id: UUID,@RequestBody input: GrantActionInput,request: HttpServletRequest)=records.action(key,id,input,auth.actor(request))
    @GetMapping("/records/{key}/{id}/history") fun history(@PathVariable key: String,@PathVariable id: UUID,request: HttpServletRequest)=records.history(key,id,auth.actor(request))
    @GetMapping("/lookups/{key}") fun lookups(@PathVariable key: String,@RequestParam(defaultValue="") q: String,request: HttpServletRequest)=records.lookups(key,auth.actor(request),q)
    @GetMapping("/applications/{id}") fun application(@PathVariable id: UUID,request: HttpServletRequest)=applications.get(id,auth.actor(request))
    @PutMapping("/applications/{id}/narrative") fun narrative(@PathVariable id: UUID,@RequestBody input: GrantRecordInput,request: HttpServletRequest)=applications.saveNarrative(id,input,auth.actor(request))
    @PostMapping("/applications/{id}/budget") fun budget(@PathVariable id: UUID,@RequestBody input: GrantRecordInput,request: HttpServletRequest)=applications.saveBudget(id,null,input,auth.actor(request))
    @PutMapping("/applications/{id}/budget/{lineId}") fun budgetLine(@PathVariable id: UUID,@PathVariable lineId: UUID,@RequestBody input: GrantRecordInput,request: HttpServletRequest)=applications.saveBudget(id,lineId,input,auth.actor(request))
    @DeleteMapping("/applications/{id}/budget/{lineId}") fun removeBudget(@PathVariable id: UUID,@PathVariable lineId: UUID,@RequestParam version: Long,request: HttpServletRequest)=applications.removeBudget(id,lineId,version,auth.actor(request))
    @PutMapping("/applications/{id}/budget-scenario") fun budgetScenario(@PathVariable id: UUID,@RequestBody input: GrantRecordInput,request: HttpServletRequest)=applications.selectBudget(id,input,auth.actor(request))
    @PostMapping("/applications/{id}/documents") fun document(@PathVariable id: UUID,@RequestBody input: GrantRecordInput,request: HttpServletRequest)=applications.addDocument(id,input,auth.actor(request))
    @PostMapping("/applications/{id}/actions") fun applicationAction(@PathVariable id: UUID,@RequestBody input: GrantActionInput,request: HttpServletRequest)=applications.action(id,input,auth.actor(request))
    @GetMapping("/sources") fun sources(request: HttpServletRequest)=discovery.sources(auth.actor(request))
    @PostMapping("/sources/search") fun import(@RequestBody input: FundingSearchInput,request: HttpServletRequest)=discovery.import(input,auth.actor(request))
    @GetMapping("/opportunities/{id}/source") fun source(@PathVariable id: UUID,request: HttpServletRequest)=discovery.sourceRecord(id,auth.actor(request))
    @PostMapping("/opportunities/{id}/source-details") fun details(@PathVariable id: UUID,request: HttpServletRequest)=discovery.fetchDetails(id,auth.actor(request))
    @GetMapping("/opportunities/{id}/matches") fun matches(@PathVariable id: UUID,request: HttpServletRequest)=discovery.matches(id,auth.actor(request))
    @GetMapping("/operations/{key}") fun operations(@PathVariable key: String,request: HttpServletRequest)=operations.list(key,auth.actor(request))
    @GetMapping("/dashboard") fun dashboard(request: HttpServletRequest)=dashboard.dashboard(auth.actor(request))
    @GetMapping("/summary") fun summary(request: HttpServletRequest)=oversight.summary(auth.actor(request))
    @GetMapping("/finance") fun finance(request: HttpServletRequest)=oversight.finance(auth.actor(request))
    @GetMapping("/personal") fun personal(request: HttpServletRequest)=oversight.personal(auth.actor(request))
    @PostMapping("/notifications/{id}/read") fun readNotification(@PathVariable id: UUID,request: HttpServletRequest)=oversight.readNotification(id,auth.actor(request))
    @GetMapping("/awards") fun awards(@RequestParam(defaultValue="") q: String,request: HttpServletRequest)=oversight.awards(auth.actor(request),q)
    @GetMapping("/awards/{id}") fun award(@PathVariable id: UUID,request: HttpServletRequest)=oversight.award(id,auth.actor(request))
    @PostMapping("/awards/{id}/actions") fun awardAction(@PathVariable id: UUID,@RequestBody input: GrantActionInput,request: HttpServletRequest)=oversight.awardAction(id,input,auth.actor(request))
}

/** The old unrestricted mutation endpoints must not provide an alternative route around the new gates. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE-100)
class LegacyGrantMutationGuard: OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.method !in setOf("POST","PUT","PATCH","DELETE") || !request.requestURI.startsWith("/api/") || request.requestURI.startsWith("/api/workbench/")
    override fun doFilterInternal(request: HttpServletRequest,response: HttpServletResponse,chain: FilterChain) {
        response.status=409
        response.contentType="application/json"
        response.characterEncoding="UTF-8"
        response.writer.write("{\"message\":\"This legacy write route is retired. Use the authorised workbench action so validation, ownership and approval gates are enforced.\"}")
    }
}
