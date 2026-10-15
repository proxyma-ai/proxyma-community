#!/usr/bin/env node
/**
 * Compares the translation mappings against the ENGLISH STRINGS ACTUALLY IN THE SOURCE.
 *
 *   node libs/ui-kit/i18n/check-catalogs.mjs
 *
 * This replaced a checker that compared one catalog against another, which is why it exists in
 * this shape. That version reported "65 keys, ok" for a catalog in which 5 keys had a call site,
 * five named settings tabs that do not exist, and five real tabs were missing. Comparing two
 * catalogs cannot find any of that: both sides were equally wrong, and agreeing with each other
 * was the whole of what it checked.
 *
 * So the source is the authority. Three verdicts, and they do NOT all fail the build:
 *
 *   untranslated  an English string in the code with no entry in the mapping. Renders English,
 *                 which is correct behaviour and not a defect - so it is reported, not fatal.
 *                 Failing here would mean no English string could be added without a translation
 *                 in the same commit. `I18N_STRICT=1` makes it fatal, for once a language is
 *                 complete and the intent is to keep it that way.
 *   orphaned      an entry in the mapping matching no string in the source. Usually a reworded
 *                 English string that silently reverted to English - the one real hazard of using
 *                 source text as the key, and the reason this check exists. FATAL.
 *   duplicate     a key written twice. One of the two translations can never render, and file
 *                 order decides which. FATAL.
 *
 * Run it after rewording anything a user reads.
 */
import { readdir, readFile } from 'node:fs/promises'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const WORKSPACE = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..')

/**
 * Where each app keeps its mapping, and which trees its call sites live in.
 *
 * THE APP IS TWO TREES SINCE 2026-10-15: its own, and Proxyma Enterprise's pages in ee/frontend
 * (closed source, private repository only - docs/architecture.html#oss-boundary), each with a catalog holding what
 * only it says. They are checked as ONE app - the catalogs merged, the sources scanned together -
 * so a key in both catalogs is a duplicate, and a translation the Community tree no longer says has
 * to move to the Enterprise catalog or go. A tree without ee/ (the public repository) has one of
 * each, and passes on its own. A directory that does not exist is skipped, here as for the apps
 * below, which are not in that tree either.
 */
const APPS = [
  { name: 'apps/app', catalogs: ['apps/app/frontend/src/i18n/catalogs', 'ee/frontend/src/i18n/catalogs'], src: ['apps/app/frontend/src', 'ee/frontend/src'] },
  { name: 'apps/landing', catalogs: ['apps/landing/src/i18n/catalogs'], src: ['apps/landing/src'] },
  { name: 'apps/license', catalogs: ['apps/license/frontend/src/i18n/catalogs'], src: ['apps/license/frontend/src'] },
  { name: 'apps/telemetry', catalogs: ['apps/telemetry/frontend/src/i18n/catalogs'], src: ['apps/telemetry/frontend/src'] },
]

/**
 * Matches `name: 'value'` or `name: "value"` for any of the given property names.
 *
 * Both quote styles, because a string containing an apostrophe is written in double quotes and a
 * catalog is a mixture either way. Group 1 or group 2 carries the value - whichever quote style
 * matched - which is why the reader below takes `m[1] ?? m[2]`.
 */
function prop(...names) {
  const body = `\\b(?:${names.join('|')}):\\s*(?:'((?:[^'\\\\]|\\\\.)*)'|"((?:[^"\\\\]|\\\\.)*)")`
  return new RegExp(body, 'g')
}

/**
 * Matches a JSX ATTRIBUTE - `name="value"` or `name={'value'}` - for any of the given names.
 *
 * Distinct from `prop()`, which matches an object property (`name: 'value'`). Both shapes carry
 * prose and neither can stand in for the other, and this one was missing: a component whose copy
 * arrives as props has its English at the CALL SITES, not in its own file. `DeleteConfirmModal` is
 * that shape - one shell reused for connectors, backups, users and API tokens - and eleven strings
 * behind it were in NO report at all, because they reach `t()` as variables and the entry that
 * existed only covered the component's own defaults. Found by a review, not by this script.
 */
function jsxProp(...names) {
  const body = `\\b(?:${names.join('|')})=(?:"((?:[^"\\\\\\n]|\\\\.)*)"|\\{'((?:[^'\\\\\\n]|\\\\.)*)'\\}|\\{"((?:[^"\\\\\\n]|\\\\.)*)"\\})`
  return new RegExp(body, 'g')
}

/**
 * Every quoted string inside a `name={[ ... ]}` JSX attribute - an inline array of prose.
 *
 * Needed because `ARRAY_ELEMENT` below matches a bare quoted line ANYWHERE in a file, and pointed
 * at a whole component that means every wrapped Tailwind class list too: twenty class strings
 * reported as untranslated prose on the first attempt. The bullets under a destructive-action
 * dialog are written inline at the call site, so they cannot be scoped with `blockOf` either -
 * they belong to no named const. Matching the attribute is what separates them from the noise.
 */
function jsxArrayProp(name) {
  return new RegExp(`\\b${name}=\\{\\[([\\s\\S]*?)\\]\\}`, 'g')
}

/** A bare quoted string on its own line - an element of a string array, like a feature list. */
const ARRAY_ELEMENT = /^\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)"),\s*$/gm

/**
 * The text of one top-level `const NAME ... = [ ... ]`, so a pattern can be aimed at one array
 * rather than at a whole file.
 *
 * Needed because a property name is not always enough to say what a string is FOR. ShowcaseSection
 * and GuardrailsSection each hold both the section's prose and the replica of the running
 * application, and both use `label:` - `label: 'Ask and act'` is a tab a reader clicks, while
 * `label: 'Total Storage'` is a column header inside a picture of the product. Scanning the file
 * reported four replica strings as untranslated, which is exactly the crying-wolf this checker is
 * written to avoid.
 *
 * Ends at the first `]` OR `}` in column 1 - whichever closes this block first.
 *
 * IT USED TO LOOK FOR `\n]` ALONE, and that was silently wrong the moment it was pointed at an
 * object literal instead of an array. `const PHRASES = { ... }` closes with `\n}`, so the scan ran
 * straight past it to the next `\n]` in the file - the end of a DETAIL_KEYS array three consts
 * later - and swallowed two unrelated maps whole. The report grew twenty argument names and
 * proper nouns presented as untranslated prose, which is precisely the crying-wolf this scoping
 * exists to prevent. Taking the EARLIER of the two terminators is correct for both shapes.
 */
function blockOf(text, name) {
  const decl = text.search(new RegExp(`^(?:export )?const ${name}\\b`, 'm'))
  if (decl === -1) return ''
  // From the INITIALIZER, not the declaration. A multi-line type annotation - `const ADMIN_VIEWS:
  // Array<{ ... }> = [` - has a line starting with `}` of its own, which read as the end of the
  // block, so the block was the type and nothing in the value was scanned. Found 2026-09-30, when
  // strings added to ADMIN_VIEWS were in neither report.
  const init = text.slice(decl).search(/=\s*[[{(]/)
  const start = init === -1 ? decl : decl + init
  const endArray = text.indexOf('\n]', start)
  const endObject = text.indexOf('\n}', start)
  const candidates = [endArray, endObject].filter((i) => i !== -1)
  if (!candidates.length) return text.slice(start)
  return text.slice(start, Math.min(...candidates) + 2)
}

/**
 * Strings that reach `t()` as a VARIABLE rather than a literal, so scanning for `t('...')` cannot
 * see them.
 *
 * The settings rail renders `t(tab)` and `t(g.label)`, where the value is a TabKey or a nav group
 * heading - both of which are English text already, which is precisely why the source-key model
 * suits them. Without this the 19 tab names would every one be reported as orphaned.
 *
 * IT IS ALSO THE ONLY THING THAT MAKES THE UNTRANSLATED DIRECTION WORK FOR THEM, and that is not
 * a nicety. A string reaching `t()` as a variable is in neither report otherwise: not orphaned,
 * because `quotedStrings` finds it in the source; and not untranslated, because `asked` only
 * holds literals. The landing site has 24 such call sites, and eight strings behind them were
 * rendering English on a page reported as "all in use". That was found by opening the page in a
 * browser, which is the only thing that could have found it. Now the checker can.
 *
 * Kept as an explicit, narrow list rather than "treat every string union as used", which would
 * mask real orphans everywhere - and the PROPERTY NAMES are the narrowing. `label` and `caption`
 * are prose; `id`, `name`, `protocol`, `baseUrl`, `meta`, `running` and `done` are the
 * application replicas those same files hold, and naming a field here is what says which is
 * which.
 */
const DYNAMIC = [
  // The connector catalog's words - type names, field labels and help, sign-in methods, categories.
  // They arrive from the server (GET /api/v1/connectors/catalog) and reach `t()` as variables all
  // over the Sources page and the source and connection dialogs; the generated list is what says
  // they are asked for. GENERATED from the descriptors by tools/i18n/connector-labels.mjs, one string
  // a line in whichever quote it does not contain, nothing else escaped.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/i18n/connectorLabels.generated.ts',
    pattern: /^  (?:'([^'\n]*)'|"([^"\n]*)"),$/gm,
  },
  // The Enterprise modules' descriptors (the Microsoft 365 types and service), the same way - their
  // own list since 2026-10-15, absent from a tree without ee/.
  {
    app: 'apps/app',
    file: 'ee/frontend/src/i18n/connectorLabels.generated.ts',
    pattern: /^  (?:'([^'\n]*)'|"([^"\n]*)"),$/gm,
  },
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/settings/components/SettingsModal.tsx',
    // export type TabKey = 'Account' | 'About' | ...
    pattern: /export type TabKey =([^\n]+)/,
  },
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/settings/pages/index.desktop.tsx',
    pattern: /label: '([^']+)'/g,
  },
  {
    app: 'apps/app',
    file: 'ee/frontend/src/features/settings/pages/index.server.tsx',
    pattern: /label: '([^']+)'/g,
  },
  // NAV_ITEMS - the header's three destinations. Topbar maps `t` over them before rendering, and
  // before handing them to MobileMenu, so each label is a variable at both call sites. `label` is
  // the only prose property in the file; `to` is a route and `auth` a role.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/components/layout/Topbar.tsx',
    pattern: prop('label'),
  },

  // DATA_ITEMS / ACCOUNT_ITEMS - what deleting an account destroys, rendered through `t(item.*)`.
  // NOT `icon`, which is a lucide component reference.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/auth/components/AccountDeletionModal.tsx',
    pattern: prop('title', 'subtitle'),
  },

  // DeleteConfirmModal's CALL SITES. Its copy arrives as props, so the English lives in whoever
  // renders it - every file listed below, which must name EVERY caller (grep for
  // `<DeleteConfirmModal`) - and each reaches `t()` as a variable inside the component,
  // where the entry above covers only its own defaults.
  //
  // NOT `connectorName`, and that omission is a correction. Listing it here marked three catalog
  // entries "in use" that can never render: the component prints `{connectorName}` VERBATIM,
  // because that prop is usually real data - a backup filename, a user's email. The three call
  // sites that pass PROSE now wrap it themselves, so the literal sits at the call site where
  // `literals()` sees it and where it actually renders. A checker entry that reports a string as
  // covered when nothing translates it is worse than no entry: it is a green light for a bug.
  //
  // The `consequences` bullets ARE covered, by the ARRAY_ELEMENT entry beside each - about
  // twenty-two of them, previously promised by a comment and not delivered by the code.
  // Since the 2026-10 kit rebuild only one caller is left: Settings, Skills, Workflows, Reset and
  // (since 2026-10-14) Administration confirm through the kit's ConfirmDialog, whose call sites pass
  // `t('...')` literals the scan sees.
  ...['features/indexing/components/ConnectorDetailModal.tsx'].flatMap((f) => [
    {
      app: 'apps/app',
      file: `apps/app/frontend/src/${f}`,
      // `title` joined this list on 2026-09-06. It is prose, the modal renders it through
      // `t(title)`, and it was missing only because every call site happened to pre-translate
      // it - `title={t('Revoke Token')}` puts the literal where `literals()` already sees it.
      // The one call site that followed the component's OWN documented contract and passed a
      // bare English key was therefore in neither report, which is how it sat rendering the
      // template literal `Delete ?` through three review sweeps.
      pattern: jsxProp('title', 'actionVerb', 'confirmLabel', 'deletingLabel'),
    },
    {
      app: 'apps/app',
      file: `apps/app/frontend/src/${f}`,
      pattern: jsxArrayProp('consequences'),
      // The attribute match yields the whole array body; pull the strings out of it.
      inner: /'((?:[^'\\\n]|\\.)*)'|"((?:[^"\\\n]|\\.)*)"/g,
    },
  ]),

  // GuardrailCheck's `description`, a JSX attribute on six switch rows of the Guardrails tab. The
  // titles beside them are translated at the call site and these are not - the shape only a
  // per-attribute pattern catches, since the component renders `t(description)`. (It named
  // SettingsModal.tsx, and About's link rows, until the tabs were split into files of their own on
  // 2026-10-14.)
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/settings/tabs/GuardrailsTab.tsx',
    pattern: jsxProp('description'),
  },

  // The performance tiles and the retrieval-stage labels, both rendered through `t(x.label)`.
  {
    app: 'apps/app',
    file: 'ee/frontend/src/features/admin/components/PerformanceSection.tsx',
    pattern: prop('label'),
  },

  // THE ADMIN PAGE'S LABEL MAPS. Tab names, role names, feedback moods, the cost page's
  // service and role names, and the date-range options - all rendered through `t(<variable>)`.
  // NOT MOOD_BADGE in FeedbackDetailModal, which holds Tailwind class strings; naming the block
  // rather than scanning the file is what keeps those out.
  // One file per Administration tab since 2026-10-14: the tab names stay with the page, the role
  // names went with the Users tab and the moods with the Feedback tab.
  ...[['pages/AdminPage.tsx', 'ALL_TABS'], ['tabs/UsersTab.tsx', 'ROLE_LABEL'], ['tabs/FeedbackTab.tsx', 'MOOD_LABEL']].map(([file, block]) => ({
    app: 'apps/app',
    file: `ee/frontend/src/features/admin/${file}`,
    block,
    pattern: /:\s*'((?:[^'\\\n]|\\.)*)'/g,
  })),
  {
    app: 'apps/app',
    file: 'ee/frontend/src/features/admin/components/FeedbackDetailModal.tsx',
    block: 'MOOD_LABEL',
    pattern: /:\s*'((?:[^'\\\n]|\\.)*)'/g,
  },
  // The Enterprise trial banner's sentences (TEXT in trialBanner.ts), kept beside the function that
  // picks one so its test can hold each to the lapse rules; TrialBanner.tsx looks the pick up.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/settings/utils/trialBanner.ts',
    block: 'TEXT',
    pattern: prop('admin', 'member'),
  },
  // The Usage & Cost tab's service names and period options, and the Overview's periods, had
  // entries here until 2026-10-14: the names are auditLabels.serviceLabel's literal calls now, and
  // every period is the one PeriodSelect's (features/audit/components/PeriodSelect.tsx).

  // DeleteConfirmModal reuses one shell for connectors, backup files and API tokens, so its
  // copy arrives as PROPS with English defaults. Two shapes to cover: the default values in the
  // destructuring, and the consequence bullets in a const array. Both reach t() as variables.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/indexing/components/DeleteConfirmModal.tsx',
    pattern: /\b(?:title|actionVerb|confirmLabel)\s*=\s*'((?:[^'\\\n]|\\.)*)'/g,
  },
  // TWO blocks, not one. CONNECTOR_CONSEQUENCES was a single const picked by a build-time ternary;
  // it became UPLOADED_CONSEQUENCES and LOCAL_FOLDER_CONSEQUENCES, chosen at render off a runtime
  // flag, when the home server turned out to be a desktop build that DOES own the bytes it indexes.
  // A block named here that no longer exists reports a warning and scans nothing, which is the
  // whole reason this file names blocks rather than files.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/indexing/components/DeleteConfirmModal.tsx',
    block: 'UPLOADED_CONSEQUENCES',
    pattern: ARRAY_ELEMENT,
  },
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/indexing/components/DeleteConfirmModal.tsx',
    block: 'LOCAL_FOLDER_CONSEQUENCES',
    pattern: ARRAY_ELEMENT,
  },
  // What each paid tier adds, shown in the upgrade dialog. Missing until 2026-09-21: the bullets
  // reach the lookup as variables, so rewording one reported only the OLD text as orphaned and the
  // new text in no report at all.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/settings/components/UpgradeModal.tsx',
    block: 'TIER_SELLING_POINTS',
    pattern: ARRAY_ELEMENT,
  },

  // ConnectorConfigModal - the two types whose add dialog is their own (a web page, a network
  // share) - keeps its field labels in a const map. NOT `placeholder`, which holds format examples,
  // and NOT `key`, an identifier. Every other type's labels are the connector catalog's (above).
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/indexing/components/ConnectorConfigModal.tsx',
    block: 'FIELDS',
    pattern: prop('label'),
  },
  // The extension-group headings on the file-type picker.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/indexing/components/FileTypeFilter.tsx',
    block: 'GROUP_LABELS',
    pattern: /:\s*'((?:[^'\\\n]|\\.)*)'/g,
  },
  // What one tracked item's indexing status says, behind `t(STATUS_LABELS[item.status])`.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/indexing/components/ConnectorDetailModal.tsx',
    block: 'STATUS_LABELS',
    pattern: /:\s*'((?:[^'\\\n]|\\.)*)'/g,
  },
  // The four connector-operation phases behind `t(PHASE_LABELS[phase])`.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/store/connectorOperationStore.ts',
    block: 'PHASE_LABELS',
    pattern: /:\s*'((?:[^'\\\n]|\\.)*)'/g,
  },

  // The Sources page's add-source menu, group headings and filter tabs are the connector catalog's
  // words since the connector framework - covered by the connectorLabels entry at the top. Several
  // are bare product names (Bitbucket, SVN, SharePoint) that stay English; they still need a catalog
  // entry, because they DO pass through t(), and an identity entry is how the catalog records that
  // somebody looked and decided.

  // THE TOOL NARRATION. `toolActivity` renders the one line a reader watches during a turn, and
  // its wording lives in two const maps of `[running, done]` pairs which reach `t()` as variables.
  // Scoped by BLOCK rather than by property, because these are bare array literals with no
  // property name to match on - and scoping matters here: DETAIL_KEYS and NOUNS in the same file
  // are argument names and proper nouns, not prose, and a file-wide scan would report every one
  // of them as an untranslated string.
  //
  // What is deliberately NOT covered: the object half of a DERIVED phrase. derive() builds it out
  // of the tool's own camelCase name at runtime, so no catalog entry could match it - see the
  // note at the top of toolActivity.ts.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/chat/utils/toolActivity.ts',
    block: 'PHRASES',
    // \n excluded, like quotedStrings' own class: without it a match can run from a quote in one
    // line's code, across a comment, to a quote several lines later and report the whole span as
    // one string. That happened here - a comment containing an apostrophe was reported as prose.
    pattern: /'((?:[^'\\\n]|\\.)*)'/g,
  },
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/chat/utils/toolActivity.ts',
    block: 'CONJUGATIONS',
    pattern: /'((?:[^'\\\n]|\\.)*)'/g,
  },
  // The REST passthroughs' phrases (callJiraApi and its two siblings), one [method, pattern,
  // running, done, ...] tuple per endpoint. Methods and patterns are regexes and backtick strings
  // there precisely so that every single-quoted literal in the block is prose.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/chat/utils/restCallActivity.ts',
    block: 'RULES',
    pattern: /'((?:[^'\\\n]|\\.)*)'/g,
  },
  // The override map only - `humanize()` derives everything else from the backend's tool name.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/chat/utils/toolDisplayName.ts',
    block: 'DISPLAY_NAME_OVERRIDES',
    pattern: /:\s*'((?:[^'\\]|\\.)*)'/g,
  },
  // The three spinner labels behind `aria-label={t(IN_FLIGHT_LABEL[decision])}`.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/chat/components/ToolApprovalPrompt.tsx',
    block: 'IN_FLIGHT_LABEL',
    pattern: /:\s*'((?:[^'\\]|\\.)*)'/g,
  },

  // THE DOCUMENT TITLES, which are the hardest strings in the app to notice. `usePageTitle` calls
  // `t` on what it is given, so every one of these is a variable by the time it reaches the
  // runtime - and a title lives in the HEAD, where nothing on the page displays it. The landing
  // site shipped an English `<title>` on a finished Vietnamese page for exactly this reason, and
  // a browser was the only thing that found it. Without these entries the seven are in NO report:
  // not orphaned, because the English exists in the source, and not untranslated, because no
  // literal reaches `t(`.
  //
  // A fresh RegExp per entry rather than one shared object - `matchAll` does not mutate the
  // original's `lastIndex`, but a global regex passed around is one edit away from something that
  // does, and that failure is silent.
  ...[
    'ee/frontend/src/features/admin/pages/AdminPage.tsx',
    'features/auth/pages/ForgotPasswordPage.tsx',
    'features/auth/pages/LoginPage.tsx',
    'features/auth/pages/ResetPasswordPage.tsx',
    'features/auth/pages/SetupPage.tsx',
    'features/chat/pages/ChatPage.tsx',
    'features/sources/pages/SourcesPage.tsx',
  ].map((f) => ({
    app: 'apps/app',
    file: f.startsWith('ee/') ? f : `apps/app/frontend/src/${f}`,
    pattern: /usePageTitle\(\s*'((?:[^'\\]|\\.)*)'/g,
  })),

  // THE PAGE TITLES, THE META DESCRIPTIONS AND THE HERO'S WORDS (site-copy.ts) - constants that
  // reach `t` as variables in the pages and layouts, and mostly live in the HEAD, where nothing on
  // the page shows them. Every `*_TITLE` and `*_DESCRIPTION` a page adds is covered by name. NOT
  // LICENSE_NAME: an SPDX identifier, shown as written in every language (LicenseLink).
  {
    app: 'apps/landing',
    file: 'apps/landing/src/lib/data/site-copy.ts',
    pattern: /export const (?:TITLE|DESCRIPTION|[A-Z]+_TITLE|[A-Z]+_DESCRIPTION|HERO_HEADLINE|HERO_SENTENCE) = '((?:[^'\\]|\\.)*)'/g,
  },

  // THE SITE'S DATA FILES (src/lib/data), each naming the properties that are PROSE. A product's own
  // name, an id, a path or a URL is never listed - connectors.ts keeps product names under `name`
  // for exactly that reason.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/lib/data/connectors.ts',
    pattern: prop('label', 'indexed', 'live', 'writes', 'note'),
  },
  {
    app: 'apps/landing',
    file: 'apps/landing/src/lib/data/security.ts',
    pattern: prop('destination', 'local', 'hosted', 'title'),
  },
  // STAYS and each edition's `items` are arrays of bare strings.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/lib/data/security.ts',
    pattern: ARRAY_ELEMENT,
  },
  // NOT `name`: Community and Enterprise are tier names and stay English in every language. A
  // comparison cell that is a string (`community`, `enterprise`) is translated; a boolean is a mark.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/lib/data/pricing.ts',
    pattern: prop('audience', 'price', 'priceNote', 'cta', 'terms', 'heading', 'label', 'community', 'enterprise', 'marker', 'title', 'body'),
  },
  // The plans' feature lists are arrays of bare strings.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/lib/data/pricing.ts',
    pattern: ARRAY_ELEMENT,
  },
  {
    app: 'apps/landing',
    file: 'apps/landing/src/lib/data/faq.ts',
    pattern: prop('q', 'a', 'label'),
  },
  // The /docs index. NOT `slug` or `file`: a route segment and a filename, English always. The
  // DOCUMENTS behind these entries are translated files, not catalog entries.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/lib/data/docs.ts',
    pattern: prop('title', 'summary'),
  },
  // The five legal page titles. The DOCUMENTS are fragments under src/content/legal/.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/lib/data/legal.ts',
    pattern: prop('title'),
  },

  // THE SECTIONS AND SCENES, each scoped to the const that holds prose: these files hold replica
  // data (issue keys, file names, figures) beside it, under other names or in other consts.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/sections/ConnectionsDiagram.tsx',
    block: 'CLIENTS',
    pattern: prop('name', 'via'),
  },
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/sections/ConnectionsDiagram.tsx',
    block: 'GROUPS',
    pattern: prop('title'),
  },
  // The story's search windows. `file`, `path`, `key`, `query` and `app` are file names, folder
  // paths, issue keys, queries and product names, and stay as written.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/sections/StorySection.tsx',
    pattern: prop('title', 'meta', 'where', 'unit', 'label'),
  },
  // The hero's chat turn: its question, greeting and answer are top-level consts passed on as
  // variables. Its steps are literal t() calls and need no entry.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/sections/HeroDemo.tsx',
    pattern: /^const (?:QUESTION|GREETING|ANSWER) = '((?:[^'\\]|\\.)*)'/gm,
  },
  // The scenes (src/components/scenes): the sentences a turn says, as top-level consts, and the
  // rows a log or a thread draws from a const array.
  ...['AnswerStill', 'ApprovalScene', 'GuardrailCatchScene', 'ProvidersScene'].map((scene) => ({
    app: 'apps/landing',
    file: `apps/landing/src/components/scenes/${scene}.tsx`,
    pattern: /^const (?:QUESTION|ANSWER|COMMENT|DONE_LINE|PROVIDERS_DESCRIPTION) = (?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")/gm,
  })),
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/scenes/GuardrailCatchScene.tsx',
    pattern: prop('running', 'done', 'label', 'error'),
  },
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/scenes/GuardrailActivityScene.tsx',
    block: 'FIRED',
    pattern: prop('name'),
  },
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/scenes/GuardrailActivityScene.tsx',
    block: 'RECENT',
    pattern: prop('check', 'fired'),
  },
  // `role` is a role in words ("Super admin"), as the product's Role column writes it; `sub` the
  // line under a figure ("1,284 calls").
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/scenes/AdminScene.tsx',
    block: 'ADMIN_VIEWS',
    pattern: prop('label', 'role', 'sub'),
  },
  // A source's Last sync and Status columns.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/scenes/SourcesScene.tsx',
    pattern: prop('synced', 'busy'),
  },
  // The log rows: a tool's display name, its arguments as the log words them, how it was approved
  // (the server's own sentence), and a model call's service.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/scenes/ApprovalScene.tsx',
    block: 'LOG_ROWS',
    pattern: prop('tool', 'args', 'approval'),
  },
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/scenes/ReceiptScene.tsx',
    block: 'TOOL_ROWS',
    pattern: prop('tool', 'args', 'approval'),
  },
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/scenes/ReceiptScene.tsx',
    block: 'CALLS',
    pattern: prop('service'),
  },
  // The application replicas' own lists (AppScreens.tsx), each scoped to its array: the file also
  // holds identifiers and class strings under other names.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/ui/AppScreens.tsx',
    block: 'START_LINKS',
    pattern: prop('label'),
  },
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/ui/AppScreens.tsx',
    block: 'SOURCE_TYPES',
    pattern: prop('group', 'chip'),
  },
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/ui/AppScreens.tsx',
    block: 'ADMIN_TABS',
    pattern: prop('label'),
  },
  // Settings > Logs: the tab row and the outcome words.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/ui/AppScreens.tsx',
    block: 'AUDIT_TABS',
    pattern: prop('label'),
  },
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/ui/AppScreens.tsx',
    block: 'OUTCOME',
    pattern: prop('label'),
  },
  // The settings rail's groups, each entry's `label`.
  ...['WORKSPACE_GROUP', 'AI_GROUP', 'APPLICATION_GROUP'].map((block) => ({
    app: 'apps/landing',
    file: 'apps/landing/src/components/ui/AppScreens.tsx',
    block,
    pattern: prop('label'),
  })),
  // The frame's own nav, a single-line const array. Non-global on purpose: the shared branch pulls
  // every quoted string out of group 1.
  {
    app: 'apps/landing',
    file: 'apps/landing/src/components/ui/AppFrame.tsx',
    pattern: /const NAV_ITEMS = \[([^\]]+)\]/,
  },

  // QUOTA_MILESTONE_QUESTIONS - five survey rows, rendered through `t(label)` by BOTH the
  // milestone modal and the admin's feedback detail view. `id` is the key the backend stores the
  // rating under and never reaches a screen.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/feedback/types/feedback.types.ts',
    block: 'QUOTA_MILESTONE_QUESTIONS',
    pattern: prop('label'),
  },

  // REGISTER_RULES / SETUP_RULES - the password checklist, rendered through `t(r.label)`. Two
  // arrays that differ in one rule, so the file is scanned whole rather than scoped to a block.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/auth/hooks/usePasswordValidation.ts',
    pattern: prop('label'),
  },

  // The product tour - a welcome and four stops since 2026-10-06, each looked up at the RENDER
  // through `t()` in TourOverlay, so none of them is a `t('...')` literal anywhere. The prose is
  // `title`, `text`, `sandboxNote`, the sandbox action's `label` and the `points` arrays; `id`,
  // `route`, `targetId`, `targetSelector` and `openSettingsTab` are identifiers and are not listed.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/tour/data/tourSteps.ts',
    pattern: prop('text'),
  },
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/tour/data/tourSteps.ts',
    pattern: prop('title'),
  },
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/tour/data/tourSteps.ts',
    pattern: prop('sandboxNote'),
  },
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/tour/data/tourSteps.ts',
    pattern: prop('label'),
  },
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/tour/data/tourSteps.ts',
    pattern: ARRAY_ELEMENT,
  },

  // WHATS_NEW - one summary and a few bullets per version, drawn by WhatsNewModal through
  // `t(entry.summary)` and `t(line)`. The bullets are bare quoted lines in an array, so they need
  // the ARRAY_ELEMENT shape rather than a property name.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/system/data/whatsNew.ts',
    pattern: prop('summary'),
  },
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/system/data/whatsNew.ts',
    pattern: ARRAY_ELEMENT,
  },

  // ---------------------------------------------------------------------------------------------
  // Option lists and label maps drawn through `t(x.label)`.
  //
  // ADDED 2026-09-05, and the reason is worth keeping: every one of these was a string a reader
  // sees that appeared in NEITHER report. Not orphaned, because `quotedStrings` finds the text in
  // the source; not untranslated, because `asked` holds only `t('...')` literals. A whole Select
  // full of English can sit behind a run that says "all in use".
  //
  // EACH ONE IS SCOPED TO THE BLOCK IT MEANS, never to a whole file. `SettingsModal` alone holds
  // both prose labels and PROVIDER_PRESETS - Anthropic, Ollama, Azure OpenAI - which are vendor
  // names and are deliberately not translated. A file-wide `prop('label')` would list those as
  // untranslated prose, which is the crying-wolf this scoping exists to prevent.
  // ---------------------------------------------------------------------------------------------
  ...[
    ['features/settings/pages/UpdatesPage.tsx', 'FREQUENCIES', prop('label')],
    ['ee/frontend/src/features/admin/components/UserDetailModal.tsx', 'ROLE_OPTIONS', prop('label')],
    ['features/indexing/components/ConnectorConfigModal.tsx', 'FIELDS', prop('label')],
    ['features/settings/tabs/AgentsTab.tsx', 'TONES', prop('label')],
    ['features/settings/pages/ConfigTransferPage.tsx', 'TRANSFER_GROUPS', prop('label')],
    ['features/settings/tabs/AgentsTab.tsx', 'PROMPT_CACHE_OPTIONS', prop('label')],
    ['features/settings/tabs/GuardrailsTab.tsx', 'GUARDRAIL_MODES', prop('label')],
    ['features/settings/tabs/GuardrailsTab.tsx', 'GUARDRAIL_ACTIONS', prop('label')],
    ['features/settings/tabs/GuardrailsTab.tsx', 'GUARDRAIL_RETRIEVAL_ACTIONS', prop('label')],
    ['features/settings/tabs/ConversionTab.tsx', 'CONVERSION_METHOD_OPTIONS', prop('label')],
    ['features/settings/tabs/ConversionTab.tsx', 'OCR_ENGINE_OPTIONS', prop('label')],
    ['features/settings/tabs/NetworkTab.tsx', 'PROXY_MODES', prop('label')],
    ['features/settings/tabs/SearchTab.tsx', 'WEB_SEARCH_GROUP', prop('label', 'description')],
  ].map(([file, block, pattern]) => ({
    app: 'apps/app',
    file: file.startsWith('ee/') ? file : `apps/app/frontend/src/${file}`,
    block,
    pattern,
  })),

  // AdminPage's own stat tiles and storage breakdown - two local arrays inside a component, so
  // `blockOf` (which looks for a top-level const) cannot scope them. `label` is the only prose
  // property in either; `value` is a formatted number.
  {
    app: 'apps/app',
    file: 'ee/frontend/src/features/admin/pages/AdminPage.tsx',
    pattern: prop('label'),
  },
  // The same two arrays, in the Overview tab's HealthSection since 2026-09-29.
  {
    app: 'apps/app',
    file: 'ee/frontend/src/features/admin/components/OverviewTab.tsx',
    pattern: prop('label'),
  },
  // UserDetailModal's `fields` array, likewise local to the component.
  {
    app: 'apps/app',
    file: 'ee/frontend/src/features/admin/components/UserDetailModal.tsx',
    pattern: prop('label'),
  },
  // ProviderModelRow's copy, which arrives as JSX attributes at its call sites - one file per
  // Settings tab since 2026-10-14 - and is looked up inside the component; and PolicyDocumentsSection's
  // `emptyText`, the same shape. `modelHint` arrives translated.
  ...['AgentsTab', 'GuardrailsTab', 'ConversionTab', 'SearchTab'].map((tab) => ({
    app: 'apps/app',
    file: `apps/app/frontend/src/features/settings/tabs/${tab}.tsx`,
    pattern: jsxProp('providerLabel', 'modelLabel', 'emptyText'),
  })),
  // INTERRUPTED - the one tool-result error this client writes rather than receives. ToolCallList
  // compares against the exported constant and translates it, so it never appears as a literal.
  {
    app: 'apps/app',
    file: 'apps/app/frontend/src/features/chat/utils/settleUnfinishedToolCalls.ts',
    // GLOBAL, and that flag is load-bearing: the non-global branch below treats group 1 as a
    // REGION and re-scans it for quoted strings, so a pattern already capturing the bare string
    // yields nothing at all. Found by the break test, which is what it is for.
    pattern: /^export const INTERRUPTED = '((?:[^'\\]|\\.)*)'/gm,
  },

  // -- apps/license -------------------------------------------------------------------------
  // Added 2026-09-12 with this console's first catalog. Everything below reaches `t()` as a
  // VARIABLE, so without these entries each string is in NEITHER report: not orphaned, because
  // the English is in the source; not untranslated, because no literal asked for it.

  // RULES - the password checklist, rendered through `t(r.label)` in PasswordChecklist. Same
  // shape as the app's own usePasswordValidation entry above, and the same reason: a hook cannot
  // call useTranslation for its caller, so the labels stay data and are looked up at the render.
  // `id` is a React key and `test` is a predicate; `label` is the only prose here.
  {
    app: 'apps/license',
    file: 'apps/license/frontend/src/features/auth/usePasswordValidation.ts',
    pattern: prop('label'),
  },

  // SECTIONS - the settings rail's one section. Scoped to the block rather than the file, because
  // the same file also carries `label={t('...')}` JSX attributes that `literals()` already sees,
  // and an unscoped scan would be wrong the moment a PROVIDER_PRESETS-style const arrives - which
  // is exactly what the app's copy of this file grew.
  {
    app: 'apps/license',
    file: 'apps/license/frontend/src/features/settings/SettingsModal.tsx',
    block: 'SECTIONS',
    pattern: prop('label'),
  },

  // STAT_CONFIG and COLUMNS - the four filter cards and the five table headers, both rendered
  // through `t(c.label)`. NOT scoped to a block, because there are two of them and they hold the
  // only `label:` properties in the file: `key`, `filter` and `className` are identifiers and a
  // Tailwind class, and `featured` is a boolean.
  {
    app: 'apps/license',
    file: 'apps/license/frontend/src/features/licenses/LicensesPage.tsx',
    pattern: prop('label'),
  },

  // -- apps/telemetry -----------------------------------------------------------------------
  // Added 2026-09-12 alongside apps/license. Same shape, same reason: every string below reaches
  // t() as a VARIABLE and would otherwise be in neither report.

  // RULES - the password checklist. The file is BYTE-IDENTICAL to the licensing console's, which
  // is why both entries exist rather than one: the checker resolves a file per app.
  {
    app: 'apps/telemetry',
    file: 'apps/telemetry/frontend/src/features/auth/usePasswordValidation.ts',
    pattern: prop('label'),
  },

  // NAV - Diagnostics and Feedback, declared once in AppShell and rendered TWICE: as header words
  // above sm, and as UserMenu's rows below it. One entry covers both, which is the payoff of
  // AppShell declaring the routes once instead of the two lists it used to carry. `to` is a
  // route and `icon` a lucide component; `label` is the only prose.
  {
    app: 'apps/telemetry',
    file: 'apps/telemetry/frontend/src/components/layout/AppShell.tsx',
    pattern: prop('label'),
  },

  // FEEDBACK_STATS and FEEDBACK_COLUMNS - the four mood cards and the six table headers.
  //
  // FEEDBACK_COLUMNS' LAST LABEL IS THE EMPTY STRING, deliberately: that column holds the resolved
  // check and the paperclip rather than a heading. `prop()` matches it, so '' would be reported as
  // an untranslated string and would need a meaningless catalog entry. The render guards it with
  // `c.label ? t(c.label) : null`, and the empty key is filtered here rather than in the catalog,
  // because a catalog entry for '' is the kind of thing somebody deletes as obviously wrong.
  {
    app: 'apps/telemetry',
    file: 'apps/telemetry/frontend/src/features/feedback/FeedbackPage.tsx',
    pattern: prop('label'),
    dropEmpty: true,
  },

  // The daily diagnostics pages (split 2026-10-15 into a frame, a summary and four detail views):
  // TABS in the frame, WINDOWS in the shared module, ENVIRONMENT_DIMENSIONS on Installations and a
  // column list on each table. `key` and `value` are identifiers; `label` is the only prose.
  {
    app: 'apps/telemetry',
    file: 'apps/telemetry/frontend/src/features/diagnostics/DiagnosticsPage.tsx',
    pattern: prop('label'),
  },
  {
    app: 'apps/telemetry',
    file: 'apps/telemetry/frontend/src/features/diagnostics/diagnostics.ts',
    pattern: prop('label'),
  },
  {
    app: 'apps/telemetry',
    file: 'apps/telemetry/frontend/src/features/diagnostics/InstallationsPage.tsx',
    pattern: prop('label'),
  },
  {
    app: 'apps/telemetry',
    file: 'apps/telemetry/frontend/src/features/diagnostics/ErrorsPage.tsx',
    pattern: prop('label'),
  },
  {
    app: 'apps/telemetry',
    file: 'apps/telemetry/frontend/src/features/diagnostics/TimingsPage.tsx',
    pattern: prop('label'),
  },
  {
    app: 'apps/telemetry',
    file: 'apps/telemetry/frontend/src/features/diagnostics/CountersPage.tsx',
    pattern: prop('label'),
  },

  // SECTIONS - the settings rail's one section.
  {
    app: 'apps/telemetry',
    file: 'apps/telemetry/frontend/src/features/settings/SettingsModal.tsx',
    block: 'SECTIONS',
    pattern: prop('label'),
  },
]

/**
 * How many strings each WARN block prints before it stops.
 *
 * Fifteen is right for CI, where the point is to say that something is wrong and roughly what -
 * a wall of two hundred strings in a log nobody reads is worse than a sample. It is wrong while
 * actually translating a screen, where the list IS the worklist and a truncated one means
 * guessing at the rest. `I18N_LIST_LIMIT=0` prints everything.
 */
const LIST_LIMIT = process.env.I18N_LIST_LIMIT === undefined
  ? 15
  : (Number(process.env.I18N_LIST_LIMIT) || Infinity)

const SOURCE_EXT = /\.(ts|tsx)$/
const SKIP_DIR = new Set(['node_modules', 'dist', '.next', 'kit', 'catalogs'])

async function walk(dir) {
  const out = []
  let entries
  try {
    entries = await readdir(dir, { withFileTypes: true })
  } catch {
    return out
  }
  for (const e of entries) {
    if (e.isDirectory()) {
      if (!SKIP_DIR.has(e.name)) out.push(...(await walk(join(dir, e.name))))
    } else if (SOURCE_EXT.test(e.name) && !e.name.endsWith('.test.ts')) {
      out.push(join(dir, e.name))
    }
  }
  return out
}

/**
 * Every quoted string in a file, whatever it is doing there.
 *
 * Used for the ORPHAN direction only. A translatable string very often reaches `t()` as a
 * variable rather than a literal - `t(label)` over a data array of connector names, `t(tab)` over
 * a TabKey - so scanning for `t('...')` alone reported thirty perfectly good entries as orphaned
 * the first time this ran.
 *
 * The invariant that actually matters is looser and easier to check: the English text must EXIST
 * somewhere in the source. That is what makes a reworded string detectable, which is the one real
 * hazard of using source text as a key. It counts a string in a comment as present, which is a
 * weaker check than matching call sites - and a great deal better than a check that cries wolf.
 *
 * The quantifier is + rather than {2,}, and that matters more than it looks: requiring two
 * characters makes a single-character string like '0' fail to match, so the scanner pairs ITS
 * closing quote with the next string's opening one and runs out of phase for the rest of the line.
 * That silently swallowed the two entries after it and reported them as orphaned.
 */
function quotedStrings(text) {
  const found = new Set()
  for (const m of text.matchAll(/'((?:[^'\\\n]|\\.)+)'/g)) found.add(m[1].replace(/\\'/g, "'"))
  for (const m of text.matchAll(/"((?:[^"\\\n]|\\.)+)"/g)) found.add(m[1].replace(/\\"/g, '"'))
  return found
}

/** Every `t('...')` / `t("...")` literal. Used for the UNTRANSLATED direction, where precision pays. */
function literals(text) {
  const found = new Set()
  for (const m of text.matchAll(/\bt\(\s*'((?:[^'\\]|\\.)*)'/g)) found.add(m[1].replace(/\\'/g, "'"))
  for (const m of text.matchAll(/\bt\(\s*"((?:[^"\\]|\\.)*)"/g)) found.add(m[1].replace(/\\"/g, '"'))
  return found
}

/**
 * Reads a mapping without importing it - it is a TS module and this script has no loader.
 *
 * THE CHARACTER CLASS IS RELATIVE TO THE DELIMITER, not a fixed list of both quotes, and that is
 * the third time this checker has been wrong in the same direction. An entry whose English
 * contains an apostrophe is written in double quotes - "Your team's Jira, Confluence and
 * Bitbucket too." - and a class of [^'"\\] cannot match its own contents, so the line silently
 * failed to parse and the entry did not exist as far as this script was concerned. Two of the
 * landing catalog's entries were invisible that way, and the report said "196 strings, all in
 * use" for a file holding 198.
 *
 * That is the failure mode this checker exists to prevent: an entry it cannot see is an entry it
 * cannot report as orphaned, so rewording that English string would revert it to English with
 * nothing said. (?!\1) excludes only the quote that opened the string; \n stays excluded so a
 * runaway match cannot swallow the rest of the file the way an unterminated one would.
 */
/**
 * Reads a catalog, and reports a key written twice.
 *
 * A DUPLICATE IS A DEAD TRANSLATION, and it was invisible here. This parses a catalog as TEXT
 * rather than importing it, so both entries matched and the later simply overwrote the earlier -
 * exactly as the object literal itself would at runtime. Nothing reported it, and which of the
 * two translations a reader gets is decided by file order rather than by anyone.
 *
 * That is not hypothetical: 'End User License Agreement' was written twice on 2026-09-05 with two
 * different capitalisations, and the entry a reader would never see was the one somebody had
 * chosen deliberately. This says so instead.
 *
 * The reported COUNT was never wrong, which is worth stating because the first version of this
 * comment claimed it was: `Object.keys` deduplicates, so the total has always been the number of
 * distinct keys. `tsc` also flags a duplicate, since a catalog is an object literal - so this is
 * a second net rather than the only one, and it is the one that runs when the checker runs alone.
 */
/** Undoes the ONE escape a quoted literal needs: its own delimiter. */
function unquote(text, delimiter) {
  return text.split('\\' + delimiter).join(delimiter)
}

async function readCatalog(file) {
  const src = await readFile(file, 'utf8')
  const out = {}
  const duplicates = []
  // 'English text': 'translated text',   with either quote style on either side, and either one
  // free to contain the other. The key and the value may sit on separate lines - \s spans the
  // newline between them - which is how the longer entries are written.
  for (const m of src.matchAll(/^\s*(['"])((?:(?!\1)[^\\\n]|\\.)*)\1\s*:\s*(['"])((?:(?!\3)[^\\\n]|\\.)*)\3\s*,?\s*$/gm)) {
    // UNESCAPED, exactly as `literals()` unescapes the call site. Without this the two sides
    // disagree the moment a string needs an escape at all: the source `t("... \"+\" ...")`
    // yields a key containing a real quote, while the catalog's `"... \"+\" ..."` yielded one
    // containing a backslash - so the entry was reported as orphaned AND the string as
    // untranslated, forever, with no way to write a catalog line that satisfied both. Found
    // writing the OCR language hint, which contains an apostrophe and a quoted "+".
    const key = unquote(m[2], m[1])
    if (Object.hasOwn(out, key)) duplicates.push(key)
    out[key] = unquote(m[4], m[3])
  }
  return { catalog: out, duplicates }
}

let warnings = 0
// Counted separately from `warnings` because they carry different verdicts - see the exit block.
let fatal = 0
let untranslatedTotal = 0
const STRICT = process.env.I18N_STRICT === '1'
let checked = 0

for (const app of APPS) {
  // locale -> every catalog file of that locale, across the app's catalog directories.
  const byLocale = new Map()
  for (const dir of app.catalogs) {
    let names
    try {
      names = (await readdir(join(WORKSPACE, dir))).filter((f) => f.endsWith('.ts'))
    } catch {
      continue
    }
    for (const name of names) {
      const locale = name.replace(/\.ts$/, '')
      if (!byLocale.has(locale)) byLocale.set(locale, [])
      byLocale.get(locale).push(join(WORKSPACE, dir, name))
    }
  }
  if (!byLocale.size) {
    console.log(`  skip ${app.name} - no mapping yet`)
    continue
  }

  // Two sets, because the two directions want different precision - see quotedStrings().
  const asked = new Set()   // t('...') literals: what the code explicitly asks to translate
  const present = new Set() // every quoted string: what exists in the code at all
  for (const f of (await Promise.all(app.src.map((dir) => walk(join(WORKSPACE, dir))))).flat()) {
    const text = await readFile(f, 'utf8')
    for (const s of literals(text)) { asked.add(s); present.add(s) }
    for (const s of quotedStrings(text)) present.add(s)
  }
  for (const d of DYNAMIC.filter((d) => d.app === app.name)) {
    let text
    try {
      text = await readFile(join(WORKSPACE, d.file), 'utf8')
    } catch {
      continue
    }
    // A renamed const would otherwise narrow the scan to nothing and report nothing, which is the
    // silent-green failure this whole script keeps having. Say so instead.
    if (d.block) {
      text = blockOf(text, d.block)
      if (!text) {
        console.warn(`  WARN ${app.name} - ${d.file} has no const ${d.block}; nothing was scanned for it`)
        warnings++
      }
    }
    if (d.pattern.global) {
      // The FIRST DEFINED capture group carries the string. `prop()` alternates over two quote
      // styles and `jsxProp()` over three attribute forms, so all but one group is undefined on
      // every match - taking `m[1] ?? m[2]` alone silently dropped every jsxProp third-form hit.
      for (const m of text.matchAll(d.pattern)) {
        // An `inner` pattern means the outer one captured a REGION rather than one string - an
        // inline array's body, say - so the strings come from a second pass over it.
        if (d.inner) {
          for (const im of (m[1] ?? '').matchAll(d.inner)) {
            const s = im.slice(1).find((g) => g !== undefined)
            if (s !== undefined) { asked.add(s); present.add(s) }
          }
          continue
        }
        const lit = m.slice(1).find((g) => g !== undefined)
        if (lit === undefined) continue
        // `dropEmpty` is for a const array with a deliberately BLANK prose property - a table
        // column that holds icons rather than a heading. `prop()` matches `label: ''` like any
        // other, so without this the empty string is reported as needing a translation and the
        // only way to silence it is a catalog entry for '', which reads as a mistake and gets
        // deleted. Opt-in per entry, never global: a blank label is usually worth reporting.
        if (d.dropEmpty && lit === '') continue
        asked.add(lit)
        present.add(lit)
      }
    } else {
      const m = text.match(d.pattern)
      if (m) for (const lit of m[1].matchAll(/'([^']+)'/g)) { asked.add(lit[1]); present.add(lit[1]) }
    }
  }

  checked++
  for (const [locale, paths] of byLocale) {
    // One catalog per locale, read from each tree's file in turn. A key in two of them is a
    // duplicate like a key written twice in one: only one translation can ever render.
    const catalog = {}
    const duplicates = []
    for (const path of paths) {
      const part = await readCatalog(path)
      duplicates.push(...part.duplicates)
      for (const [key, value] of Object.entries(part.catalog)) {
        if (Object.hasOwn(catalog, key)) duplicates.push(key)
        catalog[key] = value
      }
    }
    const keys = Object.keys(catalog)

    const untranslated = [...asked].filter((s) => !(s in catalog)).sort()
    const orphaned = keys.filter((k) => !present.has(k)).sort()

    // Reported FIRST, and before the early return, because a duplicate makes every other number
    // on this line suspect - and because one of the two translations is dead code that nothing
    // will ever render.
    if (duplicates.length) {
      console.warn(`  WARN ${app.name}/${locale} - ${duplicates.length} key(s) written twice. The later entry wins and the earlier one is dead:`)
      for (const s of duplicates.slice(0, LIST_LIMIT)) console.warn(`         ${JSON.stringify(s)}`)
      warnings++
      fatal += duplicates.length
    }

    if (!untranslated.length && !orphaned.length && !duplicates.length) {
      console.log(`  ok   ${app.name}/${locale} - ${keys.length} strings, all in use`)
      continue
    }
    if (untranslated.length) {
      console.warn(`  WARN ${app.name}/${locale} - ${untranslated.length} English string(s) with no translation:`)
      for (const s of untranslated.slice(0, LIST_LIMIT)) console.warn(`         ${JSON.stringify(s)}`)
      warnings++
      untranslatedTotal += untranslated.length
    }
    if (orphaned.length) {
      console.warn(`  WARN ${app.name}/${locale} - ${orphaned.length} entr(ies) matching nothing in the source.`)
      console.warn('         Usually a reworded English string, which has silently reverted to English:')
      for (const s of orphaned.slice(0, LIST_LIMIT)) console.warn(`         ${JSON.stringify(s)}`)
      warnings++
      fatal += orphaned.length
    }
  }
}

if (!checked) {
  console.log('i18n: no mappings found')
} else {
  console.log(`\ni18n: ${checked} app(s) checked${warnings ? `, ${warnings} thing(s) to look at` : ''}`)
}

/**
 * THE EXIT CODE, which until now did not exist.
 *
 * `ci-app.yml` said "a key that exists only in a translation always fails" and
 * `.claude/rules/frontend.md` said `I18N_STRICT=1` turns the missing-translation warning into a
 * failure. Neither was true: this script printed warnings and exited 0 every time, so the CI step
 * could not fail for any reason and the gate everyone believed in gated nothing.
 *
 * That is this project's own most expensive shape - a documented property with nothing enforcing
 * it - and it was sitting in the one script the i18n work leans on. Now:
 *
 *   orphaned    ALWAYS fatal. Nothing looks the entry up, and it survives every rename of the
 *               English string it was written for, so it is dead weight that reads as coverage.
 *   duplicate   ALWAYS fatal. One of the two translations can never be rendered, and which one
 *               a reader gets is decided by file order.
 *   untranslated  a warning, so an English string can still be added without its translation in
 *               the same commit - unless I18N_STRICT=1, which is the switch to throw once a
 *               language is complete.
 */
if (fatal > 0) {
  console.error(`\ni18n: FAILED - ${fatal} orphaned or duplicated entr(ies)`)
  process.exit(1)
}
if (STRICT && untranslatedTotal > 0) {
  console.error(`\ni18n: FAILED - ${untranslatedTotal} untranslated string(s) and I18N_STRICT is set`)
  process.exit(1)
}
