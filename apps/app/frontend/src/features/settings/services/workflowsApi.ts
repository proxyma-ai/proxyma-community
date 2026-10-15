import { http } from '@/services/httpClient'

// A workflow's shape is the skill's shape plus an ordered step list, because the frontmatter is
// literally the same set of fields parsed by the same code on the backend - see WorkflowService,
// which is a deliberate near-copy of ChatSkillService rather than a generalization of it.
//
// The distinction that matters is not in these types: a SKILL is instructions and the model
// decides what to do with them, so nothing can be drawn before it happens; a WORKFLOW is a program
// whose steps are fixed by its author, which is what lets the UI draw the whole plan BEFORE
// anything runs. `stepCount` is on the summary for exactly that - the "/" dropdown says how long a
// workflow is before you commit to running it.
export interface WorkflowSummary {
  id: string
  name: string
  shortDescription: string
  userId: string | null
  shared: boolean
  tags: string[]
  stepCount: number
  // Computed relative to the calling user at read time, not persisted.
  mine: boolean
  canEdit: boolean
}

export interface WorkflowStep {
  // 0-based and positional - the step's identity for a whole run, including its SSE events and its
  // table row. Derived from file order by the backend, never stored and never trusted from a
  // client: a workflow whose steps ran in an order the editor never showed would be a bad bug.
  index: number
  title: string
  instruction: string
}

export interface WorkflowDetail extends WorkflowSummary {
  // Everything above the first step - shared context stated once for the whole run.
  preamble: string
  steps: WorkflowStep[]
  tools: string[]
  connectorNames: string[]
  // The provider instance and model every step runs on. Null on both means "whatever the chat role
  // is pointed at", which is what a workflow with no model of its own has always done.
  //
  // A pair, not a bare model name: a provider is a base URL and a credential somebody configured,
  // so a model name only means something relative to one. A model set with no provider is the one
  // legal half-answer and means "the chat role's provider, this model" - see
  // WorkflowService.normalizeModelChoice, which refuses the other half.
  provider: string | null
  model: string | null
}

export interface WorkflowRequest {
  name: string
  shortDescription: string
  preamble: string
  steps: WorkflowStep[]
  tags: string[]
  tools: string[]
  connectorNames: string[]
  shared?: boolean
  provider?: string | null
  model?: string | null
}

// Mirrors WorkflowService.MAX_STEPS. The backend REJECTS a longer list rather than truncating it,
// unlike the tag/tool caps beside it, because dropping a step silently changes what the workflow
// DOES while still reporting success. The number is a spend ceiling, not a storage one: every step
// is another turn carrying all the previous ones.
export const MAX_STEPS = 20

// Same path convention and same reason as skillsApi - see its note on why this is not under
// /api/v1/settings/.
export const workflowsApi = {
  list: () => http.get<WorkflowSummary[]>('/api/v1/chat/workflows').then(r => r.data),
  get: (id: string) => http.get<WorkflowDetail>(`/api/v1/chat/workflows/${id}`).then(r => r.data),
  create: (request: WorkflowRequest) =>
    http.post<WorkflowDetail>('/api/v1/chat/workflows', request).then(r => r.data),
  update: (id: string, request: WorkflowRequest) =>
    http.patch<WorkflowDetail>(`/api/v1/chat/workflows/${id}`, request).then(r => r.data),
  remove: (id: string) => http.delete(`/api/v1/chat/workflows/${id}`),
}
