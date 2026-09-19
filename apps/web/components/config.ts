import {BarChart3,BriefcaseBusiness,CircleDollarSign,FlaskConical,Gauge,LockKeyhole,Settings,ShieldCheck,ShoppingCart,Target,UserRound,UsersRound} from "lucide-react";
export type Group={name:string;icon:any;items:string[]};
export const groups:Group[]=[
{name:"Executive",icon:Gauge,items:["Executive Overview","Portfolio Analytics","Pipeline Analytics"]},
{name:"Pre-Award",icon:Target,items:["Opportunity Intelligence","Eligibility & Fit","Applications","Proposal Workspace","Budget Builder","Internal Review","Approvals","Submissions"]},
{name:"Award Management",icon:BriefcaseBusiness,items:["Award Register","Award Setup","Contracts & Agreements","Partners & Subawards","Due Diligence","Amendments","Deliverables","Financial Monitoring","Risks & Issues"]},
{name:"Finance & Accounts",icon:CircleDollarSign,items:["Finance Dashboard","Award Budgets","Funds Received","Expenditure","Commitments","Budget vs Actual","Partner Advances","Financial Reporting","Financial Forecasts","Reconciliations","Financial Approvals","Financial Closeout"]},
{name:"Procurement",icon:ShoppingCart,items:["Procurement Dashboard","Procurement Plans","Requisitions","Purchase Orders","Suppliers & Due Diligence","Procurement Contracts","Equipment & Assets","Procurement Tracking"]},
{name:"Laboratory",icon:FlaskConical,items:["Laboratory Oversight","Laboratory Procurement","Reagents & Consumables","Laboratory Equipment","Maintenance & Calibration","Laboratory Compliance"]},
{name:"Research Governance",icon:ShieldCheck,items:["Ethics & Regulatory Links","Conflicts / Declarations","Compliance Calendar","Privacy & Information Governance","Legal & Contracts Review"]},
{name:"Performance",icon:BarChart3,items:["Reports","Outputs","Impact","Closeout"]},
{name:"People & Organisation",icon:UsersRound,items:["Researcher Profiles","Partner Directory","Funder Directory","NHRC Institutional Profile"]},
{name:"Personal",icon:UserRound,items:["My Work","Notifications","Calendar"]},
{name:"Administration",icon:Settings,items:["Users & Roles","Organisation Structure","Approval Rules","Delegations & Acting Roles","Workflow Configuration","Forms & Fields","Reference Data","Templates","Notification Rules","Calendar Rules","Data Import & Export","Records & Archives","Access Reviews","Help & Support","Audit Log","System Settings"]},
{name:"IT & Security",icon:LockKeyhole,items:["System Health","Service Monitoring","Integration Health","Job Monitor","Backup & Recovery","Security Centre","Security Events","Privileged Activity","Business Continuity","Environment Management","Superadmin Console"]}];

const R={
  research:["RESEARCHER","RESEARCH_FELLOW"],
  grants:["GRANTS_OFFICER","POST_AWARD_OFFICER"],
  finance:["FINANCE_OFFICER","FINANCE_APPROVER"],
  procurement:["PROCUREMENT_OFFICER"],
  lab:["LABORATORY_OFFICER"],
  governance:["GOVERNANCE_OFFICER"],
  executive:["DIRECTOR","AUDITOR"],
  admin:["ADMIN"],
  technical:["IT_ADMIN","SUPERADMIN"]
};
const union=(...sets:string[][])=>Array.from(new Set(sets.flat()));
const itemRoles:Record<string,string[]>={
  "Executive Overview":union(R.executive,R.grants,R.admin),
  "Portfolio Analytics":union(R.executive,R.grants,R.admin),
  "Pipeline Analytics":union(R.executive,R.grants,R.admin),

  "Opportunity Intelligence":union(R.grants,R.research,R.executive,R.admin),
  "Eligibility & Fit":union(R.grants,R.research,R.executive),
  "Applications":union(R.grants,R.research,R.executive),
  "Proposal Workspace":union(R.grants,R.research,R.executive),
  "Budget Builder":union(R.grants,R.research,R.finance,R.executive),
  "Internal Review":union(R.grants,R.research,R.finance,R.governance,R.executive),
  "Approvals":union(R.grants,R.executive),
  "Submissions":union(R.grants,R.executive),

  "Award Register":union(R.grants,R.research,R.finance,R.procurement,R.lab,R.governance,R.executive),
  "Award Setup":union(R.grants,R.research,R.executive),
  "Contracts & Agreements":union(R.grants,R.governance,R.executive),
  "Partners & Subawards":union(R.grants,R.research,R.finance,R.executive),
  "Due Diligence":union(R.grants,R.procurement,R.governance,R.executive),
  "Amendments":union(R.grants,R.research,R.finance,R.executive),
  "Deliverables":union(R.grants,R.research,R.executive),
  "Financial Monitoring":union(R.grants,R.research,R.finance,R.executive),
  "Risks & Issues":union(R.grants,R.research,R.governance,R.executive),

  "Finance Dashboard":union(R.finance,R.executive),
  "Award Budgets":union(R.finance,R.grants,R.executive),
  "Funds Received":union(R.finance,R.executive),
  "Expenditure":union(R.finance,R.executive),
  "Commitments":union(R.finance,R.grants,R.executive),
  "Budget vs Actual":union(R.finance,R.grants,R.research,R.executive),
  "Partner Advances":union(R.finance,R.grants,R.executive),
  "Financial Reporting":union(R.finance,R.grants,R.executive),
  "Financial Forecasts":union(R.finance,R.executive),
  "Reconciliations":union(R.finance,R.executive),
  "Financial Approvals":union(R.finance,R.executive),
  "Financial Closeout":union(R.finance,R.executive),

  "Procurement Dashboard":union(R.procurement,R.executive),
  "Procurement Plans":union(R.procurement,R.grants,R.research,R.executive),
  "Requisitions":union(R.procurement,R.grants,R.research,R.executive),
  "Purchase Orders":union(R.procurement,R.finance,R.executive),
  "Suppliers & Due Diligence":union(R.procurement,R.governance,R.executive),
  "Procurement Contracts":union(R.procurement,R.governance,R.executive),
  "Equipment & Assets":union(R.procurement,R.lab,R.executive),
  "Procurement Tracking":union(R.procurement,R.grants,R.research,R.executive),

  "Laboratory Oversight":union(R.lab,R.executive),
  "Laboratory Procurement":union(R.lab,R.procurement,R.executive),
  "Reagents & Consumables":union(R.lab,R.executive),
  "Laboratory Equipment":union(R.lab,R.procurement,R.executive),
  "Maintenance & Calibration":union(R.lab,R.executive),
  "Laboratory Compliance":union(R.lab,R.governance,R.executive),

  "Ethics & Regulatory Links":union(R.governance,R.grants,R.research,R.executive),
  "Conflicts / Declarations":union(R.governance,R.executive),
  "Compliance Calendar":union(R.governance,R.grants,R.research,R.executive),
  "Privacy & Information Governance":union(R.governance,R.executive),
  "Legal & Contracts Review":union(R.governance,R.executive),

  "Reports":union(R.grants,R.research,R.finance,R.executive),
  "Outputs":union(R.grants,R.research,R.executive),
  "Impact":union(R.grants,R.research,R.executive),
  "Closeout":union(R.grants,R.research,R.finance,R.procurement,R.governance,R.executive),

  "Researcher Profiles":union(R.research,R.grants,R.admin,R.executive),
  "Partner Directory":union(R.grants,R.admin,R.governance,R.executive),
  "Funder Directory":union(R.grants,R.admin,R.executive),
  "NHRC Institutional Profile":union(R.grants,R.admin,R.executive),

  "My Work":union(R.research,R.grants,R.finance,R.procurement,R.lab,R.governance,R.executive,R.admin,R.technical),
  "Notifications":union(R.research,R.grants,R.finance,R.procurement,R.lab,R.governance,R.executive,R.admin,R.technical),
  "Calendar":union(R.research,R.grants,R.finance,R.procurement,R.lab,R.governance,R.executive,R.admin,R.technical),

  "Users & Roles":R.admin,
  "Organisation Structure":R.admin,
  "Approval Rules":union(R.admin,R.executive),
  "Delegations & Acting Roles":union(R.admin,R.executive),
  "Workflow Configuration":R.admin,
  "Forms & Fields":R.admin,
  "Reference Data":R.admin,
  "Templates":R.admin,
  "Notification Rules":R.admin,
  "Calendar Rules":R.admin,
  "Data Import & Export":R.admin,
  "Records & Archives":R.admin,
  "Access Reviews":R.admin,
  "Help & Support":union(R.admin,R.technical),
  "Audit Log":union(R.admin,R.executive,["AUDITOR"]),
  "System Settings":R.admin,

  "System Health":R.technical,
  "Service Monitoring":R.technical,
  "Integration Health":union(R.technical,R.grants,R.admin),
  "Job Monitor":R.technical,
  "Backup & Recovery":R.technical,
  "Security Centre":R.technical,
  "Security Events":R.technical,
  "Privileged Activity":R.technical,
  "Business Continuity":R.technical,
  "Environment Management":R.technical,
  "Superadmin Console":["SUPERADMIN"]
};

export function groupsForRoles(roles:string[]):Group[]{
  if(roles.includes("SUPERADMIN"))return groups;
  const owned=new Set(roles);
  return groups.map(group=>({...group,items:group.items.filter(item=>(itemRoles[item]||[]).some(role=>owned.has(role)))})).filter(group=>group.items.length>0);
}

export const api=process.env.NEXT_PUBLIC_API_URL||"http://localhost:8080";
export const endpoints:Record<string,string>={"Opportunity Intelligence":"/api/opportunities","Eligibility & Fit":"/api/opportunities","Applications":"/api/applications","Proposal Workspace":"/api/applications","Budget Builder":"/api/applications","Internal Review":"/api/applications","Submissions":"/api/applications","Award Register":"/api/awards","Award Setup":"/api/awards","Financial Monitoring":"/api/finance/summary","Finance Dashboard":"/api/finance/summary","Award Budgets":"/api/awards","Funds Received":"/api/finance/receipts","Budget vs Actual":"/api/awards","Financial Forecasts":"/api/finance/summary","Procurement Dashboard":"/api/procurement/requisitions","Requisitions":"/api/procurement/requisitions","Procurement Tracking":"/api/procurement/requisitions","Laboratory Oversight":"/api/laboratory/items","Laboratory Procurement":"/api/laboratory/items","Reagents & Consumables":"/api/laboratory/items?type=REAGENT","Laboratory Equipment":"/api/laboratory/items?type=EQUIPMENT","Maintenance & Calibration":"/api/laboratory/items?type=EQUIPMENT","Users & Roles":"/api/admin/users","Audit Log":"/api/admin/audit","Security Events":"/api/operations/system-events","Privileged Activity":"/api/admin/audit","Backup & Recovery":"/api/operations/backups"};
