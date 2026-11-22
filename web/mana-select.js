// Mana select for <select data-mana-select>: same trigger/listbox markup and classes as BotActivity's
// SettingsDialog language picker. The native <select> stays in the DOM (hidden) as the source of truth,
// so `.value`, `onchange` and option updates in app.js keep working unchanged.
const CHEVRON =
  '<svg class="mana-select-chevron" width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path d="M5.3 9.3a1 1 0 0 1 1.4 0l5.3 5.29 5.3-5.3a1 1 0 1 1 1.4 1.42l-6 6a1 1 0 0 1-1.4 0l-6-6a1 1 0 0 1 0-1.42Z"/></svg>';
const CHECK =
  '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false"><path fill-rule="evenodd" clip-rule="evenodd" d="M19.06 6.94a1.5 1.5 0 0 1 0 2.12l-8 8a1.5 1.5 0 0 1-2.12 0l-4-4a1.5 1.5 0 0 1 2.12-2.12L10 13.88l6.94-6.94a1.5 1.5 0 0 1 2.12 0Z"/></svg>';

let uid = 0;

function enhance(select) {
  const id = `mana-select-${++uid}`;
  select.classList.add('mana-visually-hidden');
  select.tabIndex = -1;
  select.setAttribute('aria-hidden', 'true');

  const trigger = document.createElement('button');
  trigger.type = 'button';
  trigger.className = 'mana-select-trigger';
  trigger.dataset.fullWidth = '';
  trigger.setAttribute('role', 'combobox');
  trigger.setAttribute('aria-haspopup', 'listbox');
  trigger.setAttribute('aria-expanded', 'false');
  const labelEl = select.id && document.querySelector(`label[for="${select.id}"]`);
  if (labelEl) trigger.setAttribute('aria-labelledby', (labelEl.id ||= `${id}-label`));
  trigger.innerHTML = `<span class="mana-text-md-normal mana-select-value"></span>${CHEVRON}`;
  select.after(trigger);
  const valueEl = trigger.firstChild;

  let list = null;
  let active = -1;

  const options = () => [...select.options];
  const syncValue = () => {
    valueEl.textContent = select.selectedOptions[0]?.textContent ?? '';
  };

  function setActive(i, scroll = true) {
    const items = list?.children;
    if (!items?.length) return;
    active = Math.max(0, Math.min(items.length - 1, i));
    for (const [n, el] of [...items].entries()) el.toggleAttribute('data-active', n === active);
    trigger.setAttribute('aria-activedescendant', items[active].id);
    if (scroll) items[active].scrollIntoView({ block: 'nearest' });
  }

  function place() {
    const r = trigger.getBoundingClientRect();
    const below = window.innerHeight - r.bottom - 12;
    const above = r.top - 12;
    const up = below < 180 && above > below;
    Object.assign(list.style, {
      left: `${r.left}px`,
      width: `${r.width}px`,
      maxHeight: `${Math.min(280, up ? above : below)}px`,
      top: up ? 'auto' : `${r.bottom + 4}px`,
      bottom: up ? `${window.innerHeight - r.top + 4}px` : 'auto',
    });
  }

  function open() {
    if (list || select.disabled || !select.options.length) return;
    list = document.createElement('div');
    list.className = 'mana-select-listbox';
    list.id = `${id}-list`;
    list.setAttribute('role', 'listbox');
    list.style.cssText = 'position:fixed;z-index:var(--mana-z-popout)';
    options().forEach((o, i) => {
      const el = document.createElement('div');
      el.className = 'mana-select-option';
      el.id = `${id}-opt-${i}`;
      el.setAttribute('role', 'option');
      el.setAttribute('aria-selected', String(o.selected));
      el.innerHTML = `<span class="mana-select-option-text"><span class="mana-text-md-normal mana-select-option-label"></span></span><span class="mana-select-option-check">${o.selected ? CHECK : ''}</span>`;
      el.querySelector('.mana-select-option-label').textContent = o.textContent;
      el.onpointerdown = (e) => e.preventDefault(); // keep focus on the trigger
      el.onclick = () => choose(i);
      list.append(el);
    });
    document.body.append(list);
    place();
    trigger.setAttribute('aria-expanded', 'true');
    trigger.setAttribute('aria-controls', list.id);
    trigger.toggleAttribute('data-open', true);
    setActive(select.selectedIndex);
    addEventListener('resize', close);
    addEventListener('scroll', onScroll, true);
    document.addEventListener('pointerdown', onOutside, true);
  }

  function close() {
    if (!list) return;
    list.remove();
    list = null;
    trigger.setAttribute('aria-expanded', 'false');
    trigger.removeAttribute('aria-controls');
    trigger.removeAttribute('aria-activedescendant');
    trigger.removeAttribute('data-open');
    removeEventListener('resize', close);
    removeEventListener('scroll', onScroll, true);
    document.removeEventListener('pointerdown', onOutside, true);
  }

  const onScroll = (e) => {
    if (!list?.contains(e.target)) close();
  };
  const onOutside = (e) => {
    if (!list?.contains(e.target) && !trigger.contains(e.target)) close();
  };

  function choose(i) {
    if (select.selectedIndex !== i) {
      select.selectedIndex = i;
      select.dispatchEvent(new Event('change', { bubbles: true }));
    }
    syncValue();
    close();
    trigger.focus();
  }

  trigger.onclick = () => (list ? close() : open());
  trigger.onkeydown = (e) => {
    const n = select.options.length;
    if (!list) {
      if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) {
        e.preventDefault();
        open();
      }
      return;
    }
    switch (e.key) {
      case 'ArrowDown': setActive(active + 1); break;
      case 'ArrowUp': setActive(active - 1); break;
      case 'Home': setActive(0); break;
      case 'End': setActive(n - 1); break;
      case 'Enter':
      case ' ': choose(active); break;
      case 'Escape': close(); break;
      case 'Tab': close(); return;
      default: return;
    }
    e.preventDefault();
  };
  select.addEventListener('focus', () => trigger.focus());
  select.addEventListener('change', syncValue);
  // app.js fills <option>s and sets .value programmatically; neither fires events.
  new MutationObserver(() => {
    syncValue();
    close();
  }).observe(select, { childList: true });
  syncValue();
}

for (const s of document.querySelectorAll('select[data-mana-select]')) enhance(s);
