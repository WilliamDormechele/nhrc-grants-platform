package org.nhrc.grants.workflow
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.nhrc.grants.operations.QuestionnaireSchema
class QuestionnaireSchemaTest {
 private val mapper=ObjectMapper()
 @Test fun `legacy field identities retain readable labels`() {
   val fields=QuestionnaireSchema.fields(mapper.readTree("""{"fields":["application_version","reviews_complete"]}"""),mapper)
   assertEquals("application_version",fields[0].path("name").asText());assertEquals("Application version",fields[0].path("label").asText());assertFalse(fields[0].path("required").asBoolean())
 }
 @Test fun `sections and budget columns retain all existing questions`() {
   val fields=QuestionnaireSchema.fields(mapper.readTree("""{"sections":[{"title":"Scientific case","fields":["summary","methods"]},{"title":"Review","checks":["summary","evidence"]}],"columns":["currency"]}"""),mapper)
   assertEquals(listOf("currency","summary","methods","evidence"),fields.map {it.path("name").asText()});assertEquals("Scientific case",fields[1].path("section").asText())
 }
 @Test fun `typed required fields and choices are preserved`() {
   val fields=QuestionnaireSchema.fields(mapper.readTree("""{"fields":[{"name":"decision","label":"Decision","type":"select","required":true,"choices":["Yes","No"]}]}"""),mapper)
   assertTrue(fields[0].path("required").asBoolean());assertEquals("No",fields[0].path("choices")[1].asText())
 }
}
