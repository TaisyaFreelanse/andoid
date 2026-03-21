import { useState, useEffect, useMemo } from 'react';
import { tasksApi, TaskType } from '../api/tasks';
import { devicesApi, Device } from '../api/devices';
import './TaskForm.css';

interface TaskFormProps {
  onSuccess: () => void;
  onCancel: () => void;
}

type FormMode = 'simple' | 'advanced';

interface ActionBlock {
  id: string;
  type: 'navigate' | 'wait' | 'scroll' | 'extract' | 'screenshot' | 'click' | 'loop';
  url?: string;
  duration?: number;
  direction?: 'up' | 'down';
  pixels?: number;
  selector?: string;
  attribute?: string;
  saveName?: string;
  text?: string;
  loopCount?: number;
  loopActions?: ActionBlock[];
}

interface FormFields {
  name: string;
  type: TaskType;
  deviceId: string;
  targetDomain: string;
  countryCode: string;
  proxy: string;
  loopCount: number;
  humanLike: boolean;
  randomDelays: boolean;
  takeScreenshots: boolean;
  waitForAds: boolean;
  actions: ActionBlock[];
}

const COUNTRIES = [
  { code: 'US', label: 'США' },
  { code: 'GB', label: 'Великобритания' },
  { code: 'DE', label: 'Германия' },
  { code: 'FR', label: 'Франция' },
  { code: 'UA', label: 'Украина' },
  { code: 'RU', label: 'Россия' },
  { code: 'PL', label: 'Польша' },
  { code: 'IT', label: 'Италия' },
  { code: 'ES', label: 'Испания' },
  { code: 'BR', label: 'Бразилия' },
  { code: 'IN', label: 'Индия' },
  { code: 'JP', label: 'Япония' },
  { code: 'CA', label: 'Канада' },
  { code: 'AU', label: 'Австралия' },
];

const ACTION_LABELS: Record<ActionBlock['type'], string> = {
  navigate: 'Открыть страницу',
  wait: 'Подождать',
  scroll: 'Скролл',
  extract: 'Извлечь данные',
  screenshot: 'Скриншот',
  click: 'Клик',
  loop: 'Цикл',
};

const ACTION_ICONS: Record<ActionBlock['type'], string> = {
  navigate: '🌐',
  wait: '⏳',
  scroll: '↕️',
  extract: '📋',
  screenshot: '📸',
  click: '👆',
  loop: '🔄',
};

function uid(): string {
  return Math.random().toString(36).slice(2, 9);
}

function createDefaultAction(type: ActionBlock['type']): ActionBlock {
  const base: ActionBlock = { id: uid(), type };
  switch (type) {
    case 'navigate': return { ...base, url: '' };
    case 'wait': return { ...base, duration: 3 };
    case 'scroll': return { ...base, direction: 'down', pixels: 600 };
    case 'extract': return { ...base, selector: '', attribute: 'href', saveName: 'data' };
    case 'screenshot': return { ...base, saveName: 'screenshot' };
    case 'click': return { ...base, selector: '', text: '' };
    case 'loop': return { ...base, loopCount: 3, loopActions: [] };
  }
}

const GOOGLE_ADS_TEMPLATE: ActionBlock[] = [
  { id: uid(), type: 'navigate', url: '{{google_search}}' },
  { id: uid(), type: 'wait', duration: 4 },
  { id: uid(), type: 'scroll', direction: 'down', pixels: 400 },
  { id: uid(), type: 'wait', duration: 1.5 },
  { id: uid(), type: 'extract', selector: '{{domain_links}}', attribute: 'href', saveName: 'site_links' },
  { id: uid(), type: 'navigate', url: '{{from_results}}' },
  {
    id: uid(), type: 'loop', loopCount: 5, loopActions: [
      { id: uid(), type: 'wait', duration: 5 },
      { id: uid(), type: 'screenshot', saveName: 'ad_top' },
      { id: uid(), type: 'scroll', direction: 'down', pixels: 600 },
      { id: uid(), type: 'wait', duration: 2.5 },
      { id: uid(), type: 'screenshot', saveName: 'ad_mid' },
      { id: uid(), type: 'extract', selector: '{{ad_links}}', attribute: 'href', saveName: 'ad_links' },
      { id: uid(), type: 'scroll', direction: 'down', pixels: 800 },
      { id: uid(), type: 'wait', duration: 2 },
      { id: uid(), type: 'screenshot', saveName: 'ad_bottom' },
    ],
  },
];

const SIMPLE_PARSE_TEMPLATE: ActionBlock[] = [
  { id: uid(), type: 'navigate', url: '' },
  { id: uid(), type: 'wait', duration: 3 },
  { id: uid(), type: 'scroll', direction: 'down', pixels: 500 },
  { id: uid(), type: 'extract', selector: '', attribute: 'href', saveName: 'links' },
  { id: uid(), type: 'screenshot', saveName: 'page_result' },
];

/** Открыть api.ipify.org и скрин — на картинке один IP; сравни с прокси / без прокси. */
const PROXY_IP_CHECK_TEMPLATE: ActionBlock[] = [
  { id: uid(), type: 'navigate', url: 'https://api.ipify.org' },
  { id: uid(), type: 'wait', duration: 2 },
  { id: uid(), type: 'screenshot', saveName: 'ip_check' },
];

/** Копия шаблона с новыми id (вложенные loop тоже). */
function cloneActionTemplate(template: ActionBlock[]): ActionBlock[] {
  const fresh = JSON.parse(JSON.stringify(template)) as ActionBlock[];
  const assignIds = (actions: ActionBlock[]) => {
    for (const a of actions) {
      a.id = uid();
      if (a.loopActions) assignIds(a.loopActions);
    }
  };
  assignIds(fresh);
  return fresh;
}

/** Все вхождения site:example.com / site%3Aexample.com в строке (для вложенных Google URL). */
function collectSiteDomainsFromText(s: string): string[] {
  const decoded = (() => {
    try {
      return decodeURIComponent(s.replace(/\+/g, ' '));
    } catch {
      return s;
    }
  })();
  const out: string[] = [];
  const reList = [
    /site:\s*([a-z0-9][a-z0-9.-]*\.[a-z]{2,})/gi,
    /site%3A([a-z0-9][a-z0-9.-]*\.[a-z]{2,})/gi,
  ];
  for (const re of reList) {
    let m: RegExpExecArray | null;
    const r = new RegExp(re.source, re.flags);
    while ((m = r.exec(decoded)) !== null) {
      out.push(m[1].toLowerCase());
    }
  }
  return out;
}

function pickBestSiteDomain(candidates: string[]): string | null {
  const isGoogle = (h: string) =>
    h === 'google.com' || h.endsWith('.google.com') || h === 'gstatic.com';
  const nonGoogle = candidates.filter((h) => !isGoogle(h));
  if (nonGoogle.length > 0) return nonGoogle[nonGoogle.length - 1];
  return candidates.length > 0 ? candidates[candidates.length - 1] : null;
}

function cleanDomain(raw: string): string {
  let d = raw.trim();
  if (!d) return d;

  try {
    const url = new URL(d.startsWith('http') ? d : `https://${d}`);
    const hostNorm = url.hostname.replace(/^www\./, '').toLowerCase();

    if (hostNorm === 'google.com') {
      const q = url.searchParams.get('q') || '';
      const fromQuery = pickBestSiteDomain(collectSiteDomainsFromText(q));
      if (fromQuery) d = fromQuery;
      else return hostNorm;
    } else {
      d = url.hostname;
    }
  } catch {
    d = d.replace(/^https?:\/\//, '');
    const nested = pickBestSiteDomain(collectSiteDomainsFromText(d));
    if (nested) return nested.replace(/^www\./, '');
  }

  return d.replace(/^www\./, '').split('/')[0].split('?')[0];
}

const AD_SELECTORS = "a[href*='adurl'], a[href*='googleads'], a[href*='doubleclick'], iframe[src*='googleads'], iframe[src*='doubleclick']";

function actionsToSteps(actions: ActionBlock[], rawDomain: string): any[] {
  const domain = cleanDomain(rawDomain);
  const steps: any[] = [];
  let stepIdx = 1;

  for (const action of actions) {
    switch (action.type) {
      case 'navigate':
        if (action.url === '{{google_search}}') {
          steps.push({
            id: `step_${stepIdx++}`, type: 'set_cookie',
            url: 'https://www.google.com',
            value: 'SOCS=CAISNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjQwMzEzLjA3X3AxGgJlbiADGgYIgI6tsgY; path=/; domain=.google.com; max-age=31536000; secure;;CONSENT=YES+eur.20240101-0; path=/; domain=.google.com; max-age=31536000',
            optional: true,
          });
          steps.push({
            id: `step_${stepIdx++}`, type: 'navigate',
            url: `https://www.google.com/search?q=site%3A${domain}&ie=UTF-8`,
            wait_for_load: true, timeout: 30000,
          });
          steps.push({
            id: `step_${stepIdx++}`, type: 'wait',
            duration: 3000, random_offset: 1000,
          });
          steps.push({
            id: `step_${stepIdx++}`, type: 'native_tap',
            value: '0.5,0.82', optional: true,
          });
          steps.push({
            id: `step_${stepIdx++}`, type: 'wait',
            duration: 2000,
          });
        } else if (action.url === '{{from_results}}') {
          steps.push({
            id: `step_${stepIdx++}`, type: 'navigate',
            from_results: 'site_links',
            loadTimeout: 20000, waitAfter: 5000,
          });
        } else {
          steps.push({
            id: `step_${stepIdx++}`, type: 'navigate',
            url: action.url || `https://${domain}`,
            wait_for_load: true, timeout: 30000,
          });
        }
        continue;
      case 'wait':
        steps.push({
          id: `step_${stepIdx}`, type: 'wait',
          duration: (action.duration || 3) * 1000,
          random_offset: Math.round((action.duration || 3) * 300),
        });
        break;
      case 'scroll':
        steps.push({
          id: `step_${stepIdx}`, type: 'scroll',
          direction: action.direction || 'down',
          pixels: action.pixels || 500,
          smooth: true,
        });
        break;
      case 'extract':
        if (action.selector === '{{domain_links}}') {
          steps.push({
            id: `step_${stepIdx}`, type: 'extract',
            selector: `a[href*='${domain}']`,
            attribute: 'href', save_as: 'site_links', multiple: true,
          });
        } else if (action.selector === '{{ad_links}}') {
          steps.push({
            id: `step_${stepIdx}`, type: 'extract',
            selector: AD_SELECTORS,
            attribute: 'href', save_as: 'ad_links', multiple: true, optional: true,
          });
        } else {
          steps.push({
            id: `step_${stepIdx}`, type: 'extract',
            selector: action.selector || 'a',
            attribute: action.attribute || 'href',
            save_as: action.saveName || 'data',
            multiple: true,
          });
        }
        break;
      case 'screenshot':
        steps.push({
          id: `step_${stepIdx}`, type: 'screenshot',
          save_as: action.saveName || `screenshot_${stepIdx}`,
        });
        break;
      case 'click':
        if (action.text) {
          steps.push({ id: `step_${stepIdx}`, type: 'click_text', value: action.text });
        } else {
          const sel = (action.selector || '').trim();
          // Пустой селектор → пробуем button, но не валим задачу (на многих сайтах нет <button>)
          steps.push({
            id: `step_${stepIdx}`,
            type: 'click',
            selector: sel || 'button',
            ...(!sel ? { optional: true } : {}),
          });
        }
        break;
      case 'loop':
        steps.push({
          id: `step_${stepIdx}`, type: 'loop',
          max_iterations: action.loopCount || 3,
          steps: action.loopActions ? actionsToSteps(action.loopActions, rawDomain) : [],
        });
        break;
    }
    stepIdx++;
  }
  return steps;
}

function buildScenarioJson(fields: FormFields): object {
  const domain = cleanDomain(fields.targetDomain);
  return {
    id: `task_${Date.now()}`,
    name: fields.name || `Задача ${domain}`,
    type: 'automation',
    browser: 'webview',
    proxy: fields.proxy || '',
    requires_root: true,
    timeout: 300000,
    retries: 2,
    config: {
      wait_for_ads: fields.waitForAds,
      take_screenshots: fields.takeScreenshots,
      human_like: fields.humanLike,
      random_delays: fields.randomDelays,
      min_delay: 1500,
      max_delay: 4000,
      use_proxy_geolocation: true,
      auto_detect_country: true,
    },
    variables: {
      target_domain: domain,
      country_code: fields.countryCode,
      proxy_url: fields.proxy || '',
      loop_count: String(fields.loopCount),
    },
    actions: [
      { id: 'a1', type: 'detect_proxy_location', save_as: 'proxy_location' },
      { id: 'a2', type: 'change_timezone', timezone: 'auto', country_code: fields.countryCode },
      { id: 'a3', type: 'change_location', latitude: 'auto', longitude: 'auto', country_code: fields.countryCode },
      { id: 'a4', type: 'change_locale', locale: fields.countryCode },
      { id: 'a5', type: 'change_user_agent', ua: 'random', locale: fields.countryCode },
      { id: 'a6', type: 'regenerate_android_id' },
      { id: 'a7', type: 'regenerate_aaid' },
      { id: 'a8', type: 'clear_chrome_data' },
      { id: 'a9', type: 'clear_webview_data' },
      { id: 'a10', type: 'modify_build_prop', params: { 'ro.build.fingerprint': 'random', 'ro.product.model': 'random' } },
    ],
    steps: actionsToSteps(fields.actions, fields.targetDomain),
    post_process: {
      extract_adurl: true,
      deduplicate_domains: true,
      save_to_backend: true,
      log_visited_urls: true,
      send_to_backend: true,
    },
    output: {
      format: 'json',
      include_screenshots: true,
      include_metadata: true,
    },
  };
}

function ActionBlockCard({
  action, onChange, onRemove, nested,
}: {
  action: ActionBlock;
  onChange: (updated: ActionBlock) => void;
  onRemove: () => void;
  nested?: boolean;
}) {
  return (
    <div className={`action-card ${nested ? 'nested' : ''}`}>
      <div className="action-card-header">
        <span className="action-icon">{ACTION_ICONS[action.type]}</span>
        <span className="action-label">{ACTION_LABELS[action.type]}</span>
        <button type="button" className="action-remove" onClick={onRemove} title="Удалить">&times;</button>
      </div>
      <div className="action-card-body">
        {action.type === 'navigate' && (
          action.url === '{{google_search}}'
            ? <div className="template-hint">Google: поиск по домену (site:...)</div>
            : action.url === '{{from_results}}'
              ? <div className="template-hint">Перейти на сайт из результатов поиска</div>
              : <input type="text" placeholder="https://example.com"
                  value={action.url || ''} onChange={e => onChange({ ...action, url: e.target.value })} />
        )}
        {action.type === 'wait' && (
          <div className="inline-field">
            <input type="number" min={0.5} step={0.5} value={action.duration || 3}
              onChange={e => onChange({ ...action, duration: parseFloat(e.target.value) || 1 })} />
            <span className="field-suffix">сек</span>
          </div>
        )}
        {action.type === 'scroll' && (
          <div className="inline-fields">
            <select value={action.direction || 'down'}
              onChange={e => onChange({ ...action, direction: e.target.value as 'up' | 'down' })}>
              <option value="down">Вниз</option>
              <option value="up">Вверх</option>
            </select>
            <div className="inline-field">
              <input type="number" min={100} step={100} value={action.pixels || 500}
                onChange={e => onChange({ ...action, pixels: parseInt(e.target.value) || 500 })} />
              <span className="field-suffix">px</span>
            </div>
          </div>
        )}
        {action.type === 'extract' && (
          action.selector === '{{domain_links}}'
            ? <div className="template-hint">Извлечь ссылки на целевой домен</div>
            : action.selector === '{{ad_links}}'
              ? <div className="template-hint">Извлечь рекламные ссылки (AdSense, DoubleClick)</div>
              : <>
                  <input type="text" placeholder="CSS-селектор (a, div.class, ...)"
                    value={action.selector || ''}
                    onChange={e => onChange({ ...action, selector: e.target.value })} />
                  <div className="inline-fields">
                    <input type="text" placeholder="Атрибут (href, src, text)"
                      value={action.attribute || 'href'}
                      onChange={e => onChange({ ...action, attribute: e.target.value })} />
                    <input type="text" placeholder="Сохранить как"
                      value={action.saveName || ''}
                      onChange={e => onChange({ ...action, saveName: e.target.value })} />
                  </div>
                </>
        )}
        {action.type === 'screenshot' && (
          <input type="text" placeholder="Имя скриншота"
            value={action.saveName || ''}
            onChange={e => onChange({ ...action, saveName: e.target.value })} />
        )}
        {action.type === 'click' && (
          <div className="click-block">
            <div className="inline-fields">
              <input type="text" placeholder="CSS (например a.menu, .btn) — пусто = button, шаг необязательный"
                value={action.selector || ''}
                onChange={e => onChange({ ...action, selector: e.target.value })} />
              <input type="text" placeholder="или текст кнопки (click_text)"
                value={action.text || ''}
                onChange={e => onChange({ ...action, text: e.target.value })} />
            </div>
            <p className="field-hint">Если селектор пустой, агент ищет <code>button</code> и не падает, если не нашёл.</p>
          </div>
        )}
        {action.type === 'loop' && (
          <>
            <div className="inline-field">
              <input type="number" min={1} max={100} value={action.loopCount || 3}
                onChange={e => onChange({ ...action, loopCount: parseInt(e.target.value) || 1 })} />
              <span className="field-suffix">повторений</span>
            </div>
            <div className="loop-actions">
              {(action.loopActions || []).map((la, i) => (
                <ActionBlockCard key={la.id} action={la} nested
                  onChange={updated => {
                    const copy = [...(action.loopActions || [])];
                    copy[i] = updated;
                    onChange({ ...action, loopActions: copy });
                  }}
                  onRemove={() => {
                    onChange({ ...action, loopActions: (action.loopActions || []).filter((_, j) => j !== i) });
                  }} />
              ))}
              <AddActionButton onAdd={type => {
                onChange({ ...action, loopActions: [...(action.loopActions || []), createDefaultAction(type)] });
              }} />
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function AddActionButton({ onAdd }: { onAdd: (type: ActionBlock['type']) => void }) {
  const [open, setOpen] = useState(false);
  const types: ActionBlock['type'][] = ['navigate', 'wait', 'scroll', 'extract', 'screenshot', 'click', 'loop'];
  return (
    <div className="add-action-wrapper">
      {open ? (
        <div className="add-action-menu">
          {types.map(t => (
            <button key={t} type="button" className="add-action-option"
              onClick={() => { onAdd(t); setOpen(false); }}>
              <span>{ACTION_ICONS[t]}</span> {ACTION_LABELS[t]}
            </button>
          ))}
          <button type="button" className="add-action-option cancel" onClick={() => setOpen(false)}>
            Отмена
          </button>
        </div>
      ) : (
        <button type="button" className="add-action-btn" onClick={() => setOpen(true)}>
          + Добавить действие
        </button>
      )}
    </div>
  );
}

export default function TaskForm({ onSuccess, onCancel }: TaskFormProps) {
  const [mode, setMode] = useState<FormMode>('simple');
  const [fields, setFields] = useState<FormFields>({
    name: '',
    type: 'parsing',
    deviceId: '',
    targetDomain: '',
    countryCode: 'US',
    proxy: '',
    loopCount: 5,
    humanLike: true,
    randomDelays: true,
    takeScreenshots: true,
    waitForAds: true,
    actions: [],
  });
  const [configJson, setConfigJson] = useState('{}');
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [formNotice, setFormNotice] = useState('');
  const [showJson, setShowJson] = useState(false);

  useEffect(() => { loadDevices(); }, []);

  const loadDevices = async () => {
    try {
      const data = await devicesApi.getAll();
      setDevices(data);
      const online = data.find(d => d.status === 'online');
      if (online) updateField('deviceId', online.id);
      else if (data.length > 0) updateField('deviceId', data[0].id);
    } catch (err) {
      console.error('Ошибка загрузки устройств:', err);
    }
  };

  const updateField = <K extends keyof FormFields>(key: K, value: FormFields[K]) => {
    setFields(prev => ({ ...prev, [key]: value }));
  };

  const generatedJson = useMemo(() => {
    try {
      return JSON.stringify(buildScenarioJson(fields), null, 2);
    } catch {
      return '{}';
    }
  }, [fields]);

  const applyTemplate = (template: ActionBlock[], name: string) => {
    const fresh = cloneActionTemplate(template);
    updateField('actions', fresh);
    if (!fields.name) updateField('name', name);
  };

  /** Шаблон + тот же JSON в редакторе + синхронные fields.actions (удобно из режима JSON). */
  const applyTemplateToFieldsAndJson = (
    template: ActionBlock[],
    defaultName: string,
    opts?: { allowEmptyDomain?: boolean },
  ) => {
    const fresh = cloneActionTemplate(template);
    const raw = fields.targetDomain.trim();
    const hadDomain = raw.length > 0;
    if (!hadDomain) {
      setFormNotice(
        opts?.allowEmptyDomain
          ? 'Домен пуст — для сценария подставлен example.com. Впиши свой домен в поле «Цель» и снова нажми этот шаблон.'
          : 'Домен не указан — подставлен example.com. Укажи свой домен в «Цели» и снова нажми шаблон или «Из формы».',
      );
    } else {
      setFormNotice('');
    }
    const next: FormFields = {
      ...fields,
      actions: fresh,
      name: fields.name || defaultName,
      targetDomain: hadDomain ? fields.targetDomain : 'example.com',
    };
    setFields(next);
    setConfigJson(JSON.stringify(buildScenarioJson(next), null, 2));
    setError('');
  };

  /** Самый простой тест прокси: IP на скрине с прокси vs без прокси. */
  const applyIpCheckTemplate = () => {
    const fresh = cloneActionTemplate(PROXY_IP_CHECK_TEMPLATE);
    const next: FormFields = {
      ...fields,
      actions: fresh,
      name: fields.name || 'Проверка IP (прокси)',
      targetDomain: fields.targetDomain.trim() || 'api.ipify.org',
    };
    setFields(next);
    setError('');
    setFormNotice(
      'С прокси в поле выше нажми «Создать», потом открой скрин **ip_check** в результате — там внешний IP. Повтори задачу с **пустым** прокси: если IP другой, прокси реально меняет выход.',
    );
    if (mode === 'advanced') {
      setConfigJson(JSON.stringify(buildScenarioJson(next), null, 2));
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setFormNotice('');

    let parsedConfig: any;

    if (mode === 'advanced') {
      try {
        parsedConfig = JSON.parse(configJson);
      } catch {
        setError('Неверный JSON');
        return;
      }
      const steps = parsedConfig?.steps;
      if (!Array.isArray(steps) || steps.length === 0) {
        setError(
          'В JSON нет шагов (steps пустой). В простом режиме нажми «Google + Реклама» или «Простой парсинг», затем «Из формы» — или вставь свой JSON со списком steps.',
        );
        return;
      }
    } else {
      if (!fields.targetDomain.trim()) {
        setError('Укажите домен / URL сайта');
        return;
      }
      if (fields.actions.length === 0) {
        setError(
          'Нет действий: нажми «Google + Реклама», «Простой парсинг» или «+ Добавить действие» — иначе задача сразу завершится без браузера.',
        );
        return;
      }
      parsedConfig = buildScenarioJson(fields);
    }

    setLoading(true);
    try {
      await tasksApi.create({
        name: fields.name || `Задача ${fields.targetDomain}`,
        type: fields.type,
        configJson: parsedConfig,
        deviceId: fields.deviceId || undefined,
      });
      onSuccess();
    } catch (err: any) {
      setError(err.response?.data?.error?.message || 'Ошибка создания задачи');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="task-form-container">
      <form onSubmit={handleSubmit} className="task-form">

        <div className="form-header">
          <div className="form-header-titles">
            <h3>Создать задачу</h3>
            <span className="ui-build-tag" title="Если эта метка не совпадает с последней — сайт на Render ещё со старой сборкой. Сделай push в main и дождись деплоя.">
              UI: {__UI_BUILD_TAG__}
            </span>
          </div>
          <div className="mode-switch">
            <button type="button" className={`mode-btn ${mode === 'simple' ? 'active' : ''}`}
              onClick={() => { setMode('simple'); setFormNotice(''); }}>Простой</button>
            <button type="button" className={`mode-btn ${mode === 'advanced' ? 'active' : ''}`}
              onClick={() => {
                setMode('advanced');
                setConfigJson(generatedJson);
                if (fields.actions.length === 0) {
                  setError('');
                  setFormNotice('Сейчас нет действий — JSON с пустым steps. Нажми «Google + Реклама» или «Простой парсинг» ниже, либо переключись в «Простой» и выбери шаблон.');
                } else {
                  setError('');
                  setFormNotice('');
                }
              }}>JSON</button>
          </div>
        </div>

        {error && <div className="error-message">{error}</div>}
        {formNotice && !error && <div className="form-notice">{formNotice}</div>}

        {/* Самый верх формы: не зависит от режима Простой/JSON; видно сразу после открытия */}
        <div className="form-card proxy-verify-card proxy-verify-card-top">
          <div className="proxy-verify-badge">API / IP</div>
          <div className="card-title proxy-verify-title">Проверка прокси — внешний IP</div>
          <p className="proxy-verify-text">
            Впиши прокси в блоке «Цель» ниже (или оставь пустым). Нажми кнопку — подставятся шаги:{' '}
            <strong>api.ipify.org</strong> + скрин <code>ip_check</code>. Потом <strong>Создать</strong> внизу.
            Два запуска (с прокси и без) — сравни IP на скринах.
          </p>
          <button type="button" className="proxy-ip-check-btn proxy-ip-check-btn-large" onClick={applyIpCheckTemplate}>
            Собрать сценарий проверки IP
          </button>
        </div>

        {mode === 'simple' ? (
          <>
            {/* --- Основное --- */}
            <div className="form-card">
              <div className="card-title">Основное</div>
              <div className="card-fields">
                <div className="form-group">
                  <label>Название</label>
                  <input type="text" placeholder="Парсинг example.com"
                    value={fields.name} onChange={e => updateField('name', e.target.value)} />
                </div>
                <div className="form-row">
                  <div className="form-group flex-1">
                    <label>Тип</label>
                    <select value={fields.type} onChange={e => updateField('type', e.target.value as TaskType)}>
                      <option value="parsing">Парсинг</option>
                      <option value="surfing">Серфинг</option>
                      <option value="uniqueness">Уникализация</option>
                      <option value="screenshot">Скриншот</option>
                    </select>
                  </div>
                  <div className="form-group flex-1">
                    <label>Устройство</label>
                    <select value={fields.deviceId} onChange={e => updateField('deviceId', e.target.value)}>
                      <option value="">-- Выбрать --</option>
                      {devices.map((d, i) => (
                        <option key={d.id} value={d.id}>
                          {String(i + 1).padStart(2, '0')} — {d.name || d.androidId?.substring(0, 8) || 'Device'} ({d.status})
                        </option>
                      ))}
                    </select>
                  </div>
                </div>
              </div>
            </div>

            {/* --- Цель --- */}
            <div className="form-card">
              <div className="card-title">Цель</div>
              <div className="card-fields">
                <div className="form-group">
                  <label>Домен / URL сайта</label>
                  <input type="text" placeholder="example.com"
                    value={fields.targetDomain} onChange={e => updateField('targetDomain', e.target.value)} />
                </div>
                <div className="form-row">
                  <div className="form-group flex-1">
                    <label>Страна</label>
                    <select value={fields.countryCode} onChange={e => updateField('countryCode', e.target.value)}>
                      {COUNTRIES.map(c => (
                        <option key={c.code} value={c.code}>{c.code} — {c.label}</option>
                      ))}
                    </select>
                  </div>
                  <div className="form-group flex-1">
                    <label>Прокси</label>
                    <input type="text" placeholder="socks5://user:pass@host:port"
                      value={fields.proxy} onChange={e => updateField('proxy', e.target.value)} />
                    <span className="label-hint-inline">проверка — зелёный блок вверху формы ↑</span>
                  </div>
                </div>
              </div>
            </div>

            {/* --- Действия --- */}
            <div className="form-card">
              <div className="card-title-row">
                <span className="card-title">Действия</span>
                <div className="template-buttons">
                  <button type="button" className="template-btn"
                    onClick={() => applyTemplate(GOOGLE_ADS_TEMPLATE, 'Google Search + Реклама')}>
                    Google + Реклама
                  </button>
                  <button type="button" className="template-btn"
                    onClick={() => applyTemplate(SIMPLE_PARSE_TEMPLATE, 'Простой парсинг')}>
                    Простой парсинг
                  </button>
                  <button type="button" className="template-btn template-btn-accent"
                    onClick={applyIpCheckTemplate}>
                    Проверка IP
                  </button>
                </div>
              </div>
              <div className="actions-list">
                {fields.actions.length === 0 && (
                  <div className="actions-empty">
                    Нет действий. Сверху формы — зелёный блок проверки IP; здесь — шаблоны Google / парсинг или «+ Добавить действие».
                  </div>
                )}
                {fields.actions.map((a, i) => (
                  <ActionBlockCard key={a.id} action={a}
                    onChange={updated => {
                      const copy = [...fields.actions];
                      copy[i] = updated;
                      updateField('actions', copy);
                    }}
                    onRemove={() => updateField('actions', fields.actions.filter((_, j) => j !== i))} />
                ))}
                <AddActionButton onAdd={type => {
                  updateField('actions', [...fields.actions, createDefaultAction(type)]);
                }} />
              </div>
            </div>

            {/* --- Настройки --- */}
            <div className="form-card">
              <div className="card-title">Настройки</div>
              <div className="card-fields">
                <div className="form-group">
                  <label>Количество повторений</label>
                  <input type="number" min={1} max={100} value={fields.loopCount}
                    onChange={e => updateField('loopCount', parseInt(e.target.value) || 1)} />
                </div>
                <div className="toggles-grid">
                  <ToggleSwitch label="Имитация человека" checked={fields.humanLike}
                    onChange={v => updateField('humanLike', v)} />
                  <ToggleSwitch label="Случайные задержки" checked={fields.randomDelays}
                    onChange={v => updateField('randomDelays', v)} />
                  <ToggleSwitch label="Делать скриншоты" checked={fields.takeScreenshots}
                    onChange={v => updateField('takeScreenshots', v)} />
                  <ToggleSwitch label="Ожидание рекламы" checked={fields.waitForAds}
                    onChange={v => updateField('waitForAds', v)} />
                </div>
              </div>
            </div>

            {/* --- JSON preview --- */}
            <div className="json-preview-section">
              <button type="button" className="show-json-btn" onClick={() => setShowJson(!showJson)}>
                {showJson ? 'Скрыть JSON' : 'Показать JSON'}
              </button>
              {showJson && (
                <pre className="json-preview">{generatedJson}</pre>
              )}
            </div>
          </>
        ) : (
          /* --- Продвинутый режим --- */
          <div className="form-card">
            <div className="card-title-row">
              <span className="card-title">JSON-редактор</span>
              <div className="template-buttons">
                <button type="button" className="template-btn"
                  onClick={() => {
                    setConfigJson(generatedJson);
                    if (fields.actions.length === 0) {
                      setError('');
                      setFormNotice('Список действий пуст — в JSON попадёт пустой steps. Сначала выбери шаблон в «Простом» или нажми «Google + Реклама» / «Простой парсинг» здесь.');
                    } else {
                      setError('');
                      setFormNotice('');
                    }
                  }}>
                  Из формы
                </button>
                <button type="button" className="template-btn"
                  onClick={() => applyTemplateToFieldsAndJson(GOOGLE_ADS_TEMPLATE, 'Google Search + Реклама')}>
                  Google + Реклама
                </button>
                <button type="button" className="template-btn"
                  onClick={() => applyTemplateToFieldsAndJson(SIMPLE_PARSE_TEMPLATE, 'Простой парсинг', { allowEmptyDomain: true })}>
                  Простой парсинг
                </button>
                <button type="button" className="template-btn template-btn-accent"
                  onClick={applyIpCheckTemplate}>
                  Проверка IP
                </button>
              </div>
            </div>
            <p className="json-mode-hint">
              Оба режима отправляют один и тот же сценарий на телефон. «Из формы» подставляет карточки из «Простого»;
              кнопки шаблонов здесь сразу заполняют JSON и список действий. После смены домена/прокси нажми «Из формы» или шаблон снова.
            </p>
            <div className="json-target-section">
              <div className="card-title">Цель (и для JSON тоже)</div>
              <div className="card-fields">
                <div className="form-group">
                  <label>Домен / URL сайта</label>
                  <input type="text" placeholder="mysite.com"
                    value={fields.targetDomain} onChange={e => updateField('targetDomain', e.target.value)} />
                </div>
                <div className="form-row">
                  <div className="form-group flex-1">
                    <label>Страна</label>
                    <select value={fields.countryCode} onChange={e => updateField('countryCode', e.target.value)}>
                      {COUNTRIES.map(c => (
                        <option key={c.code} value={c.code}>{c.code} — {c.label}</option>
                      ))}
                    </select>
                  </div>
                  <div className="form-group flex-1">
                    <label>Прокси</label>
                    <input type="text" placeholder="socks5://user:pass@host:port"
                      value={fields.proxy} onChange={e => updateField('proxy', e.target.value)} />
                    <span className="label-hint-inline">проверка — зелёный блок вверху формы ↑</span>
                  </div>
                </div>
              </div>
            </div>
            <div className="form-row" style={{ marginBottom: '1rem' }}>
              <div className="form-group flex-1">
                <label>Название</label>
                <input type="text" value={fields.name}
                  onChange={e => updateField('name', e.target.value)} placeholder="Название задачи" />
              </div>
              <div className="form-group" style={{ width: '160px' }}>
                <label>Тип</label>
                <select value={fields.type} onChange={e => updateField('type', e.target.value as TaskType)}>
                  <option value="parsing">Парсинг</option>
                  <option value="surfing">Серфинг</option>
                  <option value="uniqueness">Уникализация</option>
                  <option value="screenshot">Скриншот</option>
                </select>
              </div>
              <div className="form-group" style={{ width: '200px' }}>
                <label>Устройство</label>
                <select value={fields.deviceId} onChange={e => updateField('deviceId', e.target.value)}>
                  <option value="">-- Выбрать --</option>
                  {devices.map((d, i) => (
                    <option key={d.id} value={d.id}>
                      {String(i + 1).padStart(2, '0')} — {d.name || d.androidId?.substring(0, 8)} ({d.status})
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <textarea className="json-editor" value={configJson}
              onChange={e => setConfigJson(e.target.value)} rows={20} spellCheck={false} />
          </div>
        )}

        <div className="form-actions">
          <button type="button" onClick={onCancel} className="cancel-btn">Отмена</button>
          <button type="submit" disabled={loading} className="submit-btn">
            {loading ? 'Создание...' : 'Создать'}
          </button>
        </div>
      </form>
    </div>
  );
}

function ToggleSwitch({ label, checked, onChange }: {
  label: string; checked: boolean; onChange: (v: boolean) => void;
}) {
  return (
    <label className="toggle-row">
      <div className={`toggle-track ${checked ? 'on' : ''}`} onClick={() => onChange(!checked)}>
        <div className="toggle-thumb" />
      </div>
      <span className="toggle-label">{label}</span>
    </label>
  );
}
