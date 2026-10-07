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

function renderSkeleton() {
  reportBox.innerHTML = `
    <div class="report" aria-hidden="true">
      <div class="skeleton">
        <div class="skeleton__bar skeleton__bar--wide"></div>
        <div class="skeleton__pair">
          <div class="skeleton__bar skeleton__bar--block"></div>
          <div class="skeleton__bar skeleton__bar--block"></div>
        </div>
        <div class="skeleton__bar skeleton__bar--row"></div>
        <div class="skeleton__bar skeleton__bar--row"></div>
        <div class="skeleton__bar skeleton__bar--row"></div>
      </div>
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
  renderSkeleton()

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
