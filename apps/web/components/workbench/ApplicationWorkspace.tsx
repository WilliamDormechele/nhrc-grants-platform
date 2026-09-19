"use client";
import {useState} from "react";
import {Action,Page,Row,dateText,human,payload} from "./client";
import {useLoad,useResource,useWorkbench} from "./context";
import {Badge,DecisionDialog,Empty,ErrorBox,FormDialog,Heading,Link,Loading,Notice,Panel,Stat,Table} from "./controls";
import {decisionFields} from "./applicationActions";
import {BudgetEditor,DocumentEditor,NarrativeEditor} from "./ApplicationEditing";

const stages=[
  {label:"Intake and eligibility",values:["DISCOVERED","ELIGIBILITY_REVIEW","DIRECTOR_DECISION","READY_FOR_ASSIGNMENT"]},
  {label:"Assigned and preparing",values:["ASSIGNED","ACCEPTED","PREPARATION"]},
  {label:"Review and authorisation",values:["INTERNAL_REVIEW","INSTITUTIONAL_APPROVAL","APPROVED_FOR_SUBMISSION"]},
  {label:"Submitted and outcome",values:["SUBMITTED","OUTCOME_RECORDED","AWARDED","CLOSED"]}
];
const defaultTab:Record<string,string>={"Eligibility & Fit":"Eligibility","Proposal Workspace":"Proposal","Budget Builder":"Budget","Internal Review":"Reviews","Approvals":"Reviews","Submissions":"Submission"};
const descriptions:Record<string,string>={
  "Applications":"Track institutional applications from discovery through preparation, review, submission and outcome.",
  "Eligibility & Fit":"Assess the funder's conditions before committing proposal effort or assigning a researcher.",
  "Proposal Workspace":"Prepare the required SOI, EOI, LOI, concept note, proposal or fellowship sections with a saved revision history.",
  "Budget Builder":"Build balanced scenarios with separate funder, NHRC and partner contributions.",
  "Internal Review":"Independent scientific, finance, grants and governance reviews for the current proposal revision.",
  "Approvals":"Authorise a reviewed package without confusing institutional approval with a funder's decision.",
  "Submissions":"Record an authorised external submission, its evidence and the funder's subsequent decision."
};

export default function ApplicationWorkspace({name,id}:{name:string;id?:string}){
  const {client,go,notify}=useWorkbench(),resource=useResource("applications");
  const [creating,setCreating]=useState(false),[query,setQuery]=useState(""),[page,setPage]=useState(0);
  const list=useLoad<Page>(!id&&resource?`/records/applications?q=${encodeURIComponent(query)}&page=${page}&size=40`:null);
  if(id)return <ApplicationDetail key={`${id}-${name}`} id={id} initialTab={defaultTab[name]||"Overview"}/>;
  return <>
    <Heading title={name} description={descriptions[name]||descriptions.Applications}>
      {resource?.canCreate&&<button className="btn primary" onClick={()=>setCreating(true)}>Start application</button>}
      <button className="btn" onClick={()=>go("Opportunity Intelligence")}>Review funding calls</button>
    </Heading>
    {!resource?<Notice warning>Your current role cannot open applications.</Notice>:<>
      <div className="filters"><div className="field"><label htmlFor="application-search">Find an application</label><input id="application-search" type="search" maxLength={200} value={query} onChange={event=>{setQuery(event.target.value);setPage(0);}} placeholder="Search application title or recorded challenge"/></div><button className="btn" onClick={list.reload} disabled={list.loading}>Refresh</button></div>
      {list.error&&<ErrorBox message={list.error} retry={list.reload}/>}
      {list.loading?<Loading/>:list.data&&<>
        <p className="subtle">{list.data.total} matching applications. Stage counts below cover the {list.data.items.length} applications on this page.</p>
        <div className="flow">{stages.map(stage=>{
          const rows=list.data!.items.filter(row=>stage.values.includes(row.stage));
          return <section className="flowbox" key={stage.label}>
            <h2>{stage.label}</h2><div className="flowcount">{rows.length}</div>
            {rows.map(row=><div className="item" key={row.id}>
              <h3>{row.title}</h3><span className="tiny">{row.reference} · {human(row.application_type)}</span><Badge value={row.stage}/>
              <p className="tiny">Deadline: {dateText(row.deadline_at)}<br/>{row.current_challenge||"No challenge recorded"}</p>
              <button className="btn small" onClick={()=>go(name,row.id)}>Open application</button>
            </div>)}
            {!rows.length&&<Empty>No applications at this stage on this page.</Empty>}
          </section>;
        })}</div>
        <div className="actions pagination"><button className="btn" disabled={page===0} onClick={()=>setPage(value=>value-1)}>Previous page</button><span>Page {page+1}</span><button className="btn" disabled={!list.data.hasMore} onClick={()=>setPage(value=>value+1)}>Next page</button></div>
      </>}
      {creating&&<FormDialog title="Start institutional application" fields={resource.fields} onClose={()=>setCreating(false)} onSave={async values=>{
        const result=await client<Row>("/records/applications",{method:"POST",body:payload(values)});
        notify("Application created. Begin the eligibility assessment before assignment.");go("Eligibility & Fit",result.id);
      }}/>}
    </>}
  </>;
}

function ApplicationDetail({id,initialTab}:{id:string;initialTab:string}){
  const {actor,go}=useWorkbench(),load=useLoad<Row>(`/applications/${id}`);
  const [tab,setTab]=useState(initialTab),[action,setAction]=useState<Action|null>(null),[dirty,setDirty]=useState(false);
  const changeTab=(next:string)=>{
    if(dirty&&!window.confirm("Discard unsaved proposal changes?"))return;
    setDirty(false);setTab(next);
  };
  if(load.loading)return <Loading/>;
  if(load.error)return <ErrorBox message={load.error} retry={load.reload}/>;
  const row=load.data;if(!row)return null;
  const form=action?decisionFields(action,row,actor.id):null;
  const refreshed=(result:Row)=>{
    setDirty(false);
    if(result.createdApplicationId)go("Eligibility & Fit",result.createdApplicationId);
    else if(result.createdAwardId)go("Award Setup",result.createdAwardId);
    else load.reload();
  };
  return <>
    <Heading title={row.title} description={`${row.reference} · ${human(row.application_type)} · Content revision ${row.content_revision}`}>
      <button className="btn" onClick={()=>go("Applications")}>Application register</button><button className="btn" disabled={dirty} onClick={load.reload}>Refresh</button>
    </Heading>
    <div className="stats">
      <Stat label="Current stage" value={<Badge value={row.stage}/>} note="Controlled application workflow"/>
      <Stat label="Required narrative sections" value={`${row._readiness}%`} note="Completeness is not a quality score"/>
      <Stat label="Selected budget" value={row.budget_scenario||"Not selected"} note="Scenario used for review and authorisation"/>
      <Stat label="Published deadline" value={dateText(row.deadline_at)} note="Confirm the precise deadline with the funder"/>
    </div>
    <Panel title="Available actions" note="Only decisions available to your identity at the current stage are shown.">
      <div className="panelbody actions">
        {(row._actions||[]).map((item:Action)=><button key={item.code} className="btn" disabled={dirty} onClick={()=>setAction(item)}>{item.label}</button>)}
        {!row._actions?.length&&<span className="subtle">There is no decision awaiting this account at the current stage.</span>}
        {dirty&&<span className="subtle">Save or discard proposal changes before taking a workflow action.</span>}
      </div>
    </Panel>
    <div className="tabs workspace-tabs">{["Overview","Eligibility","Proposal","Budget","Documents","Reviews","Submission","History"].map(name=><button className={`tab ${name===tab?"active":""}`} key={name} onClick={()=>changeTab(name)}>{name}</button>)}</div>
    {tab==="Overview"&&<div className="grid">
      <Panel title="Application progress"><div className="panelbody checks">{stages.map(stage=><p key={stage.label}><strong>{stage.label}</strong>{stage.values.includes(row.stage)?<Badge value={row.stage}/>:"Inspect the recorded history for earlier decisions."}</p>)}</div></Panel>
      <Panel title="Responsibilities and preparation"><div className="panelbody"><Table rows={row.assignments||[]} columns={[{key:"researcher",label:"Researcher"},{key:"response",label:"Response",render:item=><Badge value={item.response}/>},{key:"assigned_at",label:"Assigned",render:item=>dateText(item.assigned_at)}]}/><Notice>{row.current_challenge||"No preparation challenge has been recorded."}</Notice></div></Panel>
    </div>}
    {tab==="Eligibility"&&<>
      <Notice>Use the funding call's original conditions as evidence. Unknown or failed requirements must not be recorded as fully eligible. Conditional cases remain in eligibility review.</Notice>
      <Panel title="Eligibility and pursuit decisions"><Table rows={(row.decisions||[]).filter((item:Row)=>["START_ELIGIBILITY","RECORD_ELIGIBILITY","DIRECTOR_DECISION"].includes(item.gate))} columns={[
        {key:"gate",label:"Decision stage"},{key:"decision",label:"Decision",render:item=><Badge value={item.decision}/>},{key:"note",label:"Rationale"},{key:"decided_by_name",label:"Decision maker"},{key:"evidence_url",label:"Evidence",render:item=><Link url={item.evidence_url}/>}
      ]}/></Panel>
    </>}
    {tab==="Proposal"&&<NarrativeEditor key={`${row.id}-${row.record_version}`} row={row} onSaved={()=>{setDirty(false);load.reload();}} onDirty={setDirty}/>}
    {tab==="Budget"&&<BudgetEditor row={row} reload={load.reload}/>}
    {tab==="Documents"&&<DocumentEditor row={row} reload={load.reload}/>}
    {tab==="Reviews"&&<>
      <Notice>Reviews apply to the recorded content revision. Returned proposals must be corrected and reviewed again before institutional authorisation.</Notice>
      <Panel title="Assigned reviews"><Table rows={row.reviews||[]} columns={[
        {key:"review_type",label:"Review"},{key:"reviewer",label:"Assigned reviewer"},{key:"content_revision",label:"Revision"},{key:"status",label:"Disposition",render:item=><Badge value={item.status}/>},{key:"comments",label:"Comments"}
      ]}/></Panel>
      <Panel title="Institutional decisions"><Table rows={(row.decisions||[]).filter((item:Row)=>["COMPLETE_REVIEW","APPROVE","RETURN"].includes(item.gate))} columns={[
        {key:"gate",label:"Action"},{key:"decision",label:"Decision"},{key:"content_revision",label:"Revision"},{key:"decided_by_name",label:"Authorised officer"},{key:"note",label:"Decision note"}
      ]}/></Panel>
    </>}
    {tab==="Submission"&&<div className="grid">
      <Panel title="External submission record"><div className="panelbody"><dl className="kv">
        <dt>Reference</dt><dd>{human(row.submission_reference)}</dd><dt>Submitted</dt><dd>{dateText(row.submitted_at)}</dd>
        <dt>Evidence</dt><dd><Link url={row.submission_evidence_url}/></dd><dt>Funder outcome</dt><dd><Badge value={row.funder_outcome}/></dd><dt>Outcome evidence</dt><dd><Link url={row.outcome_evidence_url}/></dd>
      </dl></div></Panel>
      <Panel title="Submission safeguards"><div className="panelbody checks">
        <p><strong>Institutional authorisation first</strong>The current package must be authorised before submission can be recorded.</p>
        <p><strong>No automatic funder submission</strong>Staff submit through the funder's approved channel, then record the reference and evidence here.</p>
        <p><strong>Early-stage applications</strong>An SOI, EOI, LOI or concept note advances through a linked invitation. It is not recorded as a financial award.</p>
      </div></Panel>
    </div>}
    {tab==="History"&&<Panel title="Recorded stage history"><Table rows={row.history||[]} columns={[
      {key:"from_stage",label:"Previous stage"},{key:"to_stage",label:"New stage"},{key:"changed_by_name",label:"Recorded by"},{key:"changed_at",label:"Date",render:item=>dateText(item.changed_at)},{key:"note",label:"Decision note"}
    ]}/></Panel>}
    {action&&form&&<DecisionDialog action={action} record={row} route={`/applications/${id}/actions`} fields={form.fields} transform={form.transform} onClose={()=>setAction(null)} onDone={refreshed}/>}
  </>;
}
