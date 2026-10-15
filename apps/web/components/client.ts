import {beginRequest,completeRequest,failRequest,clearActionFeedback} from "./actionFeedback";
export const api=process.env.NEXT_PUBLIC_API_URL||"";
export type SessionUser={id:string;name:string;email:string;roles:string[];groups:string[]};
let currentUser:SessionUser|null=null;
export function setSessionUser(user:SessionUser|null){if(currentUser&&currentUser.id!==user?.id)clearActionFeedback();currentUser=user}
export function sessionUser(){return currentUser}
export function human(value:any):string {
 const roleNames:Record<string,string>={ADMIN:"Business Administrator",SUPERADMIN:"Superadmin",IT_ADMIN:"IT Administrator",DIRECTOR:"Director",GRANTS_OFFICER:"Grants Officer",POST_AWARD_OFFICER:"Post-Award Officer",RESEARCHER:"Researcher / PI",RESEARCH_FELLOW:"Research Fellow",FINANCE_OFFICER:"Finance Officer",FINANCE_APPROVER:"Finance Approver",PROCUREMENT_OFFICER:"Procurement Officer",LABORATORY_OFFICER:"Laboratory Officer",GOVERNANCE_OFFICER:"Research Governance Officer",AUDITOR:"Auditor",SYSTEM_AUTOMATION:"System Automation"};
 if(roleNames[String(value)])return roleNames[String(value)];
 const text=String(value??"").replace(/[\u2013\u2014]/g,"-").replace(/_/g," ").replace(/([a-z])([A-Z])/g,"$1 $2");
 const acronyms:Record<string,string>={PI:"Principal investigator",EOI:"Expression of interest",NHRC:"NHRC",USD:"USD",GBP:"GBP",GHS:"GHS",EUR:"EUR",ORCID:"ORCID",IT:"IT"};
 if(acronyms[text])return acronyms[text];
 return /^[A-Z][A-Z_ ]+$/.test(String(value??""))?text.toLowerCase().replace(/^\w/,x=>x.toUpperCase()):text;
}
export type ApiRequestOptions=RequestInit & {feedback?:false};
export async function apiFetch(input:RequestInfo|URL,options:ApiRequestOptions={}){
 const {feedback,...nativeOptions}=options;const method=(options.method||"GET").toUpperCase();const headers=new Headers(options.headers);const action=feedback===false?null:beginRequest(String(input),method);
 try{
 if(!["GET","HEAD","OPTIONS"].includes(method)){
   const c=await fetch(api+"/api/auth/csrf",{credentials:"include"});
   if(c.ok){const token=await c.json();headers.set(token.headerName,token.token)}
 }
 const response=await fetch(input,{...nativeOptions,headers,credentials:"include"});
 if(response.status===401 && typeof window!=="undefined" && !String(input).includes("/auth/login"))window.dispatchEvent(new Event("grant-session-expired"));
 await completeRequest(action,response,String(input));return response;
 }catch(e){failRequest(action,method,String(input));throw e}
}
export async function request(path:string,options:ApiRequestOptions={}) {
 const headers=new Headers(options.headers);if(options.body && !(options.body instanceof FormData) && !headers.has("Content-Type"))headers.set("Content-Type","application/json");
 let r:Response;try{r=await apiFetch(api+path,{...options,headers})}catch{throw Error("The grant service could not be reached. Check your connection and retry.")}const text=await r.text();let result:any={};try{result=text?JSON.parse(text):{}}catch{throw Error(r.ok?"The service returned an unreadable response. Please retry.":`The service is temporarily unavailable (${r.status}). Please retry.`)}
 if(!r.ok)throw Error(human(result.message||result.detail||result.error||`Request failed (${r.status})`));return result;
}
