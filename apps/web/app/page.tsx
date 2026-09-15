import { BarChart3, BriefcaseBusiness, Building2, FlaskConical, Landmark, Microscope, Network, ShieldCheck, UserRoundCog, WalletCards } from "lucide-react";

const areas = [
  { title: "Executive", icon: BarChart3, description: "Portfolio oversight, pipeline intelligence, decisions and institutional performance." },
  { title: "Pre-Award", icon: BriefcaseBusiness, description: "Opportunities, eligibility, applications, proposal development, reviews and submissions." },
  { title: "Award Management", icon: Landmark, description: "Award setup, contracts, partners, amendments, deliverables, risks and closeout readiness." },
  { title: "Finance & Accounts", icon: WalletCards, description: "Budgets, receipts, expenditure, commitments, forecasts, reconciliations and reporting." },
  { title: "Procurement", icon: Building2, description: "Procurement plans, requisitions, suppliers, purchase orders, contracts and assets." },
  { title: "Laboratory", icon: Microscope, description: "Grant-linked laboratory procurement, equipment, maintenance and compliance oversight." },
  { title: "Research Governance", icon: ShieldCheck, description: "Ethics, regulatory links, declarations, due diligence and compliance calendars." },
  { title: "People & Organisation", icon: Network, description: "Researchers, partners, funders, organisation structure and institutional profile." },
  { title: "Administration", icon: UserRoundCog, description: "Roles, approval rules, workflows, templates, reference data and service configuration." },
  { title: "IT & Security", icon: FlaskConical, description: "System health, integrations, backups, security events, environments and continuity." }
];

export default function HomePage() {
  return (
    <main className="shell">
      <section className="hero">
        <div className="eyebrow">NAVRONGO HEALTH RESEARCH CENTRE</div>
        <h1>NHRC Grants</h1>
        <p className="heroCopy">Institutional research funding lifecycle, from opportunity discovery to award delivery, closeout and impact.</p>
        <div className="heroActions">
          <button>Open My Work</button>
          <button className="secondary">Executive Overview</button>
        </div>
      </section>

      <section className="sectionHeading">
        <div>
          <span className="kicker">Platform areas</span>
          <h2>One institutional record across the grant lifecycle</h2>
        </div>
        <div className="searchBox">Search grants, awards, funders, people or tasks</div>
      </section>

      <section className="grid">
        {areas.map(({ title, icon: Icon, description }) => (
          <article className="card" key={title}>
            <div className="iconWrap"><Icon size={22} /></div>
            <h3>{title}</h3>
            <p>{description}</p>
            <span className="openLink">Open workspace →</span>
          </article>
        ))}
      </section>
    </main>
  );
}
