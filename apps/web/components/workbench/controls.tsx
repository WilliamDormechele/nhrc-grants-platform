"use client";
import {useEffect,useId,useRef,useState,type FormEvent,type ReactNode} from "react";
import {Action,Field,Row,actionPayload,dateText,formValues,human,initialValues,moneyText,safeUrl} from "./client";
import {useWorkbench} from "./context";

export function Heading({title,description,children}:{title:string;description:string;children?:ReactNode}){
  return <div className="heading"><div><div className="eyebrow">NHRC GRANTS</div><h1>{title}</h1><p>{description}</p></div><div className="actions">{children}</div></div>;
}
export function Panel({title,note,children,actions}:{title:string;note?:string;children:ReactNode;actions?:ReactNode}){
  return <section className="panel"><div className="panelhead"><div><h2>{title}</h2>{note&&<p>{note}</p>}</div>{actions&&<div className="actions">{actions}</div>}</div>{children}</section>;
}
export function Notice({children,warning=false}:{children:ReactNode;warning?:boolean}){return <div className={`notice ${warning?"warn":""}`}>{children}</div>;}
export function ErrorBox({message,retry}:{message:string;retry?:()=>void}){
  return <div className="notice warn" role="alert"><p>{message}</p>{retry&&<button type="button" className="btn small" onClick={retry}>Try again</button>}</div>;
}
export function Loading(){return <div className="empty" role="status">Loading grant information...</div>;}
export function Empty({children}:{children:ReactNode}){return <div className="empty">{children}</div>;}
export function Badge({value}:{value:unknown}){
  const text=String(value||"");
  const tone=/REJECT|FAIL|OVERDUE|INELIGIBLE|RETURNED|HIGH/.test(text)?"bad":/APPROVED|ACTIVE|CLEARED|COMPLETED|ACCEPTED|VERIFIED|ELIGIBLE/.test(text)?"good":/PENDING|REVIEW|DRAFT|CONDITIONAL|ASSIGNED/.test(text)?"warn":"blue";
  return <span className={`badge ${tone}`}>{human(value)}</span>;
}
export function Stat({label,value,note}:{label:string;value:ReactNode;note?:string}){
  return <div className="stat"><div className="label">{label}</div><div className="value">{value??"Not recorded"}</div><div className="note">{note}</div></div>;
}
export function Link({url,children}:{url:unknown;children?:ReactNode}){
  const href=safeUrl(url);
  return href?<a href={href} target="_blank" rel="noopener noreferrer" className="linkbutton">{children||"Open controlled evidence"}</a>:<span className="subtle">No supported link recorded</span>;
}
export function Details({record,fields}:{record:Row;fields:Field[]}){
  return <dl className="kv">{fields.map(item=><div className="kvrow" key={item.name}><dt>{item.label}</dt><dd>{item.kind==="url"?<Link url={record[item.name]}/>:item.kind==="date"?dateText(record[item.name]):item.kind==="money"?moneyText(record[item.name],record.currency):human(record._references?.[item.name]||record[item.name])}</dd></div>)}</dl>;
}
export type Column={key:string;label:string;render?:(row:Row)=>ReactNode};
export function Table({rows,columns,onOpen,label="Open record"}:{rows:Row[];columns:Column[];onOpen?:(row:Row)=>void;label?:string}){
  if(!rows.length)return <Empty>No records match this selection.</Empty>;
  return <div className="tablewrap"><table>
    <thead><tr>{columns.map(column=><th key={column.key} scope="col">{column.label}</th>)}{onOpen&&<th scope="col">Action</th>}</tr></thead>
    <tbody>{rows.map((row,index)=><tr key={row.id||index}>{columns.map(column=><td key={column.key}>{column.render?column.render(row):human(row[column.key])}</td>)}{onOpen&&<td><button type="button" className="btn small" onClick={()=>onOpen(row)}>{label}</button></td>}</tr>)}</tbody>
  </table></div>;
}

export function Modal({title,children,onClose,busy=false,wide=false}:{title:string;children:ReactNode;onClose:()=>void;busy?:boolean;wide?:boolean}){
  const panel=useRef<HTMLDivElement>(null),heading=useId();
  useEffect(()=>{
    const previous=document.activeElement as HTMLElement|null;
    const oldOverflow=document.body.style.overflow;document.body.style.overflow="hidden";
    const timer=window.setTimeout(()=>panel.current?.querySelector<HTMLElement>("input,select,textarea,button")?.focus(),0);
    return ()=>{window.clearTimeout(timer);document.body.style.overflow=oldOverflow;previous?.focus();};
  },[]);
  return <div className="modalback"><div className={`modal ${wide?"wide":""}`} role="dialog" aria-modal="true" aria-labelledby={heading} ref={panel} onKeyDown={event=>{
    if(event.key==="Escape"&&!busy){event.stopPropagation();onClose();}
    if(event.key==="Tab"){
      const focusable=Array.from(panel.current?.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex="0"]')||[]).filter(element=>element.offsetParent!==null);
      const first=focusable[0],last=focusable[focusable.length-1];
      if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
      if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}
    }
  }}>
    <button type="button" className="close" aria-label="Close dialog" disabled={busy} onClick={onClose}>×</button>
    <h2 id={heading}>{title}</h2>{children}
  </div></div>;
}

function Lookup({field,value,onChange,disabled,id}:{field:Field;value:any;onChange:(value:string)=>void;disabled:boolean;id:string}){
  const {client}=useWorkbench();
  const [query,setQuery]=useState(""),[items,setItems]=useState<Row[]>([]),[loading,setLoading]=useState(false),[error,setError]=useState(""),[truncated,setTruncated]=useState(false);
  useEffect(()=>{
    const controller=new AbortController();
    const timer=window.setTimeout(()=>{
      setLoading(true);setError("");
      client<Row>(`/lookups/${encodeURIComponent(field.lookup||"")}?q=${encodeURIComponent(query)}`,{signal:controller.signal}).then(result=>{
        if(!controller.signal.aborted){setItems(result.items||[]);setTruncated(Boolean(result.truncated));}
      }).catch(ex=>{if(!controller.signal.aborted)setError(ex.message);}).finally(()=>{if(!controller.signal.aborted)setLoading(false);});
    },250);
    return ()=>{window.clearTimeout(timer);controller.abort();};
  },[client,field.lookup,query]);
  return <>
    <input aria-label={`Search ${field.label}`} type="search" placeholder={`Find ${field.label.toLowerCase()}`} maxLength={200} value={query} disabled={disabled} onChange={event=>setQuery(event.target.value)}/>
    <select id={id} required={field.required} disabled={disabled} value={value||""} onChange={event=>onChange(event.target.value)}>
      <option value="">{loading?"Loading choices...":"Select a record"}</option>
      {value&&!items.some(item=>item.id===value)&&<option value={value}>Current selection ({String(value).slice(0,8)})</option>}
      {items.map(item=><option key={item.id} value={item.id}>{item.label}{item.roles?.length?` (${item.roles.map(human).join(", ")})`:""}</option>)}
    </select>
    {truncated&&<small>Refine the search to see more choices.</small>}
    {error&&<span className="error" role="alert">{error}</span>}
  </>;
}
export function FieldInput({field,value,onChange,disabled=false}:{field:Field;value:any;onChange:(value:any)=>void;disabled?:boolean}){
  const id=useId(),kind=field.kind;
  let input:ReactNode;
  if(kind==="lookup") input=<Lookup field={field} value={value} onChange={onChange} disabled={disabled} id={id}/>;
  else if(kind==="textarea") input=<textarea id={id} value={value??""} rows={5} maxLength={field.maxLength} required={field.required} disabled={disabled} onChange={event=>onChange(event.target.value)}/>;
  else if(kind==="select") input=<select id={id} value={value??""} required={field.required} disabled={disabled} onChange={event=>onChange(event.target.value)}><option value="">Select an option</option>{field.choices.map(choice=><option key={choice} value={choice}>{human(choice)}</option>)}</select>;
  else if(kind==="boolean") input=<select id={id} value={String(value===true)} disabled={disabled} onChange={event=>onChange(event.target.value==="true")}><option value="false">No</option><option value="true">Yes</option></select>;
  else input=<input id={id} value={value??""} required={field.required} disabled={disabled} maxLength={field.maxLength} type={kind==="date"?"date":kind==="url"?"url":kind==="email"?"email":kind==="integer"?"number":"text"} inputMode={["money","decimal"].includes(kind)?"decimal":undefined} min={kind==="integer"?0:undefined} step={kind==="integer"?1:undefined} placeholder={kind==="datetime"?"2026-09-19T14:30:00+00:00":kind==="url"?"https://...":kind==="orcid"?"0000-0000-0000-0000":undefined} onChange={event=>onChange(event.target.value)}/>;
  return <div className={`field ${kind==="textarea"?"full":""}`}>
    <label htmlFor={id}>{field.label}{field.required?" *":""}</label>{input}
    {kind==="tags"&&<small>Separate research themes or methods with commas.</small>}
    {kind==="datetime"&&<small>Include the stated time zone. Do not infer a closing time.</small>}
  </div>;
}

export function FormDialog({title,fields,record,onSave,onClose,submitLabel="Save record",note}:{title:string;fields:Field[];record?:Row;onSave:(values:Row)=>Promise<void>;onClose:()=>void;submitLabel?:string;note?:string}){
  const [values,setValues]=useState<Row>(()=>initialValues(fields,record));
  const [busy,setBusy]=useState(false),[error,setError]=useState(""),[dirty,setDirty]=useState(false);
  const close=()=>{if(!busy&&(!dirty||window.confirm("Discard the changes in this form?")))onClose();};
  useEffect(()=>{
    const warn=(event:BeforeUnloadEvent)=>{if(dirty){event.preventDefault();event.returnValue="";}};
    window.addEventListener("beforeunload",warn);return()=>window.removeEventListener("beforeunload",warn);
  },[dirty]);
  const submit=async(event:FormEvent<HTMLFormElement>)=>{
    event.preventDefault();if(busy)return;setBusy(true);setError("");
    setDirty(false);
    try{await onSave(formValues(fields,values));onClose();}
    catch(ex){setDirty(true);setError((ex as Error).message);}
    finally{setBusy(false);}
  };
  return <Modal title={title} onClose={close} busy={busy} wide>
    <form onSubmit={submit} data-unsaved={dirty?"true":"false"}>
      {note&&<Notice>{note}</Notice>}
      <p className="subtle">Fields marked * are required. Changes are saved only when you submit this form.</p>
      {error&&<ErrorBox message={error}/>}
      <div className="formgrid">{fields.map(item=><FieldInput key={item.name} field={item} value={values[item.name]} disabled={busy} onChange={value=>{setValues(current=>({...current,[item.name]:value}));setDirty(true);}}/>)}</div>
      <div className="actions"><button type="button" className="btn" disabled={busy} onClick={close}>Cancel</button><button type="submit" className="btn primary" disabled={busy}>{busy?"Saving...":submitLabel}</button></div>
    </form>
  </Modal>;
}
export function field(name:string,label:string,kind="text",required=true,choices:string[]=[],lookup?:string):Field{
  return {name,label,kind,required,choices,lookup,maxLength:kind==="textarea"?8000:kind==="url"?2000:255};
}
export function DecisionDialog({action,record,route,fields=[],onDone,onClose,transform}:{action:Action;record:Row;route:string;fields?:Field[];onDone:(result:Row)=>void;onClose:()=>void;transform?:(values:Row)=>Row}){
  const {client,notify}=useWorkbench();
  const request=useRef<{body:string;id:string}|null>(null);
  const allFields=[...fields,field("note","Decision note and rationale","textarea"),field("evidenceUrl","Supporting evidence link","url",Boolean(action.evidenceRequired))];
  return <FormDialog title={action.label} submitLabel={action.label} fields={allFields} onClose={onClose} note="This action records your identity, the current record version and the decision evidence. The service checks your authority again before saving." onSave={async values=>{
    const {note,evidenceUrl,...extra}=values;
    const mapped=transform?transform(extra):extra;
    const signature=JSON.stringify({note,evidenceUrl,extra:mapped});
    if(!request.current||request.current.body!==signature)request.current={body:signature,id:crypto.randomUUID()};
    const result=await client<Row>(route,{method:"POST",body:actionPayload(action.code,record,note,mapped,evidenceUrl,request.current.id)});
    notify(`${action.label}: recorded successfully.`);onDone(result);
  }}/>;
}
