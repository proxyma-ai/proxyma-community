import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  RotateCw, Shield, ScrollText, Server, LaptopMinimal, CircleQuestionMark, NotebookText, Code2,
  Braces, BookOpen, Scale,
} from 'lucide-react'
import {
  Button, Caption, KeyValue, KeyValueList, LinkRow, List, SettingsSection, SettingsSections,
  SwitchRow,
} from '@/components/ui/kit'
import { Logo } from '@/components/ui/Logo'
import { configApi } from '@/services/configApi'
import { settingsApi } from '../services/settingsApi'
import { useTourStore } from '@/store/tourStore'
import { withReason } from '@/services/apiError'
import { useSettings } from '../hooks/useSettings'
import { useAuthStore } from '@/store/authStore'
import { useToastStore } from '@/store/toastStore'
import { authApi } from '@/features/auth/services/authApi'
import { FEATURES } from '@/config/features'
import { ThirdPartyNoticesModal } from '../components/ThirdPartyNoticesModal'
import { edition } from '@settings-pages'
import type { OpenTab } from '../pages/types'
import { useSpaceFactPreference } from '@/features/chat/hooks/useSpaceFactPreference'
import { keys } from '@/services/queryKeys'
import { useTranslation } from '@/i18n'
import { useDates } from '@/hooks/useDates'
import { SignpostSection } from '../pages/shared'

// - About tab: what is running, where to read about it, and the privacy documents and switches -
//
// Two links out to the published documents on proxyma.ai. This modal has no room to render a whole
// legal document itself, and there is no site-wide footer anywhere in this app to link them from
// otherwise - they are also linked from Login/Setup, but a signed-in user has no other way back to
// them, which is what this tab exists for.
//
// They open in a new tab rather than navigating this one: the documents are no longer in-app
// routes, and sending someone out of a running conversation to read a privacy policy - losing an
// unsent draft on the way - is not a trade the reader agreed to. The kit LinkRow carries
// `noopener`, which is what stops the opened tab from getting a handle on this one.

// What you are running, and where to go next. It answers the question a support thread opens
// with - "what version?" - without asking anyone to find Administration, which desktop should
// not have at all.
//
// Reads the ['config'] query ThemeProvider already primes with staleTime: Infinity, so opening
// this tab issues no request; version, platform and edition all ride on that one payload.
// About - what you are running, where to read about it, and what it does with your data.
//
// THREE TABS UNTIL 2026-09-02: About, Documentation and Privacy. None of them changed anything
// about how the install behaves - two were lists of links to proxyma.ai and the third was a
// version readout with two consent switches - so the settings rail opened with three entries that
// nobody can set. They are one tab and one question now, read top to bottom: what is this, where
// do I read about it, what do you do with my data, and how do I see the tour again.
//
// The privacy SWITCHES came with the links deliberately. They are the two controls in this product
// a reader is most likely to go looking for after reading the policy that describes them, and the
// policy is now one section above them rather than one tab away.
export function AboutTab({ onClose, onOpenTab }: { onClose: () => void; onOpenTab: OpenTab }) {
  const { t } = useTranslation()
  const { data: config } = useQuery({ queryKey: ['config'], queryFn: configApi.get, staleTime: Infinity, retry: false })
  // Three editions from two builds: the home server is the desktop bundle, so only the runtime
  // config can name it. "Home Server" and "Enterprise Server" stay untranslated - they are product
  // names the installer, the manuals and the License tab all use.
  const editionName = FEATURES.auth ? t('Enterprise Server') : config?.homeServer ? t('Home Server') : t('Desktop')
  const [showNotices, setShowNotices] = useState(false)
  return (
    <SettingsSections>
      {/* No heading of its own: the tab's title already says About, and "About Proxyma" under it
          said it twice.

          THE MARK AND THE THREE FACTS, side by side, as one identity plate. This tab is the one
          place in the product that answers "what am I looking at", and it answered it in three
          rows of grey text - the only screen in the app carrying no brand at all.

          ITS HEIGHT IS THE LIST'S HEIGHT, and it is not a number. `items-stretch` on the row
          plus `h-full` on the mark means the square tracks whatever the three rows actually
          measure - which changes with the type scale, with a longer translated label wrapping,
          and with the row spacing. A size in pixels would be right today and quietly wrong at
          the first of those.

          THE WIDTH IS RESERVED AND THE HEIGHT IS NOT, which is the only arrangement that works
          here. A flex item's width is resolved from its content BEFORE the row's cross size is
          known, so nothing that derives width from the stretched height - aspect-square included
          - can win: both were tried, and both left the wrapper 28px wide (the kit's default svg
          width attribute) with a 128px drawing spilling out of it, painting over the Edition,
          Version and Platform labels. A logo that looked deliberately placed and had quietly
          eaten the left column.

          So `w-20` reserves the column - about the height of the kit's three-row fact list - and
          `self-stretch` ties the height to the rows. The svg fills that box, and its default
          preserveAspectRatio scales it uniformly and centres it - so if the rows ever grow taller
          than 80 the mark stays square and gains slack rather than distorting.

          Logo with markOnly, never ProxymaIcon directly - .claude/rules/frontend.md. markOnly
          because the Edition row beside it already reads "Proxyma Desktop", which is the
          duplicate that prop exists to avoid.

          The facts are the kit's KeyValueList, a name and its value per row. */}
      <div className="flex items-stretch gap-5">
        <span className="shrink-0 self-stretch w-20" aria-hidden="true">
          {/* Turns at the header's idle speed, 30 degrees a second: the kit's named mark-idle
              animation, which the kit's prefers-reduced-motion rule stops with no code here. */}
          <Logo markOnly className="h-full w-full animate-mark-idle" />
        </span>
        <KeyValueList layout="rows" className="flex-1 min-w-0 content-center gap-y-3">
          <KeyValue layout="rows" label={t('Edition')}>{`Proxyma ${editionName}`}</KeyValue>
          {/* A figure, so a readout - its digits in the figures face like every other version number. */}
          <KeyValue layout="rows" label={t('Version')} figures>{config?.version ?? '-'}</KeyValue>
          <KeyValue layout="rows" label={t('Platform')}>
            {config?.platform ? PLATFORM_LABELS[config.platform] ?? config.platform : '-'}
          </KeyValue>
        </KeyValueList>
      </div>

      {/* Community's source offer and license (AGPL-3.0-only, section 13), right under the edition
          it describes; nothing on Enterprise Server. See SourceCodeSection.desktop.tsx. */}
      <edition.SourceCodeSection />

      {/* Community's sponsor link, beside its source; nothing on Enterprise Server. See
          SponsorSection.desktop.tsx. */}
      <edition.SponsorSection />

      {/* The guides a new user asks for first, which used to be chips under the empty chat. */}
      <GuidesSection />

      {/* Was its own tab. The links are unchanged and so is their order - what changed is that
          they no longer need a rail entry of their own to be found. */}
      <DocumentationSection />

      {/* Was its own tab, and it is the largest of the three - two legal documents and, in server
          mode, the two consent switches. It reads in the order somebody actually needs it: what
          you agreed to, then what you can change about it. */}
      <PrivacySection onOpenTab={onOpenTab} />

      {/* Desktop's way to the feedback form since it stopped being a tab (2026-10-12); nothing on
          server, where the account menu carries it. See FeedbackSection.desktop.tsx. */}
      <edition.FeedbackSection />

      <DisplaySection />

      {/* The walkthrough's own final step says "Revisit this walkthrough whenever you like from
          Settings". Until now that was not true anywhere: the replay control lived on the Help
          page, the Help page was removed, and the only remaining call to the tour store's start()
          was the once-per-user auto-start. So the tour ended by telling every user where to find
          something that did not exist. Here is the control it names.

          Closes the modal first - every one of the tour's settings steps opens this modal itself,
          so leaving it up would put the overlay behind a panel it is about to reopen. */}
      <SignpostSection
        title={t('Walkthrough')}
        caption={t('What Proxyma does, in a few short stops, under a minute.')}
        action={
          <Button
            variant="secondary"
            onClick={() => {
              onClose()
              useTourStore.getState().start()
            }}
          >
            <RotateCw size={14} />
            {t('Replay walkthrough')}
          </Button>
        }
      />

      {/* THE NAME IS A REFERENCE: the End User License Agreement sends a reader to "Settings >
          About > Open-source licenses", so this heading must keep saying exactly that. The text is
          THIRD-PARTY-NOTICES.txt, the same file the installers put in the installation folder. */}
      <SignpostSection
        title={t('Open-source licenses')}
        caption={t('The open-source software in Proxyma, the license each part is under, and how to get the source code of the GPL and LGPL parts.')}
        action={
          <Button variant="secondary" onClick={() => setShowNotices(true)}>
            <Scale size={14} />
            {t('View licenses')}
          </Button>
        }
      />
      {showNotices && <ThirdPartyNoticesModal onClose={() => setShowNotices(false)} />}
    </SettingsSections>
  )
}

// PER-BROWSER DISPLAY PREFERENCES, since 2026-09-30. The empty chat's space fact became opt-in that
// day (useSpaceFactPreference) with nothing that could opt in; this is that switch. It is stored in
// this browser, not on the account or the deployment, which is what the caption says - a viewer who
// turns it on here will not see it on another machine.
function DisplaySection() {
  const { t } = useTranslation()
  const spaceFact = useSpaceFactPreference(s => s.enabled)
  const setSpaceFact = useSpaceFactPreference(s => s.setEnabled)
  return (
    <SettingsSection title={t('Display')} gap="list">
      <SwitchRow
        className="pt-0"
        title={t('Show a space fact on the empty chat')}
        caption={t('A short astronomy fact under the logo on a new chat. Saved in this browser only.')}
        checked={spaceFact}
        onChange={setSpaceFact}
      />
    </SettingsSection>
  )
}

const PLATFORM_LABELS: Record<string, string> = {
  windows: 'Windows', macos: 'macOS', linux: 'Linux',
}

// THE GUIDES, since 2026-09-30 (F27 of that day's review). The three documents people ask for
// first - reaching Proxyma from a coding assistant, calling its API, and what the agent can do -
// were chips under the empty chat, where they competed with the one thing that screen is for. They
// live here now, beside the rest of the documentation. Each opens in the reader's language: every
// one of them exists in both, which is the condition for building the URL from the locale at all.
//
// Each document is the kit LinkRow: THE TITLE IS THE LINK, and the whole row is not. A full-width
// button with a hover fill lit a wide grey band across the panel and said nothing about which words
// were clickable - a block that highlights is how a list row you SELECT behaves, and these go to
// another site. An anchor also keeps middle-click, "copy link address" and the status bar.
function GuidesSection() {
  const { t, locale } = useTranslation()
  const docs = (slug: string) => `https://proxyma.ai${locale === 'en' ? '' : `/${locale}`}/docs/${slug}/`
  return (
    <SettingsSection title={t('Guides')} gap="list">
      <List>
        <LinkRow
          icon={Code2}
          title={t('Claude Code, VS Code & Copilot')}
          description={t('Reaching this instance from your own tools or coding assistant.')}
          href={docs('integrations')}
        />
        <LinkRow
          icon={Braces}
          title={t('Proxyma API')}
          description={t('Searching your sources and asking the agent from a script.')}
          href={docs('api')}
        />
        <LinkRow
          icon={BookOpen}
          title={t('What the agent can do')}
          description={t('Every tool the agent has, and what each one may change.')}
          href={docs('agent')}
        />
      </List>
    </SettingsSection>
  )
}

// A SECTION of AboutTab since 2026-09-02, not a tab. Unchanged otherwise - it kept its own
// function rather than being inlined, because it is a self-contained list and folding it into the
// caller would make one already-long component longer for no gain.
function DocumentationSection() {
  const { t } = useTranslation()
  // The same primed ['config'] query AboutTab reads, so this issues no request of its own.
  const { data: config } = useQuery({ queryKey: ['config'], queryFn: configApi.get, staleTime: Infinity, retry: false })
  const manual = FEATURES.auth
    ? { icon: Server, title: t('Proxyma Enterprise Server manual'), href: 'https://proxyma.ai/docs/manual/' }
    : config?.homeServer
      ? { icon: Server, title: t('Proxyma Home Server manual'), href: 'https://proxyma.ai/docs/manual/' }
      : { icon: LaptopMinimal, title: t('Proxyma Desktop manual'), href: 'https://proxyma.ai/docs/manual/' }
  return (
    <SettingsSection
      title={t('Documentation')}
      caption={t('Manuals, the API and MCP reference, and answers to common questions, published on proxyma.ai for the current release.')}
      gap="list"
    >
      {/* Icons match proxyma.ai/docs one-for-one: a rack for the server manual, a laptop for the
          desktop one, plug-zap for the API and MCP, a question mark for Q&A, a notebook for the
          release notes. The same document was a book here and a plug there before.

          A plain divided list, NOT a bordered rounded card: this modal IS the panel, and a card
          inside a card only draws a second border a few pixels in from the first. */}
      <div data-tour-id="tour-settings-docs">
        <List>
          <LinkRow
            icon={manual.icon}
            title={manual.title}
            description={t('Installing, configuring and operating Proxyma.')}
            href={manual.href}
          />
          <LinkRow
            icon={CircleQuestionMark}
            title={t('Questions and answers')}
            description={t('Where data goes, who can see what, and how to get help.')}
            href="https://proxyma.ai/docs/manual/#privacy"
          />
          {/* Absent before, which left the one document that answers "what changed, and does it
              need anything from me before I upgrade" reachable only from the website. */}
          <LinkRow
            icon={NotebookText}
            title={t('Release notes')}
            description={t('What changed in each version, and what needs action before you upgrade.')}
            href="https://proxyma.ai/docs/release-notes/"
          />
        </List>
      </div>
    </SettingsSection>
  )
}

// A SECTION of AboutTab since 2026-09-02, not a tab. It took an unused `onClose` while it was one;
// that prop is gone rather than being threaded through the new parent for nothing.
function PrivacySection({ onOpenTab }: { onOpenTab: OpenTab }) {
  const { t } = useTranslation()
  const dates = useDates()
  const qc = useQueryClient()
  const addToast = useToastStore(s => s.addToast)

  // The account's own consent record, for the line under the Terms: which version it accepted, and
  // when. Server mode only - desktop mode has no account.
  //
  // THERE WAS A SWITCH HERE, to contribute anonymous usage counts, until 2026-10-15. Its answer was
  // stored on the account and read by nothing: Enterprise Server sends no diagnostics at all
  // (TelemetryConsent), and the local counters it once fed were gone. A consent control that
  // controls nothing reads as dead code or an undisclosed data path, so it was removed, with the
  // checkbox at sign-up (release review 2026-10-15, W26; NoUsageCountConsentTest).
  const { data: me } = useQuery({ queryKey: keys.me(), queryFn: authApi.me, enabled: FEATURES.auth })

  // The deployment-wide diagnostics setting. It
  // existed, PrivacySettings defaulted it to false, and NOTHING anywhere could set it to true -
  // no control in this modal, no other caller of saveDataCollectionAllowed, nothing in the API
  // surface but the generic /settings/{prefix}/{field} route. So the switch this section is
  // written as though it has was unreachable, and TelemetryShipper.record() returned at its gate
  // on every installation that has ever run.
  //
  // Deployment-wide, so on server it is SUPER_ADMIN-only - PATCH /api/v1/settings/privacy/*
  // 403s for anyone else (SettingsController.checkAccess). Gated on the RESOLVED USER rather
  // than on FEATURES.auth, so a guest never fires a query that would 401.
  const user = useAuthStore(s => s.user)
  const canEditDeploymentPrivacy = !FEATURES.auth || user?.role === 'SUPER_ADMIN'
  const { data: privacySettings } = useSettings('privacy', canEditDeploymentPrivacy)
  // Enterprise Server never ships diagnostics or ratings off its own infrastructure, licensed or
  // not (TelemetryConsent, FeedbackShipper) - by EDITION since 2026-10-14. Shown disabled rather
  // than hidden: an absent control says nothing, a disabled one with a reason says why.
  const telemetryBlockedByTier = FEATURES.auth
  const dataCollectionAllowed = privacySettings?.['data-collection-allowed'] === 'true'
  const dataCollectionMutation = useMutation({
    mutationFn: (allowed: boolean) =>
      settingsApi.saveField('privacy', 'data-collection-allowed', String(allowed)),
    onSuccess: (_data, allowed) => {
      qc.invalidateQueries({ queryKey: keys.settings('privacy') })
      // Turning it off also deletes what was waiting - the server does that on the save itself
      // (DataCollectionChangedEvent), so the toast can say so truthfully.
      addToast(allowed ? t('Diagnostics are now sent to Proxyma') : t('Diagnostics are no longer sent, and what was waiting has been deleted'), 'success')
    },
    onError: (e: unknown) => addToast(withReason(t('Could not save that preference'), e), 'danger'),
  })
  return (
    <SettingsSection
      title={t('Privacy')}
      caption={FEATURES.auth
        ? t('Read how your data is handled, and the terms that govern your use of this service.')
        : t('Read how your data is handled, and the terms for the services Proxyma runs.')}
      gap="list"
    >
      {/* The two documents, then the switch they describe, in ONE list. It had a heading of its
          own over it ("Diagnostics") that only restated the row's own title a size larger. */}
      <List>
        {/* On Community the Terms do NOT govern the software: the AGPL alone does (Terms section 1),
            and the Terms cover the services Proxyma runs - feedback, diagnostics, the website. On
            Enterprise Server they govern the software too (release review 2026-10-15, W23d). */}
        <LinkRow
          icon={ScrollText}
          title={t('Terms of Service')}
          description={FEATURES.auth
            ? (
              <>
                {t('The terms that govern your use of this service.')}
                {me?.legalAcceptedAt && ' '}
                {me?.legalAcceptedAt && t('You accepted version {version} of the terms on {date}.', {
                  version: me.legalVersionAccepted ?? '',
                  date: dates.date(me.legalAcceptedAt),
                })}
              </>
            )
            : t('The terms for the services Proxyma runs, such as feedback and diagnostics. This edition itself is under the AGPL.')}
          href="https://proxyma.ai/terms"
        />
        <LinkRow
          icon={Shield}
          title={t('Privacy Policy')}
          description={t("What data is collected, how it's used, and your rights over it.")}
          href="https://proxyma.ai/privacy"
        />

        {/* The kit SwitchRow, the same shape as every other switch in this window.

            Short and concrete on purpose: what is in it, what never is, how often it goes. The
            rated-answer clause stays because this same switch governs ratings (SEC-38), and a
            consent screen that leaves that out would be the wrong kind of short.
            Enterprise Server never sends diagnostics (TelemetryConsent), licensed or not, so
            there this switch governs answer ratings alone - and must say only that. */}
        {canEditDeploymentPrivacy && (
          <SwitchRow
            title={t('Send diagnostics to Proxyma')}
            caption={FEATURES.auth
              ? t('Proxyma Enterprise Server never sends diagnostics or rated answers to Proxyma.')
              : t('Error types, timings and enabled features, sent once a day - never your documents, questions, file names or account. A rated answer and its question are sent too; off by default.')}
            checked={dataCollectionAllowed && !telemetryBlockedByTier}
            onChange={v => dataCollectionMutation.mutate(v)}
            disabled={dataCollectionMutation.isPending || telemetryBlockedByTier}
            footnote={telemetryBlockedByTier ? (
              <Caption>
                {t('Enterprise deployments never send diagnostics outside their own infrastructure, so this cannot be turned on.')}
              </Caption>
            ) : !FEATURES.auth && (
              // What was collected, what would go next and every send are in Logs since
              // 2026-10-12, with the preview and the delete that used to sit here.
              <edition.AuditLogsLink log="diagnostics" onOpenTab={onOpenTab}>
                {t('See what is collected and sent')}
              </edition.AuditLogsLink>
            )}
          />
        )}
      </List>
    </SettingsSection>
  )
}

