"use client";
import {useEffect,useState} from "react";
import {Bell} from "lucide-react";
import {request} from "./client";
export default function NotificationBell({onOpen}:{onOpen:()=>void}){
 const[count,setCount]=useState<number|null>(null),[failed,setFailed]=useState(false);
 useEffect(()=>{let stopped=false;const refresh=()=>request("/api/notifications/unread-count",{feedback:false}).then(r=>{if(!stopped){setCount(r.unread);setFailed(false)}}).catch(()=>{if(!stopped)setFailed(true)});refresh();const timer=setInterval(refresh,30000);window.addEventListener("focus",refresh);window.addEventListener("grant-notifications-changed",refresh);return()=>{stopped=true;clearInterval(timer);window.removeEventListener("focus",refresh);window.removeEventListener("grant-notifications-changed",refresh)}},[]);
 const label=failed?"Notifications - count could not be refreshed":count===null?"Notifications - loading unread count":`Notifications, ${count} unread`;
 return <button className="notification-bell" aria-label={label} title={label} onClick={onOpen}><Bell size={22} aria-hidden="true"/>{!failed&&count!==null&&count>0&&<span className="notification-count" aria-hidden="true">{count>99?"99+":count}</span>}{failed&&<span className="notification-count unavailable" aria-hidden="true">!</span>}</button>
}
