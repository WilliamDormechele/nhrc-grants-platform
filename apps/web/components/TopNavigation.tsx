"use client";
import {useEffect,useRef,useState} from "react";
import {ChevronDown,ChevronRight,Check,Search,Home,Menu,X} from "lucide-react";
import type {Group} from "./config";
import {workspaceLabel} from "./branding";

const sections=[
 {label:"Overview",groups:["Executive","Personal"]},
 {label:"Funding & Applications",groups:["Pre-Award"]},
 {label:"Awards",groups:["Award Management"]},
 {label:"Operations",groups:["Finance & Accounts","Procurement","Laboratory","Research Governance"]},
 {label:"Reports",groups:["Performance"]},
 {label:"Administration",groups:["People & Organisation","Administration","IT & Security"]}
];

export default function TopNavigation({navigation,active,go,onHome}:{navigation:Group[];active:string;go:(group:string,page:string)=>void;onHome:()=>void}){
 const [expanded,setExpanded]=useState<string|null>(null),[mobile,setMobile]=useState(false);
 const [menuSearch,setMenuSearch]=useState("");
 const root=useRef<HTMLElement>(null),trigger=useRef<HTMLButtonElement|null>(null),mobileTrigger=useRef<HTMLButtonElement|null>(null);
 const visible=sections.map(s=>({...s,items:navigation.filter(g=>s.groups.includes(g.name))})).filter(s=>s.items.length);
 useEffect(()=>{
  function outside(e:PointerEvent){if(!root.current?.contains(e.target as Node)){setExpanded(null);setMobile(false)}}
  function escape(e:KeyboardEvent){if(e.key==="Escape"&&(expanded||mobile)){e.preventDefault();const target=mobile?mobileTrigger.current:trigger.current;setExpanded(null);setMobile(false);requestAnimationFrame(()=>target?.focus({preventScroll:true}))}}
  document.addEventListener("pointerdown",outside);document.addEventListener("keydown",escape);
  return()=>{document.removeEventListener("pointerdown",outside);document.removeEventListener("keydown",escape)};
 },[expanded,mobile]);
 useEffect(()=>{setExpanded(null);setMobile(false)},[active]);
 function visit(group:string,page:string){go(group,page);setExpanded(null);setMobile(false);requestAnimationFrame(()=>document.getElementById("workspace-main")?.focus({preventScroll:true}))}
 return <nav ref={root} className="primary-navigation" aria-label="Grant workspaces" onBlur={e=>{if(!e.currentTarget.contains(e.relatedTarget as Node)){setExpanded(null)}}}>
  <button ref={mobileTrigger} className="navigation-mobile-toggle" aria-expanded={mobile} aria-controls="primary-navigation-links" onClick={e=>{trigger.current=e.currentTarget;setMobile(!mobile);setExpanded(null)}}>{mobile?<X size={20}/>:<Menu size={20}/>}<span>{mobile?"Close menu":"Workspaces"}</span></button>
  <div id="primary-navigation-links" className={"primary-navigation-links "+(mobile?"is-open":"")}>
   <button className={"primary-home "+(active==="Home"?"is-current":"")} aria-current={active==="Home"?"page":undefined} onClick={()=>{onHome();setExpanded(null);setMobile(false);requestAnimationFrame(()=>document.getElementById("workspace-main")?.focus({preventScroll:true}))}}><Home size={18}/><span>Home</span></button>
   {visible.map((section,i)=>{const open=expanded===section.label,current=section.items.some(g=>g.items.includes(active));return <div className="primary-navigation-section" key={section.label}>
    <button className={"primary-navigation-trigger "+(current?"is-current":"")} aria-expanded={open} aria-controls={"navigation-panel-"+i} onClick={e=>{trigger.current=e.currentTarget;setMenuSearch("");setExpanded(open?null:section.label)}}>{section.label}<ChevronDown size={16} className={open?"rotate":""}/></button>
    {open&&<div id={"navigation-panel-"+i} className="workspace-mega-panel"><div className="workspace-mega-heading"><div><span className="menu-eyebrow">WORKSPACES</span><strong>{section.label}</strong></div><label className="workspace-menu-search"><Search size={18} aria-hidden="true"/><input aria-label={"Find a workspace in "+section.label} placeholder="Find a workspace" value={menuSearch} onChange={e=>setMenuSearch(e.target.value)}/></label><button aria-label={"Close "+section.label+" menu"} onClick={()=>{setExpanded(null);trigger.current?.focus()}}><X size={20}/></button></div><div className="workspace-mega-columns">{section.items.map(group=>{const Icon=group.icon;const pages=group.items.filter(page=>workspaceLabel(page).toLowerCase().includes(menuSearch.trim().toLowerCase()));return pages.length?<section className="workspace-menu-group" key={group.name}><h2><Icon size={18} aria-hidden="true"/>{workspaceLabel(group.name)}<span className="workspace-menu-count">{pages.length}</span></h2><div className="workspace-menu-pages">{pages.map(page=><button key={page} aria-label={workspaceLabel(page)} aria-current={active===page?"page":undefined} className={active===page?"is-current":""} onClick={()=>visit(group.name,page)}><span className="workspace-item-label">{workspaceLabel(page)}</span>{active===page?<Check size={17} aria-hidden="true"/>:<ChevronRight size={16} aria-hidden="true"/>}</button>)}</div></section>:null})}</div>{!section.items.some(group=>group.items.some(page=>workspaceLabel(page).toLowerCase().includes(menuSearch.trim().toLowerCase())))&&<p className="workspace-menu-empty" role="status">No workspaces match “{menuSearch}”. Try another name.</p>}</div>}
   </div>})}
  </div>
 </nav>
}
