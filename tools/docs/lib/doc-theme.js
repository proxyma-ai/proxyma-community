/* Proxyma documentation theme - behavior. No dependencies, no network.

   1. Appearance: System / Light / Proxyma / Dark, the product's own four choices and the same
      stored words (system, light, gray, black - see libs/ui-kit/react/appearance.ts). The pre-paint
      snippet in <head> applies it before first paint; this wires the control and follows the OS.
   2. Scroll spy: the table of contents marks the section being read.
   3. Per-section disclosure in the table of contents.
   4. The table of contents' width: a separator dragged or stepped by keyboard, remembered.

   Everything degrades to a plain scrolling document with anchor links if this does not run. */
(function () {
  'use strict';

  var KEY = 'proxyma-docs-appearance';
  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function stored() {
    try { return localStorage.getItem(KEY) || 'system'; } catch (e) { return 'system'; }
  }

  // A dark OS means Dark (the neutral shade), as in the product; Proxyma is chosen on purpose.
  function apply(pref) {
    var el = document.documentElement;
    var dark = media && media.matches;
    var mode = pref === 'light' ? 'light' : pref === 'gray' || pref === 'black' ? 'dark' : (dark ? 'dark' : 'light');
    var shade = pref === 'black' || (pref === 'system' && dark) ? 'black' : null;
    el.setAttribute('data-mode', mode);
    if (shade) el.setAttribute('data-shade', shade); else el.removeAttribute('data-shade');
    var buttons = document.querySelectorAll('.appearance button');
    Array.prototype.forEach.call(buttons, function (b) {
      b.setAttribute('aria-pressed', String(b.getAttribute('data-pref') === pref));
    });
  }

  function wireAppearance() {
    var buttons = document.querySelectorAll('.appearance button');
    Array.prototype.forEach.call(buttons, function (b) {
      b.addEventListener('click', function () {
        var pref = b.getAttribute('data-pref');
        try { localStorage.setItem(KEY, pref); } catch (e) { /* storage blocked: still applies */ }
        apply(pref);
      });
    });
    if (media && media.addEventListener) {
      media.addEventListener('change', function () { if (stored() === 'system') apply('system'); });
    }
    apply(stored());
  }

  function wireScrollSpy() {
    var links = Array.prototype.slice.call(document.querySelectorAll('.toc nav a[href^="#"]'));
    if (!links.length) return;
    var byId = {};
    var targets = [];
    links.forEach(function (a) {
      var el = document.getElementById(decodeURIComponent(a.getAttribute('href').slice(1)));
      if (el) { byId[el.id] = a; targets.push(el); }
    });
    if (!targets.length) return;

    // Keeps the active row in view by scrolling the CONTENTS PANEL alone. It used scrollIntoView,
    // which scrolls every scrollable ancestor - the page too - so it broke off the smooth scroll a
    // contents click had started and left the reader at the wrong section (2026-10-15).
    function keepInView(a) {
      var box = document.querySelector('.toc-body');
      if (!box || box.scrollHeight <= box.clientHeight) return;
      var r = a.getBoundingClientRect(), b = box.getBoundingClientRect();
      if (r.top < b.top) box.scrollTop -= b.top - r.top + 8;
      else if (r.bottom > b.bottom) box.scrollTop += r.bottom - b.bottom + 8;
    }

    function activate(id) {
      links.forEach(function (a) { a.classList.remove('active'); });
      var a = byId[id];
      if (!a) return;
      a.classList.add('active');
      reveal(a);
      keepInView(a);
    }

    // A click names the section: it is marked at once, and the spy holds off until the scroll it
    // started has settled, instead of marking every section the page passes on the way.
    // A jump of more than two screens goes there at once: smooth scrolling through a document of
    // several hundred thousand pixels took over a second and a half to arrive.
    var heldUntil = 0;
    links.forEach(function (a) {
      a.addEventListener('click', function (e) {
        var id = decodeURIComponent(a.getAttribute('href').slice(1));
        var el = document.getElementById(id);
        if (!byId[id] || !el) return;
        heldUntil = Date.now() + 900;
        activate(id);
        if (Math.abs(el.getBoundingClientRect().top) > window.innerHeight * 2) {
          e.preventDefault();
          if (history.pushState) history.pushState(null, '', '#' + id);
          el.scrollIntoView({ behavior: 'instant', block: 'start' });
        }
      });
    });

    if ('IntersectionObserver' in window) {
      var visible = {};
      var obs = new IntersectionObserver(function (entries) {
        entries.forEach(function (e) { if (e.isIntersecting) visible[e.target.id] = true; else delete visible[e.target.id]; });
        if (Date.now() < heldUntil) return;
        for (var i = 0; i < targets.length; i++) { if (visible[targets[i].id]) { activate(targets[i].id); return; } }
      }, { rootMargin: '0px 0px -70% 0px', threshold: 0 });
      targets.forEach(function (el) { obs.observe(el); });
    }
  }

  function setExpanded(btn, sub, open) {
    btn.setAttribute('aria-expanded', String(open));
    var label = btn.getAttribute('aria-label') || '';
    btn.setAttribute('aria-label', (open ? 'Collapse' : 'Expand') + label.replace(/^(Expand|Collapse)/, ''));
    if (open) sub.removeAttribute('hidden'); else sub.setAttribute('hidden', '');
  }

  function reveal(link) {
    var sub = link.closest('ol[id^="sub-"]');
    if (!sub) {
      var row = link.closest('.toc-row');
      var btn = row && row.querySelector('.toc-caret');
      if (btn) sub = document.getElementById(btn.getAttribute('aria-controls'));
    }
    if (!sub || !sub.hasAttribute('hidden')) return;
    var caret = document.querySelector('.toc-caret[aria-controls="' + sub.id + '"]');
    if (caret) setExpanded(caret, sub, true);
  }

  function wireCarets() {
    Array.prototype.forEach.call(document.querySelectorAll('.toc-caret'), function (btn) {
      var sub = document.getElementById(btn.getAttribute('aria-controls'));
      if (!sub) return;
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        setExpanded(btn, sub, btn.getAttribute('aria-expanded') !== 'true');
      });
    });
  }

  // 4. The contents panel's width.
  // The separator between the panel and the page: dragged with a mouse, pen or finger, stepped
  // with the arrow keys (16px, 64px with Shift; Home and End go to the ends), reset by a double
  // click. Remembered per reader; the pre-paint snippet in <head> applies it before first paint,
  // and its bounds are these - change both together. With storage blocked it still resizes, for
  // this visit. Below the stacked width the panel is above the page and the separator is hidden.
  var TOC_KEY = 'proxyma-docs-toc-width';
  var TOC_MIN = 200, TOC_MAX = 480, TOC_DEFAULT = 272;

  function wireResize() {
    var handle = document.querySelector('.toc-resize');
    var toc = document.querySelector('.toc');
    if (!handle || !toc) return;
    var root = document.documentElement;
    var width = TOC_DEFAULT;
    try {
      var saved = parseInt(localStorage.getItem(TOC_KEY), 10);
      if (saved >= TOC_MIN && saved <= TOC_MAX) width = saved;
    } catch (e) { /* storage blocked: the default */ }

    function set(w, save) {
      width = Math.max(TOC_MIN, Math.min(TOC_MAX, Math.round(w)));
      root.style.setProperty('--toc-width', width + 'px');
      handle.setAttribute('aria-valuenow', String(width));
      if (!save) return;
      try {
        if (width === TOC_DEFAULT) localStorage.removeItem(TOC_KEY);
        else localStorage.setItem(TOC_KEY, String(width));
      } catch (e) { /* storage blocked: kept for this visit only */ }
    }

    // Live only now: without this script the element is a plain divider, not a control.
    handle.setAttribute('tabindex', '0');
    handle.setAttribute('aria-controls', toc.id || 'toc');
    handle.setAttribute('aria-label', 'Resize the contents');
    handle.setAttribute('aria-valuemin', String(TOC_MIN));
    handle.setAttribute('aria-valuemax', String(TOC_MAX));
    set(width, false);

    handle.addEventListener('pointerdown', function (e) {
      if (e.button !== 0) return;
      e.preventDefault();
      var startX = e.clientX, start = width;
      try { handle.setPointerCapture(e.pointerId); } catch (err) { /* the pointer is gone */ }
      root.setAttribute('data-toc-resizing', '');
      function move(ev) { set(start + ev.clientX - startX, false); }
      function end() {
        root.removeAttribute('data-toc-resizing');
        handle.removeEventListener('pointermove', move);
        handle.removeEventListener('pointerup', end);
        handle.removeEventListener('pointercancel', end);
        set(width, true);
      }
      handle.addEventListener('pointermove', move);
      handle.addEventListener('pointerup', end);
      handle.addEventListener('pointercancel', end);
    });
    handle.addEventListener('dblclick', function () { set(TOC_DEFAULT, true); });
    handle.addEventListener('keydown', function (e) {
      var step = e.shiftKey ? 64 : 16, next = null;
      if (e.key === 'ArrowLeft') next = width - step;
      else if (e.key === 'ArrowRight') next = width + step;
      else if (e.key === 'Home') next = TOC_MIN;
      else if (e.key === 'End') next = TOC_MAX;
      if (next === null) return;
      e.preventDefault();
      set(next, true);
    });
  }

  function init() { wireAppearance(); wireCarets(); wireScrollSpy(); wireResize(); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
