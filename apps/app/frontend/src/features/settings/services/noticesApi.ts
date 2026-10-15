import { http } from '@/services/httpClient'

/**
 * THIRD-PARTY-NOTICES.txt, as the jar serves it: a static file the build joins into the bundled
 * UI's folder (apps/app/third-party/notices.xml), so it is answered by the same handler as
 * index.html, with no endpoint and no sign-in.
 *
 * `null` means this build has none. A missing file is not a 404 here - the UI's fallback answers
 * every unknown path with index.html - so an HTML answer is what "missing" looks like, and that
 * is the case for `npm run dev` and for a jar built with -Dskip.frontend.build=true.
 */
export const noticesApi = {
  get: () =>
    http
      .get<string>('/THIRD-PARTY-NOTICES.txt', { responseType: 'text', transformResponse: r => r })
      .then(r => {
        const type = String(r.headers['content-type'] ?? '')
        const body = typeof r.data === 'string' ? r.data : ''
        if (type.includes('text/html') || /^\s*<!doctype html/i.test(body) || !body.trim()) return null
        return body
      }),
}
