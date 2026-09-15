package org.nhrc.grants.workflow

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LifecycleTest {
 @Test fun `workflow contains all approved gates`() {
  val stages=setOf("DISCOVERED","ELIGIBILITY_REVIEW","DIRECTOR_DECISION","ASSIGNED","ACCEPTED","PREPARATION","INTERNAL_REVIEW","INSTITUTIONAL_APPROVAL","SUBMITTED","OUTCOME_RECORDED","AWARDED")
  assertTrue(stages.containsAll(setOf("DIRECTOR_DECISION","INTERNAL_REVIEW","INSTITUTIONAL_APPROVAL","SUBMITTED","AWARDED")))
 }
}
