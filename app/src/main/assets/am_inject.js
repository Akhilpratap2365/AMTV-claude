(function () {
  'use strict';
  var TARGET_WIDTH = 1600;

  // ---------------------------------------------------------------------------
  // applyGuaranteed1600pxViewport (page side).
  // The native side already lays the page out at exactly 1600 CSS px. This keeps
  // the viewport meta in agreement (for WebView builds that honour it), and
  // returns the real window.innerWidth so Java can verify / self-correct.
  // ---------------------------------------------------------------------------
  if (!window.__applyGuaranteed1600pxViewport) {
    var WANT = 'width=' + TARGET_WIDTH + ', user-scalable=no';
    window.__applyGuaranteed1600pxViewport = function () {
      try {
        var metas = document.querySelectorAll('meta[name="viewport"]');
        for (var i = 0; i < metas.length; i++) {
          if (metas[i].getAttribute('content') !== WANT) metas[i].setAttribute('content', WANT);
        }
        if (!metas.length) {
          var parent = document.head || document.documentElement;
          if (parent) {
            var m = document.createElement('meta');
            m.setAttribute('name', 'viewport');
            m.setAttribute('content', WANT);
            parent.insertBefore(m, parent.firstChild);
          }
        }
      } catch (e) {}
      return window.innerWidth;
    };
    window.__applyGuaranteed1600pxViewport();
    // Keep it locked even if the page inserts / rewrites its own viewport meta.
    try {
      new MutationObserver(function () { window.__applyGuaranteed1600pxViewport(); })
        .observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
    } catch (e) {}
    window.addEventListener('resize', function () { window.__applyGuaranteed1600pxViewport(); });
  }

  // ---------------------------------------------------------------------------
  // Performance CSS. Everything here removes GPU/main-thread work that a 2 GB
  // TV cannot keep up with. No colours or themes are touched (native dark only).
  // ---------------------------------------------------------------------------
  var root = document.head || document.documentElement;
  if (root && !document.getElementById('__am_tv_css__')) {
    var style = document.createElement('style');
    style.id = '__am_tv_css__';
    style.textContent =
      'html, body { -webkit-tap-highlight-color: transparent !important; scrollbar-width: none !important; touch-action: manipulation !important; }' +
      '::-webkit-scrollbar { width: 0 !important; height: 0 !important; display: none !important; }' +
      'a, button, input, textarea, select { touch-action: manipulation !important; -webkit-tap-highlight-color: transparent !important; outline: none !important; }' +
      'input, textarea, select { font-size: 16px !important; }' +
      // Lyrics: per-line blur/shadow filters are the single most expensive thing on TV GPUs.
      '[class*="lyric" i], [class*="lyric" i] *, amp-lyrics, amp-lyrics * {' +
      '  filter: none !important; -webkit-filter: none !important;' +
      '  backdrop-filter: none !important; -webkit-backdrop-filter: none !important;' +
      '  text-shadow: none !important; }' +
      // Frosted-glass bars (sidebar / header / player) re-blur the whole area on every scroll frame.
      '* { backdrop-filter: none !important; -webkit-backdrop-filter: none !important; }';
    root.appendChild(style);
  }

  window.__applyGuaranteed1600pxViewport();

  // Hide "Open in Music" prompts (deep links that do nothing on TV).
  if (!window.__amCleaned) {
    window.__amCleaned = true;
    var clean = function () {
      try {
        var els = document.querySelectorAll('nav a, nav button');
        for (var i = 0; i < els.length; i++) {
          var el = els[i];
          var t = (el.textContent || '').trim().toLowerCase();
          var href = (el.getAttribute('href') || '').toLowerCase();
          if (t === 'open in music' || t === 'open in itunes' ||
              href.indexOf('itmss://') === 0 || href.indexOf('musics://') === 0) {
            el.style.setProperty('display', 'none', 'important');
          }
        }
      } catch (e) {}
    };
    setTimeout(clean, 2500);
    setTimeout(clean, 8000);
  }
})();
