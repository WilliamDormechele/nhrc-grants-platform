package org.nhrc.grants.opportunity

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.math.ceil

data class InstitutionalProfile(
    val id:UUID,
    val code:String,
    val name:String,
    val countryCode:String?,
    val organisationType:String?,
    val mission:String?,
    val strategicThemes:List<String>,
    val capabilities:List<String>,
    val methodsPlatforms:List<String>,
    val populationsContexts:List<String>,
    val geographyKeywords:List<String>
)

data class FitAssessmentResult(
    val opportunityId:UUID,
    val totalScore:BigDecimal,
    val themeScore:BigDecimal,
    val capabilityScore:BigDecimal,
    val researcherScore:BigDecimal,
    val contextScore:BigDecimal,
    val matchedThemes:List<String>,
    val matchedCapabilities:List<String>,
    val matchedContexts:List<String>,
    val bestResearcher:String?,
    val modelVersion:String
)

@Service
class InstitutionalFitService(
    private val jdbc:JdbcTemplate,
    private val mapper:ObjectMapper
){
    private val modelVersion="NHRC_FIT_V2"

    fun activeProfile():InstitutionalProfile =
        jdbc.query(
            """select id,code,name,country_code,organisation_type,mission,strategic_themes,capabilities,methods_platforms,
                      populations_contexts,geography_keywords
               from institutional_profiles where active=true order by code limit 1"""
        ){rs,_ ->
            InstitutionalProfile(
                rs.getObject("id",UUID::class.java),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("country_code"),
                rs.getString("organisation_type"),
                rs.getString("mission"),
                stringArray(rs.getArray("strategic_themes")?.array),
                stringArray(rs.getArray("capabilities")?.array),
                stringArray(rs.getArray("methods_platforms")?.array),
                stringArray(rs.getArray("populations_contexts")?.array),
                stringArray(rs.getArray("geography_keywords")?.array)
            )
        }.firstOrNull() ?: throw IllegalStateException("No active institutional fit profile is configured")

    @Transactional
    fun recalculate(opportunityId:UUID):FitAssessmentResult{
        val row=jdbc.queryForMap("select title,summary from opportunities where id=?",opportunityId)
        return assess(opportunityId,(row["title"]?:"").toString(),row["summary"]?.toString())
    }

    @Transactional
    fun assess(opportunityId:UUID,title:String,summary:String?):FitAssessmentResult{
        val profile=activeProfile()
        val sourceText=normalize(title+" "+(summary?:""))

        val dbThemes=jdbc.queryForList("select id,code,name from research_themes where active=true order by name")
        val themeTerms=(profile.strategicThemes + dbThemes.map{(it["name"]?:"").toString()}).filter{it.isNotBlank()}.distinct()
        val matchedThemes=themeTerms.filter{matches(sourceText,it)}.distinct()
        val themeScore=componentScore(matchedThemes.size,40.0,15.0,7.5)

        for(theme in dbThemes){
            val name=(theme["name"]?:"").toString()
            if(matches(sourceText,name)){
                jdbc.update("insert into opportunity_themes(opportunity_id,theme_id) values (?,?) on conflict do nothing",opportunityId,theme["id"])
            }
        }

        val capabilityTerms=(profile.capabilities+profile.methodsPlatforms).filter{it.isNotBlank()}.distinct()
        val matchedCapabilities=capabilityTerms.filter{matches(sourceText,it)}
        val capabilityScore=componentScore(matchedCapabilities.size,25.0,8.0,4.0)

        val contextTerms=(profile.populationsContexts+profile.geographyKeywords).filter{it.isNotBlank()}.distinct()
        val matchedContexts=contextTerms.filter{matches(sourceText,it)}
        val contextScore=componentScore(matchedContexts.size,15.0,5.0,3.0)

        data class Researcher(val id:UUID,val name:String,val expertise:List<String>)
        val researchers=jdbc.query(
            """select rp.id,u.display_name,rp.expertise
               from researcher_profiles rp join users u on u.id=rp.user_id
               where rp.active=true"""
        ){rs,_ ->
            Researcher(
                rs.getObject("id",UUID::class.java),
                rs.getString("display_name"),
                stringArray(rs.getArray("expertise")?.array)
            )
        }

        var bestResearcher:Researcher?=null
        var bestResearcherTerms:List<String> = emptyList()
        for(researcher in researchers){
            val matched=researcher.expertise.filter{matches(sourceText,it)}.distinct()
            if(matched.size>bestResearcherTerms.size){
                bestResearcher=researcher
                bestResearcherTerms=matched
            }
            if(matched.isNotEmpty()){
                val individualScore=minOf(95.0,50.0 + matched.size*10.0 + matchedThemes.size*3.0)
                val rationale="Matched researcher expertise: "+matched.joinToString(", ")+
                    if(matchedThemes.isNotEmpty()) ". Institutional themes: "+matchedThemes.take(4).joinToString(", ") else ""
                jdbc.update(
                    """insert into opportunity_researcher_matches(opportunity_id,researcher_profile_id,fit_score,rationale,human_confirmed)
                       values (?,?,?,?,false)
                       on conflict (opportunity_id,researcher_profile_id) do update
                       set fit_score=excluded.fit_score,rationale=excluded.rationale
                       where opportunity_researcher_matches.human_confirmed=false""",
                    opportunityId,researcher.id,bd(individualScore),rationale
                )
            }
        }
        val researcherScore=componentScore(bestResearcherTerms.size,20.0,8.0,4.0)
        val total=bd(themeScore.toDouble()+capabilityScore.toDouble()+researcherScore.toDouble()+contextScore.toDouble())

        val rationale=buildString{
            append("Institutional fit uses NHRC_FIT_V2: thematic alignment 40%, institutional capabilities/platforms 25%, ")
            append("researcher expertise capacity 20%, and population/geographic context 15%. ")
            append("Theme matches: ").append(if(matchedThemes.isEmpty()) "none" else matchedThemes.joinToString(", ")).append(". ")
            append("Capability/platform matches: ").append(if(matchedCapabilities.isEmpty()) "none" else matchedCapabilities.joinToString(", ")).append(". ")
            append("Context matches: ").append(if(matchedContexts.isEmpty()) "none" else matchedContexts.joinToString(", ")).append(". ")
            append("Best researcher evidence: ").append(
                if(bestResearcher==null||bestResearcherTerms.isEmpty()) "no expertise match"
                else bestResearcher.name+" ("+bestResearcherTerms.joinToString(", ")+")"
            ).append(". Fit is relevance, not probability of funding or eligibility.")
        }

        val breakdown=mapOf(
            "modelVersion" to modelVersion,
            "weights" to mapOf("themes" to 40,"capabilities" to 25,"researcherCapacity" to 20,"contextGeography" to 15),
            "scores" to mapOf("themes" to themeScore,"capabilities" to capabilityScore,"researcherCapacity" to researcherScore,"contextGeography" to contextScore),
            "matchedThemes" to matchedThemes,
            "matchedCapabilities" to matchedCapabilities,
            "matchedContexts" to matchedContexts,
            "bestResearcher" to bestResearcher?.name,
            "bestResearcherTerms" to bestResearcherTerms
        )

        jdbc.update(
            """insert into opportunity_fit_assessments(
                   opportunity_id,institutional_profile_id,theme_score,capability_score,researcher_score,context_score,total_score,
                   matched_themes,matched_capabilities,matched_contexts,best_researcher_profile_id,researcher_rationale,model_version,assessed_at)
               values (?,?,?,?,?,?,?,?,?,?,?,?,?,now())
               on conflict (opportunity_id) do update set
                   institutional_profile_id=excluded.institutional_profile_id,theme_score=excluded.theme_score,
                   capability_score=excluded.capability_score,researcher_score=excluded.researcher_score,
                   context_score=excluded.context_score,total_score=excluded.total_score,
                   matched_themes=excluded.matched_themes,matched_capabilities=excluded.matched_capabilities,
                   matched_contexts=excluded.matched_contexts,best_researcher_profile_id=excluded.best_researcher_profile_id,
                   researcher_rationale=excluded.researcher_rationale,model_version=excluded.model_version,assessed_at=now()""",
            opportunityId,profile.id,themeScore,capabilityScore,researcherScore,contextScore,total,
            matchedThemes.toTypedArray(),matchedCapabilities.toTypedArray(),matchedContexts.toTypedArray(),
            bestResearcher?.id,
            if(bestResearcherTerms.isEmpty()) null else "Matched expertise: "+bestResearcherTerms.joinToString(", "),
            modelVersion
        )

        jdbc.update(
            """update opportunities set automated_fit_score=?,automated_fit_rationale=?,fit_breakdown=?::jsonb,
               fit_model_version=?,fit_assessed_at=now(),
               institutional_fit_score=case when fit_override then institutional_fit_score else ? end,
               fit_rationale=case when fit_override then fit_rationale else ? end,updated_at=now()
               where id=?""",
            total,rationale,mapper.writeValueAsString(breakdown),modelVersion,total,rationale,opportunityId
        )

        return FitAssessmentResult(
            opportunityId,total,themeScore,capabilityScore,researcherScore,contextScore,
            matchedThemes,matchedCapabilities,matchedContexts,bestResearcher?.name,modelVersion
        )
    }

    private fun matches(text:String,term:String):Boolean{
        val normalized=normalize(term)
        if(normalized.isBlank()) return false
        if(text.contains(normalized)) return true
        val tokens=tokens(normalized)
        if(tokens.isEmpty()) return false
        val found=tokens.count{text.contains(Regex("""\b${Regex.escape(it)}\b"""))}
        val needed=if(tokens.size==1) 1 else ceil(tokens.size*0.6).toInt().coerceAtLeast(2)
        return found>=needed
    }

    private fun normalize(value:String)=value.lowercase().replace(Regex("[^a-z0-9]+")," ").replace(Regex("\\s+")," ").trim()

    private fun tokens(value:String)=normalize(value).split(" ").filter{
        it.length>=3 && it !in setOf("and","the","for","with","from","into","using","based","centre","center")
    }

    private fun componentScore(count:Int,max:Double,base:Double,increment:Double):BigDecimal{
        if(count<=0) return BigDecimal.ZERO.setScale(2)
        return bd(minOf(max,base+(count-1)*increment))
    }

    private fun bd(value:Double)=BigDecimal.valueOf(value).setScale(2,RoundingMode.HALF_UP)

    private fun stringArray(value:Any?):List<String> = when(value){
        is Array<*> -> value.mapNotNull{it?.toString()}
        is java.sql.Array -> stringArray(value.array)
        else -> emptyList()
    }
}
