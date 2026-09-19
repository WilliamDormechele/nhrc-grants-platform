"use client";
import {Row,dateText,human} from "./client";
import {useLoad,useWorkbench} from "./context";
import {Badge,Empty,ErrorBox,Loading,Notice,Panel,Stat,Table} from "./controls";

export default function RoleDashboard(){
  const {go}=useWorkbench(),load=useLoad<Row>("/dashboard");
  if(load.loading)return <Loading/>;
  if(load.error)return <ErrorBox message={load.error} retry={load.reload}/>;
  const row=load.data||{};
  const title:Record<string,string>={
    GRANTS:"Grants & Pre-Award Dashboard",
    RESEARCH:"Researcher Dashboard",
    FINANCE:"Finance Dashboard",
    PROCUREMENT:"Procurement Dashboard",
    LABORATORY:"Laboratory Dashboard",
    GOVERNANCE:"Research Governance Dashboard",
    EXECUTIVE:"Executive Dashboard",
    ADMIN:"Administration Dashboard",
    SUPERADMIN:"Superadmin Oversight Dashboard",
    PERSONAL:"My Dashboard"
  };
  return <>
    <div className="role-dashboard-head">
      <div><div className="eyebrow">{human(row.type||"PERSONAL")}</div><h2>{title[row.type]||"My Dashboard"}</h2></div>
      <button className="btn" onClick={load.reload}>Refresh dashboard</button>
    </div>
    <div className="stats">{(row.metrics||[]).map((metric:Row)=><Stat key={metric.label} label={metric.label} value={metric.value}/>)}</div>
    <div className="grid">
      <Panel title="Priority work queue" note="Open records are ordered using the most relevant recorded date for this dashboard.">
        {(row.queues||[]).length?<Table rows={row.queues||[]} columns={[
          {key:"title",label:"Record"},
          {key:"reference",label:"Reference"},
          {key:"stage",label:"Stage",render:item=><Badge value={item.stage}/>},
          {key:"due_date",label:"Due",render:item=>dateText(item.due_date)}
        ]} onOpen={item=>go(item.workspace||"My Work",item.id)} label="Open record"/>:<Empty>No current queue items for this role.</Empty>}
      </Panel>
      <Panel title="Quick actions" note="Only workspaces intended for this role are shown here.">
        <div className="panelbody actions dashboard-links">{(row.quickLinks||[]).map((item:string)=><button key={item} className="btn" onClick={()=>go(item)}>{item}</button>)}</div>
      </Panel>
    </div>
    {row.note&&<Notice>{row.note}</Notice>}
  </>;
}
