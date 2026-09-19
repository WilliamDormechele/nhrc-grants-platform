"use client";
import {useCallback,useEffect,useMemo,useState} from "react";
import {ChevronDown,Menu} from "lucide-react";
import {groups,groupsForRoles} from "./config";
import {Actor,Identity,Resource,Row,human,makeClient} from "./workbench/client";
import {WorkbenchContext,useLoad,useWorkbench} from "./workbench/context";
import {ErrorBox,Loading,Notice,Stat} from "./workbench/controls";
import WorkspaceRouter from "./workbench/WorkspaceRouter";
import "./workbench/workbench.css";

type Session={actor:Actor;catalogue:Resource[]};
const identityKey="nhrc-grants-uat-user";
export default function GrantsApp(){
  const [environment,setEnvironment]=useState<Identity|null>(null),[uatUser,setUatUser]=useState("");
  const [session,setSession]=useState<Session|null>(null),[error,setError]=useState(""),[loading,setLoading]=useState(true),[retry,setRetry]=useState(0);
  const [active,setActive]=useState("Home"),[selectedId,setSelectedId]=useState<string|undefined>(),[open,setOpen]=useState<string|null>(null),[mobile,setMobile]=useState(false),[toast,setToast]=useState("");
  useEffect(()=>{
    const controller=new AbortController();setLoading(true);setError("");setEnvironment(null);setSession(null);
    makeClient()<Identity>("/identity",{signal:controller.signal}).then(identity=>{
      if(controller.signal.aborted)return;
      setEnvironment(identity);
      if(identity.development){
        let saved="";try{saved=sessionStorage.getItem(identityKey)||"";}catch{}
        setUatUser(identity.uatUsers.some(user=>user.id===saved)?saved:"");
      }else setUatUser("");
    }).catch(ex=>{if(!controller.signal.aborted)setError(ex.message);}).finally(()=>{if(!controller.signal.aborted)setLoading(false);});
    return ()=>controller.abort();
  },[retry]);
  const client=useMemo(()=>makeClient(environment?.development&&uatUser?uatUser:undefined),[environment?.development,uatUser]);
  useEffect(()=>{
    setSession(null);
    if(!environment||(environment.development&&!uatUser))return;
    const controller=new AbortController();setLoading(true);setError("");
    Promise.all([client<Identity>("/identity",{signal:controller.signal}),client<Resource[]>("/catalogue",{signal:controller.signal})]).then(([identity,catalogue])=>{
      if(controller.signal.aborted)return;
      if(!identity.actor)throw new Error("An authorised account must be selected before opening grant records.");
      setSession({actor:identity.actor,catalogue});
    }).catch(ex=>{if(!controller.signal.aborted)setError(ex.message);}).finally(()=>{if(!controller.signal.aborted)setLoading(false);});
    return ()=>controller.abort();
  },[client,environment]);
  useEffect(()=>{if(!toast)return;const timer=window.setTimeout(()=>setToast(""),9000);return()=>window.clearTimeout(timer);},[toast]);
  const notify=useCallback((message:string)=>setToast(message),[]);
  const visibleGroups=useMemo(()=>groupsForRoles(session?.actor.roles||[]),[session?.actor.roles]);
  const leaveAllowed=()=>!document.querySelector('[data-unsaved="true"]')||window.confirm("Discard unsaved proposal changes before leaving this workspace?");
  const go=useCallback((name:string,id?:string)=>{
    if(!leaveAllowed())return;
    const parent=visibleGroups.find(group=>group.items.includes(name));
    if(name!=="Home"&&!parent){setToast("The requested workspace could not be found.");return;}
    setActive(name);setSelectedId(id);setOpen(parent?.name||null);setMobile(false);window.scrollTo({top:0,behavior:"auto"});
  },[visibleGroups]);
  const parent=visibleGroups.find(group=>group.items.includes(active));
  const chooseUser=(id:string)=>{
    if(!leaveAllowed())return;
    setSession(null);setError("");setUatUser(id);setActive("Home");setSelectedId(undefined);
    try{if(id)sessionStorage.setItem(identityKey,id);else sessionStorage.removeItem(identityKey);}catch{}
  };
  const usable=session&&(!environment?.development||session.actor.id===uatUser);
  const content=loading?<Loading/>:error?<ErrorBox message={error} retry={()=>setRetry(value=>value+1)}/>:!usable?<section className="panel"><div className="panelhead"><h1>{environment?.development?"Select a test account":"Authorised sign-in required"}</h1></div><div className="panelbody"><p>{environment?.development?"Choose a UAT account in the header to inspect the grants workspaces and test its permitted actions. Each account has separately assigned responsibilities.":"The institutional sign-in service must identify and provision an authorised account before grant records can be accessed."}</p><Notice warning>Development accounts are for controlled test data only. Do not enter real proposal content, financial details or personal information in this environment.</Notice></div></section>:null;
  return <>
    <a className="skip" href="#grant-main">Skip to main content</a>
    {mobile&&<button className="navback" aria-label="Close navigation" onClick={()=>setMobile(false)}/>}
    <aside className={`sidebar ${mobile?"open":""}`}>
      <div className="brand"><button className="brandhome" aria-label="NHRC Grants home" onClick={()=>go("Home")}><span className="brandmark"><img src="/nhrc-logo.svg" alt="Navrongo Health Research Centre"/></span></button><div><b>NHRC Grants</b><small>RESEARCH. ACCOUNTABILITY.</small></div></div>
      <nav aria-label="Grant management workspaces">{visibleGroups.map(group=>{
        const Icon=group.icon,expanded=open===group.name,selected=parent?.name===group.name;
        return <div className={`navsection ${selected?"activeSection":""}`} key={group.name}>
          <button className={`navtoggle ${selected?"activegroup":""}`} aria-expanded={expanded} onClick={()=>setOpen(expanded?null:group.name)}><span className="groupname"><span className="groupico"><Icon/></span><span>{group.name}</span></span><ChevronDown className={expanded?"rotate":""} size={14}/></button>
          <div className={`navitems ${expanded?"":"collapsed"}`}>{group.items.map(item=><button className={`navbtn ${active===item?"active":""}`} aria-current={active===item?"page":undefined} key={item} onClick={()=>go(item)}><span className="miniDot"/>{item}</button>)}</div>
        </div>;
      })}</nav>
      <div className="sidefoot"><strong>NHRC Grants {environment?.development?"UAT":""}</strong>Institutional funding lifecycle.<br/>Role-controlled decisions and recorded evidence.<br/><br/>No automatic funder submissions or accounting entries.</div>
    </aside>
    <div className="shell">
      <header className="topbar"><div className="orgwrap"><button className="btn mobilemenu" aria-label="Open navigation" onClick={()=>setMobile(!mobile)}><Menu size={16}/></button><div><div className="org">Navrongo Health Research Centre</div><small>Grants management and institutional oversight</small></div></div>
        <div className="topright">{environment?.development&&<div className="field identity-select"><label htmlFor="uat-account">UAT account</label><select id="uat-account" value={uatUser} onChange={event=>chooseUser(event.target.value)}><option value="">Select a test account</option>{environment.uatUsers.map(user=><option key={user.id} value={user.id}>{user.display_name}</option>)}</select></div>}
          <div className="avatar" aria-hidden="true">{session?.actor.name.split(" ").map(part=>part[0]).join("").slice(0,2)||"NH"}</div><div className="identity-caption"><b>{session?.actor.name||"No account selected"}</b><small>{session?.actor.roles.map(human).join(", ")||"Institutional access required"}</small></div>
        </div>
      </header>
      {environment?.development&&<div className="demo"><strong>UAT / DEVELOPMENT</strong> Controlled demonstration records only. Test-account selection is disabled outside development.</div>}
      <main className="main" id="grant-main" tabIndex={-1}>
        {content}
        {!loading&&!error&&usable&&session&&<WorkbenchContext.Provider key={session.actor.id} value={{client,actor:session.actor,catalogue:session.catalogue,go,notify}}>
          {active==="Home"?<Landing groups={visibleGroups}/>:<WorkspaceRouter key={`${active}-${selectedId||""}`} name={active} id={selectedId}/>}
        </WorkbenchContext.Provider>}
        <div className="footnote">NHRC Grants. Saved decisions retain the responsible account and record version. Unconnected services are labelled explicitly; no simulated action is presented as completed.</div>
      </main>
    </div>
    {toast&&<div className="toast" role="status" aria-live="polite">{toast}<button aria-label="Dismiss notification" onClick={()=>setToast("")}>×</button></div>}
  </>;
}

function Landing({groups}:{groups:import("./config").Group[]}){
  const {go,catalogue}=useWorkbench();const summary=useLoad<Row>(catalogue.length?"/summary":null);
  const values=summary.data;
  return <>
    <section className="landinghero"><div className="eyebrow light">NHRC GRANTS</div><h1>Research funding, managed as one institutional lifecycle.</h1><p>Senior-level oversight from funding discovery through proposal development, award management, financial control, compliance, reporting and closeout.</p><div className="actions"><button className="btn primary" onClick={()=>go("Executive Overview")}>Open Executive Overview</button><button className="btn" onClick={()=>go("My Work")}>Open My Work</button></div></section>
    {summary.error&&<ErrorBox message={summary.error} retry={summary.reload}/>}
    {values&&<div className="stats homeStats"><Stat label="Funding calls" value={values.opportunities}/><Stat label="Applications" value={values.applications}/><Stat label="Active awards" value={values.activeAwards}/><Stat label="My pending reviews" value={values.myPendingReviews}/></div>}
    <div className="landinggrid">{groups.map(group=>{const Icon=group.icon;return <button className="landingcard" key={group.name} onClick={()=>go(group.items[0])}><span className="groupico large"><Icon/></span><h2>{group.name}</h2><p>{descriptions[group.name]||group.items.slice(0,4).join(", ")}</p><span className="tiny">{group.items.length} workspaces · Open section</span></button>;})}</div>
  </>;
}
const descriptions:Record<string,string>={Executive:"Portfolio oversight, application stages and institutional decisions.","Pre-Award":"Discover, assess, prepare, review, authorise and record submissions.","Award Management":"Activate funding, manage agreements, partners, delivery and risks.","Finance & Accounts":"Control verified receipts, expenditure, commitments and reporting.",Procurement:"Plan requirements, secure approvals and track orders and assets.",Laboratory:"Coordinate grant-linked equipment, consumables and servicing.","Research Governance":"Record external approvals and compliance obligations.",Performance:"Track reports, research outputs, impact evidence and closeout.","People & Organisation":"Maintain researcher, partner, funder and institutional profiles.",Personal:"Your assignments, review decisions, notifications and obligations.",Administration:"Inspect controlled definitions and maintain authorised master records.","IT & Security":"Protected operational workspaces and technical-authority separation."};
