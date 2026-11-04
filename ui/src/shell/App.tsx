import { NavLink, Route, Routes } from 'react-router-dom';
import type { SliceModule } from '../types';
import { JobsPage } from '../pages/JobsPage';
import { RunsPage } from '../pages/RunsPage';
import { RunDetailPage } from '../pages/RunDetailPage';
import { ParityPage } from '../pages/ParityPage';

const slices = Object.values(
  import.meta.glob<{ slice: SliceModule }>('../slices/*/index.tsx', { eager: true }),
).map((module) => module.slice);

export function App() {
  return (
    <div className="console">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">CD</span>
          <div><strong>CardDemo</strong><small>OPERATIONS</small></div>
        </div>
        <div className="nav-caption">WORKSPACE</div>
        <nav className="primary-nav" aria-label="Main navigation">
          <NavLink to="/jobs"><span className="nav-icon">▦</span> Jobs</NavLink>
          <NavLink to="/runs"><span className="nav-icon">◷</span> Runs</NavLink>
          <NavLink to="/parity"><span className="nav-icon">⌁</span> Parity report</NavLink>
          {slices.map((slice) => (
            <NavLink key={slice.id} to={slice.routes[0]?.path ?? `/slices/${slice.id}`}>
              <span className="nav-icon">◇</span>{slice.navLabel}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-footer">
          <span className="status-dot" /> Estate connected
          <small>JAVA FOUNDATION · 21</small>
        </div>
      </aside>
      <main className="main-area">
        <header className="topbar">
          <div><span className="eyebrow">CARD DEMO ESTATE</span><span className="top-separator">/</span><span>Batch operations</span></div>
          <div className="topbar-status"><span className="status-dot" /> Local environment</div>
        </header>
        <div className="page-content">
          <Routes>
            <Route path="/" element={<JobsPage />} />
            <Route path="/jobs" element={<JobsPage />} />
            <Route path="/runs" element={<RunsPage />} />
            <Route path="/runs/:runId" element={<RunDetailPage />} />
            <Route path="/parity" element={<ParityPage />} />
            {slices.flatMap((slice) => slice.routes.map((route) => (
              <Route key={`${slice.id}-${route.path}`} path={route.path} element={route.element} />
            )))}
            <Route path="*" element={<JobsPage />} />
          </Routes>
        </div>
      </main>
    </div>
  );
}
