"use client";

import {useEffect} from "react";

export default function ErrorPage({error,reset}:{error:Error & {digest?:string};reset:()=>void}){
  useEffect(()=>{console.error(error)},[error]);
  return <main className="main"><section className="panel"><div className="eyebrow">NHRC GRANTS / UAT</div><h1>Workspace could not be displayed</h1><p className="subtle">The application caught this client-side error instead of leaving the page unusable.</p><div className="notice warn">{error.message||"Unexpected workspace error"}</div><div className="actions"><button className="btn primary" onClick={reset}>Try again</button><button className="btn" onClick={()=>window.location.href="/"}>Return home</button></div></section></main>
}
