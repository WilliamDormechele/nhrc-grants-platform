"use client";
import {useState} from "react";
import {Action,Row,dateText,human,payload} from "./client";
import {useLoad,useWorkbench} from "./context";
import {Badge,DecisionDialog,Details,ErrorBox,FormDialog,Heading,Loading,Modal,Notice,Panel,Table,field} from "./controls";

export default function DiligenceWorkspace(){
  const {client,go,notify}=useWorkbench();
  const [query,setQuery]=useState(""),[type,setType]=useState<string|null>(null),[selected,setSelected]=useState<string|null>(null);
  const list=useLoad<Row>(`/due-diligence?q=${encodeURIComponent(query)}`);
  return <>
    <Heading title="Partner and supplier due diligence" description="Prepare a documented institutional assessment, resolve conditions and obtain independent approval with a defined validity period.">
      {(list.data?.createTypes||[]).map((kind:string)=><button className="btn primary" key={kind} onClick={()=>setType(kind)}>Assess {kind.toLowerCase()}</button>)}
      <button className="btn" onClick={list.reload} disabled={list.loading}>Refresh</button>
    </Heading>
    <div className="pipeline">{["Choose institution","Document checks","Assess risk","Submit review","Independent decision","Track validity"].map((step,index)=><div className="step" key={step}><span className="n">STEP {index+1}</span><b>{step}</b></div>)}</div>
    <Notice>Directory registration does not constitute due diligence approval. Failed or unresolved checks cannot be approved. Existing historical status is retained, but new controlled approvals require assessment evidence and a validity date.</Notice>
    <div className="filters"><div className="field"><label htmlFor="diligence-search">Find an assessment</label><input id="diligence-search" type="search" maxLength={200} value={query} onChange={event=>setQuery(event.target.value)} placeholder="Institution, supplier or assessment scope"/></div><button className="btn" onClick={()=>go("Partner Directory")}>Partner directory</button><button className="btn" onClick={()=>go("Suppliers & Due Diligence")}>Supplier directory</button></div>
    {list.loading?<Loading/>:list.error?<ErrorBox message={list.error} retry={list.reload}/>:<Panel title="Institutional assessment register" note="Open a review to inspect its evidence, conditions, decisions and recorded history.">
      <Table rows={list.data?.items||[]} columns={[
        {key:"subject_name",label:"Institution or supplier"},{key:"entity_type",label:"Category"},{key:"review_type",label:"Scope"},
        {key:"risk_rating",label:"Assessed risk",render:row=><Badge value={row.risk_rating}/>},{key:"status",label:"Review stage",render:row=><Badge value={row.status}/>},
        {key:"expires_at",label:"Approval valid until",render:row=>dateText(row.expires_at)}
      ]} onOpen={row=>setSelected(row.id)}/>
    </Panel>}
    {list.data?.truncated&&<Notice warning>The first 100 assessments are shown. Narrow the search to find older reviews.</Notice>}
    {type&&<FormDialog title={`Prepare ${type.toLowerCase()} assessment`} fields={list.data?.forms[type]||[]} onClose={()=>setType(null)} onSave={async values=>{
      const record=await client<Row>(`/due-diligence/subjects/${type}`,{method:"POST",body:payload(values)});
      notify("Due diligence assessment saved as a draft. Independent approval is still required.");list.reload();setSelected(record.id);
    }}/>}
    {selected&&<AssessmentDetail id={selected} onClose={()=>setSelected(null)} onChanged={list.reload}/>}
  </>;
}

function AssessmentDetail({id,onClose,onChanged}:{id:string;onClose:()=>void;onChanged:()=>void}){
  const {client,notify}=useWorkbench();const record=useLoad<Row>(`/due-diligence/${id}`);
  const [editing,setEditing]=useState(false),[action,setAction]=useState<Action|null>(null),[tab,setTab]=useState("Assessment");
  const row=record.data;
  const refresh=()=>{record.reload();onChanged();};
  return <>
    {!editing&&!action&&<Modal title={row?`${row.subject_name}: due diligence`:"Due diligence assessment"} onClose={onClose} wide>
      {record.loading?<Loading/>:record.error?<ErrorBox message={record.error} retry={record.reload}/>:row&&<>
        <div className="actions recordactions"><Badge value={row.status}/>{row._editable&&<button className="btn primary" onClick={()=>setEditing(true)}>Edit assessment</button>}{(row._actions||[]).map((item:Action)=><button className="btn" key={item.code} onClick={()=>setAction(item)}>{item.label}</button>)}</div>
        {!row.prepared_by&&<Notice warning>This is a historical assessment without a recorded preparer under the new controls. Start a new assessment rather than attempting to approve this record.</Notice>}
        <div className="tabs">{["Assessment","History"].map(name=><button key={name} className={`tab ${tab===name?"active":""}`} onClick={()=>setTab(name)}>{name}</button>)}</div>
        {tab==="Assessment"?<><Details record={row} fields={(row.fields||[]).filter((item:Row)=>item.name!=="entity_id")}/><Notice>{row.decision_note||"No approval decision has been recorded."}</Notice></>:<Table rows={row.history||[]} columns={[{key:"action",label:"Decision"},{key:"actor",label:"Recorded by"},{key:"occurred_at",label:"Date",render:item=>dateText(item.occurred_at)},{key:"note",label:"Rationale"}]}/>}
      </>}
    </Modal>}
    {editing&&row&&<FormDialog title="Edit due diligence assessment" fields={row.fields} record={row} onClose={()=>setEditing(false)} onSave={async values=>{
      await client(`/due-diligence/subjects/${row.entity_type}/${id}`,{method:"PUT",body:payload(values,row.record_version)});notify("Assessment changes saved.");refresh();
    }}/>}
    {action&&row&&<DecisionDialog action={action} record={row} route={`/due-diligence/${id}/actions`} fields={action.code!=="REQUEST_REVIEW"?[field("noConflict","I confirm that I have no conflict of interest","boolean")]:[]} onClose={()=>setAction(null)} onDone={refresh}/>}
  </>;
}
