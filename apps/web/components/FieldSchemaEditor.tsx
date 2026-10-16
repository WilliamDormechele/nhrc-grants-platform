"use client";
import {questionnaireFields} from "./questionnaireSchema";
import {useState} from "react";
type Question={name:string;label:string;type:string;required:boolean;help?:string;choices?:string[]};
export default function FieldSchemaEditor({value,onChange}:{value:any;onChange:(value:string)=>void}){
 let original:any={};try{original=typeof value==="string"?JSON.parse(value||"{}"):value||{}}catch{}
 const[fields,setFields]=useState<Question[]>(questionnaireFields(original)); const[choiceText,setChoiceText]=useState<Record<string,string>>({});
 function save(next:Question[]){setFields(next);onChange(JSON.stringify({fields:next}))}
 function update(i:number,patch:Partial<Question>){save(fields.map((q,index)=>index===i?{...q,...patch}:q))}
 return <div className="schema-editor"><p className="field-help">Define labelled questions for the institutional proposal form. Published application forms appear in Proposal Workspace.</p>{fields.map((q,i)=><fieldset key={i}><legend>Question {i+1}</legend><div className="form-grid"><label>Question label<input value={q.label} required onChange={e=>update(i,{label:e.target.value})}/></label><label>Answer format<select value={q.type} onChange={e=>update(i,{type:e.target.value})}>{[["text","Short text"],["textarea","Detailed response"],["number","Number"],["date","Date"],["select","Choice"],["checkbox","Yes or no"]].map(([type,label])=><option key={type} value={type}>{label}</option>)}</select></label><label className="wide">Guidance for the applicant<input value={q.help||""} onChange={e=>update(i,{help:e.target.value})}/></label>{q.type==="select"&&<label className="wide">Choices, separated by commas<input value={choiceText[q.name]??(q.choices||[]).join(', ')} onChange={e=>{const text=e.target.value;setChoiceText(c=>({...c,[q.name]:text}));update(i,{choices:text.split(',').map(s=>s.trim()).filter(Boolean)})}}/></label>}<label className="check-input"><input type="checkbox" checked={q.required} onChange={e=>update(i,{required:e.target.checked})}/>Response required</label><button className="text-action" type="button" onClick={()=>save(fields.filter((_,index)=>index!==i))}>Remove question</button></div></fieldset>)}<button className="btn" type="button" onClick={()=>save([...fields,{name:"question"+(fields.length+1)+Date.now(),label:"",type:"text",required:false,help:""}])}>Add form question</button></div>
}

