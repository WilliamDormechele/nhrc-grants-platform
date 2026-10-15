package org.nhrc.grants.operations
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
object QuestionnaireSchema {
 fun fields(source:JsonNode,mapper:ObjectMapper):ArrayNode {
   val result=mapper.createArrayNode();val seen=mutableSetOf<String>()
   fun collect(items:JsonNode,section:String="") {
     if(!items.isArray)return
     items.forEach { raw ->
       val name=if(raw.isTextual)raw.asText() else raw.path("name").asText()
       if(name.isBlank() || !seen.add(name))return@forEach
       val q=if(raw.isObject)raw.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>() else mapper.createObjectNode().put("name",name).put("type","text").put("required",false)
       if(q.path("label").asText().isBlank())q.put("label",name.replace('_',' ').replaceFirstChar {it.uppercase()})
       if(section.isNotBlank() && q.path("section").asText().isBlank())q.put("section",section)
       result.add(q)
     }
   }
   collect(source.path("fields"));collect(source.path("columns"));collect(source.path("checks"));collect(source.path("areas"))
   source.path("sections").forEach {section -> collect(section.path("fields"),section.path("title").asText());collect(section.path("checks"),section.path("title").asText())}
   return result
 }
 fun normalize(source:JsonNode,mapper:ObjectMapper):JsonNode=mapper.createObjectNode().set<JsonNode>("fields",fields(source,mapper))
}
