const CONDITION_ICONS = {
  0: 'sun',
  1: 'cloud-sun',
  2: 'cloudy',
  3: 'cloud',
  45: 'cloud-fog',
  48: 'cloud-fog',
  51: 'cloud-drizzle',
  53: 'cloud-drizzle',
  55: 'cloud-drizzle',
  56: 'cloud-drizzle',
  57: 'cloud-drizzle',
  61: 'cloud-rain',
  63: 'cloud-rain',
  65: 'cloud-rain',
  66: 'cloud-rain',
  67: 'cloud-rain',
  71: 'cloud-snow',
  73: 'cloud-snow',
  75: 'cloud-snow',
  77: 'cloud-snow',
  80: 'cloud-rain',
  81: 'cloud-rain',
  82: 'cloud-rain',
  85: 'cloud-snow',
  86: 'cloud-snow',
  95: 'cloud-lightning',
  96: 'cloud-lightning',
  99: 'cloud-lightning',
}

const form = document.getElementById('search')
const input = document.getElementById('site')
const submit = document.getElementById('submit')
const errorBox = document.getElementById('error')
const reportBox = document.getElementById('report')

let pending = null
let lastReport = null
let activeCrop = 'wheat'

function escape(value) {
  return String(value).replace(
    /[&<>"']/g,
    (char) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char],
  )
}

function formatDelta(value, precision) {
  // Порог зависит от точности: −0.4 при целых должно стать «±0», а не «−0».
  if (Math.abs(value) < 0.5 * 10 ** -precision) return `±${(0).toFixed(precision)}`
  const sign = value > 0 ? '+' : '−'
  return `${sign}${Math.abs(value).toFixed(precision)}`
}

function showError(message) {
  errorBox.textContent = ''
  errorBox.insertAdjacentHTML(
    'beforeend',
    `<i data-lucide="circle-alert" class="icon" aria-hidden="true"></i><span>${escape(message)}</span>`,
  )
  errorBox.classList.remove('hidden')
  input.setAttribute('aria-invalid', 'true')
  lucide.createIcons()
}

function clearError() {
  errorBox.classList.add('hidden')
  input.removeAttribute('aria-invalid')
}

function setBusy(busy) {
  submit.disabled = busy
  submit.innerHTML = busy
    ? '<i data-lucide="refresh-cw" class="icon spin" aria-hidden="true"></i>Опрос'
    : 'Сравнить'
  lucide.createIcons()
}

// Окно загрузки поверх затуманенной страницы: ветер раскручивает мельницу, пока идёт запрос.
// Показывается не меньше MILL_MIN_MS, чтобы быстрый ответ не мелькал.
const MILL_MIN_MS = 700
const MILL_FADE_MS = 250
let millOverlay = null
let millShownAt = 0

function showMill() {
  if (millOverlay) return
  millOverlay = document.createElement('div')
  millOverlay.className = 'mill-overlay'
  millOverlay.innerHTML = `<div class="mill-overlay__veil"></div>${millMarkup()}`
  document.body.append(millOverlay)
  millShownAt = performance.now()
  const overlay = millOverlay
  requestAnimationFrame(() => requestAnimationFrame(() => overlay.classList.add('mill-overlay--in')))
}

function hideMill() {
  const overlay = millOverlay
  if (!overlay) return
  millOverlay = null
  const wait = Math.max(0, MILL_MIN_MS - (performance.now() - millShownAt))
  setTimeout(() => {
    overlay.classList.remove('mill-overlay--in')
    setTimeout(() => overlay.remove(), MILL_FADE_MS)
  }, wait)
}

/** Подпись под мельницей сменится сама, если ответ задержится дольше 4 секунд. */
function millMarkup() {
  return `
    <div class="mill" role="status" aria-live="polite">
      <div class="mill__scene">
        <svg viewBox="0 0 320 200" aria-hidden="true" fill="none" stroke-linecap="round" stroke-linejoin="round">
          <defs>
            <linearGradient id="mill-sky" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stop-color="#dcebfb" />
              <stop offset="1" stop-color="#eef5fd" />
            </linearGradient>
            <clipPath id="mill-clip"><rect width="320" height="200" rx="20" /></clipPath>
          </defs>
          <g clip-path="url(#mill-clip)">
            <rect width="320" height="200" fill="url(#mill-sky)" />
            <g class="mill__cloud mill__cloud--near">
              <path d="M10 52h46a11 11 0 0 0-4-21 15 15 0 0 0-28-3 10 10 0 0 0-14 24z" />
            </g>
            <g class="mill__cloud mill__cloud--far">
              <path d="M14 30h32a8 8 0 0 0-3-15 11 11 0 0 0-20-2 7 7 0 0 0-9 17z" />
            </g>
            <path d="M0 166Q70 150 150 162T320 156V200H0Z" fill="#d6e4d4" />
            <path d="M0 178Q90 166 190 174T320 172V200H0Z" fill="#c4d8c3" />
            <path class="mill__tower" d="M140 168L148 94H172L180 168Z" />
            <path class="mill__roof" d="M144 95Q147 76 160 72Q173 76 176 95Z" />
            <path class="mill__door" d="M154 168V154a6 6 0 0 1 12 0V168" />
            <g class="mill__sails">
            <g class="mill__blade">
              <line x1="160" y1="86" x2="160" y2="34" />
              <rect x="162" y="36" width="11" height="40" rx="2" />
            </g>
            <g class="mill__blade" transform="rotate(90 160 86)">
              <line x1="160" y1="86" x2="160" y2="34" />
              <rect x="162" y="36" width="11" height="40" rx="2" />
            </g>
            <g class="mill__blade" transform="rotate(180 160 86)">
              <line x1="160" y1="86" x2="160" y2="34" />
              <rect x="162" y="36" width="11" height="40" rx="2" />
            </g>
            <g class="mill__blade" transform="rotate(270 160 86)">
              <line x1="160" y1="86" x2="160" y2="34" />
              <rect x="162" y="36" width="11" height="40" rx="2" />
            </g>
            </g>
            <circle class="mill__hub" cx="160" cy="86" r="4" />
            <g class="mill__grass">
              <path d="M128 172V162" style="animation-delay:0s" />
              <path d="M133 172V165" style="animation-delay:-0.4s" />
              <path d="M187 171V161" style="animation-delay:-0.8s" />
              <path d="M192 171V164" style="animation-delay:-1.2s" />
              <path d="M60 178V170" style="animation-delay:-0.6s" />
              <path d="M250 176V168" style="animation-delay:-1.6s" />
            </g>
            <g class="mill__wind">
              <path pathLength="100" stroke="#9db4d6" style="animation-delay:0.25s"
                d="M-20 40C50 32 96 48 140 38C156 34 168 36 176 42C182 47 178 55 171 53C165 51 167 44 173 45" />
              <path pathLength="100" stroke="#fff"
                d="M-20 66C30 54 70 78 112 66C142 58 160 52 176 62C186 69 182 82 172 80C164 78 166 68 174 68" />
              <path pathLength="100" stroke="#9db4d6" style="animation-delay:0.12s"
                d="M-20 108C40 96 80 118 130 106C160 99 186 98 200 108C208 115 202 126 193 123C186 120 189 111 196 112" />
              <path pathLength="100" stroke="#fff" style="animation-delay:0.35s"
                d="M-20 140C30 132 70 148 104 140C122 136 134 134 142 140C148 145 144 153 137 151C131 149 133 142 139 143" />
            </g>
          </g>
        </svg>
      </div>
      <p class="mill__caption">
        <span class="mill__text mill__text--first">Спрашиваем метеослужбу и датчики…</span>
        <span class="mill__text mill__text--later">Ещё немного, ветер усиливается…</span>
      </p>
    </div>`
}

function adviceItems(advice) {
  return advice
    .map(
      (item) => `
      <li class="advice__item advice__item--${escape(item.level)}">
        <i data-lucide="${escape(item.icon)}" class="icon" aria-hidden="true"></i>
        <div>
          <p class="advice__title">${escape(item.title)}</p>
          <p class="advice__detail">${escape(item.detail)}</p>
        </div>
      </li>`,
    )
    .join('')
}

function cropsMarkup(crops) {
  const active = crops.find((crop) => crop.id === activeCrop) ?? crops[0]
  activeCrop = active.id

  const tabs = crops
    .map(
      (crop) => `
      <button
        type="button"
        role="tab"
        class="tab${crop.id === active.id ? ' tab--active' : ''}"
        data-crop="${escape(crop.id)}"
        aria-selected="${crop.id === active.id}"
        aria-controls="advice-panel"
      >${escape(crop.name)}</button>`,
    )
    .join('')

  return `
    <section class="advice">
      <h2 class="section__title">
        <i data-lucide="clipboard-list" class="icon" aria-hidden="true"></i>
        Что делать сейчас
      </h2>
      <div class="tabs" role="tablist" aria-label="Культура">${tabs}</div>
      <ul class="advice__list" id="advice-panel" role="tabpanel">${adviceItems(active.advice)}</ul>
    </section>`
}

/** Переключение вкладки не трогает остальную карточку. */
function switchCrop(id) {
  if (!lastReport) return
  const crop = lastReport.crops.find((item) => item.id === id)
  if (!crop) return

  activeCrop = id
  document.querySelectorAll('.tab').forEach((tab) => {
    const current = tab.dataset.crop === id
    tab.classList.toggle('tab--active', current)
    tab.setAttribute('aria-selected', String(current))
  })

  const panel = document.getElementById('advice-panel')
  if (panel) {
    panel.innerHTML = adviceItems(crop.advice)
    lucide.createIcons()
  }
}

function metricsMarkup(groups) {
  const blocks = groups
    .map((group) => {
      const rows = group.metrics
        .map(
          (metric) => `
          <div class="metrics__row">
            <span class="metrics__label">${escape(metric.label)}</span>
            <span class="tabular">${metric.station.toFixed(metric.precision)}<span class="metrics__unit">${escape(metric.unit)}</span></span>
            <span class="tabular metrics__sensor">${metric.sensor.toFixed(metric.precision)}<span class="metrics__unit">${escape(metric.unit)}</span></span>
            <span class="tabular metrics__delta${metric.exceeded ? ' metrics__delta--exceeded' : ''}">
              ${formatDelta(metric.delta, metric.precision)}${metric.exceeded ? '<span class="sr-only"> — выше допуска</span>' : ''}
            </span>
          </div>`,
        )
        .join('')

      return `
        <div class="metrics__group">
          <h3 class="metrics__group-title">
            <i data-lucide="${escape(group.icon)}" class="icon" aria-hidden="true"></i>
            ${escape(group.title)}
          </h3>
          ${rows}
        </div>`
    })
    .join('')

  return `
    <section class="section">
      <h2 class="section__title">
        <i data-lucide="scale" class="icon" aria-hidden="true"></i>
        Метеослужба против датчиков
      </h2>
      <div class="metrics">
        <div class="metrics__row metrics__head">
          <span>Параметр</span>
          <span><span class="only-narrow">Метео</span><span class="only-wide">Метеослужба</span></span>
          <span>Датчики</span>
          <span><span class="only-narrow">Разница</span><span class="only-wide">Расхождение</span></span>
        </div>
        ${blocks}
      </div>
    </section>`
}

function forecastMarkup(forecast) {
  const items = forecast
    .map(
      (item) => `
      <div class="facts__item">
        <dt class="facts__label">${escape(item.label)}</dt>
        <dd class="facts__value tabular">${item.value.toFixed(item.precision)}<span class="facts__unit">${escape(item.unit)}</span></dd>
      </div>`,
    )
    .join('')

  return `
    <section class="section">
      <h2 class="section__title">
        <i data-lucide="satellite-dish" class="icon" aria-hidden="true"></i>
        Расчёт метеослужбы
      </h2>
      <p class="section__note">Величины, которых наши датчики не измеряют.</p>
      <dl class="facts">${items}</dl>
    </section>`
}

function renderReport(report) {
  const air = report.groups[0].metrics[0]
  const conditionIcon = CONDITION_ICONS[report.condition.code] ?? 'cloud'

  reportBox.innerHTML = `
    <article class="report">
      <div class="report__head">
        <span class="report__site">
          <i data-lucide="map-pin" class="icon" aria-hidden="true"></i>
          <strong>${escape(report.site.name)}</strong>
          <span aria-hidden="true">·</span>
          <span>${escape(report.site.region)}</span>
        </span>
        <span class="report__meta">
          <span>Опрос в <span class="tabular">${escape(report.readAt)}</span></span>
        </span>
      </div>

      <div class="readings">
        <div class="reading">
          <div class="reading__source">
            <i data-lucide="${conditionIcon}" class="icon" aria-hidden="true"></i>
            Метеослужба
          </div>
          <p class="reading__value tabular">${air.station.toFixed(1)}<span>°</span></p>
          <p class="reading__note">${escape(report.condition.label)}</p>
        </div>
        <div class="reading">
          <div class="reading__source">
            <i data-lucide="radio" class="icon" aria-hidden="true"></i>
            Наши датчики
          </div>
          <p class="reading__value tabular">${air.sensor.toFixed(1)}<span>°</span></p>
          <p class="reading__note">Полевая станция</p>
        </div>
      </div>

      ${cropsMarkup(report.crops)}
      ${metricsMarkup(report.groups)}
      ${forecastMarkup(report.forecast)}

      ${
        report.drifted
          ? `<p class="alert">
               <i data-lucide="triangle-alert" class="icon" aria-hidden="true"></i>
               Часть показателей расходится сильнее допуска — стоит проверить калибровку и размещение датчиков.
             </p>`
          : ''
      }
    </article>`

  lucide.createIcons()
}

async function load(site) {
  pending?.abort()
  const controller = new AbortController()
  pending = controller

  clearError()
  setBusy(true)
  showMill()

  try {
    const response = await fetch(`/api/report/${encodeURIComponent(site)}`, {
      signal: controller.signal,
    })
    const payload = await response.json()
    if (!response.ok) throw new Error(payload.error ?? 'Не удалось получить данные')
    lastReport = payload
    renderReport(payload)
  } catch (cause) {
    if (cause.name === 'AbortError') return
    // Неудачный поиск не стирает последний успешный результат.
    if (lastReport) renderReport(lastReport)
    else reportBox.innerHTML = ''
    showError(cause.message)
  } finally {
    if (pending === controller) {
      pending = null
      setBusy(false)
      hideMill()
    }
  }
}

reportBox.addEventListener('click', (event) => {
  const tab = event.target.closest('[data-crop]')
  if (tab) switchCrop(tab.dataset.crop)
})

form.addEventListener('submit', (event) => {
  event.preventDefault()
  const site = input.value.trim()
  if (!site) {
    showError('Укажите населённый пункт площадки')
    input.focus()
    return
  }
  input.value = ''
  load(site)
})

lucide.createIcons()
load('Минск')
