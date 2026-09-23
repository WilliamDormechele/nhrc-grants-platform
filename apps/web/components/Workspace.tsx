"use client";
import {useEffect,useState} from "react";
import {Plus} from "lucide-react";
import {api,endpoints} from "./config";
import {AddOpportunity,Calendar,Drawer,Purpose,Register,Summary} from "./WorkspaceParts";
import PreAwardWorkspace from "./PreAwardWorkspace";
type Row=Record<string,any>;

export default function Workspace({name,group,query,go}:{name:string;group:string;query:string;go:(g:string,i:string)=>void}){
 const[data,setData]=useState<any>(null),[loading,setLoading]=useState(false),[selected,setSelected]=useState<Row|null>(null),[modal,setModal]=useState(false),[refresh,setRefresh]=useState(0);
 const ep=endpoints[name]||((group==="Executive")?"/api/dashboard/summary":"");
 useEffect(()=>{let stop=false;if(!ep){setData(null);return}setLoading(true);const join=ep.includes("?")?"&":"?";fetch(api+ep+(query?join+"q="+encodeURIComponent(query):"")).then(r=>r.ok?r.json():Promise.reject(new Error("Request failed"))).then(v=>!stop&&setData(v)).catch(e=>!stop&&setData({error:e.message})).finally(()=>!stop&&setLoading(false));return()=>{stop=true}},[ep,query,refresh]);
 const rows=Array.isArray(data)?data:[];
 return <div className="content"><section className="workspaceHead"><div><span className="eyebrow">{group.toUpperCase()}</span><h2>{name}</h2><p>{description(name,group)}</p></div><Action name={name} go={go} add={()=>setModal(true)}/></section>
 {loading?<div className="panel">Loading…</div>:data?.error?<div className="panel error">{data.error}</div>:group==="Pre-Award"?<PreAwardWorkspace name={name} primary={data} refresh={()=>setRefresh(x=>x+1)}/>:<Body name={name} group={group} data={data} rows={rows} select={setSelected} go={go}/>}
 {selected&&<Drawer row={selected} close={()=>setSelected(null)}/>}
 {modal&&<AddOpportunity close={()=>setModal(false)} saved={()=>{setModal(false);setRefresh(x=>x+1)}}/>}</div>
}

function description(n:string,g:string){const d:Record<string,string>={
"Executive Overview":"Senior-level view of the institutional grant portfolio, actions and risks.",
"Portfolio Analytics":"Portfolio concentration, award-end exposure and sustainability intelligence.",
"Pipeline Analytics":"Application movement across the governed pre-award lifecycle.",
"Opportunity Intelligence":"Discover, register and prioritise funding opportunities before proposal effort is committed.",
"Eligibility & Fit":"Structured eligibility and strategic fit assessment before a go/no-go decision.",
"Applications":"Institutional application register with ownership, stage, progress and outcome.",
"Proposal Workspace":"Coordinate proposal sections, documents, responsibilities and readiness.",
"Budget Builder":"Develop costing, NHRC contribution and partner share before Finance clearance.",
"Internal Review":"Coordinate scientific, Grants, Finance, legal and management review.",
"Approvals":"Controlled institutional decisions with separation of duties.",
"Submissions":"Final quality check, submission evidence, funder communication and outcome tracking.",
"Award Register":"Institutional record of funded awards and their current status.",
"Award Setup":"Activate a successful award across finance, contracts, governance and delivery.",
"Finance Dashboard":"Accounts view of budgets, receipts, expenditure, commitments and balance.",
"Procurement Dashboard":"Grant-funded procurement workload and requisition pipeline.",
"Laboratory Oversight":"Grant-linked laboratory equipment, consumables, maintenance and compliance.",
"Researcher Profiles":"Institutional researcher expertise used for opportunity routing and team formation.",
"Audit Log":"Traceable institutional history of significant platform actions.",
"System Health":"Operational assurance for backups, support and access review controls.",
"Superadmin Console":"Restricted technical administration without business approval authority."
};return d[n]||`${n} workspace within the approved NHRC ${g} operating model.`}

function Action({name,go,add}:{name:string;go:(g:string,i:string)=>void;add:()=>void}){
 if(name==="Opportunity Intelligence")return <button className="primary" onClick={add}><Plus size={16}/>Add opportunity</button>;
 const n:Record<string,[string,string,string]>={
 "Eligibility & Fit":["Pre-Award","Applications","Open applications"],
 "Proposal Workspace":["Pre-Award","Budget Builder","Open budget builder"],
 "Budget Builder":["Pre-Award","Internal Review","Continue to review"],
 "Award Register":["Award Management","Award Setup","Award setup"],
 "Finance Dashboard":["Finance & Accounts","Funds Received","Funds received"],
 "Procurement Dashboard":["Procurement","Requisitions","Open requisitions"],
 "Laboratory Oversight":["Laboratory","Maintenance & Calibration","Maintenance calendar"]
 };
 const x=n[name];return x?<button className="primary" onClick={()=>go(x[0],x[1])}>{x[2]}</button>:<span className="configured">Configured workspace</span>
}

function Body({name,group,data,rows,select,go}:{name:string;group:string;data:any;rows:Row[];select:(r:Row)=>void;go:(g:string,i:string)=>void}){
 if(name==="Executive Overview")return <ExecutiveDashboard data={data||{}} go={go}/>;
 if(name==="Finance Dashboard"||name==="Financial Monitoring")return <FinanceDashboard data={data||{}} go={go}/>;
 if(name==="Procurement Dashboard")return <DomainDashboard title="Procurement workload" rows={rows} select={select} metrics={[["Open requisitions",rows.filter(r=>!["CLOSED","DELIVERED","CANCELLED"].includes(String(r.status))).length,"Active procurement demand"],["Total requisitions",rows.length,"Award-linked requests"],["Awaiting action",rows.filter(r=>/PENDING|REVIEW|DRAFT/.test(String(r.status))).length,"Review or approval"],["Currencies",new Set(rows.map(r=>r.currency).filter(Boolean)).size,"Currencies represented"]]}/>;
 if(name==="Laboratory Oversight")return <DomainDashboard title="Laboratory grant oversight" rows={rows} select={select} metrics={[["Tracked items",rows.length,"Grant-linked items"],["Reagents",rows.filter(r=>r.item_type==="REAGENT").length,"Consumable records"],["Equipment",rows.filter(r=>r.item_type==="EQUIPMENT").length,"Asset-linked records"],["Attention due",rows.filter(r=>r.expiry_date||r.calibration_due_date||r.maintenance_due_date).length,"Dated obligations"]]}/>;
 if(name==="Award Register")return <DomainDashboard title="Institutional award portfolio" rows={rows} select={select} metrics={[["Awards",rows.length,"Institutional award records"],["Active",rows.filter(r=>r.status==="ACTIVE").length,"Currently active"],["Setup",rows.filter(r=>r.status==="SETUP").length,"Activation in progress"],["Open value",sum(rows,"nhrc_allocation"),"NHRC allocation in source currencies"]]}/>;
 if(["System Health","Service Monitoring","Security Centre","Business Continuity","Environment Management"].includes(name)&&!Array.isArray(data))return <Summary data={data||{}}/>;
 if(name==="Award Setup")return <div className="workflowGrid"><Panel title="Award setup"><Cards rows={rows} select={select}/></Panel><Panel title="Activation checklist"><Checks items={["Executed agreement recorded","Approved budget verified","Reporting schedule created","Compliance dependencies linked","Partners and due diligence reviewed"]}/></Panel><Panel title="Downstream workstreams"><button className="linkBtn" onClick={()=>go("Finance & Accounts","Finance Dashboard")}>Finance & Accounts</button><button className="linkBtn" onClick={()=>go("Procurement","Procurement Dashboard")}>Procurement</button><button className="linkBtn" onClick={()=>go("Research Governance","Ethics & Regulatory Links")}>Research Governance</button></Panel></div>;
 if(name==="Maintenance & Calibration"||name.includes("Calendar"))return <Calendar rows={rows} name={name}/>;
 if(!endpoints[name]&&group!=="Executive")return <Purpose name={name} group={group}/>;
 return <Register rows={rows} select={select}/>
}

function ExecutiveDashboard({data,go}:{data:Row;go:(g:string,i:string)=>void}){return <><div className="stats"><Tile l="Opportunities" v={data.opportunities??0} n="Discovery and review queue"/><Tile l="Applications" v={data.applications??0} n="Institutional pipeline"/><Tile l="Active awards" v={data.activeAwards??0} n="Current portfolio"/><Tile l="Reports due" v={data.reportsDue??0} n="Next 30 days"/></div><div className="grid"><Panel title="Management attention"><div className="checks"><p><strong>Pending approvals</strong>{data.pendingApprovals??0} institutional decisions awaiting action.</p><p><strong>Open risks</strong>{data.openRisks??0} award risks remain open.</p><p><strong>Reporting obligations</strong>{data.reportsDue??0} reports are within the dashboard due window.</p></div></Panel><Panel title="Lifecycle shortcuts"><button className="linkBtn" onClick={()=>go("Pre-Award","Opportunity Intelligence")}>Opportunity Intelligence</button><button className="linkBtn" onClick={()=>go("Award Management","Award Register")}>Award Register</button><button className="linkBtn" onClick={()=>go("Finance & Accounts","Finance Dashboard")}>Finance Dashboard</button><button className="linkBtn" onClick={()=>go("Performance","Reports")}>Reporting & Performance</button></Panel></div></>}

function FinanceDashboard({data,go}:{data:Row;go:(g:string,i:string)=>void}){const approved=Number(data.approved_budget||0),spent=Number(data.expenditure||0),commit=Number(data.commitments||0);return <><div className="stats"><Tile l="Approved budget" v={num(approved)} n="Approved award budgets"/><Tile l="Cash received" v={num(data.cash_received)} n="Recorded funder receipts"/><Tile l="Expenditure" v={num(spent)} n="Reconciled expenditure"/><Tile l="Uncommitted budget" v={num(data.uncommitted)} n="Budget less expenditure and commitments"/></div><div className="grid"><Panel title="Budget use"><div className="barrow"><div className="barlabels"><span>Expenditure</span><b>{approved?Math.round(spent/approved*100):0}%</b></div><div className="bar"><span className="spent" style={{width:(approved?Math.min(100,spent/approved*100):0)+"%"}}/><span className="commit" style={{width:(approved?Math.min(100,commit/approved*100):0)+"%"}}/></div></div><div className="notice">Uncommitted budget is not the same as available cash. Finance remains the authoritative accounting source.</div></Panel><Panel title="Finance operations"><button className="linkBtn" onClick={()=>go("Finance & Accounts","Funds Received")}>Funds received</button><button className="linkBtn" onClick={()=>go("Finance & Accounts","Expenditure")}>Expenditure</button><button className="linkBtn" onClick={()=>go("Finance & Accounts","Commitments")}>Commitments</button><button className="linkBtn" onClick={()=>go("Finance & Accounts","Reconciliations")}>Reconciliations</button></Panel></div></>}

function DomainDashboard({title,rows,select,metrics}:{title:string;rows:Row[];select:(r:Row)=>void;metrics:[string,any,string][]}){return <><div className="stats">{metrics.map(([l,v,n])=><Tile key={l} l={l} v={v} n={n}/>)}</div><Panel title={title}><Register rows={rows} select={select}/></Panel></>}
function Tile({l,v,n}:{l:string;v:any;n:string}){return <div className="stat"><div className="label">{l}</div><div className="value">{v??0}</div><div className="note">{n}</div></div>}
function num(v:any){return Number(v||0).toLocaleString("en-GB",{maximumFractionDigits:2})}
function sum(rows:Row[],key:string){return num(rows.reduce((a,r)=>a+Number(r[key]||0),0))}
function Panel({title,children}:{title:string;children:any}){return <section className="panel"><h3>{title}</h3>{children}</section>}
function Cards({rows,select}:{rows:Row[];select:(r:Row)=>void}){return <>{rows.length?rows.map((r,i)=><button className="recordCard" key={r.id||i} onClick={()=>select(r)}><b>{r.title||r.reference||r.name||"Record"}</b><span>{r.funder||r.lead_researcher||r.status||"NHRC"}</span><em>{r.stage||r.eligibility_status||r.status||"Active"}</em></button>):<p className="note">No records currently in this queue.</p>}</>}
function Checks({items}:{items:string[]}){return <>{items.map(x=><div className="check" key={x}><span>✓</span>{x}</div>)}</>}
