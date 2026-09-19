"use client";
import {useState} from "react";
import {Row,dateText,human,payload} from "./client";
import {useLoad,useResource,useWorkbench} from "./context";
import {Badge,ErrorBox,FormDialog,Link,Loading,Notice,Panel,Table,field} from "./controls";
import RecordWorkspace from "./RecordWorkspace";

export default function OpportunityWorkspace({id}:{id?:string}){
  const {client,notify}=useWorkbench();
  const sources=useLoad<Row>("/sources");
  const [importing,setImporting]=useState(false),[refresh,setRefresh]=useState(0);
  const available:Row[]=(sources.data?.sources||[]).filter((source:Row)=>source.configured);
  const runs:Row[]=sources.data?.runs||[];
  return <RecordWorkspace key={refresh} resourceKey="opportunities" title="Opportunity Intelligence" description="Search live approved funding sources, import calls into NHRC review, preserve provenance, and route eligible opportunities to researchers." createLabel="Add funding call" selectedId={id} columns={["title","call_type","source_reference","deadline_date","amount_max"]} extra={row=><OpportunityEvidence row={row}/>}>
    {sources.error&&<ErrorBox message={sources.error} retry={sources.reload}/>}
    <div className="grid">
      <Panel title="Live funding-source APIs" note="On-demand internet searches import up to 100 calls into NHRC review. No external submission is sent."><div className="panelbody">
        {sources.loading?<Loading/>:(sources.data?.sources||[]).map((source:Row)=><div className="item" key={source.code}><div><h3>{source.name}</h3><p className="tiny">{source.capability}</p><p className="tiny">{source.requiresKey?"API key required for live search":"Keyless live search"}</p></div><Badge value={source.configured?"LIVE_READY":"NOT_CONFIGURED"}/></div>)}
        {sources.data?.canImport&&<button className="btn primary" disabled={!available.length} onClick={()=>setImporting(true)}>Search internet funding calls</button>}
        <p className="tiny">Grants.gov is available without a key. Simpler.Grants.gov appears as not configured until SIMPLER_GRANTS_API_KEY is set for the API container. Additional portals, scheduled searches and grant-alert emails are not connected.</p>
      </div></Panel>
      <Panel title="Matching safeguards" note="Explainable assistance, not a prediction of funding success."><div className="panelbody checks">
        <p><strong>Eligibility first</strong>Check applicant, location, scope, deadline, budget and partnership requirements against the original call.</p>
        <p><strong>Recorded expertise</strong>Matching uses research expertise and methods. Open a call's related information to inspect the matching terms.</p>
        <p><strong>Human routing</strong>A suggested match is not an assignment. Complete the institutional decisions before assigning a researcher.</p>
      </div></Panel>
    </div>
    {runs.length>0&&<details className="panel"><summary className="panelhead">Recent funding searches</summary><Table rows={runs} columns={[
      {key:"source_code",label:"Source"},{key:"search_term",label:"Search"},{key:"status",label:"Result",render:row=><Badge value={row.status}/>},
      {key:"imported_count",label:"New calls"},{key:"refreshed_count",label:"Source updates"},{key:"started_at",label:"Started",render:row=>dateText(row.started_at)},{key:"failure_message",label:"Issue"}
    ]}/></details>}
    {importing&&<FormDialog title="Search an approved funding source" submitLabel="Search and import calls" fields={[field("source","Funding source","select",true,available.map(source=>source.code)),{...field("query","Search terms"),maxLength:100}]} note="New results are saved to the review queue. Staff-reviewed call details are not overwritten. An incomplete result set is identified explicitly." onClose={()=>setImporting(false)} onSave={async values=>{
      const result=await client<Row>("/sources/search",{method:"POST",body:JSON.stringify(values)});
      notify(`${result.imported} new calls; ${result.refreshed} source records refreshed. ${result.message}`);sources.reload();setRefresh(value=>value+1);
    }}/>}
  </RecordWorkspace>;
}

function OpportunityEvidence({row}:{row:Row}){
  const {client,actor,go,notify}=useWorkbench(),applications=useResource("applications");
  const source=useLoad<Row>(`/opportunities/${row.id}/source`),matches=useLoad<Row>(`/opportunities/${row.id}/matches`);
  const [creating,setCreating]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState("");
  const original=source.data?.source;
  return <div>
    <Notice>Staff-reviewed call details are used by the application. The original source remains available as supporting evidence.</Notice>
    <div className="actions"><Link url={row.url}>Open official call</Link>{applications?.canCreate&&<button className="btn primary" onClick={()=>setCreating(true)}>Start application from this call</button>}</div>
    <h3>Suggested researchers</h3>
    {matches.loading?<Loading/>:matches.error?<ErrorBox message={matches.error} retry={matches.reload}/>:<>
      <Table rows={matches.data?.items||[]} columns={[{key:"name",label:"Researcher"},{key:"matchedTerms",label:"Matching expertise and methods"},{key:"careerStage",label:"Career stage"}]}/>
      <p className="tiny">{matches.data?.method}</p>
      {matches.data?.truncated&&<Notice warning>Only the first {matches.data?.profilesConsidered} profiles were considered. This is not a complete institutional search.</Notice>}
    </>}
    <h3>Original source evidence</h3>
    {source.loading?<Loading/>:source.error?<ErrorBox message={source.error} retry={source.reload}/>:original?<>
      <p>{human(original.source_code)} · Last checked {dateText(original.last_seen_at)}</p>
      {original.source_code==="GRANTS_GOV"&&actor.roles.includes("GRANTS_OFFICER")&&<button className="btn small" disabled={busy} onClick={async()=>{
        setBusy(true);setError("");try{await client(`/opportunities/${row.id}/source-details`,{method:"POST"});source.reload();notify("Official details refreshed without changing the reviewed call.");}catch(ex){setError((ex as Error).message);}finally{setBusy(false);}
      }}>{busy?"Checking source...":"Refresh official details"}</button>}
      <details><summary>Inspect recorded source information</summary><pre className="source-evidence">{JSON.stringify(original.source_snapshot??{},null,2).slice(0,40000)}</pre></details>
    </>:<Notice>This call was recorded manually. Verify its conditions using the official call and controlled evidence.</Notice>}
    {error&&<ErrorBox message={error}/>}
    {creating&&applications&&<FormDialog title="Start an application from this call" fields={applications.fields} record={{title:row.title,opportunity_id:row.id,application_type:["SOI","EOI","LOI","CONCEPT_NOTE","FULL_PROPOSAL","FELLOWSHIP"].includes(row.call_type)?row.call_type:"FULL_PROPOSAL"}} onClose={()=>setCreating(false)} onSave={async values=>{
      const result=await client<Row>("/records/applications",{method:"POST",body:payload(values)});notify("Application created for eligibility review.");go("Eligibility & Fit",result.id);
    }}/>}
  </div>;
}
