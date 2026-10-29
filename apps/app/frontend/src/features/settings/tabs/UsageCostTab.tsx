import { Button, SettingsSections } from '@/components/ui/kit'
import { SignpostSection } from '../pages/shared'
import { edition } from '@settings-pages'
import type { OpenTab } from '../pages/types'
import { useTranslation } from '@/i18n'
import type { TabProps } from '../pages/shared'

// - Usage & Cost tab (desktop only) -
//
// What this machine has spent on chat: this calendar month as the headline, lifetime beside it,
// and the month broken down by the model that did the work.
//
// DESKTOP ONLY, and not because the data is unavailable on server - the ledger is written in both
// editions - but because the question is different there. A server deployment asks "what is this
// costing us, across whom, against which quota", which Administration > Usages already answers with
// a user table and a global ceiling. A desktop install has one user and one machine, so the only
// honest question is "what has this cost me". Quotas, reset day and the global ceiling are absent
// here on purpose: there is nobody to hold to a budget and no cycle to reset.
//
// A personal tab rather than a System Configuration one. SYSTEM_CONFIG_TABS is for knobs that
// change behaviour; this one only reports. (The modal's own rule of thumb sends monitoring surfaces
// to the /admin page instead - that is not available here, since Administration is removed
// altogether in desktop mode.)
// - Usage & Cost tab -
//
// TWO TABS UNTIL 2026-09-02: "Usage & Cost", which reported what had been spent, and "Cost
// Settings", which held the ceiling governing that spend and the caveat explaining how both were
// calculated. They were in different nav groups, so the number and the limit on the number could
// not be seen together, and the estimates caveat had to be written out twice because the tab
// people would actually try to reconcile against an invoice was not the tab that carried it.
//
// One tab now, read top to bottom: how these figures are arrived at, what has been spent, what it
// may spend, and where the rates come from.
//
// THE SPEND HALF IS PER-EDITION and comes from the edition module, exactly as the quota half
// already did. Desktop renders the real figures; server renders a signpost to Administration >
// Usages, which answers the same question with the per-user and per-quota dimensions that only
// exist where there are accounts. See SpendSection.{desktop,server}.tsx.
//
// MODEL PRICING MOVED TO SETTINGS > PROVIDERS, into each provider's own dialog (see
// ProviderPricingSection). A rate belongs to the provider it is a rate for: here it was a flat
// list of provider/model pairs whose Provider dropdown was a second, parallel way to name
// something the Providers tab already owns, and keeping the two in agreement was left to whoever
// happened to remember. It is now one section inside the thing it configures - which also means
// the model combobox can offer that provider's REAL model list, since the dialog knows the
// instance id and this tab only ever had the name.

// onOpenTab is this modal's own setTab, not the global settingsModalStore's open(): that store's
// initialTab is only re-read when the value CHANGES, so an open('AI Providers') from a session that
// had already been opened on Providers would be a link that does nothing. Switching the tab
// directly cannot miss.
export function UsageCostTab({ trackChange, pending, onOpenTab }: TabProps & { onOpenTab: OpenTab }) {
  const { t } = useTranslation()
  return (
    <SettingsSections>
      {/* LIMITS FIRST, since 2026-09-24 at the maintainer's request - the settings on this tab
          lead, and what has been spent follows as the readout they govern. Server renders a
          deployment ceiling plus the per-user allowances, desktop only the spending limit it
          actually enforces. See features/settings/pages/QuotaSection.{desktop,server}.tsx. */}
      <edition.QuotaSection trackChange={trackChange} pending={pending} />

      {/* Where the spend readout went: Logs, since 2026-10-12. See SpendSection.{desktop,server}.tsx. */}
      <edition.SpendSection onOpenTab={onOpenTab} />

      {/* A signpost, not a second editor. Rates used to live here and someone looking for them
          will look here first - a tab that silently lost a section reads as a regression. */}
      <SignpostSection
        title={t('Model pricing')}
        caption={t('Rates are set per provider; a model without one uses its list price or $0.')}
        action={
          <Button variant="hyperlink" onClick={() => onOpenTab('AI Providers')}>
            {t('Open AI Providers')}
          </Button>
        }
      />
    </SettingsSections>
  )
}

