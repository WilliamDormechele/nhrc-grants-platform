"use client";
import {useEffect,useState} from "react";
import EmailDeliverySetup from "./EmailDeliverySetup";
import {human,request} from "./client";
type Row=Record<string,any>;
const list=(v:any):string[]=>Array.isArray(v)?v:JSON.parse(v?.value||v||"[]");
export default function CommunicationsWorkspace({name}:{name:string}){
 const[data,setData]=useState<Row|null>(null),[error,setError]=useState(""),[message,setMessage]=useState(""),[busy,setBusy]=useState(false),[reason,setReason]=useState(""),[reminderReason,setReminderReason]=useState(""),[draft,setDraft]=useState({title:"",message:"",category:"ANNOUNCEMENT",audienceRoles:[] as string[],expiresAt:"",reason:""});
 const[withdrawalReason,setWithdrawalReason]=useState("");
 async function load(){try{const next=await request("/api/operations/communications");setData(next);if(!next.canAnnounce&&next.canNotify)setDraft(old=>({...old,category:"NOTICE"}))}catch(e:any){setError(e.message)}}
 useEffect(()=>{load()},[]);
 async function action(work:()=>Promise<any>,done:string){setBusy(true);setError("");setMessage("");try{const result=await work();setMessage(done+(typeof result.recipients==="number"?(result.recipients===0?" No additional messages were queued. Check eligible recipients and channels; recent duplicate reminders are suppressed.":` ${result.recipients} recipient${result.recipients===1?"":"s"}.`):""));await load();return true}catch(e:any){setError(e.message);return false}finally{setBusy(false)}}
 function toggleRole(roles:string[],code:string,on:boolean){return on?[...new Set([...roles,code])]:roles.filter(x=>x!==code)}
 if(!data)return <div role="status">{error||"Loading communications…"}
</div>;
 const admin=name==="Communications Control";
 if(name==="Role Guide")return <>
<header className="page-heading">
<div>
<span className="eyebrow">Access and responsibilities</span>
<h1>Role Guide</h1>
<p>Assign functions to named people. Multiple roles combine access; record the appointment reason and review it when duties change.</p>
</div>
</header>
<section className="panel">
<h2>Administrator hierarchy</h2>
<p>
<strong>Superadmin</strong> sees every workspace and administers user roles, form permissions, communications and technical settings. This role alone cannot submit grants, approve expenditure, accept reports or close awards.</p>
<p>
<strong>Business Administrator</strong> manages institutional forms, workflows and business access. Technical privileges are assigned by Superadmin.</p>
<p>
<strong>Business decision makers</strong> require the actual Grants, Director, Finance Approver, Post-Award or other applicable function. Assigned-owner checks, independent approvals and evidence requirements remain in force when roles are combined. Another administrator must change your own access.</p>
</section>
<section className="panel">
<h2>Role responsibilities</h2>
<div className="tableWrap">
<table>
<thead>
<tr>
<th>Role</th>
<th>Institutional purpose</th>
</tr>
</thead>
<tbody>{data.roles.map((r:Row)=>
<tr key={r.code}>
<td>{r.name}
</td>
<td>{r.description||human(r.code)}
</td>
</tr>)}
</tbody>
</table>
</div>
</section>
<section className="panel">
<h2>Grant workflow handoffs</h2>
<ol>
<li>Grants Officer registers and assesses the call; Director records the institutional decision.</li>
<li>The assigned investigator accepts, prepares the proposal and resolves review feedback.</li>
<li>Assigned reviewers and authorised approvers assess the work; Grants records submission evidence and the funder outcome.</li>
<li>Grants converts a successful application; Governance records the executed funding agreement; Post-Award activates and accepts handover.</li>
<li>Investigators deliver; Finance, Procurement, Laboratory and Governance complete their departmental obligations.</li>
<li>Independent authorised officers approve or accept reports; Post-Award closes the award once recorded closeout requirements are complete.</li>
</ol>
<p>Use Administration - All Forms for the detailed form catalogue and workflow guide. Reminder and announcement permissions are configured separately from grant approval authority.</p>
</section>
</>;
 if(admin&&!data.superadmin)return <div className="feedback error">Superadmin controls communications policies. Your announcements and assigned reminders are available under Personal - Announcements.</div>;
 return <>
<header className="page-heading">
<div>
<span className="eyebrow">{admin?"Superadmin":"Personal"}
</span>
<h1>{name}
</h1>
<p>{admin?"Control lifecycle channels, recipient functions, sender permissions and reminder timing.":"Institutional announcements, targeted notices and reminders for your assigned work."}
</p>
</div>
</header>{error&&<div className="feedback error" role="alert">{error}
</div>}{message&&<div className="feedback success" role="status">{message}
</div>}{admin&&<>
<div className="feedback info email-transport-summary">
<strong>Email transport: {human(data.emailMode)}
</strong>
<p>{data.emailMode==="LOCAL_CAPTURE"?<>UAT emails are delivered to the local capture inbox at <a href="http://localhost:8026" target="_blank" rel="noreferrer">Open UAT email inbox</a>. Connect Brevo or Gmail using Email delivery setup below.</>:"Delivery uses the configured mail service. Check the delivery log for failures."}
</p>
</div>
<EmailDeliverySetup onModeChange={mode=>setData({...data,emailMode:mode})}/>
<form onSubmit={async e=>{e.preventDefault();if(await action(()=>request("/api/operations/communications/settings",{method:"PUT",body:JSON.stringify({automaticReminders:data.settings.automatic_reminders,lookaheadDays:Number(data.settings.lookahead_days),repeatDays:Number(data.settings.repeat_days),events:data.policies.map((p:Row)=>({eventCode:p.event_code,inApp:p.in_app,email:p.email,recipientRoles:list(p.recipient_roles)})),senders:data.senders.map((s:Row)=>({roleCode:s.role_code,announcements:s.announcements,notifications:s.notifications,reminders:s.reminders})),reason})}),"Communication policy saved."))setReason("")}}>
<section className="panel">
<h2>Automatic task reminders</h2>
<label className="check-input">
<input type="checkbox" checked={data.settings.automatic_reminders} onChange={e=>setData({...data,settings:{...data.settings,automatic_reminders:e.target.checked}})}/>Enable automatic reminders</label>
<div className="form-grid">
<label>Days before the recorded deadline<input type="number" min={0} max={90} required value={data.settings.lookahead_days} onChange={e=>setData({...data,settings:{...data.settings,lookahead_days:e.target.value}})}/>
</label>
<label>Repeat incomplete-work reminders every (days)<input type="number" min={1} max={30} required value={data.settings.repeat_days} onChange={e=>setData({...data,settings:{...data.settings,repeat_days:e.target.value}})}/>
</label>
</div>
<p>Checks run every five minutes. Completed tasks are excluded. Undated not-started, pending and in-progress tasks are included. A named owner receives the reminder; otherwise it routes to active holders of the responsible role. Manual reminders have a five-minute duplicate guard.</p>
</section>
<section className="panel">
<h2>Lifecycle channel and audience controls</h2>
<p>Choose each event's channels and eligible recipient roles. Actual lifecycle recipients are determined from the recorded assignment or owner.</p>
<div className="tableWrap">
<table>
<thead>
<tr>
<th>Event</th>
<th>In-app</th>
<th>Email</th>
<th>Eligible recipient roles</th>
</tr>
</thead>
<tbody>{data.policies.map((p:Row,i:number)=>
<tr key={p.event_code}>
<td>{p.name}
</td>{["in_app","email"].map(k=>
<td key={k}>
<input type="checkbox" aria-label={`${p.name}: ${human(k)}`} checked={p[k]} onChange={e=>setData({...data,policies:data.policies.map((x:Row,j:number)=>j===i?{...x,[k]:e.target.checked}:x)})}/>
</td>)}<td>
<details>
<summary>{list(p.recipient_roles).length} roles selected</summary>
<div className="communication-role-list">{data.roles.map((r:Row)=>
<label className="check-input" key={r.code}>
<input type="checkbox" checked={list(p.recipient_roles).includes(r.code)} onChange={e=>setData({...data,policies:data.policies.map((x:Row,j:number)=>j===i?{...x,recipient_roles:toggleRole(list(x.recipient_roles),r.code,e.target.checked)}:x)})}/>{r.name}
</label>)}
</div>
</details>
</td>
</tr>)}
</tbody>
</table>
</div>
</section>
<section className="panel">
<h2>Who may send communications</h2>
<p>Superadmin always retains oversight. Other roles can send only when ticked here. Reminder senders remain restricted to their assigned task scope.</p>
<div className="tableWrap">
<table>
<thead>
<tr>
<th>Role</th>
<th>Announcements</th>
<th>Targeted notifications</th>
<th>Manual reminders</th>
</tr>
</thead>
<tbody>{data.senders.map((s:Row,i:number)=>
<tr key={s.role_code}>
<td>{data.roles.find((r:Row)=>r.code===s.role_code)?.name||human(s.role_code)}
</td>{["announcements","notifications","reminders"].map(k=>
<td key={k}>
<input type="checkbox" aria-label={`${human(s.role_code)}: ${human(k)}`} disabled={s.role_code==="SUPERADMIN"} checked={s.role_code==="SUPERADMIN"||s[k]} onChange={e=>setData({...data,senders:data.senders.map((x:Row,j:number)=>j===i?{...x,[k]:e.target.checked}:x)})}/>
</td>)}
</tr>)}
</tbody>
</table>
</div>
<label>Policy change reason<textarea required maxLength={2000} value={reason} onChange={e=>setReason(e.target.value)}/>
</label>
<button className="btn primary" disabled={busy}>{busy?"Saving policy…":"Save communications policy"}
</button>
</section>
</form>
</>}{(data.canAnnounce||data.canNotify)&&<section className="panel">
<h2>Compose a communication</h2>
<form onSubmit={async e=>{e.preventDefault();if(await action(()=>request("/api/operations/communications/broadcasts",{method:"POST",body:JSON.stringify({...draft,expiresAt:draft.expiresAt?new Date(draft.expiresAt).toISOString():null})}),"Communication sent."))setDraft({title:"",message:"",category:data.canAnnounce?"ANNOUNCEMENT":"NOTICE",audienceRoles:[],expiresAt:"",reason:""})}}>
<div className="form-grid">
<label>Communication type<select value={draft.category} onChange={e=>setDraft({...draft,category:e.target.value})}>{data.canAnnounce&&<option value="ANNOUNCEMENT">Announcement</option>}{data.canNotify&&<option value="NOTICE">Targeted notification</option>}
</select>
</label>
<label>Expires at (optional)<input type="datetime-local" value={draft.expiresAt} onChange={e=>setDraft({...draft,expiresAt:e.target.value})}/>
</label>
<label className="wide">Title<input required maxLength={255} value={draft.title} onChange={e=>setDraft({...draft,title:e.target.value})}/>
</label>
<label className="wide">Message<textarea required rows={4} maxLength={8000} value={draft.message} onChange={e=>setDraft({...draft,message:e.target.value})}/>
</label>
<fieldset className="wide">
<legend>Recipient roles</legend>
<div className="communication-role-list">{data.roles.map((r:Row)=>
<label className="check-input" key={r.code}>
<input type="checkbox" checked={draft.audienceRoles.includes(r.code)} onChange={e=>setDraft({...draft,audienceRoles:toggleRole(draft.audienceRoles,r.code,e.target.checked)})}/>{r.name}
</label>)}
</div>
</fieldset>
<label className="wide">Reason for sending<textarea required value={draft.reason} onChange={e=>setDraft({...draft,reason:e.target.value})}/>
</label>
</div>
<button className="btn primary" disabled={busy||!draft.audienceRoles.length}>{busy?"Sending…":"Send communication"}
</button>
</form>
</section>}<section className="panel">
<h2>Current announcements and notices</h2>{data.superadmin&&data.broadcasts.length>0&&<label>Reason for withdrawing an announcement<textarea maxLength={2000} value={withdrawalReason} onChange={e=>setWithdrawalReason(e.target.value)} placeholder="Record why the announcement should be withdrawn"/></label>}{data.broadcasts.length?data.broadcasts.map((b:Row)=>
<article className="announcement-card" key={b.id}>
<span className="eyebrow">{human(b.category)}
</span>
<h3>{b.title}
</h3>
<p style={{whiteSpace:"pre-wrap"}}>{b.message}
</p>
<small>{b.author} - {new Date(b.created_at).toLocaleString("en-GB")}{b.expires_at?" - Expires "+new Date(b.expires_at).toLocaleString("en-GB"):""}
</small>{data.superadmin&&<button className="btn" disabled={busy||!withdrawalReason.trim()} onClick={()=>action(()=>request(`/api/operations/communications/broadcasts/${b.id}/withdraw`,{method:"POST",body:JSON.stringify({reason:withdrawalReason})}),"Communication withdrawn. Already delivered emails cannot be recalled.")}>Withdraw announcement</button>}
</article>):<p>No current announcements for your roles.</p>}
</section>{(data.canRemind||data.superadmin)&&<section className="panel">
<h2>Manual task reminders</h2>
<label>Reminder message<textarea maxLength={2000} value={reminderReason} onChange={e=>setReminderReason(e.target.value)}/>
</label>
<div className="tableWrap">
<table>
<thead>
<tr>
<th>Task</th>
<th>Owner or role</th>
<th>Status</th>
<th>Due date</th>
<th>Action</th>
</tr>
</thead>
<tbody>{data.tasks.map((t:Row)=>
<tr key={t.kind+t.id}>
<td>{t.title}
</td>
<td>{t.owner_name||human(t.responsible_role)}
</td>
<td>{human(t.status)}
</td>
<td>{t.due_date||"No recorded date"}
</td>
<td>
<button className="btn" disabled={busy||!reminderReason.trim()} onClick={()=>action(()=>request("/api/operations/communications/remind",{method:"POST",body:JSON.stringify({kind:t.kind,id:t.id,reason:reminderReason})}),"Reminder processed.")}>Send reminder</button>
</td>
</tr>)}
</tbody>
</table>
</div>
</section>}{admin&&<section className="panel">
<h2>Email delivery log</h2>
<button className="btn" disabled={busy} onClick={load}>Refresh delivery status</button>
<div className="tableWrap">
<table>
<thead>
<tr>
<th>Recipient</th>
<th>Subject</th>
<th>Status</th>
<th>Delivery service</th>
<th>Attempts</th>
<th>Delivered</th>
<th>Action</th>
</tr>
</thead>
<tbody>{data.outbox.map((r:Row)=>
<tr key={r.id}>
<td>{r.recipient}
</td>
<td>{r.subject}
</td>
<td>{human(r.status)}{r.last_error&&<small>{r.last_error}
</small>}
</td>
<td>{r.delivery_mode==="GMAIL"?"Gmail":r.delivery_mode==="BREVO"?"Brevo":"Capture inbox"}</td>
<td>{r.attempts}
</td>
<td>{r.sent_at?new Date(r.sent_at).toLocaleString("en-GB"):"Pending"}
</td>
<td>{["FAILED","RETRY"].includes(r.status)&&<button className="btn" disabled={busy} onClick={()=>action(()=>request(`/api/operations/communications/outbox/${r.id}/retry`,{method:"POST",body:"{}"}),"Email queued for retry.")}>Retry email</button>}
</td>
</tr>)}
</tbody>
</table>
</div>
<p>Queued messages are processed every 15 seconds. Failures retry up to five attempts; Superadmin can retry deferred or failed deliveries. Access changes, policies, messages and manual reminders are recorded in Administration - Audit Log and record history.</p>
</section>}
</>;
}
