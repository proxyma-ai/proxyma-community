/**
 * Lookup, interpolation and plurals. No React, no dependency, no framework.
 *
 * THE KEY IS THE ENGLISH TEXT. `t('Connectors')`, not `t('settings.tab.connectors')`.
 *
 * That is a deliberate change from dotted ids, made on 2026-09-04, and it removes a whole class
 * of bug rather than just being tidier:
 *
 *   - An invented key is impossible. A dotted catalog can name a screen that does not exist -
 *     ours did, for five settings tabs - and nothing notices, because the checker only compares
 *     one catalog against another and both are equally wrong.
 *   - A missing translation renders correct ENGLISH, because the key IS the English. With dotted
 *     ids a missing key renders `settings.tab.foo` at the user, which needed special-case
 *     fallback logic to avoid.
 *   - The mapping is REVIEWABLE. English and Vietnamese sit side by side in one file, so someone
 *     who speaks the language can read it top to bottom and correct it without opening the code.
 *
 * The cost, stated plainly: rewording an English string orphans its translation, and the string
 * silently reverts to English. `check-catalogs.mjs` reports that, and it is why the catalogs are
 * checked rather than trusted.
 *
 * There is therefore NO English catalog. English is the source, in the source.
 */
import type { Locale } from './locale.ts';

/** English text -> translated text. One entry per string a user can read. */
export type Catalog = Record<string, string>;

export type Catalogs = Partial<Record<Locale, Catalog>>;

export type TranslateParams = Record<string, string | number>;

/**
 * A source string carrying `|` is a two-form plural: `"one item|{count} items"`.
 *
 * Two forms, not six. English needs exactly two; Vietnamese has no plural inflection at all and
 * uses one, with or without the separator. A full CLDR plural engine is kilobytes to say that,
 * and the day a language needing more is added is the day to reach for one.
 */
const PLURAL_SEPARATOR = '|';

const INTERPOLATION = /\{(\w+)\}/g;

/**
 * Translates one string.
 *
 * `source` is the English text and doubles as the lookup key, so an untranslated string, an
 * unknown locale and a locale with no catalog all render the English the developer wrote.
 */
export function translate(
  catalogs: Catalogs,
  locale: Locale,
  source: string,
  params?: TranslateParams,
): string {
  const raw = catalogs[locale]?.[source] ?? source;
  return interpolate(selectPluralForm(raw, params), params);
}

/**
 * Picks a plural form using `params.count`, the only name that triggers it.
 *
 * A string with no separator is returned whole, so passing `count` to a non-plural string cannot
 * truncate it at a pipe that was meant literally - which is also why a literal pipe in a
 * pluralised string is not supported. Nothing here needs one.
 */
function selectPluralForm(raw: string, params?: TranslateParams): string {
  if (!raw.includes(PLURAL_SEPARATOR)) return raw;
  const count = params?.count;
  if (typeof count !== 'number') return raw.split(PLURAL_SEPARATOR)[0];
  const [one, other] = raw.split(PLURAL_SEPARATOR);
  return count === 1 ? one : (other ?? one);
}

/**
 * Replaces `{name}` from params.
 *
 * An UNKNOWN placeholder is left standing rather than blanked. `{count}` visible in the interface
 * is a bug someone reports; a silently empty gap in a sentence is one that nobody does.
 */
function interpolate(text: string, params?: TranslateParams): string {
  if (!params) return text;
  return text.replace(INTERPOLATION, (whole, name: string) => {
    const value = params[name];
    return value === undefined ? whole : String(value);
  });
}
