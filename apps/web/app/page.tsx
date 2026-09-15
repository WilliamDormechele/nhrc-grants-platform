"use client";

import { useEffect, useMemo, useState } from "react";
import { BarChart3, BriefcaseBusiness, Building2, ChevronDown, CircleDollarSign, ClipboardCheck, FlaskConical, Gauge, Gavel, Home, Landmark, LockKeyhole, Search, Settings, ShieldCheck, ShoppingCart, Target, UserRound, UsersRound } from "lucide-react";

type Group={name:string;icon:any;items:string[]};
const groups:Group[]=[
{name:"Executive",icon:Gauge,items:["Executive Overview","Portfolio Analytics","Pipeline Analytics"]},
{name:"Pre-Award",icon:Target,items:["Opportunity Intelligence","Eligibility & Fit","Applications","Proposal Workspace","Budget Builder","Internal Review","Approvals","Submissions"]},
{name:"Award Management",icon:BriefcaseBusiness,items:["Award Register","Award Setup","Contracts & Agreements","Partners & Subawards","Due Diligence","Amendments","Deliverables","Risks & Issues"]},
{name:"Finance & Accounts",icon:CircleDollarSign,items:["Finance Dashboard","Award Budgets","Funds Received","Expenditure","Commitments","Budget vs Actual","Partner Advances","Financial Reporting","Financial Forecasts","Reconciliations","Financial Approvals","Financial Closeout"]},
{name:"Procurement",icon:ShoppingCart,items:["Procurement Dashboard","Procurement Plans","Requisitions","Purchase Orders","Suppliers & Due Diligence","Procurement Contracts","Equipment & Assets","Procurement Tracking"]},
{name:"Laboratory",icon:FlaskConical,items:["Laboratory Oversight","Laboratory Procurement","Reagents & Consumables","Laboratory Equipment","Maintenance & Calibration","Laboratory Compliance"]},
{name:"Research Governance",icon:ShieldCheck,items:["Ethics & Regulatory Links","Conflicts / Declarations","Compliance Calendar","Privacy & Information Governance","Legal & Contracts Review"]},
{name:"Performance",icon:BarChart3,items:["Reports","Outputs","Impact","Closeout"]},
{name:"People & Organisation",icon:UsersRound,items:["Researcher Profiles","Partner Directory","Funder Directory","NHRC Institutional Profile"]},
{name:"Personal",icon:UserRound,items:["My Work","Notifications","Calendar"]},
{name:"Administration",icon:Settings,items:["Users & Roles","Organisation Structure","Approval Rules","Delegations & Acting Roles","Workflow Configuration","Forms & Fields","Reference Data","Templates","Notification Rules","Calendar Rules","Data Import & Export","Records & Archives","Access Reviews","Help & Support","Audit Log","System Settings"]},
{name:"IT & Security",icon:LockKeyhole,items:["System Health","Service Monitoring","Integration Health","Job Monitor","Backup & Recovery","Security Centre","Security Events","Privileged Activity","Business Continuity","Environment Management","Superadmin Console"]}
];
const api=process.env.NEXT_PUBLIC_API_URL||"http://localhost:8080";

export default function HomePage(){
 const [open,setOpen]=useState<string|null>(null); const [active,setActive]=useState("Home"); const [summary,setSummary]=useState<any>(null); const [query,setQuery]=useState("");
 useEffect(()=>{fetch(`${api}/api/dashboard/summary`).then(r=>r.ok?r.json():null).then(setSummary).catch(()=>setSummary(null))},[]);
 const selected=useMemo(()=>groups.find(g=>g.items.includes(active)),[active]);
 function choose(group:string,item:string){setOpen(group);setActive(item)}
 return <div className="shell">
  <aside className="sidebar">
   <button className="brand" onClick={()=>{setActive("Home");setOpen(null)}}><div className="logo">NHRC</div><div><b>NHRC Grants</b><span>Research Funding Lifecycle</span></div></button>
   <nav><button className={`home ${active==="Home"?"active":""}`} onClick={()=>{setActive("Home");setOpen(null)}}><Home size={17}/>Home</button>
   {groups.map(g=>{const Icon=g.icon; const expanded=open===g.name; const groupActive=selected?.name===g.name; return <div className={`group ${groupActive?"groupActive":""}`} key={g.name}>
    <button className="groupBtn" onClick={()=>setOpen(expanded?null:g.name)}><Icon size={18}/><span>{g.name}</span><ChevronDown className={expanded?"rotate":""} size={15}/></button>
    {expanded&&<div className="items">{g.items.map(i=><button key={i} className={active===i?"active":""} onClick={()=>choose(g.name,i)}>{i}</button>)}</div>}
   </div>})}</nav>
  </aside>
  <main>
   <header><div><span className="eyebrow">NAVRONGO HEALTH RESEARCH CENTRE</span><h1>{active==="Home"?"Grant Management Platform":active}</h1></div><div className="topActions"><div className="search"><Search size={16}/><input value={query} onChange={e=>setQuery(e.target.value)} placeholder="Search this workspace"/></div><span className="env">DEVELOPMENT</span></div></header>
   {active==="Home"?<Landing summary={summary}/>:<Workspace name={active} group={selected?.name||""} query={query}/>} 
  </main>
 </div>
}

function Landing({summary}:{summary:any}){return <div className="content">
 <section className="hero"><div><span className="pill">Institutional research funding lifecycle</span><h2>One governed workspace for NHRC grants</h2><p>Discover opportunities, develop proposals, govern approvals, manage awards, finances, procurement, laboratory obligations, compliance, reporting and institutional closeout.</p></div><Landmark size={74}/></section>
 <div className="metrics"><Metric label="Opportunities" value={summary?.opportunities}/><Metric label="Applications" value={summary?.applications}/><Metric label="Active awards" value={summary?.activeAwards}/><Metric label="Pending approvals" value={summary?.pendingApprovals}/><Metric label="Reports due" value={summary?.reportsDue}/><Metric label="Open risks" value={summary?.openRisks}/></div>
 <section><div className="sectionTitle"><div><span className="eyebrow">FUNCTIONAL AREAS</span><h3>Institutional workspaces</h3></div></div><div className="cards">{groups.map(g=>{const Icon=g.icon;return <article key={g.name}><div className="cardIcon"><Icon/></div><h4>{g.name}</h4><p>{g.items.slice(0,4).join(" · ")}{g.items.length>4?" · …":""}</p></article>})}</div></section>
 </div>}
function Metric({label,value}:{label:string,value:any}){return <div className="metric"><span>{label}</span><strong>{value??"—"}</strong></div>}
function Workspace({name,group,query}:{name:string;group:string;query:string}){return <div className="content"><section className="workspaceHead"><div><span className="eyebrow">{group.toUpperCase()}</span><h2>{name}</h2><p>Database-backed workspace. Search and filters will apply to the governed records available to the signed-in role.</p></div><button className="primary">New / Add record</button></section><div className="filterbar"><div className="search wide"><Search size={16}/><input value={query} readOnly placeholder="Search"/></div><select><option>All statuses</option><option>Active</option><option>Pending</option><option>Completed</option></select><select><option>All periods</option><option>Next 30 days</option><option>This year</option></select><button>Clear filters</button></div><section className="empty"><ClipboardCheck size={34}/><h3>{name}</h3><p>This production workspace is connected to the platform shell and is being activated against its governed API and database records.</p></section></div>}
