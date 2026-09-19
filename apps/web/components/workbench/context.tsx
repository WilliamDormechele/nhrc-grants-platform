"use client";
import {createContext,useCallback,useContext,useEffect,useState} from "react";
import type {Actor,Client,Resource} from "./client";

type WorkbenchContextValue={client:Client;actor:Actor;catalogue:Resource[];go:(name:string,id?:string)=>void;notify:(message:string)=>void};
export const WorkbenchContext=createContext<WorkbenchContextValue|null>(null);
export function useWorkbench(){const context=useContext(WorkbenchContext);if(!context) throw new Error("Workspace session is missing");return context;}
export function useLoad<T>(path:string|null){
  const {client}=useWorkbench();
  const [data,setData]=useState<T|null>(null),[error,setError]=useState(""),[loading,setLoading]=useState(Boolean(path)),[revision,setRevision]=useState(0);
  useEffect(()=>{
    const controller=new AbortController();
    setData(null);setError("");setLoading(Boolean(path));
    if(path) client<T>(path,{signal:controller.signal}).then(result=>{if(!controller.signal.aborted)setData(result);}).catch(ex=>{if(!controller.signal.aborted)setError(ex.message||"The information could not be loaded.");}).finally(()=>{if(!controller.signal.aborted)setLoading(false);});
    return ()=>controller.abort();
  },[client,path,revision]);
  const reload=useCallback(()=>setRevision(value=>value+1),[]);
  return {data,error,loading,reload,setData};
}
export function useResource(key:string){return useWorkbench().catalogue.find(resource=>resource.key===key);}
