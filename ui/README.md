# CardDemo operations console

React 18 + Vite + TypeScript.  Replaces operator JCL submission and SDSF SYSOUT browsing:
list batch jobs, run one against a fixture case on the Java platform, view return codes,
SYSOUT and output datasets next to the legacy golden files, and view parity reports.

```sh
npm ci
npm run dev          # http://localhost:5173, proxies /api to the service on :8080
npm run build        # dist/ is served by the Spring Boot service itself (make serve)
npx playwright test  # functional layer; starts the service if it is not running
```

Slices add screens without editing the shell: create `src/slices/<slice>/index.tsx` with a
default export of type `SliceModule` (`src/slices/types.ts`: nav entries, routes and
per-chain run panels) and journeys in `e2e/<slice>.spec.ts`.
