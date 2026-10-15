"use client";
import {useEffect,useState} from "react";
import {createPortal} from "react-dom";
// Enhances table renderers consistently, while server-paginated registers retain their own full-register filters.
export default function LiveTableFilters(){
 const[targets,setTargets]=useState<HTMLElement[]>([]);
 useEffect(()=>{let timer:ReturnType<typeof setTimeout>;const scan=()=>{const next=Array.from(document.querySelectorAll<HTMLElement>(".main .tableWrap")).filter(e=>!e.closest(".record-panel"));setTargets(old=>old.length===next.length&&old.every((e,i)=>e===next[i])?old:next)};scan();const ob=new MutationObserver(()=>{clearTimeout(timer);timer=setTimeout(scan,80)});ob.observe(document.getElementById("workspace-main")||document.body,{childList:true,subtree:true});return()=>{clearTimeout(timer);ob.disconnect()}},[]);
 return <>{targets.map((target,i)=>createPortal(<Filter tableWrap={target}/>,target,"live-filter-"+i))}</>;
}
function Filter({tableWrap}:{tableWrap:HTMLElement}){
 const[q,setQ]=useState(""),[column,setColumn]=useState(-1),[headers,setHeaders]=useState<string[]>([]),[count,setCount]=useState({visible:0,total:0});
 useEffect(()=>{const table=tableWrap.querySelector("table");if(!table)return;const update=()=>{const heads=Array.from(table.querySelectorAll("thead th")).map(h=>h.textContent||"");setHeaders(old=>old.join("|")===heads.join("|")?old:heads);const rows=Array.from(table.querySelectorAll<HTMLTableRowElement>("tbody tr"));let visible=0;rows.forEach(row=>{const text=column<0?row.textContent:row.cells[column]?.textContent;const match=(text||"").toLocaleLowerCase().includes(q.toLocaleLowerCase());row.hidden=!match;if(match)visible++});setCount(old=>old.visible===visible&&old.total===rows.length?old:{visible,total:rows.length})};update();const ob=new MutationObserver(update);ob.observe(table,{childList:true,subtree:true,characterData:true});return()=>{ob.disconnect();table.querySelectorAll<HTMLTableRowElement>("tbody tr").forEach(r=>r.hidden=false)}},[q,column,tableWrap]);
 return <div className="live-table-filter"><label>Live table filter<input type="search" value={q} placeholder="Type to filter visible rows" onChange={e=>setQ(e.target.value)}/></label><label>Search column<select value={column} onChange={e=>setColumn(Number(e.target.value))}><option value={-1}>All columns</option>{headers.map((h,i)=><option key={i} value={i}>{h}</option>)}</select></label>{q&&<button type="button" className="btn small" onClick={()=>setQ("")}>Clear filter</button>}<span role="status">{count.visible} of {count.total} loaded rows</span></div>;
}
