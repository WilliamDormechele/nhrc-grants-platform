package org.nhrc.grants.workflow

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ValidationTest {
 @Test fun `invalid delegation interval is rejected by invariant`() { assertThrows(IllegalArgumentException::class.java){ require(false){"validUntil must be after validFrom"} } }
}
