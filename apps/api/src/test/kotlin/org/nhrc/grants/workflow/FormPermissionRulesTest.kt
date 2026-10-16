package org.nhrc.grants.workflow

import org.nhrc.grants.operations.FormRoutePermissions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FormPermissionRulesTest {
 @Test fun `all registered aliases resolve including record details`() {
  FormRoutePermissions.aliases.forEach {(path,key)->assertEquals(key,FormRoutePermissions.key(path));assertEquals(key,FormRoutePermissions.key("$path/00000000-0000-0000-0000-000000000001"))}
 }
 @Test fun `form permissions cannot capture unrelated or management routes`() {
  listOf("/api/auth/login","/api/operations/form-administration","/api/operations/questionnaire-permissions","/api/operations/accounts","/actuator/health","/api/finance/receipts-for-other-service","/api/awards-other","/api/approvals/id/decision").forEach {assertNull(FormRoutePermissions.key(it),it)}
 }
}
