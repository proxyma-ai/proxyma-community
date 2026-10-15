/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/react/BrandMark.tsx
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

import type { ComponentType } from 'react'
import { cn } from './cn'

/**
 * ONE MARK SYSTEM for everything a Proxyma surface draws beside a source, a connection or a
 * client: every source type, every AI tool, you and your scripts. Moved here from the landing
 * site on 2026-09-30 so the product and the site draw the same marks from one file. The rule it
 * carries (maintainer's, from the 2026-09 identity pass): if Jira has a mark, EVERY source shown in
 * that place has one, and every one is drawn the same way.
 *
 * THE TREATMENT, and it is the whole point of the file:
 *   - a filled silhouette on a 24-unit grid, no strokes, so a vendor's mark and a generic glyph
 *     carry the same visual weight
 *   - ONE ink: `currentColor`, set by the caller to the same token for every mark in a row. No
 *     vendor colors - ten brand colors in a row would be louder than anything else on the page,
 *     and a row of mixed colored logos and grey glyphs reads as two families. Light and dark both
 *     work for free, because the caller's semantic ink re-maps per mode
 *   - one size per context, passed as `size`: 16 in rows and running text, 14 in chips and tabs
 *   - DECORATIVE: always aria-hidden. The name is always written beside the mark, so a screen
 *     reader announcing it would only repeat the word next to it
 *
 * NOT lucide, and that is why these are inline paths while every other UI icon is lucide. A lucide
 * glyph is an outline standing for an idea; a vendor mark stands for the vendor. The generic glyphs
 * below are drawn as filled silhouettes precisely so they can stand in the same row.
 *
 * SOURCES AND TERMS, one per mark. Marks remain trademarks of their owners and are used only
 * nominatively - to say what Proxyma connects to or is reached from, always with the product's
 * name written beside the mark. The landing site's footer carries the trademark notice.
 *
 *   jira, confluence, bitbucket
 *       Simple Icons 16.33.0 (https://simpleicons.org), paths unaltered. The collection is CC0-1.0.
 *       Atlassian's logo guidelines (https://atlassian.design/foundations/logos) allow the
 *       single-color form used here.
 *   github
 *       Simple Icons 16.33.0 slug `github`, CC0-1.0, path unaltered. GitHub's logo guidelines
 *       (https://github.com/logos) allow the mark to refer to GitHub, unaltered and in one color,
 *       which is how it is used: beside the word GitHub, for the GitHub connector. Trademark of
 *       GitHub, Inc.
 *   subversion
 *       Simple Icons 16.33.0, CC0-1.0. Mark of The Apache Software Foundation
 *       (https://subversion.apache.org/logo).
 *   copilot
 *       Simple Icons 16.33.0 slug `githubcopilot`, whose source is GitHub's Primer Octicons
 *       (copilot-24, https://primer.style/foundations/icons) under the MIT License - the one mark
 *       here that is MIT rather than CC0. Copyright (c) GitHub Inc.; MIT permits this use with the
 *       notice, which is this comment. GitHub Copilot is a trademark of GitHub, Inc.
 *   claudecode
 *       Simple Icons 16.33.0 slug `claudecode`, CC0-1.0 (source https://code.claude.com). Claude and
 *       Claude Code are trademarks of Anthropic, PBC.
 *   claude
 *       Simple Icons 16.33.0 slug `claude`, CC0-1.0 (source https://claude.ai), path unaltered.
 *       Added 2026-09-30 for the app's Claude Desktop tab. Trademark of Anthropic, PBC.
 *   cursor
 *       Simple Icons 16.33.0, CC0-1.0 (source https://cursor.com/brand). Trademark of Anysphere.
 *
 *   sharepoint, onedrive, teams, outlook, microsoft365 - NO MICROSOFT MARK, deliberately, and this was
 *       checked rather than assumed (2026-09-30). Simple Icons carries no Microsoft product icons.
 *       Microsoft publishes its Microsoft 365 icon set with these terms: "Microsoft permits the use
 *       of these icons in architectural diagrams, training materials, or documentation. You may
 *       copy, distribute, and display the icons only for the permitted use unless granted explicit
 *       permission by Microsoft." (learn.microsoft.com, "Microsoft 365 architecture diagram
 *       templates and icons", now archived). A product's own interface and its marketing page are
 *       none of the three, so the product icons are not used. These are GENERIC glyphs drawn for
 *       Proxyma - a document library, a page in a cloud, a conversation, a mail, a cloud - in the same filled style,
 *       with the product's name always beside them.
 *   chatgpt - NO OPENAI MARK. Simple Icons 16.33.0 carries no OpenAI icon (it was removed from the
 *       collection), and OpenAI's brand guidelines grant no third party use of its logo. A generic
 *       glyph drawn for Proxyma: a speech bubble with a spark cut out of it.
 *   gemini - NO GOOGLE MARK. Simple Icons carries `googlegemini`, but CC0 covers only the path
 *       drawing, not the trademark, and Google's brand terms
 *       (https://about.google/brand-resource-center/) require Google's approval to show its logos.
 *       A generic glyph drawn for Proxyma: two sparks, the common "AI" sign - NOT the Gemini star.
 *   slack - NO SLACK MARK, checked 2026-10-14. Simple Icons carries none: it removed every Salesforce
 *       brand, Slack included, in simple-icons/simple-icons#13503, and declined to add Slack back
 *       (#14140, "We will not be adding this back in for the foreseeable"). Slack's media kit
 *       (https://slack.com/media-kit) grants use of its marks under guidelines it moved to a brand
 *       center (brand.slackhq.com) that is "under construction" and states no terms for naming an
 *       integration in another product. With no terms to point at, a generic glyph drawn for
 *       Proxyma: a speech bubble with a channel's # cut out - NOT the Slack hash logo.
 *   gitlab - NO GITLAB MARK, checked 2026-10-14. Simple Icons 16.33.0 carries `gitlab` (CC0 path,
 *       source about.gitlab.com/press/press-kit), but CC0 covers the drawing, not the trademark, and
 *       GitLab's Trademark Guidelines (handbook.gitlab.com, ".../brand-activation/trademark-guidelines/",
 *       revised 2024-07-03) say "Use of the Logos is not permitted under these Guidelines, except for
 *       the limited purpose set out in Section 3.1" - distributing unmodified GitLab CE. The NAME may
 *       say a product integrates with GitLab (section 1.1.1); the tanuki may not. A generic glyph
 *       drawn for Proxyma: a branch leaving a line and merging back - a merge request.
 *   linear - NO LINEAR MARK, checked 2026-10-14. Simple Icons 16.33.0 carries `linear` (source
 *       linear.app), but Linear's brand page (linear.app/brand) calls its graphics proprietary, says
 *       "Do not alter these files in any way" - recoloring to the row's ink would be one - and grants
 *       no use for integrations. A generic glyph drawn for Proxyma: an issue's status circle with a
 *       check cut out.
 *   google, googledrive, gmail, googlecalendar - NO GOOGLE MARK, for the reason gemini records: Google's
 *       brand terms (https://about.google/brand-resource-center/) require Google's approval to show its
 *       logos and product icons, and Simple Icons' CC0 covers a drawing, never the trademark (checked
 *       2026-10-14). Generic glyphs drawn for Proxyma, with the product's name always beside them: a
 *       cloud for the Google connection (the same as microsoft365's - a sign-in to a suite), a folder
 *       with a cloud cut out of it for Drive, the envelope outlook uses for Gmail, and a calendar
 *       page with one day picked out for Calendar. None imitates the Drive triangle, the Gmail M or the
 *       Calendar "31".
 *   notion - NO NOTION MARK, checked 2026-10-14. Simple Icons 16.33.0 carries `notion` (CC0, source
 *       https://www.notion.so), but CC0 covers the drawing, not the trademark, and Notion's trademark
 *       guidelines (notion.notion.site, "Notion Marks") allow only "the Notion Marks for which you are
 *       approved for use in the Brand Guidelines or other written agreement with Notion"; an
 *       integration may SAY it works with Notion, which the name beside the glyph does. A generic glyph
 *       drawn for Proxyma: a page with a folded corner and three lines - NOT the Notion "N" cube.
 *   airtable - NO AIRTABLE MARK, checked 2026-10-14. Simple Icons 16.33.0 carries `airtable` (CC0,
 *       source https://airtable.com/newsroom), but Airtable's Trademark Guidelines
 *       (https://www.airtable.com/company/trademark-guidelines) say its logo "should never be
 *       modified, distorted, or redrawn" and offer only its own lockups - and the one-ink treatment
 *       above recolors every mark. A generic glyph drawn for Proxyma: a table with a header row and
 *       four cells cut out - NOT the Airtable box.
 *   zendesk - NO ZENDESK MARK, checked 2026-10-14. Zendesk's Trademark Guidelines
 *       (https://www.zendesk.com/company/tmguidelines) license its marks to partners, developers and
 *       other third parties only under a written agreement, with Zendesk's approval of each use; a
 *       truthful reference to the product by NAME is allowed. With no such agreement, a generic glyph
 *       drawn for Proxyma: a support headset - NOT the Zendesk "Z" of two shapes.
 *   freshdesk - NO FRESHDESK MARK, checked 2026-10-14. Freshworks publishes no terms granting third
 *       parties use of its logos (its trademark page, https://www.freshworks.com/take-down-trademark/,
 *       covers reporting misuse only), and Freshdesk's own logo is a headset in a rounded blob - so a
 *       generic glyph drawn for Proxyma that is neither: a ticket stub with its tear line cut out.
 *   hubspot - NO HUBSPOT MARK, checked 2026-10-14. Simple Icons 16.33.0 carries `hubspot` (CC0 path),
 *       but CC0 covers the drawing, not the trademark, and HubSpot's Trademark Usage Guidelines
 *       (legal.hubspot.com/tm-usage-guidelines) allow its NAME in a referential phrase while ruling
 *       out "any HubSpot stylized logo, images, product icons, or graphic symbols" without HubSpot's
 *       express written permission. A generic glyph drawn for Proxyma: a contact card - NOT the
 *       sprocket.
 *   salesforce - NO SALESFORCE MARK, checked 2026-10-14. Simple Icons removed every Salesforce brand
 *       (simple-icons/simple-icons#13503, the same removal that took Slack's), and Salesforce's
 *       Trademark & Copyright Usage Guidelines (salesforce.com/company/legal/tmcusageguidelines/)
 *       allow its word marks "in plain text" to reference an integration while most other uses
 *       "require specific written permission". A generic glyph drawn for Proxyma: a sales funnel -
 *       NOT the cloud.
 *   asana - NO ASANA MARK, checked 2026-10-14. Asana's brand guidelines (asana.com/brand) say "Don't
 *       recolor the logo" and "Do not modify the Asana Brand Assets in any way, including by changing
 *       any colors" - the coral symbol in the row's ink would be both - and grant nothing for naming an
 *       integration. A generic glyph drawn for Proxyma: a clipboard with a check and a line cut out.
 *   monday - NO MONDAY.COM MARK, checked 2026-10-14. monday.com publishes no brand terms for third
 *       parties (its co-branding rules sit behind the partner portal), and its developer community
 *       answers that an app may use its design system but not its logo. A generic glyph drawn for
 *       Proxyma: a board's table, its header row kept and its cells cut out - NOT the three pills.
 *   trello - NO TRELLO MARK, checked 2026-10-14. Atlassian's logo page (atlassian.design/foundations/
 *       logos) lists Trello among its attribution lockups but says logos for third-party integrations
 *       are "available in Atlaskit staging (Atlassians only)" and states no one-color use for others,
 *       so the jira/confluence reading above is not stretched to it. A generic glyph drawn for Proxyma:
 *       a card with two lines of text cut out and the edge of the card behind it - NOT the two columns.
 *   clickup - NO CLICKUP MARK, checked 2026-10-14. ClickUp's brand page (clickup.com/brand) lists
 *       "Don't change the color" (black or white only "when necessary"), and approves partner lockups
 *       case by case. A generic glyph drawn for Proxyma: a priority flag on its pole - NOT the chevron.
 *   dropbox - NO DROPBOX MARK, checked 2026-10-14. Simple Icons 16.33.0 carries `dropbox` (CC0 covers the
 *       drawing, not the trademark). Dropbox's developer branding guide
 *       (docs.dropboxapi.com/dropbox-api/docs/developer-resources/branding-guide) lets an app use the logo to
 *       identify a Dropbox integration, but "only the approved logos", and its branding guidelines
 *       (https://www.dropbox.com/branding) say "please don't edit, change, distort, recolor ... them in any
 *       way" - and the one-ink treatment above recolors every mark. A generic glyph drawn for Proxyma: a
 *       tray with an arrow dropping into it - NOT the Dropbox open-box glyph.
 *   box - NO BOX MARK, checked 2026-10-14. Simple Icons 16.33.0 carries `box`, but Box's Trademark Guidelines
 *       (https://www.box.com/legal/trademark) say "third parties generally may not use any Box logo for any
 *       purpose" without a license, and "Users may not change the color or font of the Box Marks". A
 *       generic glyph drawn for Proxyma: a carton seen from a corner, its edges cut out - NOT the Box wordmark.
 *   zoom - NO ZOOM MARK, checked 2026-10-14. Zoom's brand site (brand.zoom.us, "Usage & legal") allows
 *       its stylized marks, icons and logos only "with written trademark license or other express
 *       permission from Zoom", and its logo only unaltered in Zoom blue or white - never this one-ink
 *       treatment; the word mark may name the product. A generic glyph drawn for Proxyma: a video
 *       camera with a recording light cut out - NOT the camera in a rounded square.
 *   imap - a generic glyph drawn for Proxyma: the envelope outlook and gmail use. IMAP is a protocol
 *       (RFC 9051) and has no mark.
 *   files, web, network, chat, you, api, plug - generic glyphs drawn for Proxyma. No third-party source.
 *
 * Adding a mark: take it from a source whose terms allow a product to name an integration, convert
 * it to a single filled path on the 24 grid, and record the source and terms above. If no such
 * source exists, draw a generic glyph instead - never an imitation of the vendor's logo.
 */
export type MarkId =
  | 'files' | 'web' | 'confluence' | 'jira' | 'bitbucket' | 'github' | 'subversion' | 'network'
  | 'sharepoint' | 'onedrive' | 'teams' | 'chat' | 'outlook' | 'microsoft365'
  | 'you' | 'copilot' | 'claudecode' | 'claude' | 'cursor' | 'chatgpt' | 'gemini' | 'api'
  | 'plug' | 'slack' | 'gitlab' | 'linear' | 'google' | 'googledrive' | 'gmail' | 'googlecalendar' | 'notion' | 'airtable' | 'zendesk' | 'freshdesk' | 'hubspot' | 'salesforce' | 'asana' | 'monday' | 'trello' | 'clickup' | 'dropbox' | 'box' | 'zoom' | 'imap'

// evenodd for every path: the generic glyphs cut their details out of the silhouette (the
// envelope's flap, the window's body) rather than drawing them in a second color.
export const MARK_PATHS: Record<MarkId, string> = {
  // A folder.
  files: 'M2 5.5A1.5 1.5 0 0 1 3.5 4h5.4a1.5 1.5 0 0 1 1.06.44L11.5 6h9A1.5 1.5 0 0 1 22 7.5v11a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18.5Z',
  // A globe: a disc with its meridian and equator cut out as thin lines.
  web: 'M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20ZM12 2.3a4.75 9.7 0 1 0 0 19.4 4.75 9.7 0 1 0 0-19.4ZM12 2.3a3.25 9.7 0 1 0 0 19.4 3.25 9.7 0 1 0 0-19.4ZM2.2 11.25h19.6v1.5H2.2Z',
  confluence: 'M.87 18.257c-.248.382-.53.875-.763 1.245a.764.764 0 0 0 .255 1.04l4.965 3.054a.764.764 0 0 0 1.058-.26c.199-.332.454-.763.733-1.221 1.967-3.247 3.945-2.853 7.508-1.146l4.957 2.337a.764.764 0 0 0 1.028-.382l2.364-5.346a.764.764 0 0 0-.382-1 599.851 599.851 0 0 1-4.965-2.361C10.911 10.97 5.224 11.185.87 18.257zM23.131 5.743c.249-.405.531-.875.764-1.25a.764.764 0 0 0-.256-1.034L18.675.404a.764.764 0 0 0-1.058.26c-.195.335-.451.763-.734 1.225-1.966 3.246-3.945 2.85-7.508 1.146L4.437.694a.764.764 0 0 0-1.027.382L1.046 6.422a.764.764 0 0 0 .382 1c1.039.49 3.105 1.467 4.965 2.361 6.698 3.246 12.392 3.029 16.738-4.04z',
  jira: 'M11.571 11.513H0a5.218 5.218 0 0 0 5.232 5.215h2.13v2.057A5.215 5.215 0 0 0 12.575 24V12.518a1.005 1.005 0 0 0-1.005-1.005zm5.723-5.756H5.736a5.215 5.215 0 0 0 5.215 5.214h2.129v2.058a5.218 5.218 0 0 0 5.215 5.214V6.758a1.001 1.001 0 0 0-1.001-1.001zM23.013 0H11.455a5.215 5.215 0 0 0 5.215 5.215h2.129v2.057A5.215 5.215 0 0 0 24 12.483V1.005A1.001 1.001 0 0 0 23.013 0Z',
  bitbucket: 'M.778 1.213a.768.768 0 00-.768.892l3.263 19.81c.084.5.515.868 1.022.873H19.95a.772.772 0 00.77-.646l3.27-20.03a.768.768 0 00-.768-.891zM14.52 15.53H9.522L8.17 8.466h7.561z',
  github: 'M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12',
  subversion: 'M24 20.753v-6.306c-3.285 1.296-7.362 2.556-12.23 3.786-4.534 1.145-8.458 1.97-11.77 2.475v.045h24zM0 14.078v5.133c3.738-.55 7.116-1.206 10.13-1.967 2.962-.748 5.245-1.475 6.847-2.18 1.602-.703 2.34-1.297 2.22-1.78-.107-.42-.846-.635-2.217-.645-.703.01-1.67.06-2.904.15-1.236.09-2.774.234-4.61.426-2.85.304-5.216.537-7.1.694-.896.075-1.685.132-2.366.17zm1.035 2.95c.06 0 .114.025.16.07.046.046.07.103.07.166 0 .066-.024.12-.07.168-.047.045-.104.066-.164.066-.032 0-.064-.006-.092-.018-.03-.012-.054-.03-.075-.05-.023-.014-.04-.044-.05-.074 0-.015-.016-.045-.016-.09 0-.06.03-.12.075-.165s.105-.06.18-.06zm.81 0c.063 0 .117.025.165.07.045.046.066.103.066.166 0 .066-.022.12-.067.168-.06.045-.106.066-.18.066-.03 0-.06-.006-.09-.018s-.06-.03-.076-.05c-.03-.014-.045-.044-.06-.074-.015-.015-.015-.045-.015-.09 0-.06.014-.12.06-.165s.104-.06.164-.06zm-.81-1.51c.06 0 .114.022.16.07.046.045.07.1.07.165 0 .064-.024.12-.07.165s-.1.07-.164.07c-.065 0-.122-.024-.167-.07-.045-.045-.07-.102-.07-.165 0-.067.016-.123.06-.168s.106-.068.166-.068zm.81 0c.063 0 .117.022.165.07.045.045.066.1.066.165 0 .064-.022.12-.067.165-.06.045-.106.07-.18.07s-.12-.024-.166-.07c-.045-.045-.075-.102-.075-.165 0-.067.014-.123.06-.168s.104-.068.164-.068zM24 4.597V9.41c-1.635.1-3.68.277-6.138.534-2.49.27-4.52.48-6.093.615-1.576.15-2.713.226-3.41.24-1.363.03-2.09-.15-2.195-.554-.105-.45.705-1.05 2.445-1.77 1.74-.735 4.05-1.47 6.9-2.19 2.505-.63 5.34-1.185 8.49-1.65zm-.855-1.35c-3.255.605-6.627 1.35-10.114 2.23C7.587 6.852 3.244 8.22 0 9.573V3.248h23.146z',
  // A network share: a drive with two status lights cut out, on a line to the network.
  network: 'M4 3h16a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 20 13H4a1.5 1.5 0 0 1-1.5-1.5v-7A1.5 1.5 0 0 1 4 3Zm2 4v2h2V7Zm4 0v2h2V7Zm1 6h2v3h-2Zm-1 3h4a1 1 0 0 1 1 1v.5h6V19h-6v.5a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V19H2v-1.5h7V17a1 1 0 0 1 1-1Z',
  // A document library: a page in front of the edge of the one behind it, two text lines cut out.
  sharepoint: 'M9 2h7.2L21 6.8V17a1.5 1.5 0 0 1-1.5 1.5H9A1.5 1.5 0 0 1 7.5 17V3.5A1.5 1.5 0 0 1 9 2Zm1.5 8v1.6h7.5V10Zm0 3.4V15h7.5v-1.6ZM3 6.5h2.5V20h11.5v2H4.5A1.5 1.5 0 0 1 3 20.5Z',
  // A personal drive: a cloud with a page cut out of it, its corner folded. OneDrive.
  onedrive: 'M7 19a5 5 0 0 1-.9-9.92A6.5 6.5 0 0 1 18.6 8.6 4.75 4.75 0 0 1 18 19ZM9.5 10.5v6.5h5.5v-4.5L13 10.5Z',
  // A conversation: a speech bubble in front, the edge of the reply behind it. Teams channels.
  teams: 'M4.5 3h10A1.5 1.5 0 0 1 16 4.5v7a1.5 1.5 0 0 1-1.5 1.5H9l-4 3.5V13h-.5A1.5 1.5 0 0 1 3 11.5v-7A1.5 1.5 0 0 1 4.5 3ZM18 7h1.5A1.5 1.5 0 0 1 21 8.5v7a1.5 1.5 0 0 1-1.5 1.5H19v3.5L15 17h-4.5A1.5 1.5 0 0 1 9 15.5V15h5.5a3.5 3.5 0 0 0 3.5-3.5Z',
  // A chat: one speech bubble with three dots cut out. Teams chats, the one-to-one kind.
  chat: 'M4.5 3h15A1.5 1.5 0 0 1 21 4.5v11a1.5 1.5 0 0 1-1.5 1.5H10l-5 4.5V17h-.5A1.5 1.5 0 0 1 3 15.5v-11A1.5 1.5 0 0 1 4.5 3ZM6.75 10a1.25 1.25 0 1 0 2.5 0 1.25 1.25 0 1 0-2.5 0ZM10.75 10a1.25 1.25 0 1 0 2.5 0 1.25 1.25 0 1 0-2.5 0ZM14.75 10a1.25 1.25 0 1 0 2.5 0 1.25 1.25 0 1 0-2.5 0Z',
  // A mail: an envelope with its flap cut out.
  outlook: 'M3.5 4h17A1.5 1.5 0 0 1 22 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18.5v-13A1.5 1.5 0 0 1 3.5 4Zm1.2 3.2v2.3l7.3 4.8 7.3-4.8V7.2L12 12Z',
  // Microsoft 365, the one sign-in behind the ones above: a cloud.
  microsoft365: 'M7 19a5 5 0 0 1-.9-9.92A6.5 6.5 0 0 1 18.6 8.6 4.75 4.75 0 0 1 18 19Z',
  // You: a person.
  you: 'M12 2.5a4.75 4.75 0 1 1 0 9.5 4.75 4.75 0 0 1 0-9.5ZM3.5 20.5c0-3.9 3.4-6.5 8.5-6.5s8.5 2.6 8.5 6.5a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1Z',
  copilot: 'M23.922 16.997C23.061 18.492 18.063 22.02 12 22.02 5.937 22.02.939 18.492.078 16.997A.641.641 0 0 1 0 16.741v-2.869a.883.883 0 0 1 .053-.22c.372-.935 1.347-2.292 2.605-2.656.167-.429.414-1.055.644-1.517a10.098 10.098 0 0 1-.052-1.086c0-1.331.282-2.499 1.132-3.368.397-.406.89-.717 1.474-.952C7.255 2.937 9.248 1.98 11.978 1.98c2.731 0 4.767.957 6.166 2.093.584.235 1.077.546 1.474.952.85.869 1.132 2.037 1.132 3.368 0 .368-.014.733-.052 1.086.23.462.477 1.088.644 1.517 1.258.364 2.233 1.721 2.605 2.656a.841.841 0 0 1 .053.22v2.869a.641.641 0 0 1-.078.256Zm-11.75-5.992h-.344a4.359 4.359 0 0 1-.355.508c-.77.947-1.918 1.492-3.508 1.492-1.725 0-2.989-.359-3.782-1.259a2.137 2.137 0 0 1-.085-.104L4 11.746v6.585c1.435.779 4.514 2.179 8 2.179 3.486 0 6.565-1.4 8-2.179v-6.585l-.098-.104s-.033.045-.085.104c-.793.9-2.057 1.259-3.782 1.259-1.59 0-2.738-.545-3.508-1.492a4.359 4.359 0 0 1-.355-.508Zm2.328 3.25c.549 0 1 .451 1 1v2c0 .549-.451 1-1 1-.549 0-1-.451-1-1v-2c0-.549.451-1 1-1Zm-5 0c.549 0 1 .451 1 1v2c0 .549-.451 1-1 1-.549 0-1-.451-1-1v-2c0-.549.451-1 1-1Zm3.313-6.185c.136 1.057.403 1.913.878 2.497.442.544 1.134.938 2.344.938 1.573 0 2.292-.337 2.657-.751.384-.435.558-1.15.558-2.361 0-1.14-.243-1.847-.705-2.319-.477-.488-1.319-.862-2.824-1.025-1.487-.161-2.192.138-2.533.529-.269.307-.437.808-.438 1.578v.021c0 .265.021.562.063.893Zm-1.626 0c.042-.331.063-.628.063-.894v-.02c-.001-.77-.169-1.271-.438-1.578-.341-.391-1.046-.69-2.533-.529-1.505.163-2.347.537-2.824 1.025-.462.472-.705 1.179-.705 2.319 0 1.211.175 1.926.558 2.361.365.414 1.084.751 2.657.751 1.21 0 1.902-.394 2.344-.938.475-.584.742-1.44.878-2.497Z',
  claudecode: 'M21 10.5h3v3h-3v3h-1.5v3H18v-3h-1.5v3H15v-3H9v3H7.5v-3H6v3H4.5v-3H3v-3H0v-3h3v-6h18Zm-15 0h1.5v-3H6Zm10.5 0H18v-3h-1.5z',
  claude: 'm4.7144 15.9555 4.7174-2.6471.079-.2307-.079-.1275h-.2307l-.7893-.0486-2.6956-.0729-2.3375-.0971-2.2646-.1214-.5707-.1215-.5343-.7042.0546-.3522.4797-.3218.686.0608 1.5179.1032 2.2767.1578 1.6514.0972 2.4468.255h.3886l.0546-.1579-.1336-.0971-.1032-.0972L6.973 9.8356l-2.55-1.6879-1.3356-.9714-.7225-.4918-.3643-.4614-.1578-1.0078.6557-.7225.8803.0607.2246.0607.8925.686 1.9064 1.4754 2.4893 1.8336.3643.3035.1457-.1032.0182-.0728-.164-.2733-1.3539-2.4467-1.445-2.4893-.6435-1.032-.17-.6194c-.0607-.255-.1032-.4674-.1032-.7285L6.287.1335 6.6997 0l.9957.1336.419.3642.6192 1.4147 1.0018 2.2282 1.5543 3.0296.4553.8985.2429.8318.091.255h.1579v-.1457l.1275-1.706.2368-2.0947.2307-2.6957.0789-.7589.3764-.9107.7468-.4918.5828.2793.4797.686-.0668.4433-.2853 1.8517-.5586 2.9021-.3643 1.9429h.2125l.2429-.2429.9835-1.3053 1.6514-2.0643.7286-.8196.85-.9046.5464-.4311h1.0321l.759 1.1293-.34 1.1657-1.0625 1.3478-.8804 1.1414-1.2628 1.7-.7893 1.36.0729.1093.1882-.0183 2.8535-.607 1.5421-.2794 1.8396-.3157.8318.3886.091.3946-.3278.8075-1.967.4857-2.3072.4614-3.4364.8136-.0425.0304.0486.0607 1.5482.1457.6618.0364h1.621l3.0175.2247.7892.522.4736.6376-.079.4857-1.2142.6193-1.6393-.3886-3.825-.9107-1.3113-.3279h-.1822v.1093l1.0929 1.0686 2.0035 1.8092 2.5075 2.3314.1275.5768-.3218.4554-.34-.0486-2.2039-1.6575-.85-.7468-1.9246-1.621h-.1275v.17l.4432.6496 2.3436 3.5214.1214 1.0807-.17.3521-.6071.2125-.6679-.1214-1.3721-1.9246L14.38 17.959l-1.1414-1.9428-.1397.079-.674 7.2552-.3156.3703-.7286.2793-.6071-.4614-.3218-.7468.3218-1.4753.3886-1.9246.3157-1.53.2853-1.9004.17-.6314-.0121-.0425-.1397.0182-1.4328 1.9672-2.1796 2.9446-1.7243 1.8456-.4128.164-.7164-.3704.0667-.6618.4008-.5889 2.386-3.0357 1.4389-1.882.929-1.0868-.0062-.1579h-.0546l-6.3385 4.1164-1.1293.1457-.4857-.4554.0608-.7467.2307-.2429 1.9064-1.3114Z',
  cursor: 'M11.503.131 1.891 5.678a.84.84 0 0 0-.42.726v11.188c0 .3.162.575.42.724l9.609 5.55a1 1 0 0 0 .998 0l9.61-5.55a.84.84 0 0 0 .42-.724V6.404a.84.84 0 0 0-.42-.726L12.497.131a1.01 1.01 0 0 0-.996 0M2.657 6.338h18.55c.263 0 .43.287.297.515L12.23 22.918c-.062.107-.229.064-.229-.06V12.335a.59.59 0 0 0-.295-.51l-9.11-5.257c-.109-.063-.064-.23.061-.23',
  // ChatGPT (generic, see above): a speech bubble with a spark cut out of it.
  chatgpt: 'M4.5 3h15A1.5 1.5 0 0 1 21 4.5v11a1.5 1.5 0 0 1-1.5 1.5H10l-5 4.5V17h-.5A1.5 1.5 0 0 1 3 15.5v-11A1.5 1.5 0 0 1 4.5 3ZM12 5.5l1.2 3.3 3.3 1.2-3.3 1.2-1.2 3.3-1.2-3.3L7.5 10l3.3-1.2Z',
  // Gemini (generic, see above): a large spark and a small one.
  gemini: 'M10 4l2.3 6.7L19 13l-6.7 2.3L10 22l-2.3-6.7L1 13l6.7-2.3ZM19 1l1 3 3 1-3 1-1 3-1-3-3-1 3-1Z',
  // Your scripts, over the API: a terminal window with a prompt cut out.
  api: 'M3.5 3h17A1.5 1.5 0 0 1 22 4.5v15a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 19.5v-15A1.5 1.5 0 0 1 3.5 3Zm2.3 5.7 3.3 3.3-3.3 3.3 1.4 1.4 4.7-4.7-4.7-4.7Zm6.2 6.8V17h6v-1.5Z',
  // An MCP server: a plug. Generic - the protocol's own logo is not drawn without its terms checked.
  plug: 'M8 2h2v5H8ZM14 2h2v5h-2ZM5 7h14v3a5 5 0 0 1-5 5h-1v7h-2v-7h-1a5 5 0 0 1-5-5Z',
  // Slack channels (generic, see above): a speech bubble with an upright # cut out, its middle kept.
  slack: 'M4.5 3h15A1.5 1.5 0 0 1 21 4.5v11a1.5 1.5 0 0 1-1.5 1.5H10l-5 4.5V17h-.5A1.5 1.5 0 0 1 3 15.5v-11A1.5 1.5 0 0 1 4.5 3ZM9 5.5h1.5v2h3v-2H15v2h2.5V9H15v2h2.5v1.5H15v2h-1.5v-2h-3v2H9v-2H6.5V11H9V9H6.5V7.5H9ZM10.5 9v2h3V9Z',
  // GitLab (generic, see above): a branch leaving a line and merging back into it - a merge request.
  gitlab: 'M6 2a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5ZM5 7h2v10H5ZM6 17a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5ZM18 4.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 1 0 0-5ZM17 9.5h2V11a5 5 0 0 1-5 5H7v-2h7a3 3 0 0 0 3-3Z',
  // Linear (generic, see above): an issue's status circle with a check cut out.
  linear: 'M12 2a10 10 0 1 0 0 20 10 10 0 1 0 0-20ZM7.3 12.2l1.4-1.4 2.3 2.3 4.6-4.6 1.4 1.4-6 6Z',
  // Google (generic, see above), the one sign-in behind the three below: a cloud.
  google: 'M7 19a5 5 0 0 1-.9-9.92A6.5 6.5 0 0 1 18.6 8.6 4.75 4.75 0 0 1 18 19Z',
  // Google Drive (generic): a folder with a cloud cut out of it.
  googledrive: 'M2 5.5A1.5 1.5 0 0 1 3.5 4h5.4a1.5 1.5 0 0 1 1.06.44L11.5 6h9A1.5 1.5 0 0 1 22 7.5v11a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18.5ZM9 17.5a2.5 2.5 0 0 1-.4-4.97A3.5 3.5 0 0 1 15.3 11.5a2.5 2.5 0 0 1-.3 6Z',
  // Gmail (generic): the envelope outlook uses - a mail is a mail.
  gmail: 'M3.5 4h17A1.5 1.5 0 0 1 22 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18.5v-13A1.5 1.5 0 0 1 3.5 4Zm1.2 3.2v2.3l7.3 4.8 7.3-4.8V7.2L12 12Z',
  // Google Calendar (generic): a calendar page on its two rings, its days cut out and one day kept.
  googlecalendar: 'M7 2h2v2h6V2h2v2h2.5A1.5 1.5 0 0 1 21 5.5v14a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19.5v-14A1.5 1.5 0 0 1 4.5 4H7ZM5 9v10h14V9Zm2 2h3v3H7Z',
  // Notion pages (generic, see above): a page with its corner folded, three lines of text cut out.
  notion: 'M6.5 2H14l5 5v13.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 20.5v-17A1.5 1.5 0 0 1 6.5 2ZM14.2 3.6V7h3.4ZM8 10.5v1.5h8v-1.5ZM8 14v1.5h8V14ZM8 17.5V19h5v-1.5Z',
  // An Airtable base (generic, see above): a table, its header row solid and four cells cut out.
  airtable: 'M4.5 3h15A1.5 1.5 0 0 1 21 4.5v15a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19.5v-15A1.5 1.5 0 0 1 4.5 3ZM5 9v4h4.5V9ZM11 9v4h8V9ZM5 14.5V19h4.5v-4.5ZM11 14.5V19h8v-4.5Z',
  // Zendesk (generic, see above): a support headset - its band, two ear cups and a microphone.
  zendesk: 'M3 13a9 9 0 0 1 18 0h-2a7 7 0 0 0-14 0ZM3 13h5v6H5a2 2 0 0 1-2-2ZM16 13h5v4a2 2 0 0 1-2 2h-3ZM18 19h1.5v1a2.5 2.5 0 0 1-2.5 2.5H13V21h4a1 1 0 0 0 1-1ZM11 20.5h2V23h-2a1.25 1.25 0 0 1 0-2.5Z',
  // Freshdesk (generic, see above): a ticket stub, notched at both sides, its tear line cut out.
  freshdesk: 'M3.5 5h17A1.5 1.5 0 0 1 22 6.5V10a2 2 0 0 0 0 4v3.5a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 17.5V14a2 2 0 0 0 0-4V6.5A1.5 1.5 0 0 1 3.5 5ZM15 7h1.5v2.5H15ZM15 10.75h1.5v2.5H15ZM15 14.5h1.5V17H15Z',
  // HubSpot (generic, see above): a contact card - a person and three lines cut out.
  hubspot: 'M3.5 4h17A1.5 1.5 0 0 1 22 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18.5v-13A1.5 1.5 0 0 1 3.5 4ZM6 9.5a2 2 0 1 0 4 0 2 2 0 1 0-4 0ZM4.5 16.5c0-1.9 1.6-3 3.5-3s3.5 1.1 3.5 3ZM13 8.5h6V10h-6ZM13 12h6v1.5h-6ZM13 15.5h4V17h-4Z',
  // Salesforce (generic, see above): a sales funnel.
  salesforce: 'M2.5 4h19l-7.5 8.6V19l-4 2.5v-8.9Z',
  // Asana (generic, see above): a clipboard with a check and a line cut out.
  asana: 'M9 2h6a1 1 0 0 1 1 1v1h2.5A1.5 1.5 0 0 1 20 5.5v15a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 20.5v-15A1.5 1.5 0 0 1 5.5 4H8V3a1 1 0 0 1 1-1ZM7.2 12.2l1.1-1.1 1.6 1.6 3.6-3.6 1.1 1.1-4.7 4.7ZM7 17h10v1.5H7Z',
  // monday.com (generic): a board's table - the header row kept, the cells cut out.
  monday: 'M3.5 3h17A1.5 1.5 0 0 1 22 4.5v15a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 19.5v-15A1.5 1.5 0 0 1 3.5 3ZM4 8.5h7.25V12H4ZM12.75 8.5H20V12h-7.25ZM4 13.5h7.25V17H4ZM12.75 13.5H20V17h-7.25Z',
  // Trello (generic): a card with two lines cut out, the edge of the card behind it.
  trello: 'M3.5 3H18v1.5H3.5V17H2V4.5A1.5 1.5 0 0 1 3.5 3ZM6 6h14.5A1.5 1.5 0 0 1 22 7.5v11a1.5 1.5 0 0 1-1.5 1.5H6a1.5 1.5 0 0 1-1.5-1.5v-11A1.5 1.5 0 0 1 6 6Zm1 3.5V11h10V9.5Zm0 3.5v1.5h7V13Z',
  // ClickUp (generic): a priority flag on its pole.
  clickup: 'M5 2h2v20H5ZM7 3h12l-3 4.5 3 4.5H7Z',
  // Dropbox (generic, see above): a tray with an arrow dropping into it.
  dropbox: 'M11 2h2v7.2h3.2L12 13.6 7.8 9.2H11ZM3 13h4.6l1.5 3h5.8l1.5-3H21v6.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19.5Z',
  // Box (generic, see above): a carton seen from a corner, its three near edges cut out.
  box: 'M12 2.5l8.5 4.25v10.5L12 21.5l-8.5-4.25V6.75ZM5 7.4l7 3.5 7-3.5-.7-1.3L12 9.25 5.7 6.1ZM11.3 11.2h1.4v8.4h-1.4Z',
  // Zoom (generic, see above): a video camera, its lens to the right, a recording light cut out.
  zoom: 'M3.5 6h10A1.5 1.5 0 0 1 15 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 2 16.5v-9A1.5 1.5 0 0 1 3.5 6ZM4.5 9a1.25 1.25 0 1 0 2.5 0 1.25 1.25 0 1 0-2.5 0ZM16.5 10.2l4.5-2.9a.65.65 0 0 1 1 .55v8.3a.65.65 0 0 1-1 .55l-4.5-2.9Z',
  // IMAP mail (generic): the envelope outlook uses - a mail is a mail.
  imap: 'M3.5 4h17A1.5 1.5 0 0 1 22 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18.5v-13A1.5 1.5 0 0 1 3.5 4Zm1.2 3.2v2.3l7.3 4.8 7.3-4.8V7.2L12 12Z',
}

/**
 * Whether a string names a mark - for a mark id that arrives as data (a connector's descriptor
 * names its own), so an unknown one falls back to a generic glyph instead of an empty path.
 */
export function isMarkId(id: string | null | undefined): id is MarkId {
  return !!id && Object.prototype.hasOwnProperty.call(MARK_PATHS, id)
}

/** What an icon slot takes: a lucide icon and a mark from `markIcon` both fit it. */
export interface GlyphProps {
  // number | string because lucide's is, and a lucide icon has to fit the same slot.
  size?: number | string
  className?: string
  'aria-hidden'?: boolean | 'true' | 'false'
}
export type GlyphComponent = ComponentType<GlyphProps>

/** The mark alone, decorative - the name is always written beside it. */
export function BrandMark({ id, size = 16, className }: { id: MarkId; size?: number | string; className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
      className={cn('shrink-0 fill-current', className)}
    >
      <path d={MARK_PATHS[id]} fillRule="evenodd" clipRule="evenodd" />
    </svg>
  )
}

const MARK_ICONS = new Map<MarkId, GlyphComponent>()

/**
 * A mark as an icon COMPONENT, for a slot typed to take one - a tab's `icon`, a tool row's glyph -
 * where a lucide icon would otherwise go. One component per id, made once, so React sees the same
 * type on every render rather than remounting the svg.
 */
export function markIcon(id: MarkId): GlyphComponent {
  let icon = MARK_ICONS.get(id)
  if (!icon) {
    const Mark = ({ size, className }: GlyphProps) => <BrandMark id={id} size={size} className={className} />
    Mark.displayName = `Mark(${id})`
    icon = Mark
    MARK_ICONS.set(id, icon)
  }
  return icon
}
