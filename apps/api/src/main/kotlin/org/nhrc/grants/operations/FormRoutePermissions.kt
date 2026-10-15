package org.nhrc.grants.operations

/** Explicit aliases for the same operational records. Unrelated routes are not changed. */
object FormRoutePermissions {
 val aliases=mapOf(
 "/api/operations/system-events" to "events", "/api/it/security/events" to "events", "/api/it/integrations" to "integrations", "/api/it/support" to "support", "/api/it/access-reviews" to "access-reviews",
 "/api/opportunities" to "opportunities", "/api/awards" to "awards",
 "/api/award-management/setup" to "awards", "/api/award-management/contracts" to "agreements", "/api/contracts" to "agreements",
 "/api/award-management/partners" to "subawards", "/api/award-management/due-diligence" to "diligence", "/api/governance/due-diligence" to "diligence",
 "/api/award-management/amendments" to "amendments", "/api/award-management/deliverables" to "deliverables", "/api/award-management/risks" to "risks",
 "/api/award-management/financial-summary" to "positions", "/api/award-management/closeout" to "closeout",
 "/api/finance/receipts" to "receipts", "/api/finance/expenditure" to "expenditure", "/api/finance/commitments" to "commitments", "/api/finance/partner-advances" to "advances",
 "/api/finance/operations/reports" to "financial-reports", "/api/finance/operations/forecasts" to "forecasts", "/api/finance/operations/reconciliations" to "reconciliations", "/api/finance/operations/approvals" to "financial-approvals",
 "/api/procurement/plans" to "plans", "/api/procurement/requisitions" to "requisitions", "/api/procurement/purchase-orders" to "orders", "/api/procurement/suppliers" to "suppliers", "/api/procurement/contracts" to "procurement-contracts", "/api/procurement/assets" to "assets",
 "/api/laboratory/items" to "laboratory", "/api/laboratory/compliance" to "laboratory", "/api/laboratory/maintenance" to "maintenance",
 "/api/governance/compliance" to "compliance", "/api/governance/declarations" to "declarations",
 "/api/performance/reports" to "reports", "/api/performance/outputs" to "outputs", "/api/performance/impact" to "impact", "/api/performance/closeout" to "closeout",
 "/api/people/researchers" to "researchers", "/api/reference/researchers" to "researchers", "/api/people/partners" to "partners", "/api/reference/partners" to "partners", "/api/people/funders" to "funders", "/api/reference/funders" to "funders", "/api/people/institutional-profile" to "institution",
 "/api/organisation/units" to "units", "/api/configuration/forms" to "forms", "/api/configuration/workflows" to "workflows", "/api/admin/workflows/steps" to "workflow-steps", "/api/configuration/templates" to "templates", "/api/configuration/approval-rules" to "approval-rules",
 "/api/admin/delegations" to "delegations", "/api/admin/reference-data/themes" to "themes", "/api/reference/themes" to "themes", "/api/admin/reference-data/notification-rules" to "notification-rules", "/api/admin/reference-data/calendar-rules" to "calendar-rules", "/api/admin/records/retention" to "retention", "/api/admin/access-reviews" to "access-reviews", "/api/admin/support" to "support", "/api/admin/announcements" to "announcements", "/api/superadmin/announcements" to "announcements"
 )
 fun key(path:String):String?=aliases.keys.filter {path==it || path.startsWith("$it/")}.maxByOrNull {it.length}?.let {aliases[it]}
}

