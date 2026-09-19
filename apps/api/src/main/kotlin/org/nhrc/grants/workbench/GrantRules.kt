package org.nhrc.grants.workbench

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Pure business rules; exercised independently of HTTP and the database. */
object GrantRules {
    private val maximumAmount = BigDecimal("999999999999999.99")
    val draftStages = setOf("DISCOVERED","ELIGIBILITY_REVIEW","DIRECTOR_DECISION","READY_FOR_ASSIGNMENT","ASSIGNED","ACCEPTED","PREPARATION")
    val narrativeEditableStages = setOf("ACCEPTED","PREPARATION")
    val reviewRoles = mapOf(
        "SCIENTIFIC" to setOf("RESEARCHER","DIRECTOR"),
        "FINANCE" to setOf("FINANCE_OFFICER","FINANCE_APPROVER"),
        "GRANTS" to setOf("GRANTS_OFFICER"),
        "GOVERNANCE" to setOf("GOVERNANCE_OFFICER")
    )
    val appActions = listOf(
        GrantTransition("START_ELIGIBILITY","Start eligibility review",setOf("DISCOVERED"),"ELIGIBILITY_REVIEW",setOf("GRANTS_OFFICER")),
        GrantTransition("RECORD_ELIGIBILITY","Record eligibility assessment",setOf("ELIGIBILITY_REVIEW"),"DIRECTOR_DECISION",setOf("GRANTS_OFFICER"),evidenceRequired=true),
        GrantTransition("DIRECTOR_DECISION","Record pursue / do not pursue decision",setOf("DIRECTOR_DECISION"),"READY_FOR_ASSIGNMENT",setOf("DIRECTOR"),true),
        GrantTransition("ASSIGN","Assign researcher",setOf("READY_FOR_ASSIGNMENT"),"ASSIGNED",setOf("GRANTS_OFFICER")),
        GrantTransition("ACCEPT","Accept assignment",setOf("ASSIGNED"),"ACCEPTED",setOf("RESEARCHER")),
        GrantTransition("DECLINE","Decline assignment",setOf("ASSIGNED"),"READY_FOR_ASSIGNMENT",setOf("RESEARCHER")),
        GrantTransition("START_PREPARATION","Start proposal preparation",setOf("ACCEPTED"),"PREPARATION",setOf("RESEARCHER","GRANTS_OFFICER")),
        GrantTransition("REQUEST_REVIEW","Request internal review",setOf("PREPARATION"),"INTERNAL_REVIEW",setOf("RESEARCHER","GRANTS_OFFICER")),
        GrantTransition("COMPLETE_REVIEW","Record assigned review",setOf("INTERNAL_REVIEW"),"INTERNAL_REVIEW",reviewRoles.values.flatten().toSet()),
        GrantTransition("APPROVE","Authorise submission package",setOf("INSTITUTIONAL_APPROVAL"),"APPROVED_FOR_SUBMISSION",setOf("DIRECTOR"),true),
        GrantTransition("RETURN","Return for revision",setOf("INSTITUTIONAL_APPROVAL","APPROVED_FOR_SUBMISSION"),"PREPARATION",setOf("DIRECTOR")),
        GrantTransition("RECORD_SUBMISSION","Record external submission",setOf("APPROVED_FOR_SUBMISSION"),"SUBMITTED",setOf("GRANTS_OFFICER"),evidenceRequired=true),
        GrantTransition("RECORD_OUTCOME","Record funder outcome",setOf("SUBMITTED"),"OUTCOME_RECORDED",setOf("GRANTS_OFFICER"),evidenceRequired=true),
        GrantTransition("NEXT_STAGE","Start invited next-stage application",setOf("OUTCOME_RECORDED"),"OUTCOME_RECORDED",setOf("GRANTS_OFFICER"),evidenceRequired=true),
        GrantTransition("CONVERT_AWARD","Create award setup record",setOf("OUTCOME_RECORDED"),"AWARDED",setOf("GRANTS_OFFICER"),evidenceRequired=true),
        GrantTransition("WITHDRAW","Withdraw internal application",draftStages,"CLOSED",setOf("GRANTS_OFFICER"))
    ).associateBy { it.code }

    fun validate(fields: List<GrantField>, values: Map<String, Any?>): Map<String, Any?> {
        val unknown = values.keys - fields.map { it.name }.toSet()
        require(unknown.isEmpty()) { "Unknown or protected fields: ${unknown.joinToString()}" }
        return fields.associate { f ->
            val raw = values[f.name] ?: f.defaultValue
            val blank = raw == null || (raw is String && raw.isBlank())
            require(!f.required || !blank) { "${f.label} is required" }
            val value: Any? = if(blank) null else when(f.kind) {
                "tags" -> {
                    val items = when(raw) {
                        is List<*> -> raw.map { require(it is String) { "${f.label} must contain text" }; it.trim() }
                        is String -> raw.split(',').map { it.trim() }
                        else -> throw IllegalArgumentException("${f.label} must be a list of text values")
                    }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
                    require(items.size <= 50 && items.all { it.length <= f.maxLength }) { "${f.label} has too many or overly long entries" }
                    items
                }
                "boolean" -> when(raw) { true,"true" -> true; false,"false" -> false; else -> throw IllegalArgumentException("${f.label} must be yes or no") }
                "money", "decimal" -> amount(raw.toString(),if(f.kind=="money") 2 else 4,f.label)
                "integer" -> raw.toString().toIntOrNull()?.also { require(it in 0..1000000) { "${f.label} is outside the permitted range" } }
                    ?: throw IllegalArgumentException("${f.label} must be a whole number")
                else -> {
                    require(raw is String) { "${f.label} must be text" }
                    val value = raw.trim()
                    require(value.length <= f.maxLength) { "${f.label} is too long" }
                    require(value.none { it.code < 32 && it !in "\n\r\t" }) { "${f.label} contains unsupported characters" }
                    when(f.kind) {
                        "date" -> LocalDate.parse(value).toString()
                        "datetime" -> OffsetDateTime.parse(value).toString()
                        "lookup" -> UUID.fromString(value).toString()
                        "url" -> safeUrl(value)
                        "email" -> value.also { require(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(it)) { "${f.label} is not a valid email address" } }
                        "orcid" -> value.also { require(validOrcid(it)) { "The ORCID check digit is invalid" } }
                        "select" -> value.also { require(it in f.choices) { "${f.label} is not one of the permitted choices" } }
                        else -> value
                    }
                }
            }
            f.name to value
        }
    }
    fun amount(value: String, scale: Int = 2, label: String = "Amount"): BigDecimal {
        val n = value.toBigDecimalOrNull() ?: throw IllegalArgumentException("$label must be a decimal number")
        require(n >= BigDecimal.ZERO && n <= maximumAmount) { "$label must be non-negative and within the supported range" }
        require(n.stripTrailingZeros().scale() <= scale) { "$label supports at most $scale decimal places" }
        return n.setScale(scale)
    }
    fun safeUrl(value: String): String {
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "Use an HTTPS link without embedded credentials" }
        require(value.length <= 2000) { "The evidence link is too long" }
        return uri.toASCIIString()
    }
    fun validOrcid(value: String): Boolean {
        if(!Regex("^\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dX]$").matches(value)) return false
        val compact=value.replace("-","")
        var total=0
        compact.take(15).forEach { total=(total+it.digitToInt())*2 }
        val remainder=(12-total%11)%11
        return compact.last()==if(remainder==10) 'X' else ('0'.code+remainder).toChar()
    }
    fun validateDates(values: Map<String,Any?>, start: String, end: String) {
        val a=values[start]?.toString(); val b=values[end]?.toString()
        require(a.isNullOrBlank() || b.isNullOrBlank() || LocalDate.parse(a) <= LocalDate.parse(b)) { "The end date must not precede the start date" }
    }
    fun budgetTotal(quantity: BigDecimal, unitCost: BigDecimal, funder: BigDecimal, nhrc: BigDecimal, partner: BigDecimal): BigDecimal {
        require(quantity > BigDecimal.ZERO) { "Quantity must be greater than zero" }
        listOf(quantity,unitCost,funder,nhrc,partner).forEach { require(it >= BigDecimal.ZERO && it <= maximumAmount) { "Budget amounts are outside the permitted range" } }
        val total=(quantity*unitCost).setScale(2,RoundingMode.HALF_UP)
        require(total <= maximumAmount) { "The line total exceeds the supported range" }
        require((funder+nhrc+partner).compareTo(total)==0) { "Funder contribution plus NHRC and partner cost share must equal quantity times unit cost" }
        return total
    }
    fun readiness(type: String, narrative: Map<String,Any?>): Int {
        val required=WorkbenchCatalogue.requiredNarrative(type)
        val complete=required.count { !narrative[it]?.toString().isNullOrBlank() }
        return if(required.isEmpty()) 0 else complete*100/required.size
    }
    fun riskRating(likelihood: String, impact: String): String {
        val scores=mapOf("LOW" to 1,"MEDIUM" to 2,"HIGH" to 3)
        val score=requireNotNull(scores[likelihood])*requireNotNull(scores[impact])
        return if(score>=6) "HIGH" else if(score>=3) "MEDIUM" else "LOW"
    }
    fun matchedTerms(terms: List<String>, title: String, summary: String, keywords: List<String>): List<String> {
        val haystack=(listOf(title,summary)+keywords).joinToString(" ").lowercase()
        return terms.map { it.trim() }.filter { it.length >= 3 }.distinctBy { it.lowercase() }.filter {
            Regex("(?<![\\p{L}\\p{N}])"+Regex.escape(it.lowercase())+"(?![\\p{L}\\p{N}])").containsMatchIn(haystack)
        }
    }
    fun requireAction(action: String, stage: String, roles: Set<String>): GrantTransition {
        val definition=appActions[action] ?: throw IllegalArgumentException("Unknown application action")
        require(stage in definition.from) { "${definition.label} is not available at stage $stage" }
        if(roles.none { it in definition.roles }) throw ResponseStatusException(HttpStatus.FORBIDDEN,"Your role does not permit this action")
        return definition
    }
}
