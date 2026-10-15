export type ActionKind="read"|"write"|"local";
export type ActionOutcome={tone?:"success"|"error"|"info";message?:string};
export type TrackedAction={id:number;label:string;kind:ActionKind;started:number;finished?:number;pending:number;status:"pending"|"success"|"error"|"info";message:string;failure?:string;accepted?:string;progress?:{done:number;total:number;label:string}};
export function confirmedOutcome(status:number,body:unknown,job=false,readOnly=false):ActionOutcome {
 if(status<200||status>=300)return {tone:"error",message:status===401?"Your session has ended. Sign in again.":status===403?"Your role does not permit this action.":status===409?"The service detected a conflict. Review the details shown in this workspace before trying again.":status>=500?(readOnly?"The service is temporarily unavailable. Refresh this view and try again.":"The service could not confirm completion. Check the record before trying again."):"The action was not accepted. Review the details shown in this workspace."};
 const result=body&&typeof body==="object"?body as {status?:unknown;success?:unknown;truncated?:unknown;answer?:unknown}:{};const state=String(result.status||"").toUpperCase();
 if(job&&(["FAILED","FAILURE","ERROR","UNAVAILABLE"].includes(state)||result.success===false))return {tone:"error",message:"The service reported an unsuccessful outcome. Review the result shown in this workspace."};
 if(job&&state.includes("PARTIAL"))return {tone:"info",message:"Completed with issues. Review the job results before proceeding."};
 if(status===202)return {tone:"info",message:"The request was accepted for processing. Check its job or record status for completion."};
 if(job&&typeof result.answer==="string")return result.truncated===true?{tone:"info",message:"Draft prepared, but it reached the output limit. Review it or ask a narrower question."}:{message:"Draft ready. Review the answer against its source records."};
 return {};
}
export function importPreviewOutcome(body:unknown):ActionOutcome|undefined {
 if(!body||typeof body!=="object")return;const preview=body as {valid?:unknown;total?:unknown;errors?:unknown};if(typeof preview.valid!=="number"||typeof preview.total!=="number"||!Array.isArray(preview.errors))return;
 if(!preview.total)return {tone:"info",message:"No data records found. Add rows to the CSV and validate again."};
 const count=`${preview.valid} of ${preview.total} ${preview.total===1?"record":"records"} validated.`;
 return preview.errors.length?{tone:"info",message:`${count} Correct the highlighted row errors before importing. Nothing has been imported.`}:{message:`${count} Review the preview, then choose Import. Nothing has been imported yet.`};
}
export class ActionState {
 private sequence=0;
 private records=new Map<number,TrackedAction>();
 private listeners=new Set<()=>void>();
 constructor(privateNow?:()=>number){this.now=privateNow||Date.now}
 private now:()=>number;
 subscribe(listener:()=>void){this.listeners.add(listener);return()=>{this.listeners.delete(listener)}}
 private publish(){this.listeners.forEach(f=>f())}
 get(id:number){return this.records.get(id)}
 all(){return [...this.records.values()].sort((a,b)=>b.id-a.id)}
 begin(label:string,kind:ActionKind,join?:number){let action=join?this.records.get(join):undefined;if(!action||action.status!=="pending"){action={id:++this.sequence,label:label.slice(0,120),kind,started:this.now(),pending:0,status:"pending",message:""};this.records.set(action.id,action)}if(kind==="write")action.kind="write";action.pending++;this.publish();return action.id}
 finish(id:number,outcome:ActionOutcome={}){const a=this.records.get(id);if(!a||a.status!=="pending")return;a.pending=Math.max(0,a.pending-1);if(outcome.tone==="error")a.failure=outcome.message||"The action could not be completed.";else if(outcome.tone==="info")a.accepted=outcome.message;else if(outcome.message)a.message=outcome.message;this.publish()}
 fail(id:number,message:string){const a=this.records.get(id);if(!a||a.status!=="pending")return;a.failure=message;this.publish()}
 progress(id:number,done:number,total:number,label:string){const a=this.records.get(id);if(!a||a.status!=="pending"||!Number.isFinite(total)||total<=0)return;a.progress={done:Math.max(0,Math.min(done,total)),total,label};this.publish()}
 settle(id:number){const a=this.records.get(id);if(!a||a.pending||a.status!=="pending")return false;a.status=a.failure?"error":a.accepted?"info":"success";a.finished=this.now();a.message=a.failure||a.accepted||a.message||(a.kind==="read"?"The requested information is ready.":"Completed successfully.");a.progress=undefined;const completed=this.all().filter(x=>x.status!=="pending").sort((x,y)=>(y.finished||0)-(x.finished||0)||y.id-x.id);for(const old of completed.slice(8))this.records.delete(old.id);this.publish();return true}
 dismiss(id:number){if(this.records.get(id)?.status==="pending")return;this.records.delete(id);this.publish()}
 clear(){this.records.clear();this.publish()}
}
export function pendingDescription(action:TrackedAction,now:number){if(action.progress)return `${action.progress.done.toLocaleString()} of ${action.progress.total.toLocaleString()} ${action.progress.label}`;if(!action.pending)return "Updating the workspace…";if(now-action.started>=15000)return "Still working. Larger jobs and local AI may take longer; keep this page open.";if(action.kind==="read")return "Loading the requested information…";if(/export|template/i.test(action.label))return "Preparing the download…";if(/upload/i.test(action.label))return "Uploading and checking the document…";if(/assistant|draft|AI response/i.test(action.label))return "Preparing a response using the local model…";if(/save|update|post|resolve/i.test(action.label))return "Saving your changes…";if(/backup|restore/i.test(action.label))return "Running the database job…";return "Processing your request…"}
