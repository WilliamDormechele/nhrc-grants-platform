package org.nhrc.grants.people

import org.nhrc.grants.opportunity.InstitutionalFitService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

data class InstitutionalProfileUpdate(
    val mission:String?=null,
    val strategicThemes:List<String>?=null,
    val capabilities:List<String>?=null,
    val methodsPlatforms:List<String>?=null,
    val populationsContexts:List<String>?=null,
    val geographyKeywords:List<String>?=null
)

@RestController
@RequestMapping("/api/people/institutional-profile")
class InstitutionalProfileController(
    private val jdbc:JdbcTemplate,
    private val fitService:InstitutionalFitService
){
 @GetMapping
 fun get()=fitService.activeProfile()

 @PatchMapping @Transactional
 fun update(@RequestBody r:InstitutionalProfileUpdate):Any{
  val p=fitService.activeProfile()
  jdbc.update(
    """update institutional_profiles set
       mission=coalesce(?,mission),
       strategic_themes=coalesce(string_to_array(?, '|'),strategic_themes),
       capabilities=coalesce(string_to_array(?, '|'),capabilities),
       methods_platforms=coalesce(string_to_array(?, '|'),methods_platforms),
       populations_contexts=coalesce(string_to_array(?, '|'),populations_contexts),
       geography_keywords=coalesce(string_to_array(?, '|'),geography_keywords),
       updated_at=now()
       where id=?""",
    r.mission,r.strategicThemes?.joinToString("|"),r.capabilities?.joinToString("|"),r.methodsPlatforms?.joinToString("|"),
    r.populationsContexts?.joinToString("|"),r.geographyKeywords?.joinToString("|"),p.id
  )
  return fitService.activeProfile()
 }
}
