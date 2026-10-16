package org.nhrc.grants.intelligence

/** Deterministic presentation of the authorised snapshot; never a model-generated answer. */
object RegisterAssistant {
 fun answer(question:String,sources:List<Map<String,Any?>>):String = buildString {
  appendLine("Register summary - no generative AI model is connected. This summary uses only the records shown below; it does not search the internet or change records.")
  if(listOf("scan","search","find","grant").any { question.lowercase().contains(it) })appendLine("To discover new funding calls, open Funding & Applications > Opportunity Intelligence and use the discovery controls. A recorded fit score is not an eligibility decision or funding probability.")
  for(source in sources) {
   val id=source["id"].toString()
   val rows=(source["evidence"] as? List<*>)?.filterIsInstance<Map<*,*>>().orEmpty()
   appendLine()
   appendLine("${source["label"]} [$id]")
   if(rows.isEmpty())appendLine("No records in this snapshot. This does not establish that no obligations or opportunities exist.")
   rows.take(if(id=="S4")4 else 5).forEach { row ->
    val title=row["title"]?:row["reference"]?:row["name"]?:"Recorded item"
    val fields=when(id) {
     "S1"->listOf("due_date","severity","workspace")
     "S2"->listOf("currency","approved_budget","expenditure","commitments","projection","variance","status")
     "S3"->listOf("deadline_at","institutional_fit_score","eligibility_status","discovery_review_status")
     else->listOf("status","detail")
    }
    appendLine("- ${text(title)}"+fields.filter {row[it]!=null}.joinToString("; ",prefix=if(fields.any{row[it]!=null})": " else "") {"${text(it)}: ${text(row[it])}"})
   }
   if(rows.size>5 && id!="S4")appendLine("Showing 5 of ${rows.size} snapshot records. Open the source workspace for the register.")
  }
  append("Confirm current record details and your assigned authority in the source workspace before acting.")
 }
 private fun text(value:Any?)=value.toString().replace('_',' ').replace('\n',' ').replace('\r',' ').take(700)
}
