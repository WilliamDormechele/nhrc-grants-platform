"use client";
import {useState} from "react";
import {Page,Row,human} from "./client";
import {useLoad,useResource,useWorkbench} from "./context";
import {Badge,ErrorBox,Heading,Loading,Notice,Panel,Table} from "./controls";
import RecordWorkspace from "./RecordWorkspace";
import ApplicationWorkspace from "./ApplicationWorkspace";
import OpportunityWorkspace from "./OpportunityWorkspace";
import AwardWorkspace from "./AwardWorkspace";
import DiligenceWorkspace from "./DiligenceWorkspace";
import {ExecutiveWorkspace,FinanceWorkspace,PersonalWorkspace} from "./OverviewWorkspace";
import {registers} from "./workspaceRegisters";
import ConnectedOperationalWorkspace,{hasConnectedOperationalWorkspace} from "./ConnectedOperationalWorkspace";

export default function WorkspaceRouter({name,id}:{name:string;id?:string}){
  const {go}=useWorkbench();
  if(name==="Opportunity Intelligence")return <OpportunityWorkspace id={id}/>;
  if(name==="Due Diligence")return <DiligenceWorkspace/>;
  if(["Eligibility & Fit","Applications","Proposal Workspace","Budget Builder","Internal Review","Approvals","Submissions"].includes(name))return <ApplicationWorkspace name={name} id={id}/>;
  if(["Executive Overview","Portfolio Analytics","Pipeline Analytics"].includes(name))return <ExecutiveWorkspace name={name}/>;
  if(["Award Register","Award Setup"].includes(name))return <AwardWorkspace name={name} id={id}/>;
  if(["Finance Dashboard","Financial Monitoring","Award Budgets","Budget vs Actual"].includes(name))return <FinanceWorkspace name={name}/>;
  if(["My Work","Notifications","Calendar"].includes(name))return <PersonalWorkspace name={name}/>;
  if(["Procurement Dashboard","Procurement Tracking","Laboratory Oversight","Laboratory Procurement","Laboratory Compliance","Financial Approvals","Financial Closeout"].includes(name))return <OperationalOverview name={name}/>;
  if(["Forms & Fields","Workflow Configuration","Approval Rules"].includes(name))return <FormCatalogue name={name}/>;
  if(hasConnectedOperationalWorkspace(name))return <ConnectedOperationalWorkspace name={name}/>;
  const options=registers[name];
  if(options)return <RecordWorkspace key={`${name}-${id||""}`} {...options} title={name} selectedId={id}>
    {["Partner Directory","Suppliers & Due Diligence","Partners & Subawards"].includes(name)&&<div className="actions" style={{marginBottom:20}}><button className="btn" onClick={()=>go("Due Diligence")}>Open due diligence assessments</button></div>}
  </RecordWorkspace>;
  return <ProtectedWorkspace name={name}/>;
}

function QueuePanel({resourceKey,title,route}:{resourceKey:string;title:string;route:string}){
  const {go}=useWorkbench(),resource=useResource(resourceKey),load=useLoad<Page>(resource?`/records/${resourceKey}?size=5`:null);
  return <Panel title={title} note={load.data?`${load.data.total} recorded items. Showing the first five in register order.`:"Access follows your institutional role."} actions={resource?<button className="btn small" onClick={()=>go(route)}>Open workspace</button>:undefined}>
    {!resource?<div className="panelbody"><Notice>Not available to your current role.</Notice></div>:load.loading?<Loading/>:load.error?<ErrorBox message={load.error} retry={load.reload}/>:<Table rows={load.data?.items||[]} columns={[
      {key:"title",label:"Record",render:row=>human(row.title||row.name||row.reference||row.item_description||row.subject||row.activity_type||row.report_type)},
      {key:"status",label:"Stage",render:row=><Badge value={row.status||row.agreement_status||"RECORDED"}/>}
    ]} onOpen={row=>go(route,row.id)}/>}</Panel>;
}
function OperationalOverview({name}:{name:string}){
  const lab=name.startsWith("Laboratory"),finance=name.startsWith("Financial");
  const queues=lab?
    [["laboratory-items","Laboratory register","Laboratory Equipment"],["maintenance","Service and calibration obligations","Maintenance & Calibration"],["compliance","External approval obligations","Ethics & Regulatory Links"]]:finance?
    [["receipts","Recorded receipts","Funds Received"],["reconciliations","Reconciliations","Reconciliations"],["closeout","Award closeout requirements","Closeout"]]:
    [["requisitions","Purchasing requirements","Requisitions"],["purchase-orders","Orders and deliveries","Purchase Orders"],["procurement-plans","Planned procurement","Procurement Plans"],["assets","Asset accountability","Equipment & Assets"]];
  const steps=lab?["Register requirement","Confirm procurement","Receive and identify","Track expiry","Maintain and calibrate","Retain evidence"]:finance?
    ["Record transaction","Verify evidence","Reconcile balances","Resolve advances","Complete reports","Authorise closure"]:
    ["Plan requirement","Prepare requisition","Finance approval","Approve supplier","Issue order","Record delivery"];
  return <>
    <Heading title={name} description={lab?"Coordinate laboratory equipment, grant-funded requirements, servicing and external obligations.":finance?"Inspect financial evidence and controlled completion requirements.":"Oversee purchasing from planned requirements through approval, ordering, delivery and asset custody."}/>
    <div className="pipeline">{steps.map((step,index)=><div className="step" key={step}><span className="n">STEP {index+1}</span><b>{step}</b></div>)}</div>
    <div className="guidegrid">{queues.map(([resourceKey,title,route])=><QueuePanel key={resourceKey} resourceKey={resourceKey} title={title} route={route}/>)}</div>
    <Notice>{lab?"Laboratory procurement follows institutional purchasing controls. This register does not measure live stock consumption or replace the laboratory information system.":finance?"Open each record to inspect the actions currently permitted to your account. These panels are register previews, not complete approval queues.":"Purchasing requires an approved requisition and current approved supplier assessment. Orders are not automatically sent to suppliers or posted to accounting."}</Notice>
  </>;
}

function FormCatalogue({name}:{name:string}){
  const {catalogue}=useWorkbench();const [key,setKey]=useState(catalogue[0]?.key||"");
  const resource=catalogue.find(item=>item.key===key);
  return <>
    <Heading title={name} description="Inspect the controlled form requirements and approval definitions available to your role."/>
    <Notice>Definitions are maintained through a reviewed software change. Direct editing of a field or approval rule is not enabled here.</Notice>
    <div className="filters"><div className="field"><label htmlFor="catalogue-form">Workspace definition</label><select id="catalogue-form" value={key} onChange={event=>setKey(event.target.value)}>{catalogue.map(item=><option key={item.key} value={item.key}>{item.title}</option>)}</select></div></div>
    {resource&&<>
      <Panel title={resource.title}><Table rows={resource.fields} columns={[{key:"label",label:"Field"},{key:"kind",label:"Entry type"},{key:"required",label:"Required"},{key:"choices",label:"Permitted values"},{key:"lookup",label:"Linked register"}]}/></Panel>
      <Panel title="Configured record transitions"><Table rows={resource.transitions} columns={[{key:"label",label:"Action"},{key:"from",label:"Permitted stages"},{key:"to",label:"Resulting stage"},{key:"roles",label:"Authorised roles"},{key:"independent",label:"Independent decision"},{key:"evidenceRequired",label:"Evidence required"}]}/></Panel>
    </>}
  </>;
}
function ProtectedWorkspace({name}:{name:string}){
  const {actor,go}=useWorkbench();
  return <>
    <Heading title={name} description="Protected institutional administration and operational oversight."/>
    <div className="grid">
      <Panel title="Connection status"><div className="panelbody">
        <Notice warning>The controlled service for this workspace is not connected to this interface yet. No inactive or simulated save, approval or administrative action is presented as working.</Notice>
        <p>Access changes, retention, backups and service settings require separately validated administrative workflows.</p>
        <button className="btn" onClick={()=>go("Help & Support")}>Record a support request</button>
      </div></Panel>
      <Panel title="Authority and accountability"><div className="panelbody">
        <h3>{actor.name}</h3><div className="taglist">{actor.roles.map(role=><Badge key={role} value={role}/>)}</div>
        <Notice>Technical administrator privileges do not grant authority to approve proposals, verify expenditure or close awards. Business decisions remain restricted to assigned institutional roles.</Notice>
      </div></Panel>
    </div>
  </>;
}
