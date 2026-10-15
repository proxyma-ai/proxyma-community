// Run: node --test libs/ui-kit/i18n/translate.test.ts
//
// Plain `.ts` with no JSX, so Node's own type stripping runs it - no test runner, no bundler, no
// new dependency. That is also why the logic lives here and I18nProvider.tsx holds only the React
// binding: the half worth testing stays testable.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import { translate, type Catalogs } from './translate.ts';
import { matchLocale, isLocale, initialLocale, DEFAULT_LOCALE } from './locale.ts';

// English is the source AND the key. There is no English catalog.
const catalogs: Catalogs = {
  vi: {
    'Save': 'Lưu',
    'Hello {name}': 'Chào {name}',
    'one turn left|{count} turns left': 'còn {count} lượt',
  },
};

describe('the key is the English text', () => {
  test('a translated string comes back translated', () => {
    assert.equal(translate(catalogs, 'vi', 'Save'), 'Lưu');
  });

  test('an UNtranslated string comes back as the English that was written', () => {
    // The whole point of source keys: no fallback logic, and never an identifier on screen.
    assert.equal(translate(catalogs, 'vi', 'Connectors'), 'Connectors');
  });

  test('English asks for no catalog at all', () => {
    assert.equal(translate(catalogs, 'en', 'Save'), 'Save');
    assert.equal(DEFAULT_LOCALE, 'en');
  });

  test('a locale with no catalog renders English rather than throwing', () => {
    assert.equal(translate({}, 'vi', 'Save'), 'Save');
  });
});

describe('interpolation', () => {
  test('substitutes into the translation', () => {
    assert.equal(translate(catalogs, 'vi', 'Hello {name}', { name: 'Hau' }), 'Chào Hau');
  });

  test('substitutes into the English when untranslated', () => {
    assert.equal(translate(catalogs, 'en', 'Hello {name}', { name: 'Hau' }), 'Hello Hau');
  });

  test('an unknown placeholder is LEFT STANDING, not blanked', () => {
    // A visible {name} gets reported; a silently empty gap in a sentence does not.
    assert.equal(translate(catalogs, 'en', 'Hello {name}', {}), 'Hello {name}');
  });

  test('numbers are stringified', () => {
    assert.equal(translate({}, 'en', 'n={n}', { n: 42 }), 'n=42');
  });
});

describe('plurals', () => {
  test('English picks the singular at exactly one', () => {
    assert.equal(translate(catalogs, 'en', 'one turn left|{count} turns left', { count: 1 }), 'one turn left');
  });

  test('English picks the plural otherwise', () => {
    assert.equal(translate(catalogs, 'en', 'one turn left|{count} turns left', { count: 4 }), '4 turns left');
    assert.equal(translate(catalogs, 'en', 'one turn left|{count} turns left', { count: 0 }), '0 turns left');
  });

  test('Vietnamese needs one form and simply omits the separator', () => {
    assert.equal(translate(catalogs, 'vi', 'one turn left|{count} turns left', { count: 1 }), 'còn 1 lượt');
    assert.equal(translate(catalogs, 'vi', 'one turn left|{count} turns left', { count: 5 }), 'còn 5 lượt');
  });

  test('a pluralised string with no count falls to the first form, never showing the pipe', () => {
    assert.equal(translate(catalogs, 'en', 'one turn left|{count} turns left'), 'one turn left');
  });

  test('a string with no separator is returned whole even when count is passed', () => {
    assert.equal(translate(catalogs, 'vi', 'Save', { count: 4 }), 'Lưu');
  });
});

describe('locale matching', () => {
  test('matches on the primary subtag, so vi-VN is Vietnamese', () => {
    assert.equal(matchLocale(['vi-VN']), 'vi');
    assert.equal(matchLocale(['VI']), 'vi');
    assert.equal(matchLocale(['en-GB', 'vi']), 'en');
  });

  test('returns null when nothing matches, so the caller decides the default', () => {
    assert.equal(matchLocale(['fr-FR', 'de']), null);
    assert.equal(matchLocale([]), null);
  });

  test('isLocale rejects anything not in the list', () => {
    assert.equal(isLocale('vi'), true);
    assert.equal(isLocale('fr'), false);
    assert.equal(isLocale(undefined), false);
  });
});

// The provider's FIRST render takes this, so a stored Vietnamese is what the first frame paints -
// it used to start in English and correct itself in an effect, a flash on every load.
describe('the first frame', () => {
  // A browser for the length of one test: `window`, and a localStorage holding `stored`.
  function withBrowser(stored: string | null, run: () => void) {
    const g = globalThis as Record<string, unknown>;
    const had = {
      window: Object.getOwnPropertyDescriptor(g, 'window'),
      localStorage: Object.getOwnPropertyDescriptor(g, 'localStorage'),
    };
    Object.defineProperty(g, 'window', { value: g, configurable: true, writable: true });
    Object.defineProperty(g, 'localStorage', {
      value: { getItem: (key: string) => (key === 'proxyma.locale' ? stored : null), setItem: () => {} },
      configurable: true,
      writable: true,
    });
    try {
      run();
    } finally {
      for (const name of ['window', 'localStorage'] as const) {
        const descriptor = had[name];
        if (descriptor) Object.defineProperty(g, name, descriptor);
        else delete g[name];
      }
    }
  }

  test('a stored Vietnamese is the locale of the first render', () => {
    withBrowser('vi', () => assert.equal(initialLocale(), 'vi'));
  });

  test('nothing stored, or something unknown stored, is English', () => {
    withBrowser(null, () => assert.equal(initialLocale(), 'en'));
    withBrowser('fr', () => assert.equal(initialLocale(), 'en'));
  });

  test('a forced locale wins over the stored one', () => {
    withBrowser('vi', () => assert.equal(initialLocale('en'), 'en'));
  });

  test('a server render, with no window, starts at the default', () => {
    assert.equal(typeof (globalThis as Record<string, unknown>).window, 'undefined');
    assert.equal(initialLocale(), DEFAULT_LOCALE);
    assert.equal(initialLocale('vi'), 'vi');
  });
});
