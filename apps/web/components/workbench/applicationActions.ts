import {Action,Field,Row} from "./client";

const f=(name:string,label:string,kind="text",choices:string[]=[],required=true,lookup?:string):Field=>({name,label,kind,required,choices,lookup,maxLength:kind==="textarea"?8000:255});
const choice=(name:string,label:string,choices:string[])=>f(name,label,"select",choices);
const user=(name:string,label:string)=>f(name,label,"lookup",[],true,"users");
export function decisionFields(action:Action,row:Row,actorId:string):{fields:Field[];transform:(values:Row)=>Row}{
  const code=action.code;
  let fields:Field[]=[];
  if(code==="RECORD_ELIGIBILITY") fields=[choice("decision","Eligibility decision",["ELIGIBLE","CONDITIONAL","INELIGIBLE"]),...Object.entries({applicant:"Applicant and institutional eligibility",geography:"Geographical eligibility",theme:"Research theme and scope",deadline:"Deadline and time available",budget:"Budget and funding limits",partners:"Partner and consortium requirements"}).map(([key,label])=>choice(`check_${key}`,label,["PASS","FAIL","UNKNOWN","NOT_APPLICABLE"])),f("conditions","Outstanding conditions and how they will be resolved","textarea",[],false)];
  if(code==="DIRECTOR_DECISION") fields=[choice("decision","Institutional decision",["PURSUE","DO_NOT_PURSUE"])];
  if(code==="ASSIGN") fields=[user("researcherId","Lead researcher with an active profile")];
  if(code==="REQUEST_REVIEW") fields=[...["SCIENTIFIC","FINANCE","GRANTS",...(row.narrative?.governanceReviewRequired?["GOVERNANCE"]:[])].map(type=>user(`reviewer_${type}`,`${type.toLowerCase()} reviewer`)),f("documentsConfirmed","I confirm the package meets the funder's document requirements","boolean")];
  if(code==="COMPLETE_REVIEW") fields=[choice("reviewType","Assigned review",(row.reviews||[]).filter((review:Row)=>review.reviewer_id===actorId&&review.status==="PENDING"&&review.content_revision===row.content_revision).map((review:Row)=>review.review_type)),choice("decision","Review disposition",["CLEARED","RETURNED"]),f("noConflict","I confirm that I have no conflict of interest","boolean")];
  if(code==="APPROVE") fields=[f("noConflict","I confirm that I have no conflict of interest","boolean")];
  if(code==="RECORD_SUBMISSION") fields=[f("submittedAt","Actual submission date, time and time zone","datetime"),f("reference","Funder submission reference")];
  if(code==="RECORD_OUTCOME") fields=[choice("outcome","Recorded funder outcome",[...(["FULL_PROPOSAL","FELLOWSHIP"].includes(row.application_type)?["AWARDED"]:[]),"UNSUCCESSFUL","WITHDRAWN","INVITED_TO_NEXT_STAGE","WAITLISTED"])];
  if(code==="NEXT_STAGE") fields=[choice("applicationType","Invited submission type",["EOI","CONCEPT_NOTE","FULL_PROPOSAL","FELLOWSHIP"].filter(type=>type!==row.application_type))];
  if(code==="CONVERT_AWARD") fields=[f("reference","Award reference"),f("startDate","Award start date","date"),f("endDate","Award end date","date"),choice("currency","Award currency",["GHS","USD","GBP","EUR","CAD","AUD","CHF","ZAR","KES"]),f("totalAward","Total award","money"),f("nhrcAllocation","NHRC allocation","money"),f("partnerAllocation","Partner allocation","money")];
  return {fields,transform:(values:Row)=>{
    if(code==="RECORD_ELIGIBILITY"){
      const {decision,conditions,...checks}=values;
      return {decision,conditions,checks:Object.fromEntries(Object.entries(checks).map(([key,value])=>[key.replace("check_",""),value]))};
    }
    if(code==="REQUEST_REVIEW"){
      const {documentsConfirmed,...reviewers}=values;
      return {documentsConfirmed,reviewers:Object.fromEntries(Object.entries(reviewers).map(([key,value])=>[key.replace("reviewer_",""),value]))};
    }
    return values;
  }};
}
