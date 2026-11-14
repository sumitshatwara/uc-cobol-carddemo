import { HashRouter, NavLink, Route, Routes } from 'react-router-dom';
import { JobPage } from './pages/JobPage';
import { JobsPage } from './pages/JobsPage';
import { ParityPage } from './pages/ParityPage';
import { RunPage } from './pages/RunPage';
import { RunsPage } from './pages/RunsPage';
import { slices } from './slices/registry';

/** Console shell: navigation + core screens; slice screens are added through ui/src/slices/<id>/. */
export function App() {
  return (
    <HashRouter>
      <div className="shell">
        <aside>
          <div className="brand"><span className="logo">CD</span><div><b>CardDemo</b><div className="small muted">Operations console</div></div></div>
          <nav>
            <NavLink to="/" end data-testid="nav-jobs">Batch jobs</NavLink>
            <NavLink to="/runs" data-testid="nav-runs">Run history</NavLink>
            <NavLink to="/parity" data-testid="nav-parity">Parity reports</NavLink>
            {slices.flatMap((s) => s.nav ?? []).map((n) => (
              <NavLink key={n.path} to={n.path} data-testid={n.testId}>{n.label}</NavLink>
            ))}
          </nav>
          <div className="foot small muted">Java 21 · Spring Batch · PostgreSQL 15</div>
        </aside>
        <main>
          <Routes>
            <Route path="/" element={<JobsPage />} />
            <Route path="/jobs/:chain" element={<JobPage />} />
            <Route path="/runs" element={<RunsPage />} />
            <Route path="/runs/:id" element={<RunPage />} />
            <Route path="/parity" element={<ParityPage />} />
            {slices.flatMap((s) => s.routes ?? []).map((r) => <Route key={r.path} path={r.path} element={r.element} />)}
          </Routes>
        </main>
      </div>
    </HashRouter>
  );
}
