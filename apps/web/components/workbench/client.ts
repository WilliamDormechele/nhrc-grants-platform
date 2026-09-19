import {api} from "../config";

export type Row = Record<string, any>;
export type Field = {name:string;label:string;kind:string;required:boolean;choices:string[];lookup?:string;defaultValue?:string;maxLength:number};
export type Action = {code:string;label:string;evidenceRequired?:boolean};
export type Resource = {key:string;title:string;fields:Field[];canCreate:boolean;statusColumn?:string;transitions:Action[]};
export type Actor = {id:string;name:string;roles:string[];development:boolean};
export type Identity = {development:boolean;uatUsers:{id:string;display_name:string}[];actor:Actor|null};
export type Page = {items:Row[];total:number;page:number;size:number;hasMore:boolean};
export type Client = <T=any>(path:string,options?:RequestInit)=>Promise<T>;

export class RequestError extends Error {
  constructor(message:string,public status:number){super(message);this.name="RequestError";}
}
/** Development identity is supplied only after the server confirms development mode. */
export function makeClient(uatUser?:string):Client {
  return async <T=any>(path:string,options:RequestInit={}):Promise<T> => {
    if(!path.startsWith("/") || path.startsWith("//")) throw new Error("Invalid workspace route");
    const headers=new Headers(options.headers);
    headers.set("Accept","application/json");
    if(options.body && !(options.body instanceof FormData)) headers.set("Content-Type","application/json");
    if(uatUser) headers.set("X-UAT-User",uatUser);
    let response:Response;
    try {response=await fetch(`${api}/api/workbench${path}`,{...options,headers,credentials:"include",cache:"no-store"});}
    catch(error){if((error as Error).name==="AbortError") throw error;throw new RequestError("The grants service could not be reached. Your changes have not been confirmed. Please retry.",0);}
    const text=await response.text();
    let body:any=null;
    try {body=text?JSON.parse(text):null;} catch {if(response.ok) throw new RequestError("The service returned an unexpected response. Reload before continuing.",response.status);}
    if(!response.ok) throw new RequestError(typeof body?.message==="string"?body.message:`The request was not completed (${response.status}).`,response.status);
    return body as T;
  };
}
export function payload(values:Row,version?:number){return JSON.stringify({values,...(version===undefined?{}:{version})});}
export function actionPayload(action:string,record:Row,note:string,values:Row={},evidenceUrl?:string,requestId?:string){
  return JSON.stringify({action,version:record.record_version,requestId:requestId||crypto.randomUUID(),note,values,evidenceUrl:evidenceUrl||null});
}
export function human(value:unknown):string {
  if(value===null || value===undefined || value==="") return "Not recorded";
  if(typeof value==="boolean") return value?"Yes":"No";
  if(Array.isArray(value)) return value.map(human).join(", ");
  if(typeof value==="object") return Object.entries(value).map(([k,v])=>`${human(k)}: ${human(v)}`).join("; ");
  const text=String(value);
  return /^[A-Z][A-Z_]+$/.test(text)?text.toLowerCase().replaceAll("_"," ").replace(/^./,x=>x.toUpperCase()):text;
}
export function safeUrl(value:unknown):string|undefined {
  try {const parsed=new URL(String(value));return parsed.protocol==="https:"?parsed.href:undefined;} catch{return undefined;}
}
export function dateText(value:unknown):string {
  if(!value) return "Not recorded";
  const date=new Date(String(value));
  return Number.isNaN(date.getTime())?String(value):new Intl.DateTimeFormat("en-GB",{day:"2-digit",month:"short",year:"numeric",timeZone:"UTC"}).format(date);
}
export function moneyText(value:unknown,currency?:string):string {
  if(value===null || value===undefined || value==="") return "Not recorded";
  const amount=Number(value);
  if(!Number.isFinite(amount)) return "Not recorded";
  try {return new Intl.NumberFormat("en-GB",currency?{style:"currency",currency,maximumFractionDigits:2}:{maximumFractionDigits:2}).format(amount);} catch {return `${currency||""} ${amount.toFixed(2)}`.trim();}
}
export function initialValues(fields:Field[],record?:Row):Row {
  return Object.fromEntries(fields.map(field=>{
    let value=record?.[field.name]??field.defaultValue??(field.kind==="boolean"?false:"");
    if(field.kind==="boolean") value=value===true || value==="true";
    if(field.kind==="tags") value=Array.isArray(value)?value.join(", "):value;
    return [field.name,value];
  }));
}
export function formValues(fields:Field[],values:Row):Row {
  return Object.fromEntries(fields.map(field=>{
    let value=values[field.name];
    if(field.kind==="tags") value=String(value||"").split(",").map(s=>s.trim()).filter(Boolean);
    else if(field.kind==="boolean") value=value===true;
    else if(value==="" || value===undefined) value=null;
    // Money remains a decimal string. The service performs exact decimal calculations.
    return [field.name,value];
  }));
}
