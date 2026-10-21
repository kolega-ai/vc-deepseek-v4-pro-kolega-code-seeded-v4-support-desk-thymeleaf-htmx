/* RelayDesk — progressive-enhancement UI behaviour (vanilla JS, no deps beyond htmx). */
(function () {
  'use strict';

  /* 1. htmx CSRF hook: every htmx request carries the session CSRF token. */
  document.body.addEventListener('htmx:configRequest', function (evt) {
    var meta = document.querySelector('meta[name="_csrf"]');
    if (meta && meta.content) {
      evt.detail.headers['X-CSRF-TOKEN'] = meta.content;
    }
  });

  /* 2. HX-Trigger flash → toast. Backend returns HX-Trigger: {"relayFlash":{"kind","message"}}. */
  function parseFlash(evt) {
    try {
      if (!evt.detail || !evt.detail.xhr) return null;
      var raw = evt.detail.xhr.getResponseHeader('HX-Trigger');
      if (!raw) return null;
      var data = JSON.parse(raw);
      return data.relayFlash || null;
    } catch (e) {
      return null;
    }
  }

  function showToast(kind, message) {
    var container = document.getElementById('toast-container');
    if (!container) return;
    var toast = document.createElement('div');
    toast.className = 'toast toast--' + (kind === 'error' ? 'error' : 'success');
    toast.setAttribute('role', kind === 'error' ? 'alert' : 'status');
    var text = document.createElement('span');
    text.textContent = message;
    toast.appendChild(text);
    container.appendChild(toast);
    setTimeout(function () {
      toast.classList.add('is-leaving');
      setTimeout(function () { toast.remove(); }, 350);
    }, 4500);
  }

  ['htmx:afterSwap', 'htmx:responseError'].forEach(function (name) {
    document.body.addEventListener(name, function (evt) {
      var flash = parseFlash(evt);
      if (flash && flash.message) showToast(flash.kind, flash.message);
    });
  });

  /* 3. data-confirm: confirm() gate for forms, buttons and links. */
  var confirmed = new WeakSet();
  document.addEventListener('click', function (evt) {
    var el = evt.target instanceof Element ? evt.target.closest('[data-confirm]') : null;
    if (!el) return;
    if (confirmed.has(el)) { confirmed.delete(el); return; }
    evt.preventDefault();
    var message = el.getAttribute('data-confirm') || 'Are you sure?';
    if (window.confirm(message)) {
      confirmed.add(el);
      el.click();
    }
  });

  /* 4. Dropdown toggles (notification bell, user menu). */
  var openDropdown = null;
  function closeDropdown() {
    if (openDropdown) { openDropdown.hidden = true; openDropdown = null; }
  }
  document.addEventListener('click', function (evt) {
    var btn = evt.target instanceof Element ? evt.target.closest('[data-dropdown-toggle]') : null;
    var target = btn ? document.getElementById(btn.getAttribute('data-dropdown-toggle')) : null;
    if (btn && target) {
      evt.preventDefault();
      if (target.hidden) {
        closeDropdown();
        target.hidden = false;
        openDropdown = target;
      } else {
        closeDropdown();
      }
      return;
    }
    var panel = evt.target instanceof Element ? evt.target.closest('.dropdown-panel') : null;
    if (!panel) closeDropdown();
  });

  /* 5. Mobile sidebar drawer + overlay. */
  var drawerToggle = document.getElementById('sidebar-toggle');
  var overlay = document.getElementById('sidebar-overlay');
  if (drawerToggle) {
    drawerToggle.addEventListener('click', function () {
      var open = document.body.classList.toggle('drawer-open');
      if (overlay) overlay.hidden = !open;
    });
  }
  if (overlay) {
    overlay.addEventListener('click', function () {
      document.body.classList.remove('drawer-open');
      overlay.hidden = true;
    });
  }

  /* 6. File input: show chosen filename on the paired label. */
  document.addEventListener('change', function (evt) {
    var input = evt.target;
    if (!input || input.type !== 'file') return;
    var label = input.parentElement ? input.parentElement.querySelector('[data-file-label]') : null;
    if (!label) return;
    if (input.files && input.files.length) {
      label.textContent = input.files.length === 1 ? input.files[0].name : input.files.length + ' files chosen';
    } else {
      label.textContent = label.getAttribute('data-file-label-default') || 'Choose a file…';
    }
  });

  /* 7. Tabs (role=tablist). */
  function activateTab(tab, list) {
    var tabs = list.querySelectorAll('[role="tab"]');
    for (var i = 0; i < tabs.length; i++) {
      var on = tabs[i] === tab;
      tabs[i].setAttribute('aria-selected', on ? 'true' : 'false');
      tabs[i].classList.toggle('is-active', on);
      tabs[i].tabIndex = on ? 0 : -1;
      var id = tabs[i].getAttribute('data-tab-target') || ('#' + tabs[i].getAttribute('aria-controls'));
      var panel = id ? document.querySelector(id) : null;
      if (panel) panel.hidden = !on;
    }
    if (window.history && history.replaceState) history.replaceState(null, '', '#' + (tab.getAttribute('aria-controls') || ''));
  }
  document.addEventListener('click', function (evt) {
    var tab = evt.target instanceof Element ? evt.target.closest('[role="tab"]') : null;
    if (!tab) return;
    var list = tab.closest('[role="tablist"]');
    if (list) activateTab(tab, list);
  });
  document.addEventListener('keydown', function (evt) {
    var tab = evt.target instanceof Element ? evt.target.closest('[role="tab"]') : null;
    if (!tab || !list) return;
    var list = tab.closest('[role="tablist"]');
    var tabs = list ? list.querySelectorAll('[role="tab"]') : [];
    var idx = Array.prototype.indexOf.call(tabs, tab);
    var next = null;
    if (evt.key === 'ArrowRight') next = tabs[(idx + 1) % tabs.length];
    if (evt.key === 'ArrowLeft') next = tabs[(idx - 1 + tabs.length) % tabs.length];
    if (evt.key === 'Home') next = tabs[0];
    if (evt.key === 'End') next = tabs[tabs.length - 1];
    if (next) { evt.preventDefault(); next.focus(); activateTab(next, list); }
  });

  /* 8. data-autosubmit: submit the nearest form when a select changes. */
  document.addEventListener('change', function (evt) {
    var el = evt.target;
    if (!el || !el.matches || !el.matches('[data-autosubmit]')) return;
    var form = el.closest('form');
    if (!form) return;
    if (window.htmx && form.hasAttribute('hx-get')) {
      htmx.trigger(form, 'submit');
    } else {
      form.submit();
    }
  });

  /* 9. ISO timestamps → localised text; tab initialisation. */
  function init() {
    var timestamps = document.querySelectorAll('[data-timestamp]');
    for (var i = 0; i < timestamps.length; i++) {
      var el = timestamps[i];
      var value = el.getAttribute('data-timestamp');
      if (!value) continue;
      var d = new Date(value);
      if (isNaN(d.getTime())) continue;
      el.textContent = d.toLocaleString();
    }
    var lists = document.querySelectorAll('[role="tablist"]');
    for (var j = 0; j < lists.length; j++) {
      var active = lists[j].querySelector('[role="tab"].is-active') || lists[j].querySelector('[role="tab"]');
      if (active) activateTab(active, lists[j]);
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
  document.body.addEventListener('htmx:afterSwap', init);
})();
