"use client";
import {useMemo,useState} from "react";
import {Row,dateText,human} from "./client";
import {useLoad,useWorkbench} from "./context";
import {Badge,Empty,ErrorBox,Heading,Loading,Notice,Panel,Stat} from "./controls";

type View="MONTH"|"WEEK"|"AGENDA";
const eventTypes=["APPLICATION_DEADLINE","DELIVERABLE","REPORT","PROCUREMENT","LAB_MAINTENANCE","LAB_CALIBRATION","COMPLIANCE","AWARD_END"];

const routeFor=(row:Row)=>{
  switch(row.resource_key){
    case "applications": return "Applications";
    case "deliverables": return "Deliverables";
    case "reports": return "Reports";
    case "procurement-plans": return "Procurement Plans";
    case "maintenance": return "Maintenance & Calibration";
    case "compliance": return "Ethics & Regulatory Links";
    case "awards": return "Award Register";
    default: return "Calendar";
  }
};
const iso=(date:Date)=>date.toISOString().slice(0,10);
const startOfWeek=(date:Date)=>{const d=new Date(date);const day=(d.getDay()+6)%7;d.setDate(d.getDate()-day);d.setHours(0,0,0,0);return d;};
const addDays=(date:Date,n:number)=>{const d=new Date(date);d.setDate(d.getDate()+n);return d;};

export default function CalendarWorkspace(){
  const {go}=useWorkbench(),load=useLoad<Row>("/personal");
  const [view,setView]=useState<View>("MONTH");
  const [anchor,setAnchor]=useState(()=>new Date());
  const [filters,setFilters]=useState<string[]>(eventTypes);
  const rows:Row[]=load.data?.calendar||[];
  const today=iso(new Date());
  const filtered=useMemo(()=>rows.filter(row=>filters.includes(row.event_type)),[rows,filters]);
  const overdue=filtered.filter(row=>row.due_date&&row.due_date<today);
  const next7=filtered.filter(row=>row.due_date&&row.due_date>=today&&row.due_date<=iso(addDays(new Date(),7)));
  const next30=filtered.filter(row=>row.due_date&&row.due_date>=today&&row.due_date<=iso(addDays(new Date(),30)));
  const toggle=(type:string)=>setFilters(current=>current.includes(type)?current.filter(item=>item!==type):[...current,type]);
  const move=(delta:number)=>{const d=new Date(anchor);if(view==="MONTH")d.setMonth(d.getMonth()+delta);else d.setDate(d.getDate()+delta*7);setAnchor(d);};
  const periodLabel=view==="MONTH"?anchor.toLocaleDateString("en-GB",{month:"long",year:"numeric"}):"Week of "+startOfWeek(anchor).toLocaleDateString("en-GB",{day:"numeric",month:"short",year:"numeric"});
  return <>
    <Heading title="Calendar" description="A role-aware institutional calendar for application deadlines, reports, deliverables, procurement, laboratory servicing, compliance and award milestones.">
      <button className="btn" onClick={()=>setAnchor(new Date())}>Today</button>
      <button className="btn" onClick={load.reload} disabled={load.loading}>Refresh</button>
    </Heading>
    {load.loading?<Loading/>:load.error?<ErrorBox message={load.error} retry={load.reload}/>:<>
      <div className="stats">
        <Stat label="Overdue" value={overdue.length} note="Recorded obligations before today"/>
        <Stat label="Next 7 days" value={next7.length} note="Immediate institutional attention"/>
        <Stat label="Next 30 days" value={next30.length} note="Forward planning window"/>
        <Stat label="Calendar scope" value={human(load.data?.calendarScope||"ASSIGNED")} note={filtered.length+" of "+rows.length+" visible events"}/>
      </div>
      <Panel title="Calendar controls" note="Filters change only this view; they do not alter the underlying records.">
        <div className="panelbody">
          <div className="calendar-toolbar">
            <div className="actions">{(["MONTH","WEEK","AGENDA"] as View[]).map(item=><button key={item} className={"btn "+(view===item?"primary":"")} onClick={()=>setView(item)}>{human(item)}</button>)}</div>
            <div className="actions"><button className="btn" onClick={()=>move(-1)}>Previous</button><strong>{periodLabel}</strong><button className="btn" onClick={()=>move(1)}>Next</button></div>
          </div>
          <div className="calendar-filters">{eventTypes.map(type=><label key={type} className="calendar-filter"><input type="checkbox" checked={filters.includes(type)} onChange={()=>toggle(type)}/><span>{human(type)}</span></label>)}</div>
        </div>
      </Panel>
      {view==="MONTH"&&<MonthView rows={filtered} anchor={anchor} today={today} open={row=>go(routeFor(row),row.id)}/>}
      {view==="WEEK"&&<WeekView rows={filtered} anchor={anchor} today={today} open={row=>go(routeFor(row),row.id)}/>}
      {view==="AGENDA"&&<AgendaView rows={filtered} today={today} open={row=>go(routeFor(row),row.id)}/>}
      <Notice>Calendar dates come from saved grant records. Confirm exact funder submission times and external deadlines against the official source before acting.</Notice>
    </>}
  </>;
}

function MonthView({rows,anchor,today,open}:{rows:Row[];anchor:Date;today:string;open:(row:Row)=>void}){
  const first=new Date(anchor.getFullYear(),anchor.getMonth(),1),start=startOfWeek(first),days=Array.from({length:42},(_,i)=>addDays(start,i));
  return <Panel title="Month view" note="Select any event to open its source record."><div className="calendar-month">
    {["Mon","Tue","Wed","Thu","Fri","Sat","Sun"].map(day=><div className="calendar-day-name" key={day}>{day}</div>)}
    {days.map(day=>{const key=iso(day),events=rows.filter(row=>row.due_date===key);return <div key={key} className={"calendar-day "+(day.getMonth()!==anchor.getMonth()?"muted-day ":"")+(key===today?"today":"")}>
      <div className="calendar-date">{day.getDate()}</div><div className="calendar-events">
      {events.slice(0,4).map(row=><button key={row.resource_key+"-"+row.id+"-"+row.event_type} className={"calendar-event "+(key<today?"overdue":"")} onClick={()=>open(row)} title={row.title}><span>{human(row.event_type)}</span><b>{row.title}</b></button>)}
      {events.length>4&&<span className="tiny">+{events.length-4} more</span>}</div></div>;})}
  </div></Panel>;
}

function WeekView({rows,anchor,today,open}:{rows:Row[];anchor:Date;today:string;open:(row:Row)=>void}){
  const start=startOfWeek(anchor),days=Array.from({length:7},(_,i)=>addDays(start,i));
  return <Panel title="Week view" note="A focused seven-day delivery view."><div className="calendar-week">{days.map(day=>{const key=iso(day),events=rows.filter(row=>row.due_date===key);return <section key={key} className={"calendar-week-day "+(key===today?"today":"")}><h3>{day.toLocaleDateString("en-GB",{weekday:"short",day:"numeric",month:"short"})}</h3>
    {events.map(row=><button key={row.resource_key+"-"+row.id+"-"+row.event_type} className="calendar-agenda-item" onClick={()=>open(row)}><Badge value={row.event_type}/><strong>{row.title}</strong><span>{row.award_reference||""}</span></button>)}
    {!events.length&&<span className="tiny">No recorded obligations.</span>}</section>;})}</div></Panel>;
}

function AgendaView({rows,today,open}:{rows:Row[];today:string;open:(row:Row)=>void}){
  const ordered=[...rows].filter(row=>row.due_date).sort((a,b)=>String(a.due_date).localeCompare(String(b.due_date)));
  return <Panel title="Agenda" note="Chronological view across the selected event types."><div className="panelbody calendar-agenda">
    {ordered.map(row=><button key={row.resource_key+"-"+row.id+"-"+row.event_type+"-"+row.due_date} className={"calendar-agenda-row "+(row.due_date<today?"overdue":"")} onClick={()=>open(row)}>
      <div className="datebox">{dateText(row.due_date)}</div><div><Badge value={row.event_type}/><h3>{row.title}</h3><span className="tiny">{row.award_reference||"Institutional record"} · {human(row.status)}</span></div>
    </button>)}
    {!ordered.length&&<Empty>No calendar events match the current filters.</Empty>}
  </div></Panel>;
}
