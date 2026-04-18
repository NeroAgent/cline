## Cursor Cloud specific instructions

This is a TypeScript monorepo for **Cline**, a VS Code extension + CLI AI coding agent. The primary components are the VS Code extension (root `src/`), a React webview UI (`webview-ui/`), and a standalone CLI (`cli/`).

### Quick reference

- **Install deps:** `npm run install:all` (root + webview-ui)
- **Generate protobufs:** `npm run protos` (required before first build and after any `.proto` changes)
- **Dev watch mode:** `npm run dev` (runs protos + esbuild/tsc in watch mode)
- **Compile extension:** `npm run compile` (not `npm run build` — this is a VS Code extension)
- **Build webview:** `npm run build:webview`
- **Lint:** `npm run lint` (uses Biome, not ESLint)
- **Format:** `npm run format:fix`
- **Unit tests:** `npm run test:unit` (Mocha)
- **Webview tests:** `npm run test:webview` (Vitest in `webview-ui/`)
- **CLI tests:** `npm run cli:test` (Vitest in `cli/`)
- **Type checking:** `npm run check-types` (runs protos first, then checks root + webview-ui + cli)

### Non-obvious caveats

- On Ubuntu 24.04, the `libasound2` package is named `libasound2t64` and `libgtk-3-0` is `libgtk-3-0t64`. These are needed for VS Code integration tests on headless Linux.
- The `npm run protos` step is a prerequisite for almost everything — type-checking, compilation, and tests all depend on generated code in `src/generated/`. If you see missing module errors from `src/generated/` or `src/shared/proto/`, run `npm run protos` first.
- The pre-commit hook runs `lint-staged` which includes Biome formatting. Run `npm run format:fix` before committing to avoid hook failures.
- This project uses `npm` (not pnpm/yarn). The lockfile is `package-lock.json`.
- The `.nvmrc` specifies `lts/*` — Node 20+ is required (CLI enforces `>=20.0.0`).
- VS Code extension integration tests (`npm run test:integration`) require a display server; use `xvfb-run` on headless Linux.
- E2E tests (`npm run test:e2e`) require building a VSIX package first and installing Playwright browsers.
