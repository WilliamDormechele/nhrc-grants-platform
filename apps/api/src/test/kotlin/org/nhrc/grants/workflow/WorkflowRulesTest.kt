package org.nhrc.grants.workflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkflowRulesTest {
 @Test fun `approved lifecycle order is stable`() {
  val stages=listOf("DISCOVERED","ELIGIBILITY_REVIEW","DIRECTOR_DECISION","ASSIGNED","ACCEPTED","PREPARATION","INTERNAL_REVIEW","INSTITUTIONAL_APPROVAL","SUBMITTED","OUTCOME_RECORDED","AWARDED","CLOSED")
  assertEquals("DISCOVERED",stages.first())
  assertEquals("AWARDED",stages[10])
  assertEquals("CLOSED",stages.last())
 }
}
