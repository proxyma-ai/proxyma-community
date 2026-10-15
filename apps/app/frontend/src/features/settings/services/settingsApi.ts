import { http } from '@/services/httpClient'
import type { SettingsGroup } from '../types/settings.types'

export const settingsApi = {
  getGroup: (prefix: string) =>
    http.get<SettingsGroup>(`/api/v1/settings/${prefix}`).then(r => r.data),
  saveField: (prefix: string, field: string, value: string) =>
    http.patch<{ requiresRestart: boolean }>(`/api/v1/settings/${prefix}/${field}`, value, {
      headers: { 'Content-Type': 'text/plain' },
    }).then(r => r.data),
  deleteField: (prefix: string, field: string) =>
    http.delete<{ requiresRestart: boolean }>(`/api/v1/settings/${prefix}/${field}`).then(r => r.data),
  // The one chat system prompt (no smart/light variant) - the built-in default or an admin's saved
  // custom prompt, backed by a single markdown file on disk (see SystemPromptService), not a
  // settings.properties field, so it's read/written through its own endpoints rather than the
  // generic /settings/{prefix}/{field} mechanism. GET returns the effective prompt (never blank);
  // saving blank reverts to the built-in default. SUPER_ADMIN only.
  getChatSystemPrompt: () =>
    http.get<string>('/api/v1/settings/chat/system-prompt').then(r => r.data),
  // What a conversation is started with - the prompt above with the deployment's name filled in
  // and the section on delegating. For reading, never for editing.
  getChatSystemPromptAssembled: () =>
    http.get<string>('/api/v1/settings/chat/system-prompt/assembled').then(r => r.data),
  // The built-in default itself, for the editor's "Reset to default" button.
  getChatSystemPromptDefault: () =>
    http.get<string>('/api/v1/settings/chat/system-prompt/default').then(r => r.data),
  saveChatSystemPrompt: (content: string) =>
    http.put<void>('/api/v1/settings/chat/system-prompt', content, {
      headers: { 'Content-Type': 'text/plain' },
    }).then(() => undefined),
}
