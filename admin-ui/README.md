# admin-ui

The operations admin's front end: a separate deployable that talks to the Java service over
`/admin/api` and nothing else. The record of what it shows and why is in
[`docs/operations-admin.md`](../docs/operations-admin.md); this file is the stack and the
commands.

## Stack

| Layer | Choice | Why |
| --- | --- | --- |
| Build | Vite 6 | One dev server with a proxy, one `build` to static files; nothing else in the toolchain |
| UI | React 19, TypeScript 5 (strict) | The .NET sibling's choice, kept so the two admins stay comparable; no component library, the pages are tables and forms |
| Routing | react-router 7 | Client-side routes (`/tickets/:number`, `/conversations/:id`, ...), the nginx image falls back to `index.html` |
| Tests | vitest 3 | Node environment, no DOM: the API client, formatting, the Markdown subset, and a grep that no source uses a string-to-markup sink |
| Serving | nginx 1.27 (alpine), port 8084 | Static files plus a reverse proxy of `/admin/api` to the service, so the browser sees one origin |
| Image | `node:22-alpine` build stage, `nginx:1.27-alpine` runtime | `Dockerfile` here; CI builds it; `k8s/kind/verify.sh` loads it |

Not in the stack, on purpose: a UI kit, a state library, a CSS framework, a Markdown library.
Assistant text renders through `src/components/Markdown.tsx`, the demo page's subset (bold,
inline code, hyphen lists, no links) built as React elements, so there is no HTML sink to
audit; `src/no-markup-sinks.test.ts` fails the build if one appears.

## Why same-origin

The session is a cookie and the CSRF token is a readable cookie copied into a header. Both
work only when the page and the API share an origin, so the UI never calls the service
directly: in development Vite proxies `/admin/api` to `ADMIN_API_TARGET` (default
`http://localhost:8080`), in Compose and on Kubernetes nginx proxies it to
`ADMIN_API_UPSTREAM` (`app:8080`, `chat:8080`, or the Service). The service needs no CORS and
serves nothing under `/admin`. The trade, recorded in the operations-admin document: nothing
here exercises a cross-origin path, so moving the UI to its own hostname would raise the
whole CORS question at once.

## Commands

```bash
npm ci                       # exact versions from package-lock.json
npm run dev                  # http://localhost:5173, proxying /admin/api to ADMIN_API_TARGET
ADMIN_API_TARGET=http://localhost:18080 npm run dev   # against another instance
npm test                     # vitest, once
npm run typecheck            # tsc --noEmit
npm run build                # typecheck, then dist/
docker build -t admin-ui .   # the image CI builds; run with -e ADMIN_API_UPSTREAM=host:port
```

The service must be running for anything but the tests: `docker compose up -d postgres` and
the app from Maven or your IDE, with `ADMIN_SEED_USERNAME` / `ADMIN_SEED_PASSWORD` set once so
there is an account to sign in with.

## Layout

```
src/
├── api.ts            the whole API client: BASE, csrfToken, query, ApiError, api.*
├── auth.tsx          who is signed in (GET /admin/api/me), sign-out on 401
├── App.tsx           the routes and the navigation; /staff and /tenants are admin-only
├── format.ts         dates, durations, numbers
├── components/       Markdown.tsx (the subset), ui.tsx (ErrorNote, Pill, Pager, Empty, Notice), TenantPicker, OrderConnector, Telegram
├── pages/            Login, Overview, Tickets, TicketDetail, Conversations, Conversation,
│                     Feedback, Knowledge, Evaluation, Staff, Tenants, Account
└── *.test.ts         api, format, markdown, no-markup-sinks
```
