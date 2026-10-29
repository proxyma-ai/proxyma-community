import { useState, useRef, useEffect, useCallback } from 'react'
import { useDraftState } from '@/store/composerDraftStore'
import { useParams, useNavigate, useLocation } from 'react-router-dom'
import { usePageTitle } from '@/hooks/usePageTitle'
import { ChatWindow } from '../components/ChatWindow'
import { ChatHistoryModal } from '../components/ChatHistoryModal'
import { ChatComposer } from '../components/ChatComposer'
import { ChatStartLinks } from '../components/ChatStartLinks'
import { AgentStrip } from '../components/AgentStrip'
import { ToastDock } from '@/components/ui/ToastDock'
import { QuotaMilestoneFeedbackModal } from '@/features/feedback/components/QuotaMilestoneFeedbackModal'
import { useChat } from '../hooks/useChat'
import { useAttachmentUploader } from '../hooks/useAttachmentUploader'
import { useSpaceFact } from '../hooks/useSpaceFact'
import { useSpaceFactPreference } from '../hooks/useSpaceFactPreference'
import { shownSpaceFact } from '../utils/spaceFactShown'
import { useQuotaMilestoneFeedback } from '../hooks/useQuotaMilestoneFeedback'
import type { SpaceFact } from '../services/spaceFactApi'
import type { ChatAttachment } from '../types/chat.types'
import { useTourStore } from '@/store/tourStore'
import { Caption, ChatStartHero, DropOverlay, ExternalLink, FadeSwap, PROSE_CHAT, THREAD_COLUMN, cn, useFileDrop } from '@/components/ui/kit'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { FEATURES } from '@/config/features'
import { useTranslation } from '@/i18n'

// The line under the hero's logo: one astronomy fact, with its own subject linked inside the
// sentence.
//
// It replaced a personalized greeting with clickable topic suggestions woven into it. The reason
// is not that the greeting was wrong - it is that the hero is the first thing a new visitor sees
// and the suggestions were a second thing to read before the input, on the one screen whose only
// job is to get a question typed. A fact is read in a glance and asks for nothing.
//
// THE SUBJECT IS LINKED IN PLACE when the sentence names it - the phrase the source is about. A
// model-written sentence often names its subject obliquely ("the densest known objects" for a fact
// sourced from Neutron star), and then the citation is a line of its own UNDER the paragraph, in
// the paragraph's alignment and without brackets (maintainer's feedback, 2026-10-07). It was
// right-aligned once, which anchored it to a corner of the hero, and then parenthesised at the end
// of the sentence, where it ran into the fact's last words.
//
// Renders nothing at all until the fact arrives (see useSpaceFact - deliberately not part of the
// first paint), so nothing here reserves space or shows a placeholder; the kit's ChatStartHero
// eases it in. A deployment with no fact of its own shows the Proxima Centauri default
// (shownSpaceFact, since 2026-10-15) - the "no fallback fact" of SpaceFactService is the SERVER's.
//
// A real anchor, not a router link: every allowed source is an external site (see
// SpaceFactService's host allowlist), which is also why it carries rel="noopener noreferrer".
function SpaceFactLine({ fact: source }: { fact: SpaceFact }) {
  // In the reader's language when the server has it (maintainer's feedback, 2026-10-01): an English
  // sentence under a Vietnamese heading read as a leftover. Only the sentence changes; the link is
  // the same article, and the subject is linked inside the sentence when its words match.
  const { locale, t } = useTranslation()
  const translated = source.translations?.[locale]
  const fact = translated ? { ...source, text: translated } : source
  const linked = linkifySubject(fact)
  // The link in running text every answer uses (PROSE_CHAT.link): brand ink at rest, underlined
  // under a mouse only. It was a hand-made class in a second link ink.
  const linkClass = PROSE_CHAT.link
  // Bare paragraphs: ChatStartHero's fact box supplies the measure, the size and the ink. Left-aligned
  // on a phone, as the rule for anything carrying a link to tap; centered from `sm` with the hero.
  if (linked) {
    return (
      <p className="text-left sm:text-center">
        {linked.before}
        <ExternalLink href={fact.sourceUrl} className={linkClass}>
          {linked.phrase}
        </ExternalLink>
        {linked.after}
      </p>
    )
  }
  return (
    <>
      <p className="text-left sm:text-center">{fact.text}</p>
      {fact.sourceUrl && (
        <p className="mt-2 text-left sm:text-center animate-fade-in">
          <ExternalLink href={fact.sourceUrl} className={linkClass}>
            {fact.sourceTitle || t('Source')}
          </ExternalLink>
        </p>
      )}
    </>
  )
}

// Splits the fact around the first mention of what the source is about.
//
// `sourceTitle` is publisher-prefixed - "Wikipedia: Neutron star" - so the subject is what follows
// the first colon. Matching is case-insensitive but the sentence's OWN casing is what renders, so
// "Neutron star matter is so dense..." keeps its capital N rather than being replaced by the
// title's.
//
// The URL's own slug is tried as well as the title, because a citation label is often an
// abbreviation of what the sentence actually says: a fact titled "Wikipedia: ISS" whose sentence
// says "International Space Station" matches on the slug (/wiki/International_Space_Station) and
// never on the title.
//
// Returns null when nothing matches, which is a real case rather than a theoretical one: the
// sentence is model-written and may refer to its subject obliquely - "the densest known objects"
// for a fact sourced from Neutron star. The caller appends a parenthesised inline citation for
// exactly that.
function linkifySubject(fact: SpaceFact): { before: string; phrase: string; after: string } | null {
  if (!fact.sourceUrl || !fact.text) return null

  const title = (fact.sourceTitle || '').trim()
  // A COLON OR A SPACED DASH, because the generator asks for the dash. This split used to be
  // colon-only, which quietly matched nothing on real data: SpaceFactService's prompt gives
  // "NASA - Venus" as its example format, so a generated title has no colon in it, `subject`
  // stayed the whole "Wikipedia - Venus" string, and every match fell through to the URL slug.
  // That is why the citation kept appearing as a separate line - the seeds were written with
  // colons and always linked, while the generated facts this actually ships often did not.
  // The dash must be SPACED: article titles carry bare hyphens of their own ("X-ray binary"),
  // and splitting on those would cut the subject in half. Plain ASCII hyphen only, matching what
  // the prompt asks for - a model that answers with an en dash instead simply falls through to
  // the slug match, and then to the inline citation, which is what those fallbacks are for.
  const separator = /:|\s-\s/.exec(title)
  const subject = (separator ? title.slice(separator.index + separator[0].length) : title).trim()
  const slug = decodeSlug(fact.sourceUrl)

  const candidates: string[] = []
  for (const base of [subject, slug]) {
    if (!base) continue
    // The plural is tried right after its singular rather than in a second pass, so a sentence
    // saying "Neutron stars" links on the subject rather than falling through to the slug.
    for (const candidate of [base, `${base}s`]) {
      if (candidate && !candidates.includes(candidate)) candidates.push(candidate)
    }
  }

  for (const candidate of candidates) {
    // Escaped because a subject is upstream text that can carry regex punctuation ("Messier 87*",
    // "M31 (galaxy)"), and the word boundaries are conditional: \b after "87*" would demand a word
    // character next to an asterisk and never match anything at all.
    const escaped = candidate.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const open = /^\w/.test(candidate) ? '\\b' : ''
    const close = /\w$/.test(candidate) ? '\\b' : ''
    const match = new RegExp(`${open}${escaped}${close}`, 'i').exec(fact.text)
    if (match) {
      return {
        before: fact.text.slice(0, match.index),
        phrase: match[0],
        after: fact.text.slice(match.index + match[0].length),
      }
    }
  }
  return null
}

// "https://en.wikipedia.org/wiki/International_Space_Station" -> "International Space Station".
// Anything that is not a readable phrase - a numeric id, a query string, a bare host - yields ''
// and is simply not tried.
function decodeSlug(url: string): string {
  try {
    const last = new URL(url).pathname.split('/').filter(Boolean).pop()
    if (!last) return ''
    const decoded = decodeURIComponent(last).replace(/_/g, ' ').replace(/\.[a-z]{2,4}$/i, '').trim()
    return /[A-Za-z]/.test(decoded) ? decoded : ''
  } catch {
    return ''
  }
}

type View = 'hero' | 'conversation'

// Stable empties for the draft store: a fresh [] each render would read as a change to every
// effect that depends on it.
const NO_NAMES: string[] = []
const NO_ATTACHMENTS: ChatAttachment[] = []

export function ChatPage() {
  usePageTitle('Chat')
  const { t } = useTranslation()
  const spaceFactQuery = useSpaceFact()
  const { user } = useAuth()
  const greetingName = FEATURES.auth && user?.displayName ? user.displayName : null
  const spaceFactOn = useSpaceFactPreference(s => s.enabled)
  // The default sentence is one literal on one line, for the catalog checker; its source link is
  // shownSpaceFact's. 4.25 is Wikipedia's figure (Gaia DR3, 4.2465) and NASA's
  // (imagine.gsfc.nasa.gov), and the Latin is Wikipedia's "nearest [star] of Centaurus".
  const spaceFact = shownSpaceFact(
    { enabled: spaceFactOn, status: spaceFactQuery.status, data: spaceFactQuery.data },
    t('Proxima Centauri, a small red dwarf about 4.25 light-years away, is the nearest star to the Sun. Its Latin name means the nearest star of Centaurus.'),
  )
  // Server-mode-only (useUserQuota, which this wraps, is disabled outright in desktop mode -
  // see FEATURES.auth) - checked here rather than only inside the hook so the render gate below
  // stays self-evident.
  const quotaMilestone = useQuotaMilestoneFeedback()
  const [historyOpen, setHistoryOpen] = useState(false)
  const {
    messages, streaming, toolCalls, loadingHistory, send, retry, canRetry, stop, rewind, condense,
    conversationId, resumeConversation, resetChat, pendingApprovals, pendingEnableRequests,
    condensing, condenseTokensFreed, attachedToolCallCount, workflowRuns,
    ensureConversationId,
  } = useChat()
  // The composer's drafts live OUTSIDE this page (composerDraftStore), so leaving for Sources and
  // coming back finds them as they were - and a conversation's draft is its own, keyed by its id.
  const convKey = `conv:${conversationId ?? 'new'}`
  const [inputValue, setInputValue] = useDraftState('hero', 'text', '')
  const [convInput, setConvInput] = useDraftState(convKey, 'text', '')
  const [heroMentions, setHeroMentions] = useDraftState<string[]>('hero', 'mentions', NO_NAMES)
  const [convMentions, setConvMentions] = useDraftState<string[]>(convKey, 'mentions', NO_NAMES)
  const [heroAttachments, setHeroAttachments] = useDraftState<ChatAttachment[]>('hero', 'attachments', NO_ATTACHMENTS)
  const [convAttachments, setConvAttachments] = useDraftState<ChatAttachment[]>(convKey, 'attachments', NO_ATTACHMENTS)
  // Skills the user explicitly picked via the Attach menu's Skills submenu for the message being
  // composed - exactly like heroMentions/convMentions for documents, sent with the message and
  // reset afterward (see handleSend). Each one loads that skill's full instructions into the
  // conversation as a message, in effect for the rest of it (see ChatService.applyPickedSkills on
  // the backend).
  const [heroSkillMentions, setHeroSkillMentions] = useDraftState<string[]>('hero', 'skills', NO_NAMES)
  const [convSkillMentions, setConvSkillMentions] = useDraftState<string[]>(convKey, 'skills', NO_NAMES)
  // One uploader per view (see useAttachmentUploader's own docs for why it must own the
  // AbortController bookkeeping rather than being recreated per call site) - only one is ever
  // actually in use at a time, matching whichever `view` is currently rendered.
  const heroUploader = useAttachmentUploader(heroAttachments, setHeroAttachments, ensureConversationId)
  const convUploader = useAttachmentUploader(convAttachments, setConvAttachments, ensureConversationId)
  const params = useParams<{ conversationId?: string }>()
  const navigate = useNavigate()
  const location = useLocation()

  // AppShell remounts this page on every pathname change (see its key={location.pathname}), but
  // the shared zustand chat store persists across that remount - so navigating away (e.g. to
  // Settings) and back to Chat via "/" inherits whatever conversation was already loaded, and the
  // URL-sync effect below pushes the URL to match it (/chat/{id}) instead of landing on a fresh
  // hero screen. This is intentional: returning to Chat should continue where you left off, the
  // same way switching tabs and back would. Starting over is still one click away via the
  // "New conversation" button (see handleNew) - it isn't gated on navigation at all.
  const [view, setView] = useState<View>(() =>
    messages.length > 0 ? 'conversation' : 'hero')

  const hasMessages = messages.length > 0

  // The onboarding walkthrough (TourOverlay.tsx) force-navigates to '/' for its first two steps
  // regardless of any conversation in progress, since its hero-input target (data-tour-id
  // "tour-chat-input") only exists on the pristine hero screen, not the conversation thread. Both
  // effects below defer to the tour while it's active - see each one's comment for what it would
  // otherwise fight.
  const tourActive = useTourStore(s => s.active)

  useEffect(() => {
    if (tourActive) return
    if (hasMessages && view === 'hero') setView('conversation')
  }, [hasMessages, view, tourActive])

  // Forces the hero view back open for the tour's chat-welcome/chat-input steps even though a
  // conversation is already loaded (view would otherwise still read 'conversation' from this
  // page's last mount). Guarding the effect above with tourActive is what stops it immediately
  // flipping this back to 'conversation' the instant hasMessages is true.
  useEffect(() => {
    if (tourActive && location.pathname === '/') setView('hero')
  }, [tourActive, location.pathname])

  // Keeps the URL in sync with the live conversation id (first hero send, history selection, New
  // chat) - both are plain string/undefined comparisons so this can safely depend on them without
  // any loop risk once the navigate below makes them equal.
  //
  // Gated on hasMessages (not just conversationId) - ensureConversationId() (called by
  // useAttachmentUploader.handleFiles to scope the upload endpoint) mints and stores a
  // conversationId the moment a file is attached, well before any message is actually sent. Since
  // AppShell remounts the whole page on every pathname change (key={location.pathname} - see
  // that file), navigating to /chat/{id} at that point - before a real conversation exists -
  // wiped heroAttachments/convAttachments on the remount, silently dropping the attachment the
  // user had just uploaded. Requiring an actual message first (added synchronously by
  // useChat's doSend() the moment a real send happens) means attaching alone never navigates.
  //
  // "Cleared" means a conversation WAS loaded and no longer is - tracked in hadConversationRef,
  // not read off `!conversationId` alone. Opened from a link, a bookmark or a fresh tab, the page
  // starts with the URL naming a conversation and nothing loaded YET, and this effect runs before
  // the resume effect below has had its turn - so "nothing loaded" took every such link straight
  // back to the start screen. Found by the browser suite (DesktopUiIT), which opens conversations
  // by URL.
  const hadConversationRef = useRef(false)
  useEffect(() => {
    if (tourActive) return
    const cleared = hadConversationRef.current && !conversationId
    hadConversationRef.current = !!conversationId
    if (conversationId && hasMessages && conversationId !== params.conversationId) {
      navigate(`/chat/${conversationId}`, { replace: true })
    } else if (cleared && params.conversationId) {
      // Conversation cleared (e.g. "New chat") while the URL still names one - back to home,
      // not the bare /chat conversation-in-progress route (see handleHeroSend for the reverse
      // transition: / -> /chat the moment a brand-new conversation actually starts).
      navigate('/', { replace: true })
    }
  }, [conversationId, hasMessages, params.conversationId, navigate, tourActive])

  // Resumes a conversation named directly in the URL (a fresh load, or a refresh mid-turn - see
  // ../proxyma-docs/architecture/chat-reactivity.md). Reads conversationId/resumeConversation through a ref
  // rather than the effect's own deps: useChat's `store` (zustand, no selector) is a new object
  // every render, so every callback it derives (resumeConversation included) gets a new identity
  // every render too - listing it as a dep here would re-fire this effect (and re-trigger a fetch)
  // on every render instead of only when the URL's conversation id actually changes.
  const latestRef = useRef({ conversationId, resumeConversation })
  latestRef.current = { conversationId, resumeConversation }
  useEffect(() => {
    const urlId = params.conversationId
    if (urlId && urlId !== latestRef.current.conversationId) {
      latestRef.current.resumeConversation(urlId)
      setView('conversation')
    }
  }, [params.conversationId])

  const handleSelect = (id: string) => {
    resumeConversation(id)
    setHistoryOpen(false)
    setView('conversation')
  }

  const handleNew = () => {
    resetChat()
    setView('hero')
  }

  const handleSend = useCallback((msg: string, mentions?: string[], attachmentFiles?: string[], attachmentNames?: string[], skillMentions?: string[]) => {
    send(msg, mentions, attachmentFiles, attachmentNames, skillMentions)
    setInputValue('')
    setConvInput('')
    setHeroMentions([])
    setConvMentions([])
    setHeroAttachments([])
    setConvAttachments([])
    setHeroSkillMentions([])
    setConvSkillMentions([])
  }, [send])

  const handleHeroSend = () => {
    const msg = inputValue.trim()
    if (!msg) return
    handleSend(msg, heroMentions,
      heroAttachments.filter(a => a.status === 'done').map(a => a.fileName!),
      heroAttachments.filter(a => a.status === 'done').map(a => a.fileName!),
      heroSkillMentions)
    setView('conversation')
    // The base URL (/) is the home/hero page only - once a conversation actually starts, the
    // address bar should read /chat (promoted to /chat/:id once the backend assigns an id, via
    // the sync effect above). Guarded on pathname so this is a no-op if hero is ever reached
    // from somewhere other than home (e.g. a direct load of bare /chat with no prior state).
    if (location.pathname === '/') navigate('/chat')
  }

  const handleConvSend = () => {
    const msg = convInput.trim()
    if (!msg) return
    handleSend(msg, convMentions,
      convAttachments.filter(a => a.status === 'done').map(a => a.fileName!),
      convAttachments.filter(a => a.status === 'done').map(a => a.fileName!),
      convSkillMentions)
  }

  const handleRewind = useCallback(async (messageId: number) => {
    const content = await rewind(messageId)
    if (content != null) setConvInput(content)
  }, [rewind])

  // Whichever view is currently showing owns the drop - dropping a file always attaches it to
  // the pill actually visible, hero or conversation, not a fixed one.
  const activeHandleFiles = view === 'hero' ? heroUploader.handleFiles : convUploader.handleFiles

  // Drop target is the whole page (message thread included), not just the input pill - a file
  // dragged over any part of the conversation should register, not only the narrow pill at the
  // bottom. The kit's useFileDrop counts enters and leaves, since dragenter/dragleave fire again for
  // every child element the pointer crosses; DropOverlay is the full-window layer (Sources has the
  // same one).
  const pageDrop = useFileDrop({
    onDrop: data => { if (data.files.length > 0) activeHandleFiles(data.files) },
  })

  return (
    <div
      className="flex flex-col h-full overflow-hidden relative"
      {...pageDrop.handlers}
    >
      <DropOverlay active={pageDrop.active} label={t('Drop files to attach')} />
      {FEATURES.auth && quotaMilestone.isOpen && (
        <QuotaMilestoneFeedbackModal
          onDismiss={quotaMilestone.dismiss}
          onNeverShowAgain={quotaMilestone.neverShowAgain}
          onSubmitted={quotaMilestone.afterSubmit}
        />
      )}
      {/* THE TWO VIEWS SWAP BY A FADE (the kit FadeSwap): the arriving view fades in where the
          leaving one was. It was a framer-motion crossfade, whose script-driven animation the
          reduced-motion rule could not stop; FadeSwap honors it. Each view is keyed, so the
          composer in it is a fresh one. */}
      <FadeSwap swapKey={view} className="flex-1 min-h-0 flex flex-col">
        {view === 'conversation' ? (
          <div key="conversation" className="flex flex-col flex-1 min-h-0">
            {/* Over a deployed agent's own conversation: which agent this is, and the way back. */}
            <AgentStrip conversationId={conversationId} className="px-3 sm:px-6 pt-2" />
            <ChatWindow
              messages={messages}
              streaming={streaming}
              toolCalls={toolCalls}
              loading={loadingHistory}
              // undefined, not a no-op handler, when there is nothing recorded to resend - that is
              // what keeps MessageBubble from rendering a Retry that cannot do anything.
              onRetry={canRetry ? retry : undefined}
              conversationId={conversationId}
              pendingApprovals={pendingApprovals}
              pendingEnableRequests={pendingEnableRequests}
              condensing={condensing}
              onRewind={handleRewind}
              attachedToolCallCount={attachedToolCallCount}
              workflowRuns={workflowRuns}
            />
            <div className="flex-shrink-0 px-3 sm:px-6 py-2 sm:py-4 short:py-1">
              {/* THE TOAST DOCK: in a conversation a toast sits here, in the flow above the
                  composer, rather than on the layer under the header - which lies over the thread
                  and the pinned question (the UI review, 2026-10-28). */}
              <ToastDock className={THREAD_COLUMN} />
              {/* No shared-element animation between the hero and conversation views - the
                  composer is placed directly in each view's own layout, and the view fades in. */}
              <div className={THREAD_COLUMN}>
                <ChatComposer
                  value={convInput}
                  onChange={setConvInput}
                  onSend={handleConvSend}
                  onStop={stop}
                  streaming={streaming}
                  placeholder={t('Type @ to mention a source or / to use a skill')}
                  autoFocus
                  mentions={convMentions}
                  onMentionsChange={setConvMentions}
                  attachments={convAttachments}
                  onFiles={convUploader.handleFiles}
                  onRemoveAttachment={convUploader.removeAttachment}
                  skillMentions={convSkillMentions}
                  onSkillMentionsChange={setConvSkillMentions}
                  hasMessages={hasMessages}
                  conversationId={conversationId}
                  onNewConversation={handleNew}
                  onHistoryClick={() => setHistoryOpen(true)}
                  condensing={condensing}
                  condenseTokensFreed={condenseTokensFreed}
                  onCondense={condense}
                />
              </div>
              {/* Hidden on a short screen (a phone on its side), where the conversation above has
                  a few lines and this sentence would take one of them. The kit Caption at its own
                  13px - it was 10px on a phone, below the type scale. */}
              <Caption className="text-center mt-2 short:hidden">
                {t('AI can make mistakes. Always verify important information.')}
              </Caption>
            </div>
          </div>
        ) : (
          <div
            key="hero"
            className={cn(
                // TWO LAYOUTS. From `sm` up: the hero (the logo with the name under it, or on
                // Enterprise Server the greeting, and the fact when it is on), the composer and the
                // links as one centered group, which scrolls when it does not fit.
                //
                // ON A PHONE THE COMPOSER IS DOCKED AT THE BOTTOM (2026-10-02): under the thumb,
                // against the keyboard when that opens, and within a line of where it sits once the
                // first message is sent. The room above holds the hero, the fact under it and the
                // links under that (maintainer's order, 2026-10-15 - the links sat on the composer
                // until then, apart from everything they belong with), centered as one group and
                // scrolling when it does not fit. An earlier attempt at docking was undone: it put
                // the composer INSIDE the scrolling hero of a page sized in vh, and on an iPhone
                // the hero pushed it under the browser's toolbar. Neither holds now - the shell is
                // one box pinned to the visible viewport (data-app-shell) and the composer is a
                // footer outside the scroller, exactly as it is in a conversation.
                'flex-1 min-h-0 flex flex-col px-3 sm:px-6 pb-2 sm:py-6 short:py-2',
                'sm:items-center sm:justify-center sm:overflow-y-auto',
              )}
          >
            <div className="w-full max-w-4xl flex-1 min-h-0 flex flex-col sm:flex-none sm:block text-center">
              {/* THE SPACE TO THE COMPOSER, from `sm`: 48px under the Community logo (or its
                  fact), half again the composer's 32px to the links, so the hero reads as the head
                  of the group without leaving it. Enterprise Server keeps its 40px, under a
                  greeting whose heading carries 16px of its own - as it always did. Compared at
                  1280x720 and 1440x900 (2026-10-15): at 40px the wordmark's descenders sat within
                  reach of the composer's light; at 64px the logo began to read apart from it. */}
              <div className={cn(
                'flex-1 min-h-0 overflow-y-auto flex flex-col justify-center py-4 short:py-2 sm:block sm:overflow-visible sm:py-0 short:mb-3',
                FEATURES.auth ? 'sm:mb-10' : 'sm:mb-12',
              )}>
                {/* The kit's ChatStartHero. On Desktop and Home Server (Community) the Proxyma logo
                    with the name centered under it is the page's heading; on Enterprise Server it is
                    the greeting - "Hi, {name}" for a signed-in person, the question for a guest
                    (maintainer's review, 2026-10-15: the logo replaced both for half a day). Under
                    either, the astronomy fact (see SpaceFactLine), refreshed in the background every
                    half hour, or the Proxima Centauri default where the deployment has none - and
                    only for a viewer who turned it on (useSpaceFactPreference, off by default since
                    2026-09-30). */}
                <ChatStartHero
                  className="px-2 sm:px-0"
                  greeting={FEATURES.auth ? (
                    // NOT "What are you looking for?" (until 2026-10-02): the agent searches, and it
                    // also carries a long job through - a report, a set of changes, work handed to
                    // agents it deploys. The question is about the job, whatever kind it is.
                    greetingName ? t('Hi, {name}', { name: greetingName }) : t('What do you need done?')
                  ) : undefined}
                  fact={spaceFact ? (
                    <FadeSwap swapKey={spaceFact.text}><SpaceFactLine fact={spaceFact} /></FadeSwap>
                  ) : undefined}
                />
                {/* The links under the fact on a phone, in the same scroller - the docked
                    composer is the only thing below them. From `sm` up they are under the composer
                    (the copy after it), so each layout reads, and tabs, in its own order. */}
                <div className="sm:hidden mt-6 short:mt-3 px-2">
                  <ChatStartLinks />
                </div>
              </div>

              <div data-tour-id="tour-chat-input" className={cn('relative shrink-0', THREAD_COLUMN, 'sm:mb-8 short:mb-2')}>
                <ChatComposer
                  value={inputValue}
                  onChange={setInputValue}
                  onSend={handleHeroSend}
                  placeholder={t('Type @ to mention a source or / to use a skill')}
                  autoFocus
                  mentions={heroMentions}
                  onMentionsChange={setHeroMentions}
                  attachments={heroAttachments}
                  onFiles={heroUploader.handleFiles}
                  onRemoveAttachment={heroUploader.removeAttachment}
                  skillMentions={heroSkillMentions}
                  onSkillMentionsChange={setHeroSkillMentions}
                  onHistoryClick={() => setHistoryOpen(true)}
                />
              </div>
              <div className="hidden sm:block">
                <ChatStartLinks />
              </div>
            </div>
          </div>
        )}
      </FadeSwap>

      {historyOpen && (
        <ChatHistoryModal
          activeId={conversationId}
          onSelect={handleSelect}
          onClose={() => setHistoryOpen(false)}
        />
      )}
    </div>
  )
}
