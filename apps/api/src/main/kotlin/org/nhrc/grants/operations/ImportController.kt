package org.nhrc.grants.operations

import org.springframework.web.bind.annotation.*
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class CsvPreview(val dataset:String,val csv:String)
data class ImportCommit(val dataset:String,val rows:List<Map<String,Any?>>)

@RestController
@RequestMapping("/api/operations/import")
class ImportController(private val catalog:OperationalCatalog,private val operations:OperationalController) {
 private fun normalize(v:String)=v.trim().lowercase().replace('_',' ').replace(Regex("\\s+")," ")
 @PostMapping("/preview") fun preview(@RequestBody r:CsvPreview):Map<String,Any> {
   require(r.csv.length<=500000){"Import a CSV up to 500 KB"}
   val d=catalog.get(r.dataset);val parsed=parse(r.csv.removePrefix("\uFEFF"));require(parsed.isNotEmpty()){"CSV is empty"}
   val headers=parsed.first();require(headers.distinct().size==headers.size){"CSV headings must be unique"}
   val fields=headers.map { heading -> d.fields.find { normalize(it.label)==normalize(heading) || normalize(it.name)==normalize(heading) } ?: throw IllegalArgumentException("Unrecognised column: $heading") }
   val records=parsed.drop(1).filter { it.any { value -> value.isNotBlank() } };require(records.size<=100){"Import up to 100 records at a time"}
   val rows=mutableListOf<Map<String,Any?>>();val errors=mutableListOf<Map<String,Any>>()
   records.forEachIndexed { i,record ->
     try {
       require(record.size==headers.size){"Column count does not match the headings"}
       val values=fields.mapIndexed { j,f -> f.name to record[j].trim().takeIf { it.isNotBlank() } }.toMap().toMutableMap<String,Any?>()
       fields.forEach { f ->
         val value=values[f.name]?.toString() ?: return@forEach
         if(f.choices.isNotEmpty()) values[f.name]=f.choices.find { normalize(it)==normalize(value) } ?: value
         if(f.type=="checkbox") values[f.name]=when(value.lowercase()){ "true","yes","1" -> true;"false","no","0" -> false;else -> throw IllegalArgumentException("${f.label} must be Yes or No") }
         if(f.type=="reference" && !runCatching { UUID.fromString(value) }.isSuccess) {
           val key=when(f.reference){"entity" -> if(values["entity_type"]=="SUPPLIER") "suppliers" else "partners";"grant_entity" -> if(values["entity_type"]=="APPLICATION") "applications" else "awards";"financial_entity" -> throw IllegalArgumentException("Use the transaction identifier for ${f.label}");else -> f.reference}
           val matches=operations.options(key).filter { normalize(it["label"].toString())==normalize(value) }
           require(matches.size==1){"${f.label} must match one existing linked record"};values[f.name]=matches.first()["id"]
         }
       }
       operations.preflight(r.dataset,values);rows.add(values)
     } catch(ex:Exception){errors.add(mapOf("row" to i+2,"message" to (ex.message?.substringAfter('"')?.substringBeforeLast('"') ?: "Invalid record")))}
   }
   return mapOf("dataset" to r.dataset,"total" to records.size,"valid" to rows.size,"errors" to errors,"rows" to rows)
 }
 @PostMapping("/commit") @Transactional fun commit(@RequestBody r:ImportCommit):Map<String,Any> {
   require(r.rows.isNotEmpty() && r.rows.size<=100){"Import 1–100 validated records"}
   val created=r.rows.map { operations.create(r.dataset,RecordWrite(it,note="Validated CSV import"))[catalog.get(r.dataset).idField]!! }
   return mapOf("created" to created.size,"ids" to created)
 }
 private fun parse(csv:String):List<List<String>> {
   val rows=mutableListOf<List<String>>();var row=mutableListOf<String>();val cell=StringBuilder();var quoted=false;var i=0
   while(i<csv.length){val c=csv[i];when {
     c=='"' && quoted && i+1<csv.length && csv[i+1]=='"' -> {cell.append('"');i++}
     c=='"' -> quoted=!quoted
     c==',' && !quoted -> {row.add(cell.toString());cell.setLength(0)}
     (c=='\n'||c=='\r') && !quoted -> {row.add(cell.toString());cell.setLength(0);rows.add(row);row=mutableListOf();if(c=='\r'&&i+1<csv.length&&csv[i+1]=='\n')i++}
     else -> cell.append(c)
   };i++}
   require(!quoted){"CSV has an unclosed quoted field"};if(cell.isNotEmpty()||row.isNotEmpty()){row.add(cell.toString());rows.add(row)};return rows
 }
}
