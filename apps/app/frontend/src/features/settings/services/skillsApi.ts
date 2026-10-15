import { http } from '@/services/httpClient'

export interface SkillSummary {
  id: string
  name: string
  shortDescription: string
  userId: string | null
  shared: boolean
  official: boolean
  tags: string[]
  // Computed relative to the calling user at read time, not persisted.
  mine: boolean
  canEdit: boolean
  // A built-in product skill (Search, Jira, ...) is neither editable nor deletable: it belongs to
  // the product, and tool visibility is gated by skill NAME, so editing or removing one silently
  // takes its tools away. Tenant and user-created skills stay editable and deletable. Both flags
  // are enforced on the backend - see ChatSkillService.canEdit/canDelete.
  canDelete: boolean
  // Whether a seeded original exists to restore this skill to (see skillsApi.getDefault).
  hasDefault: boolean
}

export interface SkillDetail extends SkillSummary {
  detail: string
  tools: string[]
  // Registered connectors this skill prefers - fed to searchIndexedSources' own connectorNames
  // param once the agent reads this skill (see ChatSkillService.withConnectorPriorityInstruction
  // on the backend). A preference, not a hard restriction.
  connectorNames: string[]
}

export interface SkillRequest {
  name: string
  shortDescription: string
  detail: string
  tags: string[]
  tools: string[]
  connectorNames: string[]
  // Omitted (not just false) when the caller isn't allowed to change it - the backend keeps the
  // skill's existing value for a field it silently ignores rather than erroring, so a non-owner/
  // non-Super-Admin PATCH of the fields they ARE allowed to touch still succeeds.
  shared?: boolean
}

export interface ToolSummary {
  name: string
  description: string
}

// Deliberately NOT under /api/v1/settings/ - that prefix also hosts SettingsController's
// generic /{prefix}/{field} PATCH/DELETE route, which structurally overlaps any 2-segment
// path here (e.g. "chat/skills" == prefix "chat" + field "skills") and produced a 405 on
// skill save. Skills are a connector collection, not a settings scalar field, so they get
// their own top-level path instead, matching ChatController's /api/v1/chat/* convention.
export const skillsApi = {
  list: () => http.get<SkillSummary[]>('/api/v1/chat/skills').then(r => r.data),
  get: (id: string) => http.get<SkillDetail>(`/api/v1/chat/skills/${id}`).then(r => r.data),
  create: (request: SkillRequest) =>
    http.post<SkillDetail>('/api/v1/chat/skills', request).then(r => r.data),
  update: (id: string, request: SkillRequest) =>
    http.patch<SkillDetail>(`/api/v1/chat/skills/${id}`, request).then(r => r.data),
  remove: (id: string) => http.delete(`/api/v1/chat/skills/${id}`),
  // The seeded original of a built-in skill, for the editor's "Restore default". Read-only - it
  // fills the form and the user still presses Save, exactly like the system prompt's own reset.
  // Only call it when the skill's `hasDefault` is true; otherwise it 404s by design.
  getDefault: (id: string) =>
    http.get<{ name: string; shortDescription: string; detail: string }>(
      `/api/v1/chat/skills/${id}/default`).then(r => r.data),
  // Platform MCP tool catalog (name + description), for the skill modal's tool picker - a skill
  // that lists one of these in its own `tools` unlocks that tool's visibility once the agent
  // reads the skill (see ToolSkillGating). Visibility-only, no role gate on the backend.
  listTools: () => http.get<ToolSummary[]>('/api/v1/chat/tools').then(r => r.data),
}
