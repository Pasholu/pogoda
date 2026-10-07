// Последовательная шкала одного оттенка: на светлом стекле малая ошибка
// почти сливается с панелью, большая темнеет.
const RAMP = [
  '#cde2fb', '#b7d3f6', '#9ec5f4', '#86b6ef', '#6da7ec',
  '#5598e7', '#3987e5', '#2a78d6', '#256abf', '#1c5cab',
]

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

function rampColor(t) {
  const x = Math.min(1, Math.max(0, t)) * (RAMP.length - 1)
  const i = Math.min(RAMP.length - 2, Math.floor(x))
  const f = x - i
  const a = hexToRgb(RAMP[i])
  const b = hexToRgb(RAMP[i + 1])
  return `rgb(${a.map((v, k) => Math.round(v + (b[k] - v) * f)).join(',')})`
}

/** Тёмные чернила на светлых ячейках, светлые — на тёмных. */
function inkFor(rgb) {
  const [r, g, b] = rgb.match(/\d+/g).map((v) => {
    const c = Number(v) / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  const luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
  // Порог подобран так, чтобы на средних ступенях шкалы контраст текста был не ниже 4.4:1.
  return luminance > 0.2 ? '#0b1f3f' : '#ffffff'
}

const PRECISION = { temperature: 2, humidity: 1, pressure: 2, wind: 2 }

// Цвет закреплён за моделью по её месту в списке отчёта и не зависит от того, кто сейчас лидирует.
// Сдержанные оттенки под стекло; модели дополнительно подписаны на карте и в легенде.
const MODEL_COLORS = ['#2a6fc0', '#c8673a', '#2a9a78', '#c9952b', '#c0688c', '#3d7d4a', '#4a3aa7']

const root = document.getElementById('audit')
const tip = document.getElementById('tip')

let report = null
let variable = 'temperature'
let horizon = 24
// Подсказки собираются из этого списка по индексу, а не из HTML-атрибутов.
let tips = []

function escape(value) {
  return String(value).replace(
    /[&<>"']/g,
    (char) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char],
  )
}

function format(value, digits) {
  return value.toFixed(digits)
}

function signed(value, digits) {
  if (Math.abs(value) < 0.5 * 10 ** -digits) return `±${(0).toFixed(digits)}`
  return `${value > 0 ? '+' : '−'}${Math.abs(value).toFixed(digits)}`
}

function cellFor(region, model) {
  return report.cells.find(
    (cell) =>
      cell.region === region &&
      cell.model === model &&
      cell.variable === variable &&
      cell.horizon === horizon,
  )
}

// Файлы гербов областей в папке /arms.
const REGION_ARMS = {
  Брестская: 'brest',
  Витебская: 'vitebsk',
  Гомельская: 'gomel',
  Гродненская: 'grodno',
  Минская: 'minsk',
  Могилёвская: 'mogilev',
}

// Чем климат области отличается от остальной страны — короткая справка для окна области.
const REGION_CLIMATE = {
  Брестская:
    'Самая тёплая область страны: мягкая зима с частыми оттепелями, ранняя весна и самый долгий вегетационный период.',
  Витебская:
    'Самая холодная и влажная область: зима здесь длиннее и снежнее, лето прохладнее, а осадков больше, чем в среднем по стране.',
  Гомельская:
    'Самое жаркое лето в стране и меньше осадков, чем на севере и западе: летом здесь чаще случаются засушливые периоды.',
  Гродненская:
    'Климат смягчён близостью Балтики: зима мягче, чем на востоке страны, лето умеренно тёплое, погода переменчива из-за частых циклонов.',
  Минская:
    'Близка к средним условиям по стране; на возвышенностях в центре области немного прохладнее и выпадает больше осадков.',
  Могилёвская:
    'Самый континентальный климат в стране: зима холоднее, чем на западе, лето тёплое, перепады температуры между сезонами заметнее.',
}

function modelColor(id) {
  return MODEL_COLORS[report.models.findIndex((model) => model.id === id)]
}

/** Ячейка модели с наименьшей MAE в области при выбранных параметре и горизонте. */
function leaderFor(region) {
  let leader = null
  for (const model of report.models) {
    const cell = cellFor(region, model.id)
    if (cell && (!leader || cell.mae < leader.mae)) leader = cell
  }
  return leader
}

function variableInfo() {
  return report.variables.find((item) => item.id === variable)
}

function numberRu(value) {
  return value.toLocaleString('ru-RU')
}

function renderSkeleton() {
  root.innerHTML = `
    <div class="audit-loading">
      <i data-lucide="refresh-cw" class="icon spin" aria-hidden="true"></i>
      Собираем наблюдения станций Белгидромета и архив прогнозов 7 моделей — первый расчёт занимает до 30 секунд.
    </div>
    <div class="skeleton audit-skeleton" aria-hidden="true">
      <div class="skeleton__pair skeleton__pair--four">
        <div class="skeleton__bar skeleton__bar--tile"></div>
        <div class="skeleton__bar skeleton__bar--tile"></div>
        <div class="skeleton__bar skeleton__bar--tile"></div>
        <div class="skeleton__bar skeleton__bar--tile"></div>
      </div>
      <div class="skeleton__bar skeleton__bar--block"></div>
    </div>`
  lucide.createIcons()
}

function statsMarkup() {
  const t = report.totals
  const items = [
    ['Станций в работе', `${t.stationsActive} из ${t.stationsTotal}`, 'сеть наблюдений Белгидромета'],
    ['Наблюдений', numberRu(t.observations), `сводки каждые 3 ч за ${report.windowHours / 24} суток`],
    ['Пар прогноз–факт', numberRu(t.pairs), '7 моделей × 4 параметра × 3 горизонта'],
    ['Отброшено значений', numberRu(t.rejectedValues), 'не прошли проверку правдоподобности'],
  ]
  return `
    <dl class="stats">
      ${items
        .map(
          ([label, value, note]) => `
        <div class="stats__item">
          <dt class="stats__label">${escape(label)}</dt>
          <dd class="stats__value tabular">${escape(value)}</dd>
          <dd class="stats__note">${escape(note)}</dd>
        </div>`,
        )
        .join('')}
    </dl>`
}

function controlsMarkup() {
  const variables = report.variables
    .map(
      (item) => `
      <button type="button" class="segmented__btn${item.id === variable ? ' segmented__btn--active' : ''}"
        data-variable="${escape(item.id)}" aria-pressed="${item.id === variable}">${escape(item.label)}</button>`,
    )
    .join('')
  const horizons = report.horizons
    .map(
      (hours) => `
      <button type="button" class="segmented__btn${hours === horizon ? ' segmented__btn--active' : ''}"
        data-horizon="${hours}" aria-pressed="${hours === horizon}">${hours} ч</button>`,
    )
    .join('')

  return `
    <div class="controls">
      <div class="controls__group">
        <span class="controls__label" id="variable-label">Параметр</span>
        <div class="segmented" role="group" aria-labelledby="variable-label">${variables}</div>
      </div>
      <div class="controls__group">
        <span class="controls__label" id="horizon-label">Прогноз выпущен за</span>
        <div class="segmented" role="group" aria-labelledby="horizon-label">${horizons}</div>
      </div>
    </div>`
}

function mapMarkup() {
  const info = variableInfo()
  const digits = PRECISION[variable]
  const wins = {}

  const shapes = []
  const labels = []
  for (const region of BELARUS_MAP.regions) {
    const cell = leaderFor(region.name)
    if (!cell) {
      shapes.push(`<path class="map__region map__region--empty" d="${region.path}" />`)
      continue
    }
    const model = report.models.find((item) => item.id === cell.model)
    wins[model.id] = (wins[model.id] ?? 0) + 1
    const index = tips.push({ model, region: region.name, cell, info, digits }) - 1
    shapes.push(`
      <path class="map__region" d="${region.path}" fill="${modelColor(model.id)}" data-tip="${index}" data-region="${escape(region.name)}" tabindex="0"
        role="button" aria-haspopup="dialog"
        aria-label="${escape(region.name)} область: точнее всех ${escape(model.name)}, ошибка ${format(cell.mae, digits)} ${escape(info.unit)}. Открыть станции области" />`)
    const [x, y] = region.label
    labels.push(`
      <g class="map__label" transform="translate(${x} ${y})" aria-hidden="true">
        <rect x="-62" y="-23" width="124" height="46" rx="14" />
        <text class="map__label-region" y="-4">${escape(region.name)}</text>
        <text class="map__label-value" y="13">${escape(model.name)} · ${format(cell.mae, digits)} ${escape(info.unit)}</text>
      </g>`)
  }

  const legend = report.models
    .map((model) => {
      const count = wins[model.id] ?? 0
      return `
        <li class="map-legend__item${count ? '' : ' map-legend__item--idle'}">
          <span class="map-legend__swatch" style="background:${modelColor(model.id)}"></span>
          <span class="map-legend__name">${escape(model.name)}</span>
          <span class="map-legend__count tabular">${count ? `${count} из ${BELARUS_MAP.regions.length}` : '—'}</span>
        </li>`
    })
    .join('')

  return `
    <section class="section">
      <h2 class="section__title">
        <i data-lucide="map" class="icon" aria-hidden="true"></i>
        Карта точности
      </h2>
      <p class="section__note">Область закрашена цветом модели, которая ошибается в ней меньше всех по выбранному параметру и сроку прогноза. На подписи — эта модель и её средняя ошибка. Нажмите на область, чтобы увидеть её станции.</p>
      <div class="map-wrap">
        <svg class="map" viewBox="0 0 ${BELARUS_MAP.width} ${BELARUS_MAP.height}" role="group"
             aria-label="Карта Беларуси: лучшая модель в каждой области">
          <defs>
            <linearGradient id="map-gloss" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="${BELARUS_MAP.height}">
              <stop offset="0" stop-color="#fff" stop-opacity="0.55" />
              <stop offset="0.55" stop-color="#fff" stop-opacity="0" />
            </linearGradient>
          </defs>
          <g class="map__regions">${shapes.join('')}</g>
          <path class="map__gloss" d="${BELARUS_MAP.regions.map((region) => region.path).join('')}" />
          ${labels.join('')}
        </svg>
        <div class="map-legend">
          <h3 class="map-legend__title">Модель лидирует в областях</h3>
          <ul class="map-legend__list">${legend}</ul>
        </div>
      </div>
    </section>`
}

function heatmapMarkup() {
  const info = variableInfo()
  const digits = PRECISION[variable]
  const world = report.regions[report.regions.length - 1]

  // Строки упорядочены по средней ошибке в мире: сверху самая точная модель.
  const models = [...report.models].sort((a, b) => {
    const ca = cellFor(world, a.id)
    const cb = cellFor(world, b.id)
    return (ca?.mae ?? Infinity) - (cb?.mae ?? Infinity)
  })

  const values = report.cells
    .filter((cell) => cell.variable === variable && cell.horizon === horizon)
    .map((cell) => cell.mae)
  const min = Math.min(...values)
  const max = Math.max(...values)

  const best = {}
  for (const region of report.regions) best[region] = leaderFor(region)?.model

  const head = report.regions
    .map(
      (region) =>
        `<th scope="col" class="heat__col${region === world ? ' heat__col--world' : ''}">${escape(region)}</th>`,
    )
    .join('')

  const rows = models
    .map((model) => {
      const cells = report.regions
        .map((region) => {
          const cell = cellFor(region, model.id)
          const worldClass = region === world ? ' heat__cell--world' : ''
          if (!cell) {
            return `<td class="heat__cell heat__cell--empty${worldClass}">нет данных</td>`
          }
          const background = rampColor(max === min ? 0.5 : (cell.mae - min) / (max - min))
          const leader = best[region] === model.id
          const index = tips.push({ model, region, cell, info, digits }) - 1
          return `
            <td class="heat__cell${leader ? ' heat__cell--best' : ''}${worldClass}"
                style="background:${background};color:${inkFor(background)}"
                data-tip="${index}" tabindex="0">
              <span class="heat__value tabular">${format(cell.mae, digits)}</span>
              ${leader ? '<span class="heat__badge">лучшая</span>' : ''}
            </td>`
        })
        .join('')
      return `
        <tr>
          <th scope="row" class="heat__model">
            <span class="heat__model-name">${escape(model.name)}</span>
            <span class="heat__model-origin">${escape(model.origin)}</span>
          </th>
          ${cells}
        </tr>`
    })
    .join('')

  return `
    <section class="section">
      <h2 class="section__title">
        <i data-lucide="grid-3x3" class="icon" aria-hidden="true"></i>
        Средняя абсолютная ошибка (MAE), ${escape(info.unit)}
      </h2>
      <p class="section__note">Столбцы — области Беларуси. Чем темнее ячейка, тем сильнее модель ошибается. Строки отсортированы по точности в целом по стране.</p>
      <div class="heat-wrap">
        <table class="heat">
          <caption class="sr-only">
            MAE по параметру «${escape(info.label)}» для прогнозов, выпущенных за ${horizon} ч до целевого часа
          </caption>
          <thead><tr><th scope="col" class="heat__corner">Модель</th>${head}</tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
      <div class="scale" aria-hidden="true">
        <span class="scale__label tabular">${format(min, digits)} ${escape(info.unit)}</span>
        <span class="scale__bar" style="background:linear-gradient(90deg, ${RAMP.join(', ')})"></span>
        <span class="scale__label tabular">${format(max, digits)} ${escape(info.unit)}</span>
      </div>
    </section>`
}

function rankingMarkup() {
  const info = variableInfo()
  const digits = PRECISION[variable]
  const world = report.regions[report.regions.length - 1]
  const ranked = report.models
    .map((model) => ({ model, cell: cellFor(world, model.id) }))
    .filter((item) => item.cell)
    .sort((a, b) => a.cell.mae - b.cell.mae)
  const max = Math.max(...ranked.map((item) => item.cell.mae))

  const rows = ranked
    .map(({ model, cell }, position) => {
      const index = tips.push({ model, region: world, cell, info, digits }) - 1
      return `
        <li class="bars__row" data-tip="${index}" tabindex="0">
          <span class="bars__rank tabular">${position + 1}</span>
          <span class="bars__name">${escape(model.name)}</span>
          <span class="bars__track">
            <span class="bars__fill" style="width:${((cell.mae / max) * 100).toFixed(1)}%"></span>
          </span>
          <span class="bars__value tabular">${format(cell.mae, digits)} ${escape(info.unit)}</span>
        </li>`
    })
    .join('')

  const leader = ranked[0]
  const runnerUp = ranked[1]
  const margin = runnerUp ? ((runnerUp.cell.mae - leader.cell.mae) / runnerUp.cell.mae) * 100 : 0

  return `
    <section class="section">
      <h2 class="section__title">
        <i data-lucide="trophy" class="icon" aria-hidden="true"></i>
        Рейтинг моделей по Беларуси
      </h2>
      <p class="section__note">
        ${escape(leader.model.name)} ошибается в среднем на ${format(leader.cell.mae, digits)} ${escape(info.unit)}
        ${runnerUp ? `— на ${margin.toFixed(0)}% меньше, чем ${escape(runnerUp.model.name)} на втором месте.` : ''}
      </p>
      <ol class="bars">${rows}</ol>
    </section>`
}

function stationsMarkup() {
  const byRegion = {}
  for (const station of report.stations) {
    ;(byRegion[station.region] ??= []).push(station)
  }
  const groups = Object.entries(byRegion)
    .map(
      ([region, stations]) => `
      <div class="stations__group">
        <h3 class="stations__continent">${escape(region)} область</h3>
        <ul class="stations__list">
          ${stations
            .map(
              (s) => `
            <li>
              <button type="button" class="stations__item${s.observations === 0 ? ' stations__item--off' : ''}"
                      data-station="${escape(s.wmo)}" aria-haspopup="dialog">
                <span><span class="stations__city">${escape(s.city)}</span>
                <span class="stations__icao">${escape(s.wmo)}</span></span>
                <span class="stations__meta tabular">
                  ${s.observations === 0 ? 'нет сводок' : `${s.observations} сводок`}
                  <i data-lucide="chevron-right" class="icon" aria-hidden="true"></i>
                </span>
              </button>
            </li>`,
            )
            .join('')}
        </ul>
      </div>`,
    )
    .join('')

  return `
    <section class="section">
      <h2 class="section__title">
        <i data-lucide="radio-tower" class="icon" aria-hidden="true"></i>
        Станции Белгидромета
      </h2>
      <p class="section__note">Нажмите на станцию, чтобы посмотреть её сводки. Рядом с названием — международный индекс станции. В Гродненской области в открытом доступе пока одна станция.</p>
      <div class="stations">${groups}</div>
    </section>`
}

function methodMarkup() {
  const generated = new Date(report.generatedAt).toLocaleString('ru-RU', {
    day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit',
  })
  return `
    <section class="section method">
      <h2 class="section__title">
        <i data-lucide="info" class="icon" aria-hidden="true"></i>
        Как считаем
      </h2>
      <ul class="method__list">
        <li><strong>Факт</strong> — наблюдения государственной сети Белгидромета из его открытого узла данных ВМО
          за последние ${report.windowHours / 24} суток, сводки каждые 3 часа. Влажность считаем по температуре и точке росы.</li>
        <li><strong>Прогноз</strong> — архив Open-Meteo по координатам каждой станции: для каждого срока берём прогноз
          той же модели, выпущенный за 1, 2 или 3 суток до него.</li>
        <li><strong>Ошибка</strong> = прогноз − факт. MAE — средняя абсолютная ошибка,
          RMSE сильнее штрафует редкие крупные промахи, смещение показывает, в какую сторону модель ошибается.</li>
      </ul>
      <p class="section__note">Расчёт от ${escape(generated)}, обновляется раз в час. Границы областей на карте — geoBoundaries, лицензия CC BY 3.0.
        Гербы областей — Викисклад;
        <a href="https://commons.wikimedia.org/wiki/File:Coat_of_arms_of_Mohilev_Oblast.svg" target="_blank" rel="noopener">герб Могилёвской области</a>
        — по лицензии CC BY-SA 4.0.</p>
    </section>`
}

function render() {
  tips = []
  root.innerHTML = `
    ${statsMarkup()}
    ${controlsMarkup()}
    ${mapMarkup()}
    ${heatmapMarkup()}
    ${rankingMarkup()}
    ${stationsMarkup()}
    ${methodMarkup()}`
  lucide.createIcons()
}

function showTip(target, x, y) {
  const item = tips[Number(target.dataset.tip)]
  if (!item) return
  const { model, region, cell, info, digits } = item
  tip.innerHTML = `
    <p class="viz-tip__title">${escape(model.name)} · ${escape(region)}</p>
    <dl class="viz-tip__grid">
      <dt>MAE</dt><dd class="tabular">${format(cell.mae, digits)} ${escape(info.unit)}</dd>
      <dt>RMSE</dt><dd class="tabular">${format(cell.rmse, digits)} ${escape(info.unit)}</dd>
      <dt>Смещение</dt><dd class="tabular">${signed(cell.bias, digits)} ${escape(info.unit)}</dd>
      <dt>Пар</dt><dd class="tabular">${numberRu(cell.samples)}</dd>
    </dl>`
  tip.hidden = false
  const box = tip.getBoundingClientRect()
  const left = Math.min(window.innerWidth - box.width - 12, x + 14)
  const top = y + box.height + 20 > window.innerHeight ? y - box.height - 14 : y + 14
  tip.style.left = `${Math.max(12, left)}px`
  tip.style.top = `${Math.max(12, top)}px`
}

function hideTip() {
  tip.hidden = true
}

root.addEventListener('pointermove', (event) => {
  const target = event.target.closest('[data-tip]')
  if (target) showTip(target, event.clientX, event.clientY)
  else hideTip()
})
root.addEventListener('pointerleave', hideTip)
root.addEventListener('focusin', (event) => {
  const target = event.target.closest('[data-tip]')
  if (!target) return
  const rect = target.getBoundingClientRect()
  showTip(target, rect.left + rect.width / 2, rect.bottom)
})
root.addEventListener('focusout', hideTip)

// ---------- Окно со сводками станции ----------

const dialog = document.getElementById('station-dialog')
// Что сейчас показано в окне: по этому отбрасываются ответы, пришедшие уже не к месту.
const shown = { wmo: null, region: null }
// Область, из окна которой открыли станцию: в окне станции появляется кнопка возврата.
let backRegion = null
// Сводки станций запрашиваются один раз за жизнь страницы.
const details = new Map()

function stationDetail(wmo) {
  if (!details.has(wmo)) {
    const request = fetch(`/api/audit/stations/${encodeURIComponent(wmo)}`).then(async (response) => {
      const payload = await response.json()
      if (!response.ok) throw new Error(payload.error ?? 'Не удалось получить сводки')
      return payload
    })
    // Неудачный запрос не запоминается: следующее открытие попробует снова.
    request.catch(() => details.delete(wmo))
    details.set(wmo, request)
  }
  return details.get(wmo)
}
const COMPASS = ['С', 'СВ', 'В', 'ЮВ', 'Ю', 'ЮЗ', 'З', 'СЗ']

const dash = '<span class="sheet__empty">—</span>'
const cellValue = (value, digits) => (value == null ? dash : format(value, digits))

function windText(row) {
  if (row.windSpeed == null) return dash
  if (row.windSpeed === 0) return 'штиль'
  const direction = row.windDirection == null ? '' : `${COMPASS[Math.round(row.windDirection / 45) % 8]} `
  return `${direction}${format(row.windSpeed, 0)}`
}

function extremesText(row) {
  const parts = []
  if (row.minTemperature != null) parts.push(`мин ${format(row.minTemperature, 1)}`)
  if (row.maxTemperature != null) parts.push(`макс ${format(row.maxTemperature, 1)}`)
  return parts.length ? parts.join(', ') : dash
}

function minskTime(iso) {
  return new Date(iso).toLocaleString('ru-RU', {
    timeZone: 'Europe/Minsk', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
  })
}

function sheetHeader(title, subtitle, emblem = '', about = '') {
  const back = backRegion
    ? `<button type="button" class="sheet__back" data-region="${escape(backRegion)}">
        <i data-lucide="chevron-left" class="icon" aria-hidden="true"></i>${escape(backRegion)} область
      </button>`
    : ''
  return `
    <header class="sheet__head">
      ${emblem}
      <div class="sheet__heading">
        ${back}
        <h2 class="sheet__title" id="station-title">${title}</h2>
        <p class="sheet__subtitle">${subtitle}</p>
        ${about ? `<p class="sheet__about">${about}</p>` : ''}
      </div>
      <button type="button" class="sheet__close" data-close aria-label="Закрыть">
        <i data-lucide="x" class="icon" aria-hidden="true"></i>
      </button>
    </header>`
}

function renderStation(detail) {
  const rows = detail.rows
    .map(
      (row) => `
      <tr>
        <th scope="row" class="tabular">${escape(minskTime(row.time))}</th>
        <td class="tabular">${cellValue(row.temperature, 1)}</td>
        <td class="tabular">${cellValue(row.dewPoint, 1)}</td>
        <td class="tabular">${cellValue(row.humidity, 0)}</td>
        <td class="tabular">${cellValue(row.pressureSea, 1)}</td>
        <td class="tabular">${cellValue(row.pressureStation, 1)}</td>
        <td class="tabular">${windText(row)}</td>
        <td class="tabular">${cellValue(row.cloudCover, 0)}</td>
        <td class="tabular">${row.visibility == null ? dash : format(row.visibility / 1000, 0)}</td>
        <td class="tabular">${cellValue(row.precipitation, 1)}</td>
        <td class="tabular">${extremesText(row)}</td>
      </tr>`,
    )
    .join('')

  dialog.innerHTML = `
    ${sheetHeader(
      `${escape(detail.city)} · станция ${escape(detail.wmo)}`,
      `${escape(detail.region)} область · ${format(detail.latitude, 2)}° с. ш., ${format(detail.longitude, 2)}° в. д. ·
       ${detail.rows.length} сводок за ${detail.windowHours / 24} суток, время минское`,
    )}
    <div class="sheet__body">
      <table class="sheet__table">
        <thead>
          <tr>
            <th scope="col">Срок</th>
            <th scope="col">Темп., °C</th>
            <th scope="col">Точка росы, °C</th>
            <th scope="col">Влажн., %</th>
            <th scope="col">Давл. на ур. моря, гПа</th>
            <th scope="col">Давл. на станции, гПа</th>
            <th scope="col">Ветер, <span class="nowrap">м/с</span></th>
            <th scope="col">Облачн., %</th>
            <th scope="col">Видим., км</th>
            <th scope="col">Осадки, мм</th>
            <th scope="col">Экстремумы, °C</th>
          </tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    </div>
    <footer class="sheet__foot">
      Влажность рассчитана по температуре и точке росы, остальное — как передал Белгидромет.
      Осадки и экстремумы передаются не в каждый срок.
      <a href="${escape(detail.sourceUrl)}" target="_blank" rel="noopener">Исходный ответ узла данных (JSON)</a>
    </footer>`
  lucide.createIcons()
}

async function openStation(wmo) {
  const station = report.stations.find((item) => item.wmo === wmo)
  shown.wmo = wmo
  shown.region = null
  dialog.classList.remove('sheet--region')
  dialog.innerHTML = `
    ${sheetHeader(`${escape(station.city)} · станция ${escape(wmo)}`, `${escape(station.region)} область`)}
    <div class="sheet__body sheet__body--loading">
      <i data-lucide="refresh-cw" class="icon spin" aria-hidden="true"></i>
      Запрашиваем сводки у Белгидромета…
    </div>`
  lucide.createIcons()
  if (!dialog.open) dialog.showModal()

  try {
    const detail = await stationDetail(wmo)
    // Пока шёл запрос, окно могли закрыть или открыть в нём другое.
    if (dialog.open && shown.wmo === wmo) renderStation(detail)
  } catch (cause) {
    if (!dialog.open || shown.wmo !== wmo) return
    dialog.querySelector('.sheet__body').innerHTML = `
      <p class="search__error" role="alert">
        <i data-lucide="circle-alert" class="icon" aria-hidden="true"></i>
        <span>${escape(cause.message)}</span>
      </p>`
    lucide.createIcons()
  }
}

// ---------- Окно области: её станции на карте и списком ----------

function project(longitude, latitude) {
  const { west, north, scaleX, scaleY, pad } = BELARUS_MAP.projection
  return [pad + (longitude - west) * scaleX, pad + (north - latitude) * scaleY]
}

function latestText(detail) {
  const row = detail.rows[0]
  if (!row) return 'нет сводок'
  const temperature = row.temperature == null ? '' : `${format(row.temperature, 1)} °C · `
  return `${temperature}${minskTime(row.time)}`
}

function openRegion(name) {
  const region = BELARUS_MAP.regions.find((item) => item.name === name)
  const stations = report.stations.filter((station) => station.region === name)
  const cell = leaderFor(name)
  const model = cell && report.models.find((item) => item.id === cell.model)
  const info = variableInfo()

  shown.wmo = null
  shown.region = name
  backRegion = null

  const [x, y, width, height] = region.box
  const size = Math.max(width, height)
  const margin = size * 0.07
  const leaderNote = model
    ? ` · точнее всех ${escape(model.name)}: ${format(cell.mae, PRECISION[variable])} ${escape(info.unit)}
       (${escape(info.label.toLowerCase())}, прогноз за ${horizon} ч)`
    : ''

  dialog.classList.add('sheet--region')
  dialog.innerHTML = `
    ${sheetHeader(
      `${escape(name)} область`,
      `Станций Белгидромета: ${stations.length}${leaderNote}`,
      `<img class="sheet__arms" src="/arms/${REGION_ARMS[name]}.png" alt="Герб ${escape(name).replace(/ая$/, 'ой')} области" />`,
      escape(REGION_CLIMATE[name]),
    )}
    <div class="sheet__body region">
      <svg class="region__map" viewBox="${x - margin} ${y - margin} ${width + margin * 2} ${height + margin * 2}"
           role="group" aria-label="Станции на карте области">
        <path class="region__shape" d="${region.path}" ${model ? `style="fill:${modelColor(model.id)}"` : ''} />
        <g class="region__stations" style="--dot:${(size * 0.016).toFixed(2)}px;--city:${(size * 0.034).toFixed(2)}px"></g>
      </svg>
      <ul class="stations__list region__list">
        ${stations
          .map(
            (station) => `
          <li>
            <button type="button" class="stations__item" data-station="${escape(station.wmo)}">
              <span><span class="stations__city">${escape(station.city)}</span>
              <span class="stations__icao">${escape(station.wmo)}</span></span>
              <span class="stations__meta tabular">
                <span data-latest="${escape(station.wmo)}">…</span>
                <i data-lucide="chevron-right" class="icon" aria-hidden="true"></i>
              </span>
            </button>
          </li>`,
          )
          .join('')}
      </ul>
    </div>
    <footer class="sheet__foot">
      Точки на карте — станции, справа от названия — температура и время последней сводки (минское).
      Нажмите на станцию, чтобы открыть все её сводки.
    </footer>`
  lucide.createIcons()
  if (!dialog.open) dialog.showModal()

  const layer = dialog.querySelector('.region__stations')
  const middle = x + width / 2
  for (const station of stations) {
    stationDetail(station.wmo)
      .then((detail) => {
        if (shown.region !== name) return
        dialog.querySelector(`[data-latest="${station.wmo}"]`).textContent = latestText(detail)
        const [cx, cy] = project(detail.longitude, detail.latitude)
        // Подпись уходит внутрь области, чтобы не вылезать за край рисунка.
        const left = cx > middle
        layer.insertAdjacentHTML(
          'beforeend',
          `<g class="region__station${left ? ' region__station--left' : ''}" data-station="${escape(station.wmo)}"
              transform="translate(${cx.toFixed(1)} ${cy.toFixed(1)})" tabindex="0" role="button"
              aria-label="${escape(station.city)}: открыть сводки">
            <circle />
            <text>${escape(station.city)}</text>
          </g>`,
        )
      })
      .catch(() => {
        if (shown.region !== name) return
        dialog.querySelector(`[data-latest="${station.wmo}"]`).textContent = 'нет данных'
      })
  }
}

function activateInDialog(target) {
  const regionButton = target.closest('[data-region]')
  if (regionButton) {
    openRegion(regionButton.dataset.region)
    return
  }
  const stationButton = target.closest('[data-station]')
  if (stationButton) {
    backRegion = shown.region
    openStation(stationButton.dataset.station)
  }
}

dialog.addEventListener('click', (event) => {
  // Щелчок по затемнению вокруг окна приходится на сам элемент dialog.
  if (event.target === dialog || event.target.closest('[data-close]')) dialog.close()
  else activateInDialog(event.target)
})

dialog.addEventListener('close', () => {
  shown.wmo = null
  shown.region = null
  backRegion = null
})

// Области и точки станций нарисованы в SVG и сами на Enter и пробел не откликаются.
function onShapeKey(event) {
  if (event.key !== 'Enter' && event.key !== ' ') return
  const shape = event.target.closest('svg [data-region], svg [data-station]')
  if (!shape) return
  event.preventDefault()
  shape.dispatchEvent(new MouseEvent('click', { bubbles: true }))
}
root.addEventListener('keydown', onShapeKey)
dialog.addEventListener('keydown', onShapeKey)

root.addEventListener('click', (event) => {
  const stationButton = event.target.closest('[data-station]')
  if (stationButton) {
    backRegion = null
    openStation(stationButton.dataset.station)
    return
  }

  const regionShape = event.target.closest('[data-region]')
  if (regionShape) {
    hideTip()
    openRegion(regionShape.dataset.region)
    return
  }

  const button = event.target.closest('[data-variable], [data-horizon]')
  if (!button) return
  if (button.dataset.variable) variable = button.dataset.variable
  if (button.dataset.horizon) horizon = Number(button.dataset.horizon)
  hideTip()
  render()
  // После перерисовки фокус должен остаться на нажатой кнопке.
  const selector = button.dataset.variable
    ? `[data-variable="${button.dataset.variable}"]`
    : `[data-horizon="${button.dataset.horizon}"]`
  root.querySelector(selector)?.focus()
})

async function load() {
  renderSkeleton()
  try {
    const response = await fetch('/api/audit')
    const payload = await response.json()
    if (!response.ok) throw new Error(payload.error ?? 'Не удалось получить данные аудита')
    report = payload
    render()
  } catch (cause) {
    root.innerHTML = `
      <p class="search__error" role="alert">
        <i data-lucide="circle-alert" class="icon" aria-hidden="true"></i>
        <span>${escape(cause.message)}</span>
      </p>`
    lucide.createIcons()
  }
}

load()
