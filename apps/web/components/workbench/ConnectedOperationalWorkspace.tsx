"use client";
import {useEffect,useMemo,useState} from "react";
import {ErrorBox,Heading,Loading,Notice,Panel,Table} from "./controls";
import {Row,human} from "./client";
import {useWorkbench} from "./context";

type Definition={endpoint:string;description:string;note?:string};
const definitions:Record<string,Definition>={
  "Users & Roles":{endpoint:"/operations/users",description:"Inspect provisioned users, organisation units and current role assignments."},
  "Delegations & Acting Roles":{endpoint:"/operations/delegations",description:"Inspect time-bounded acting arrangements and delegated authority.",note:"Creating or changing a delegation remains restricted to the dedicated controlled administration service."},
  "Access Reviews":{endpoint:"/operations/access-reviews",description:"Review current access-review records and outstanding decisions."},
  "Audit Log":{endpoint:"/operations/audit",description:"Inspect material actions recorded against users and institutional records."},
  "Templates":{endpoint:"/operations/templates",description:"Inspect active institutional templates used by configured workflows."},
  "System Settings":{endpoint:"/operations/features",description:"Inspect current feature configuration. Secrets and privileged settings are never exposed here."},
  "Integration Health":{endpoint:"/operations/integrations",description:"Inspect configured integrations and their recorded status.",note:"Credentials are not displayed. Connection verification and key rotation require authorised technical administration."},
  "Backup & Recovery":{endpoint:"/operations/backups",description:"Inspect recorded backup runs.",note:"A recorded backup is not evidence of recoverability unless a recovery test has also been completed."},
  "Security Events":{endpoint:"/operations/security-events",description:"Inspect recorded security and operational events."},
  "Privileged Activity":{endpoint:"/operations/privileged",description:"Inspect recorded privileged administrative activity."},
  "Conflicts / Declarations":{endpoint:"/operations/declarations",description:"Inspect conflict and declaration records used in governance decisions."},
  "Privacy & Information Governance":{endpoint:"/operations/compliance",description:"Inspect recorded information-governance and compliance obligations."},
  "Legal & Contracts Review":{endpoint:"/operations/compliance",description:"Inspect recorded legal and compliance obligations linked to controlled grant activity.",note:"Contract preparation and approval are managed in Contracts & Agreements."}
};

export function hasConnectedOperationalWorkspace(name:string){return Boolean(definitions[name]);}

export default function ConnectedOperationalWorkspace({name}:{name:string}){
  const definition=definitions[name];
  const {client}=useWorkbench();
  const [data,setData]=useState<Row[]|null>(null),[error,setError]=useState(""),[loading,setLoading]=useState(true),[reload,setReload]=useState(0);
  useEffect(()=>{
    if(!definition)return;
    const controller=new AbortController();setLoading(true);setError("");
    client<any>(definition.endpoint,{signal:controller.signal})
      .then(body=>{if(!controller.signal.aborted)setData(Array.isArray(body)?body:[body||{}]);})
      .catch(ex=>{if(!controller.signal.aborted&&ex.name!=="AbortError")setError(ex.message);})
      .finally(()=>{if(!controller.signal.aborted)setLoading(false);});
    return()=>controller.abort();
  },[client,definition,reload]);
  const columns=useMemo(()=>{
    const first=data?.[0]||{};
    return Object.keys(first).filter(key=>!["password","secret","token","old_state","new_state","metadata"].includes(key)).slice(0,8)
      .map(key=>({key,label:human(key)}));
  },[data]);
  if(!definition)return null;
  return <>
    <Heading title={name} description={definition.description}><button className="btn" disabled={loading} onClick={()=>setReload(value=>value+1)}>Refresh</button></Heading>
    {definition.note&&<Notice>{definition.note}</Notice>}
    {loading?<Loading/>:error?<ErrorBox message={error} retry={()=>setReload(value=>value+1)}/>:<Panel title={name} note={data?`${data.length} records returned by the connected service.`:""}><Table rows={data||[]} columns={columns}/></Panel>}
  </>;
}
