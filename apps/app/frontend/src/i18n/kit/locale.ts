/* =====================================================================================
 * GENERATED FILE - DO NOT EDIT.
 * 
 * Source: libs/ui-kit/i18n/locale.ts
 * Regenerate: node libs/ui-kit/sync.mjs   (from the repository root)
 * 
 * Edits made here are silently reverted by the next sync. Change the source instead;
 * a change that only one surface wants does not belong in the shared kit at all.
 * ===================================================================================== */

/**
 * What a locale is, how one is chosen, and where the choice is kept.
 *
 * Framework-free on purpose: `apps/landing` is Next.js and reads this during a server render
 * where `window` does not exist, while the three Vite SPAs only ever run in a browser. Every
 * function here either takes what it needs as an argument or guards for `window` itself.
 */

export const LOCALES = ['en', 'vi'] as const;

export type Locale = (typeof LOCALES)[number];

/**
 * English is the source language and the fallback, not merely the default.
 *
 * Every catalog is authored in English first and a missing Vietnamese string renders the English
 * one - see `translate`. That is a deliberate asymmetry: a half-translated product should read as
 * a product with some English in it, not as a product with `settings.rail.retrieval` in it.
 */
export const DEFAULT_LOCALE: Locale = 'en';

export const LOCALE_NAMES: Record<Locale, string> = {
  en: 'English',
  // Written in its own language, as every language's name is; escaped, because source is ASCII.
  vi: 'Ti\u1ebfng Vi\u1ec7t',
};

const STORAGE_KEY = 'proxyma.locale';

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (LOCALES as readonly string[]).includes(value);
}

/**
 * Matches a browser language tag against what we have.
 *
 * Tags are matched on the PRIMARY SUBTAG only: `vi-VN`, `vi-Hani` and plain `vi` are all
 * Vietnamese, and there is no reason to hold separate catalogs to say so. Region-specific
 * catalogs would be the thing to add if `pt-BR` ever diverged from `pt-PT`; nothing here does.
 */
export function matchLocale(tags: readonly string[]): Locale | null {
  for (const tag of tags) {
    const primary = tag.toLowerCase().split('-')[0];
    const hit = LOCALES.find((l) => l === primary);
    if (hit) return hit;
  }
  return null;
}

/**
 * The locale to start in: an explicit choice, and ENGLISH otherwise.
 *
 * A STORED CHOICE ALWAYS WINS, including when it disagrees with the browser. Someone who set
 * English on a Vietnamese machine did so on purpose, and re-deciding for them on every load is
 * the behaviour people describe as the site "forgetting".
 *
 * THE BROWSER'S LANGUAGE IS NOT CONSULTED, since 2026-09-23 (maintainer's decision): English is
 * the default in every Proxyma surface, and a visitor who wants another one picks it. It used to
 * fall through to `matchLocale(navigator.languages)`, so the same build opened in Vietnamese on a
 * Vietnamese machine and in English beside it - which makes a screenshot, a support answer and a
 * bug report all ambiguous about what anyone was actually looking at. `matchLocale` stays
 * exported: it is still how a language TAG is mapped onto a catalog, and it is tested.
 */
export function resolveInitialLocale(): Locale {
  return readStoredLocale() ?? DEFAULT_LOCALE;
}

/**
 * The locale a provider paints its FIRST frame in: a forced one (the URL decided), else the stored
 * choice, read at once so a Vietnamese reader never sees an English first paint; and the default
 * where there is no browser to ask - a server render.
 */
export function initialLocale(forced?: Locale | null): Locale {
  if (forced) return forced;
  return typeof window === 'undefined' ? DEFAULT_LOCALE : resolveInitialLocale();
}

export function readStoredLocale(): Locale | null {
  // Private windows, disabled site data and thumbnail contexts all make this THROW rather than
  // return null, so it is wrapped rather than checked.
  try {
    if (typeof localStorage === 'undefined') return null;
    const raw = localStorage.getItem(STORAGE_KEY);
    return isLocale(raw) ? raw : null;
  } catch {
    return null;
  }
}

export function storeLocale(locale: Locale): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    /* A locale that does not persist is a nuisance; a crash here is an outage. */
  }
}
