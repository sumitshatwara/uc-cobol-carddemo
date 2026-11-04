# Operations console

The React/TypeScript operations console lives in `src/`; it reads the shared
Java API through the Vite `/api` proxy and has no runtime CDN or font
dependencies.

To add a slice screen, create `src/slices/<slice>/index.tsx` and export
`slice: SliceModule` with an `id`, `title`, `navLabel`, and `routes` array.
`src/shell/App.tsx` discovers these modules through an eager glob and adds
their navigation links and routes automatically. Add slice browser tests in
`e2e/<slice>.spec.ts`; `make test-functional SLICE=<slice>` runs only that
spec. Keep shared shell changes in `src/shell` and pages in `src/pages`.

Use `make ui-install`, `make ui-dev`, and `make ui-build` for development.
Playwright uses the built Vite preview and the Spring Boot app; the functional
target expects the Java jar and `carddemo_java` database to be ready.
