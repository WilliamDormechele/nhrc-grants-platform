"use client";
import {useEffect,useState} from "react";
import {Field,Row,formValues,human,initialValues,moneyText,payload} from "./client";
import {useWorkbench} from "./context";
import {ErrorBox,FieldInput,FormDialog,Link,Notice,Panel,Table,field} from "./controls";

export function NarrativeEditor({row,onSaved,onDirty}:{row:Row;onSaved:()=>void;onDirty:(dirty:boolean)=>void}){
  const {client,notify}=useWorkbench();
  const fields:Field[]=(row.narrativeFields||[]).map((item:Field)=>({...item,required:false}));
  const [values,setValues]=useState<Row>(()=>initialValues(fields,row.narrative||{}));
  const [busy,setBusy]=useState(false),[error,setError]=useState(""),[dirty,setDirty]=useState(false);
  useEffect(()=>{
    const warn=(event:BeforeUnloadEvent)=>{if(dirty){event.preventDefault();event.returnValue="";}};
    window.addEventListener("beforeunload",warn);
    return ()=>window.removeEventListener("beforeunload",warn);
  },[dirty]);
  return <Panel title={`${human(row.application_type)} preparation`} note="Incomplete drafts can be saved. Required sections must be completed before internal review.">
    <div className="panelbody">
      <Notice>Required for this submission type: {(row._requiredSections||[]).map((name:string)=>fields.find(item=>item.name===name)?.label||name).join("; ")}.</Notice>
      <form data-unsaved={dirty?"true":"false"} onSubmit={async event=>{
        event.preventDefault();if(busy)return;setBusy(true);setError("");
        try{
          await client(`/applications/${row.id}/narrative`,{method:"PUT",body:payload(formValues(fields,values),row.record_version)});
          setDirty(false);onDirty(false);notify("Proposal draft saved as a new content revision.");onSaved();
        }catch(ex){setError((ex as Error).message);}finally{setBusy(false);}
      }}>
        {error&&<ErrorBox message={error}/>}
        <div className="formgrid">{fields.map(item=><FieldInput key={item.name} field={item} value={values[item.name]} disabled={busy||!row._canEditNarrative} onChange={value=>{setValues(current=>({...current,[item.name]:value}));setDirty(true);onDirty(true);}}/>)}</div>
        {row._canEditNarrative?<div className="actions">
          <button className="btn primary" type="submit" disabled={busy||!dirty}>{busy?"Saving draft...":"Save proposal draft"}</button>
          <button className="btn" type="button" disabled={busy||!dirty} onClick={()=>{if(window.confirm("Discard unsaved proposal changes?")){setValues(initialValues(fields,row.narrative||{}));setDirty(false);onDirty(false);}}}>Discard changes</button>
        </div>:<Notice>This revision is read-only at the current stage or for your assigned role.</Notice>}
      </form>
    </div>
  </Panel>;
}

export function BudgetEditor({row,reload}:{row:Row;reload:()=>void}){
  const {client,notify}=useWorkbench();
  const [line,setLine]=useState<Row|null>(null),[selecting,setSelecting]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState("");
  const remove=async(item:Row)=>{
    if(busy||!window.confirm("Remove this draft budget line? The previous value remains in the decision history."))return;
    setBusy(true);setError("");
    try{await client(`/applications/${row.id}/budget/${item.id}?version=${row.record_version}`,{method:"DELETE"});notify("Draft budget line removed.");reload();}
    catch(ex){setError((ex as Error).message);}finally{setBusy(false);}
  };
  const totalColumns=["line_total","funder_amount","nhrc_contribution","partner_amount"].map((key,index)=>({key,label:["Total cost","Funder","NHRC","Partners"][index],render:(item:Row)=>moneyText(item[key],item.currency)}));
  return <>
    {error&&<ErrorBox message={error}/>}
    <Panel title="Budget scenarios" note="Amounts remain separate by scenario and currency. Contributions must balance every line." actions={row._canEditNarrative?<><button className="btn small" onClick={()=>setLine({scenario:row.budget_scenario||"Base"})}>Add budget line</button><button className="btn small" disabled={!row.budgetLines?.length} onClick={()=>setSelecting(true)}>Select review scenario</button></>:undefined}>
      <Table rows={row.budgetTotals||[]} columns={[{key:"scenario",label:"Scenario"},{key:"currency",label:"Currency"},...totalColumns,{key:"invalid_lines",label:"Unbalanced lines"}]}/>
    </Panel>
    <Panel title="Detailed costing">
      <Table rows={row.budgetLines||[]} columns={[
        {key:"scenario",label:"Scenario"},{key:"category",label:"Category"},{key:"description",label:"Description"},{key:"year_no",label:"Year"},{key:"quantity",label:"Quantity"},
        {key:"unit_cost",label:"Unit cost",render:item=>moneyText(item.unit_cost,item.currency)},
        {key:"controls",label:"Actions",render:item=>row._canEditNarrative?<div className="actions"><button className="btn small" disabled={busy} onClick={()=>setLine(item)}>Edit line</button><button className="btn small danger" disabled={busy} onClick={()=>void remove(item)}>Remove line</button></div>:"Locked for review"}
      ]}/>
    </Panel>
    {line&&<FormDialog title={line.id?"Edit budget line":"Add budget line"} fields={row.budgetFields} record={line} note="Quantity multiplied by unit cost must equal the combined funder, NHRC and partner contributions. The service checks exact decimal amounts." onClose={()=>setLine(null)} onSave={async values=>{
      await client(`/applications/${row.id}/budget${line.id?`/${line.id}`:""}`,{method:line.id?"PUT":"POST",body:payload(values,row.record_version)});notify("Balanced budget line saved.");reload();
    }}/>}
    {selecting&&<FormDialog title="Select the scenario for review" fields={[field("scenario","Budget scenario","select",true,Array.from(new Set<string>((row.budgetLines||[]).map((item:Row)=>item.scenario))))]} record={{scenario:row.budget_scenario}} onClose={()=>setSelecting(false)} onSave={async values=>{
      await client(`/applications/${row.id}/budget-scenario`,{method:"PUT",body:payload(values,row.record_version)});notify("Review scenario selected.");reload();
    }}/>}
  </>;
}

export function DocumentEditor({row,reload}:{row:Row;reload:()=>void}){
  const {client,notify}=useWorkbench();const [adding,setAdding]=useState(false);
  return <>
    <Panel title="Controlled document versions" note="Use links to institutionally controlled files. Direct file upload is not connected." actions={row._canEditNarrative?<button className="btn small" onClick={()=>setAdding(true)}>Add document version</button>:undefined}>
      <Table rows={row.documents||[]} columns={[{key:"document_type",label:"Document type"},{key:"title",label:"Title"},{key:"version_no",label:"Version"},{key:"added_by_name",label:"Recorded by"},{key:"evidence_url",label:"Document",render:item=><Link url={item.evidence_url}>Open document</Link>}]}/>
    </Panel>
    {adding&&<FormDialog title="Add controlled document version" fields={[field("document_type","Document type"),field("title","Document title"),field("evidence_url","Institutionally controlled document link","url")]} onClose={()=>setAdding(false)} onSave={async values=>{
      await client(`/applications/${row.id}/documents`,{method:"POST",body:payload(values,row.record_version)});notify("Document version recorded.");reload();
    }}/>}
  </>;
}
