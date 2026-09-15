package org.nhrc.grants.workflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FinancialConceptsTest {
 @Test fun `uncommitted budget excludes expenditure and commitments`() { val approved=1000; val expenditure=300; val commitments=200; assertEquals(500,approved-expenditure-commitments) }
}
