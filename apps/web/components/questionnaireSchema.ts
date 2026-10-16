export function questionnaireFields(source:any){
 const fields:any[]=[],seen=new Set<string>();
 function collect(items:any,section=""){if(!Array.isArray(items))return;for(const raw of items){const name=typeof raw==="string"?raw:raw?.name;if(!name||seen.has(name))continue;seen.add(name);fields.push(typeof raw==="string"?{name,label:name.replaceAll("_"," ").replace(/^./,(c:string)=>c.toUpperCase()),type:"text",required:false,section}:{...raw,label:raw.label||name.replaceAll("_"," "),section:raw.section||section})}}
 collect(source?.fields);collect(source?.columns);collect(source?.checks);collect(source?.areas);for(const s of source?.sections||[]){collect(s.fields,s.title);collect(s.checks,s.title)}return fields;
}
