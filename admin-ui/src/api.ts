// The one place the UI talks to the service. Same origin through nginx (or Vite's dev proxy),
// so the session is the cookie the service set and CSRF is the readable XSRF-TOKEN cookie
// copied into a header on every mutation. A 401 anywhere means the session is gone -- expired,
// signed out elsewhere -- and the page returns to sign-in rather than showing empty tables.
// Errors are the service's {"error": "..."} bodies; 409 means reload and look again, 422 means
// the rules refused it and reloading will not help.

export type Role = 'admin' | 'support';
/** tenant is null for platform staff, who run the deployment and see every tenant. */
export interface TenantRef { id: string; name: string }
export interface Me { username: string; role: Role; tenant: TenantRef | null }

export type TicketState = 'open' | 'claimed' | 'resolved' | 'closed';
export interface Ticket {
  ticketNumber: string; conversationId: string; category: string; summary: string; orderNumber: string | null;
  state: TicketState; owner: string | null; createdAt: string; updatedAt: string; version: number; tenantId: string;
}
export interface TicketEvent {
  id: number; ticketNumber: string; kind: string; actor: string; fromState?: string; toState?: string;
  fromOwner?: string; toOwner?: string; note?: string; occurredAt: string;
}
export interface TicketPage { tickets: Ticket[]; total: number; page: number; size: number }
export interface TicketDetail { ticket: Ticket; history: TicketEvent[] }
export interface SupportTicket { ticketNumber: string; category: string; summary: string; createdAt: string }
export interface TranscriptMessage { type: string; content: string; at: string }
export interface TicketConversation { conversationId: string; messages: TranscriptMessage[]; tickets: SupportTicket[]; notPersisted: string }

export interface ConversationSummary {
  conversationId: string; turns: number; firstAt: string; lastAt: string; lastOutcome: string;
  failed: number; interrupted: number; unknown: number; tenantId: string; externalId: string | null;
}
export interface ConversationPage { conversations: ConversationSummary[]; total: number; page: number; size: number }
export interface Retrieved { rank: number; entryId: string; language: string | null; score: number; corpusVersion: string | null }
export interface ToolCall { tool: string; outcome: string; at: string }
export interface Turn {
  turnId: string; conversationId: string; path: string; startedAt: string; endedAt: string | null; outcome: string;
  failure: string | null; model: string | null; inputTokens: number | null; outputTokens: number | null;
  traceId: string | null; question: string; answer: string | null; retrieval: Retrieved[]; toolCalls: ToolCall[];
}
export interface Feedback {
  id: number; turnId: string; conversationId: string; issue: string; note: string | null; state: 'open' | 'handled' | 'dismissed';
  conclusion: string | null; reportedBy: string; reportedAt: string; handledBy: string | null; handledAt: string | null;
  version: number; revisionId: number | null; tenantId: string;
}
export interface FeedbackPage { reports: Feedback[]; total: number; page: number; size: number }
export interface ConversationDetail { conversationId: string; tenantId: string; externalId: string | null; turns: Turn[]; tickets: SupportTicket[]; feedback: Feedback[]; notPersisted: string }

/** A golden case: a question and what a correct answer must satisfy (docs/evaluation.md). */
export interface GoldenCase {
  id: number; tenantId: string; question: string; language: string; expectedEntryIds: string[]; mustContain: string[];
  anyOf: string[]; mustNotContain: string[]; expectTool: string | null; expectRefusal: boolean; enabled: boolean;
  note: string | null; createdAt: string; createdBy: string;
}
export interface CaseInput {
  question: string; language: string; expectedEntryIds: string[]; mustContain: string[]; anyOf: string[]; mustNotContain: string[];
  expectTool: string | null; expectRefusal: boolean; enabled: boolean; note: string | null;
}
export interface EvaluationRun {
  id: number; tenantId: string; state: 'running' | 'done' | 'failed'; cases: number; passed: number; retrievalHits: number;
  answerPasses: number; toolPasses: number; inputTokens: number; outputTokens: number; model: string | null; note: string | null;
  error: string | null; requestedBy: string; startedAt: string; finishedAt: string | null;
}
export interface EvaluationResult {
  runId: number; caseId: number; question: string; conversationId: string; retrieved: string[]; tools: string[]; answer: string | null;
  retrievalHit: boolean; answerPass: boolean; toolPass: boolean; passed: boolean; failures: string | null;
  inputTokens: number | null; outputTokens: number | null; millis: number | null;
}
export interface RunDetail { run: EvaluationRun; results: EvaluationResult[] }
export interface Deflection { tenant: string; days: number; conversations: number; escalated: number; flagged: number; deflectionRate: number; definition: string }

export interface Stat { key: string; label: string; value: number | null; definition: string }
export interface Overview { from: string; to: string; turns: Stat[]; tickets: Stat[]; feedback: Stat[]; knowledge: Stat[]; staff: Stat[] }

export interface Tenant { id: string; name: string; enabled: boolean; createdAt: string }
export type KeyKind = 'secret' | 'widget';
/** A secret key is for a server the tenant controls; a widget key works only from browsers on its origins. */
export interface TenantKey { keyId: string; label: string; kind: KeyKind; origins: string[]; createdAt: string; revokedAt: string | null }
export interface TenantDetail { tenant: Tenant; keys: TenantKey[] }
/** A tenant's order system; the token comes back masked to its last four characters. */
export type ConnectorKind = 'shopify' | 'xboard';
export interface OrderConnector { tenantId: string; kind: ConnectorKind; shopDomain: string | null; baseUrl: string | null; accessToken: string | null; apiVersion: string | null; adminPath: string | null; configuredAt: string; configuredBy: string }
export interface ConnectorTest { ok: boolean; shopDomain: string; shopName: string | null; error: string | null }
/** The one response that carries a key: shown once, never readable back. */
export interface IssuedKey { keyId: string; key: string; label: string; kind: KeyKind; origins: string[] }
export interface StaffAccount { username: string; role: Role; enabled: boolean; createdAt: string; createdBy: string | null; tenantId: string | null }

export interface KnowledgeRevision {
  id: number; entryId: string; language: string; question: string; answer: string; state: 'draft' | 'published' | 'superseded';
  createdAt: string; createdBy: string; note: string | null;
}
/** sourceKind and source are set for entries an import wrote (url or pdf), null for typed ones and the bundled corpus. */
export interface KnowledgeEntry { entryId: string; category: string; retired: boolean; createdAt: string; createdBy: string; revisions: KnowledgeRevision[]; sourceKind: 'url' | 'pdf' | null; source: string | null }
/** sources: every distinct import source among the tenant's entries, whatever the page. */
export interface EntryPage { entries: KnowledgeEntry[]; total: number; page: number; size: number; sources: string[] }
/** One import of a tenant's document, polled like a publication: running, then done with the entries written, or failed with the reason. */
export interface KnowledgeImport { id: number; tenantId: string; sourceKind: 'url' | 'pdf'; source: string; state: 'running' | 'done' | 'failed'; entries: number | null; error: string | null; requestedBy: string; requestedAt: string; finishedAt: string | null }
export interface KnowledgeVersion {
  version: string; state: 'building' | 'ready' | 'active' | 'failed' | 'retired'; documentCount: number | null;
  createdAt: string; createdBy: string; activatedAt: string | null; note: string | null; error: string | null;
}
export interface Versions { tenant: string; active: string | null; versions: KnowledgeVersion[] }
export interface Passage { id: string; text: string; score: number | null; metadata: Record<string, unknown> }

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) { super(message); }
}

const BASE = '/admin/api';

export function csrfToken(cookie: string = typeof document === 'undefined' ? '' : document.cookie): string {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(cookie);
  return match ? decodeURIComponent(match[1]) : '';
}

let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn; }

export async function errorMessage(res: Response): Promise<string> {
  try { const body = await res.json() as { error?: string }; if (body && typeof body.error === 'string') return body.error; } catch { /* not JSON */ }
  return res.status === 403 ? 'Not allowed.' : `The request failed (${res.status}).`;
}

async function call<T>(method: string, path: string, body?: unknown, signOutOn401 = true): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken();
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin' });
  if (res.status === 204) return undefined as T;
  if (!res.ok) {
    const message = await errorMessage(res);
    if (res.status === 401 && signOutOn401) onUnauthorized?.();
    throw new ApiError(res.status, message);
  }
  return await res.json() as T;
}

/** A multipart upload; the CSRF header travels the same way as on every other mutation. */
async function upload<T>(path: string, form: FormData): Promise<T> {
  const res = await fetch(BASE + path, { method: 'POST', headers: { Accept: 'application/json', 'X-XSRF-TOKEN': csrfToken() }, body: form, credentials: 'same-origin' });
  if (!res.ok) {
    const message = await errorMessage(res);
    if (res.status === 401) onUnauthorized?.();
    throw new ApiError(res.status, message);
  }
  return await res.json() as T;
}

export function query(params: Record<string, string | number | undefined | null>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== '') p.set(k, String(v));
  const s = p.toString();
  return s ? `?${s}` : '';
}

export const api = {
  csrf: () => call<void>('GET', '/csrf', undefined, false),
  login: (username: string, password: string) => call<Me>('POST', '/login', { username, password }, false),
  logout: () => call<void>('POST', '/logout', {}),
  me: () => call<Me>('GET', '/me'),
  changeOwnPassword: (currentPassword: string, newPassword: string) => call<void>('POST', '/me/password', { currentPassword, newPassword }),

  overview: (f: { from?: string; to?: string; tenant?: string }) => call<Overview>('GET', '/overview' + query(f)),

  tickets: (f: { state?: string; owner?: string; page?: number; size?: number; tenant?: string }) => call<TicketPage>('GET', '/tickets' + query(f)),
  ticket: (n: string) => call<TicketDetail>('GET', `/tickets/${encodeURIComponent(n)}`),
  ticketConversation: (n: string) => call<TicketConversation>('GET', `/tickets/${encodeURIComponent(n)}/conversation`),
  ticketAction: (n: string, action: string, expectedVersion: number, extra: { assignee?: string; text?: string } = {}) =>
    call<Ticket>('POST', `/tickets/${encodeURIComponent(n)}/${action}`, { expectedVersion, ...extra }),

  conversations: (f: { conversationId?: string; outcome?: string; from?: string; to?: string; page?: number; size?: number; tenant?: string; kind?: string }) =>
    call<ConversationPage>('GET', '/conversations' + query(f)),
  conversation: (id: string) => call<ConversationDetail>('GET', `/conversations/${encodeURIComponent(id)}`),

  feedback: (f: { state?: string; page?: number; size?: number; tenant?: string }) => call<FeedbackPage>('GET', '/feedback' + query(f)),
  flag: (turnId: string, issue: string, note: string) => call<Feedback>('POST', '/feedback', { turnId, issue, note }),
  handleFeedback: (id: number, state: 'handled' | 'dismissed', conclusion: string, expectedVersion: number, revisionId?: number) =>
    call<Feedback>('POST', `/feedback/${id}/handle`, { state, conclusion, expectedVersion, revisionId: revisionId ?? null }),

  // Every knowledge call names the tenant whose knowledge it is about (ADR 002 step 3).
  /** One page of a tenant's entries, narrowed on the server: one document is hundreds of chunks. */
  entries: (tenant: string, f: { text?: string; source?: string; page?: number; size?: number } = {}) =>
    call<EntryPage>('GET', `/knowledge/entries${query({ tenant, ...f })}`),
  createEntry: (tenant: string, id: string, category: string) => call<KnowledgeEntry>('POST', `/knowledge/entries/${encodeURIComponent(id)}${query({ tenant })}`, { category }),
  saveDraft: (tenant: string, id: string, language: string, question: string, answer: string, note: string) =>
    call<KnowledgeRevision>('PUT', `/knowledge/entries/${encodeURIComponent(id)}/drafts/${encodeURIComponent(language)}${query({ tenant })}`, { question, answer, note }),
  discardDraft: (tenant: string, id: string, language: string) => call<void>('DELETE', `/knowledge/entries/${encodeURIComponent(id)}/drafts/${encodeURIComponent(language)}${query({ tenant })}`),
  retire: (tenant: string, id: string, retired: boolean) => call<KnowledgeEntry>('POST', `/knowledge/entries/${encodeURIComponent(id)}/retire${query({ tenant })}`, { retired }),
  versions: (tenant: string) => call<Versions>('GET', `/knowledge/versions${query({ tenant })}`),
  publish: (tenant: string, note: string, expectedActive: string | null) => call<{ started: boolean }>('POST', `/knowledge/publish${query({ tenant })}`, { note, expectedActive }),
  rollback: (tenant: string, version: string, expectedActive: string | null) => call<KnowledgeVersion>('POST', `/knowledge/rollback${query({ tenant })}`, { version, expectedActive }),
  imports: (tenant: string) => call<KnowledgeImport[]>('GET', `/knowledge/imports${query({ tenant })}`),
  importOf: (tenant: string, id: number) => call<KnowledgeImport>('GET', `/knowledge/imports/${id}${query({ tenant })}`),
  importUrl: (tenant: string, url: string) => call<KnowledgeImport>('POST', `/knowledge/imports/url${query({ tenant })}`, { url }),
  /** The panel's knowledge articles into drafts; needs the connector's admin token and admin path (422 otherwise). */
  importXboard: (tenant: string) => call<KnowledgeImport>('POST', `/knowledge/imports/xboard${query({ tenant })}`),
  importPdf: (tenant: string, file: File) => { const form = new FormData(); form.append('file', file, file.name); return upload<KnowledgeImport>(`/knowledge/imports/pdf${query({ tenant })}`, form); },
  preview: (tenant: string, text: string, version: string | null, topK = 5) => call<Passage[]>('POST', `/knowledge/preview${query({ tenant })}`, { text, version, topK }),

  evaluationCases: (tenant: string) => call<GoldenCase[]>('GET', `/evaluation/cases${query({ tenant })}`),
  createCase: (tenant: string, input: CaseInput) => call<GoldenCase>('POST', `/evaluation/cases${query({ tenant })}`, input),
  updateCase: (tenant: string, id: number, input: CaseInput) => call<GoldenCase>('PUT', `/evaluation/cases/${id}${query({ tenant })}`, input),
  deleteCase: (id: number) => call<void>('DELETE', `/evaluation/cases/${id}`),
  startRun: (tenant: string, note: string) => call<EvaluationRun>('POST', `/evaluation/runs${query({ tenant })}`, { note }),
  runs: (tenant: string) => call<EvaluationRun[]>('GET', `/evaluation/runs${query({ tenant })}`),
  run: (tenant: string, id: number) => call<RunDetail>('GET', `/evaluation/runs/${id}${query({ tenant })}`),
  deflection: (tenant: string, days: number) => call<Deflection>('GET', `/evaluation/deflection${query({ tenant, days })}`),

  staff: (tenant?: string) => call<StaffAccount[]>('GET', '/staff' + query({ tenant })),
  /** tenantId: a tenant's id, 'platform' for a platform admin, or undefined to let the server decide (own tenant, or default for support). */
  createStaff: (username: string, password: string, role: Role, tenantId?: string) => call<StaffAccount>('POST', '/staff', { username, password, role, tenantId }),
  setStaffEnabled: (username: string, enabled: boolean) => call<StaffAccount>('POST', `/staff/${encodeURIComponent(username)}/enabled`, { enabled }),
  setStaffRole: (username: string, role: Role) => call<StaffAccount>('POST', `/staff/${encodeURIComponent(username)}/role`, { role }),
  tenants: () => call<Tenant[]>('GET', '/tenants'),
  tenant: (id: string) => call<TenantDetail>('GET', `/tenants/${encodeURIComponent(id)}`),
  createTenant: (id: string, name: string) => call<Tenant>('POST', '/tenants', { id, name }),
  setTenantEnabled: (id: string, enabled: boolean) => call<Tenant>('POST', `/tenants/${encodeURIComponent(id)}/enabled`, { enabled }),
  orderConnector: (id: string) => call<OrderConnector | undefined>('GET', `/tenants/${encodeURIComponent(id)}/order-connector`),
  saveShopifyConnector: (id: string, shopDomain: string, accessToken: string, apiVersion?: string) =>
    call<OrderConnector>('PUT', `/tenants/${encodeURIComponent(id)}/order-connector`, { kind: 'shopify', shopDomain, accessToken, apiVersion: apiVersion || undefined }),
  /** An Xboard panel: the customer's own subscription, read with the customer's own token; the tenant's token is optional. */
  saveXboardConnector: (id: string, baseUrl: string, accessToken?: string, adminPath?: string) =>
    call<OrderConnector>('PUT', `/tenants/${encodeURIComponent(id)}/order-connector`, { kind: 'xboard', baseUrl, accessToken: accessToken || undefined, adminPath: adminPath || undefined }),
  deleteOrderConnector: (id: string) => call<void>('DELETE', `/tenants/${encodeURIComponent(id)}/order-connector`),
  testOrderConnector: (id: string) => call<ConnectorTest>('POST', `/tenants/${encodeURIComponent(id)}/order-connector/test`),
  issueTenantKey: (id: string, label: string, kind: KeyKind = 'secret', origins: string[] = []) =>
    call<IssuedKey>('POST', `/tenants/${encodeURIComponent(id)}/keys`, kind === 'widget' ? { label, kind, origins } : { label, kind }),
  revokeTenantKey: (id: string, keyId: string) => call<void>('POST', `/tenants/${encodeURIComponent(id)}/keys/${encodeURIComponent(keyId)}/revoke`),
  resetStaffPassword: (username: string, password: string) => call<void>('POST', `/staff/${encodeURIComponent(username)}/password`, { password }),
};
