/* Proxyma documentation theme - behavior. No dependencies, no network.

   1. Appearance: a Light/Dark switch beside the logo, Light by default. Dark is the product's
      neutral Dark (data-mode="dark" data-shade="black"). The pre-paint snippet in <head> applies
      the stored choice before first paint; this wires the switch.
   2. Scroll spy: the table of contents marks the section being read.
   3. Per-section disclosure in the table of contents, and Expand all / Collapse all.
   4. The table of contents' width: a separator dragged or stepped by keyboard, remembered.
   5. A wheel over the table of contents scrolls the contents and never the page.
   6. A figure's maximize button: the figure as large as the window, until Minimize or Escape.

   Everything degrades to a plain scrolling document with anchor links if this does not run. */
(function () {
  'use strict';

  var KEY = 'proxyma-docs-theme';

  function stored() {
    try { return localStorage.getItem(KEY) === 'dark'; } catch (e) { return false; }
  }

  function apply(dark) {
    var el = document.documentElement;
    el.setAttribute('data-mode', dark ? 'dark' : 'light');
    if (dark) el.setAttribute('data-shade', 'black'); else el.removeAttribute('data-shade');
    var sw = document.querySelector('.appearance');
    if (sw) sw.setAttribute('aria-checked', String(dark));
  }

  function wireAppearance() {
    var sw = document.querySelector('.appearance');
    if (!sw) return;
    sw.addEventListener('click', function () {
      var dark = sw.getAttribute('aria-checked') !== 'true';
      try {
        if (dark) localStorage.setItem(KEY, 'dark'); else localStorage.removeItem(KEY);
      } catch (e) { /* storage blocked: still applies, for this visit */ }
      apply(dark);
    });
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
    var carets = Array.prototype.slice.call(document.querySelectorAll('.toc-caret'));
    carets.forEach(function (btn) {
      var sub = document.getElementById(btn.getAttribute('aria-controls'));
      if (!sub) return;
      btn.addEventListener('click', function (e) {
        e.preventDefault();
        setExpanded(btn, sub, btn.getAttribute('aria-expanded') !== 'true');
      });
    });
    var tools = document.querySelector('.toc-tools');
    if (!tools || !carets.length) return;
    tools.removeAttribute('hidden');
    Array.prototype.forEach.call(tools.querySelectorAll('button[data-toc-all]'), function (b) {
      var open = b.getAttribute('data-toc-all') === 'open';
      b.addEventListener('click', function () {
        carets.forEach(function (btn) {
          var sub = document.getElementById(btn.getAttribute('aria-controls'));
          if (sub) setExpanded(btn, sub, open);
        });
      });
    });
  }

  // 5. The wheel over the contents panel. `overscroll-behavior: contain` (doc-theme.css) stops a
  // scroll at either end of the contents from carrying on into the page, but only where the
  // contents CAN scroll; a short list, or the panel's head above the list, handed the wheel to the
  // page (maintainer, 2026-10-16: "scrolling the table of content must NOT scroll the page"). Over
  // the head the wheel scrolls the list; where the list cannot scroll it scrolls nothing. Only while
  // the panel sits beside the page - stacked above it on a narrow window, it is part of the page.
  function wireTocWheel() {
    var toc = document.querySelector('.toc');
    var box = document.querySelector('.toc-body');
    if (!toc || !box) return;
    var side = window.matchMedia ? window.matchMedia('(min-width: 961px)') : null;
    toc.addEventListener('wheel', function (e) {
      if (side && !side.matches) return;
      if (e.ctrlKey) return; // a pinch or Ctrl+wheel zooms
      if (box.contains(e.target) && box.scrollHeight > box.clientHeight) return;
      e.preventDefault();
      var dy = e.deltaMode === 1 ? e.deltaY * 16 : e.deltaMode === 2 ? e.deltaY * box.clientHeight : e.deltaY;
      box.scrollTop += dy;
    }, { passive: false });
  }

  // 6. Figures. Each gets a maximize button in its top right corner, which opens a copy of it in a
  // lightbox as large as the window; the copy's own button minimizes, and so do Escape and a click
  // on the darkened page around it. Focus goes to Minimize, and back to the button that opened it.
  // A copy rather than the figure itself, so the page under the lightbox does not reflow.
  var MAXIMIZE = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9.5 2.5h4v4M13.5 2.5L9 7M6.5 13.5h-4v-4M2.5 13.5L7 9"/></svg>';
  var MINIMIZE = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M13.5 6.5h-4v-4M9.5 6.5L14 2M2.5 9.5h4v4M6.5 9.5L2 14"/></svg>';

  function wireFigures() {
    // The document's own figures - not a figure a live kit specimen draws (`.kit`, the UI guideline).
    var figures = Array.prototype.filter.call(document.querySelectorAll('.content figure'), function (f) {
      return !f.closest('.kit');
    });
    if (!figures.length) return;
    var root = document.documentElement;
    var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var open = null;

    function close() {
      if (!open) return;
      var box = open.box, opener = open.opener;
      open = null;
      document.removeEventListener('keydown', onKey, true);
      box.classList.remove('is-open');
      root.classList.remove('fig-open');
      var done = function () { if (box.parentNode) box.parentNode.removeChild(box); };
      if (reduced) done(); else { box.addEventListener('transitionend', done, { once: true }); setTimeout(done, 400); }
      opener.focus();
    }

    function onKey(e) {
      if (e.key === 'Escape') { e.preventDefault(); close(); }
      // Focus stays on Minimize, the lightbox's one control, rather than leaving for the page.
      else if (e.key === 'Tab' && open) { e.preventDefault(); open.min.focus(); }
    }

    function maximize(figure, opener) {
      if (open) close();
      var copy = figure.cloneNode(true);
      copy.removeAttribute('id');
      Array.prototype.forEach.call(copy.querySelectorAll('[id]'), function (el) { el.removeAttribute('id'); });
      var min = copy.querySelector('.fig-max');
      min.innerHTML = MINIMIZE;
      min.setAttribute('aria-label', 'Minimize');
      min.setAttribute('title', 'Minimize (Esc)');
      min.addEventListener('click', close);
      var box = document.createElement('div');
      box.className = 'fig-lightbox';
      box.setAttribute('role', 'dialog');
      box.setAttribute('aria-modal', 'true');
      var cap = figure.querySelector('figcaption');
      box.setAttribute('aria-label', cap ? cap.textContent.trim() : 'Figure');
      box.addEventListener('click', function (e) { if (e.target === box) close(); });
      box.appendChild(copy);
      document.body.appendChild(box);
      root.classList.add('fig-open');
      open = { box: box, opener: opener, min: min };
      document.addEventListener('keydown', onKey, true);
      // One frame at the start state, so the fade and the scale run.
      requestAnimationFrame(function () { requestAnimationFrame(function () { box.classList.add('is-open'); }); });
      min.focus();
    }

    Array.prototype.forEach.call(figures, function (figure) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'fig-max';
      b.innerHTML = MAXIMIZE;
      b.setAttribute('aria-label', 'Maximize');
      b.setAttribute('title', 'Maximize');
      b.addEventListener('click', function () { maximize(figure, b); });
      figure.insertBefore(b, figure.firstChild);
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

  function init() { wireAppearance(); wireCarets(); wireScrollSpy(); wireResize(); wireTocWheel(); wireFigures(); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
