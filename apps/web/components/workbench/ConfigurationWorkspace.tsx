"use client";
import {useMemo,useState} from "react";
import {Row,human} from "./client";
import {useLoad,useWorkbench} from "./context";
import {Badge,Empty,ErrorBox,Heading,Loading,Modal,Notice,Panel} from "./controls";

export default function ConfigurationWorkspace({kind}:{kind:"forms"|"templates"}){
  const {client,actor,notify}=useWorkbench(),load=useLoad<Row>("/configuration/"+kind);
  const [selected,setSelected]=useState<Row|null>(null),[editing,setEditing]=useState(false),[name,setName]=useState(""),[description,setDescription]=useState(""),[json,setJson]=useState(""),[error,setError]=useState("");
  const items:Row[]=load.data?.items||[];
  const grouped=useMemo(()=>{
    const result:Record<string,Row[]>={};
    for(const item of items){
      const key=kind==="forms"?human(item.entity_type):human(item.category||item.template_type);
      (result[key]??=[]).push(item);
    }
    return result;
  },[items,kind]);
  const canEdit=actor.roles.includes("ADMIN");
  const open=(item:Row)=>{setSelected(item);setEditing(false);setError("");setName(item.name||"");setDescription(item.description||"");setJson(JSON.stringify(kind==="forms"?item.schema_json:item.content_json,null,2));};
  const save=async()=>{
    if(!selected)return;setError("");
    let parsed:any;try{parsed=JSON.parse(json);}catch{setError("Enter valid JSON.");return;}
    try{
      await client("/configuration/"+kind+"/"+selected.id,{method:"PATCH",body:JSON.stringify({values:kind==="forms"?{name,schema_json:parsed}:{name,description,content_json:parsed}})});
      notify((kind==="forms"?"Form":"Template")+" updated as a new controlled version.");
      setEditing(false);setSelected(null);load.reload();
    }catch(ex){setError((ex as Error).message);}
  };
  const title=kind==="forms"?"Forms & Fields":"Templates";
  const description=kind==="forms"?"Versioned institutional forms used across the grant lifecycle.":"Versioned NHRC templates for proposals, reviews, finance, procurement, laboratory, governance and closeout.";
  return <>
    <Heading title={title} description={description}><button className="btn" onClick={load.reload} disabled={load.loading}>Refresh</button></Heading>
    <Notice>{canEdit?"Business Administrators can revise definitions. Each save increments the controlled version.":"You can inspect current definitions. Editing requires the Business Administrator role."}</Notice>
    {load.loading?<Loading/>:load.error?<ErrorBox message={load.error} retry={load.reload}/>:Object.entries(grouped).map(([group,rows])=><Panel key={group} title={group} note={rows.length+" "+(kind==="forms"?"forms":"templates")+"."}>
      <div className="template-grid">{rows.map(item=><button key={item.id} className="template-card" onClick={()=>open(item)}>
        <div className="template-card-head"><strong>{item.name}</strong><Badge value={"V"+item.version_no}/></div>
        <span className="tiny">{item.code}</span>
        {item.description&&<p>{item.description}</p>}
        <span className="tiny">{kind==="forms"?human(item.entity_type):human(item.template_type)}</span>
      </button>)}</div>
    </Panel>)}
    {!load.loading&&!items.length&&<Empty>No active definitions were found.</Empty>}
    {selected&&<Modal title={selected.name} onClose={()=>setSelected(null)} wide>
      <div className="panelbody">
        <dl className="kv"><dt>Code</dt><dd>{selected.code}</dd><dt>Version</dt><dd>{selected.version_no}</dd><dt>Type</dt><dd>{human(kind==="forms"?selected.entity_type:selected.template_type)}</dd></dl>
        {editing?<>
          <div className="formgrid">
            <div className="field full"><label>Name</label><input value={name} onChange={e=>setName(e.target.value)}/></div>
            {kind==="templates"&&<div className="field full"><label>Description</label><textarea rows={3} value={description} onChange={e=>setDescription(e.target.value)}/></div>}
            <div className="field full"><label>{kind==="forms"?"Form schema":"Template definition"}</label><textarea rows={18} value={json} onChange={e=>setJson(e.target.value)}/></div>
          </div>
          {error&&<ErrorBox message={error}/>}
          <div className="actions"><button className="btn primary" onClick={()=>void save()}>Save new version</button><button className="btn" onClick={()=>setEditing(false)}>Cancel</button></div>
        </>:<>
          {selected.description&&<p>{selected.description}</p>}
          <pre className="source-evidence">{JSON.stringify(kind==="forms"?selected.schema_json:selected.content_json,null,2)}</pre>
          {canEdit&&<button className="btn primary" onClick={()=>setEditing(true)}>Edit definition</button>}
        </>}
      </div>
    </Modal>}
  </>;
}
