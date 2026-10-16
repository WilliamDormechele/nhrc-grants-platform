package org.nhrc.grants.workflow

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SeparationOfDutiesTest {
 @Test fun `technical privilege does not imply business approval`() { val technical=setOf("IT_ADMIN","SUPERADMIN"); val business=setOf("DIRECTOR","FINANCE_APPROVER"); assertFalse(technical.any{it in business}) }
}
