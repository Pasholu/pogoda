// Просмотр записей хранилища: таблица, отбор и постраничный вывод.
const TABLES = [
  { id: 'observation', label: 'Сводки станций' },
  { id: 'forecast', label: 'Прогнозы моделей' },
  { id: 'station', label: 'Станции' },
  { id: 'ingest_run', label: 'Журнал выгрузок' },
]

const COLUMNS = {
  station_wmo: 'Станция',
  wmo: 'Индекс',
  city: 'Город',
  region: 'Область',
  latitude: 'Широта',
  longitude: 'Долгота',
  source_url: 'Запрос к узлу',
  report_time: 'Срок',
  temperature: 'Темп., °C',
  dew_point: 'Точка росы, °C',
  humidity: 'Влажн., %',
  pressure_sea: 'Давл. ур. моря, гПа',
  pressure_station: 'Давл. на станции, гПа',
  wind_speed: 'Ветер, м/с',
  wind_direction: 'Направл., °',
  visibility: 'Видим., м',
  cloud_cover: 'Облачн., %',
  precipitation: 'Осадки, мм',
  min_temperature: 'Мин., °C',
  max_temperature: 'Макс., °C',
  rejected_values: 'Отброшено',
  source: 'Источник',
  loaded_at: 'Загружено',
  model: 'Модель',
  variable: 'Параметр',
  horizon_days: 'За суток',
  target_time: 'Целевой срок',
  issued_time: 'Выпущен',
  forecast_value: 'Прогноз',
  id: '№',
  job: 'Задача',
  started_at: 'Начало',
  finished_at: 'Конец',
  status: 'Итог',
  rows_written: 'Строк',
  message: 'Сообщение',
}

const VARIABLES = {
  TEMPERATURE: 'Температура',
  HUMIDITY: 'Влажность',
  PRESSURE: 'Давление',
  WIND: 'Ветер',
}

const PAGE_SIZE = 50
// Каскад появления строк: шаг задержки и число строк, после которого задержка перестаёт расти.
const CASCADE_STEP_MS = 70
const CASCADE_ROWS = 10
const LEAVE_MS = 300
const TIME_COLUMNS = new Set(['report_time', 'loaded_at', 'target_time', 'issued_time', 'started_at', 'finished_at'])

const root = document.getElementById('data')
const badgeText = document.getElementById('db-badge-text')
const badge = document.getElementById('db-badge')

let table = 'observation'
let page = 0
let filters = {}
let stations = []
let models = []
let pending = null

function escape(value) {
  return String(value).replace(
    /[&<>"']/g,
    (char) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char],
  )
}

function minskTime(iso) {
  return new Date(iso).toLocaleString('ru-RU', {
    timeZone: 'Europe/Minsk', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
  })
}

function cellText(column, value) {
  if (value == null) return '<span class="sheet__empty">—</span>'
  if (TIME_COLUMNS.has(column)) return escape(minskTime(value))
  if (column === 'station_wmo') {
    const station = stations.find((item) => item.wmo === value)
    return escape(station ? `${station.city} · ${value}` : value)
  }
  if (column === 'variable') return escape(VARIABLES[value] ?? value)
  if (column === 'model') return escape(models.find((item) => item.id === value)?.name ?? value)
  if (column === 'source_url') return `<a href="${escape(value)}" target="_blank" rel="noopener">открыть</a>`
  if (typeof value === 'number' && !Number.isInteger(value)) return escape(value.toFixed(2))
  return escape(value)
}

function selectMarkup(name, label, options) {
  const current = filters[name] ?? ''
  return `
    <label class="data-filter">
      <span class="controls__label">${escape(label)}</span>
      <select class="data-filter__select" data-filter="${name}">
        <option value="">Все</option>
        ${options
          .map(
            ([value, text]) =>
              `<option value="${escape(value)}"${String(value) === current ? ' selected' : ''}>${escape(text)}</option>`,
          )
          .join('')}
      </select>
    </label>`
}

function filtersMarkup() {
  const stationOptions = stations.map((item) => [item.wmo, `${item.city} · ${item.wmo}`])
  if (table === 'observation') return selectMarkup('station_wmo', 'Станция', stationOptions)
  if (table === 'forecast') {
    return [
      selectMarkup('station_wmo', 'Станция', stationOptions),
      selectMarkup('model', 'Модель', models.map((item) => [item.id, item.name])),
      selectMarkup('variable', 'Параметр', Object.entries(VARIABLES)),
      selectMarkup('horizon_days', 'За суток', [[1, '1'], [2, '2'], [3, '3']]),
    ].join('')
  }
  return ''
}

function render(result, error) {
  const tabs = TABLES.map(
    (item) => `
      <button type="button" class="segmented__btn${item.id === table ? ' segmented__btn--active' : ''}"
        data-table="${item.id}" aria-pressed="${item.id === table}">${escape(item.label)}</button>`,
  ).join('')

  let body = ''
  if (error) {
    body = `
      <p class="search__error" role="alert">
        <i data-lucide="circle-alert" class="icon" aria-hidden="true"></i>
        <span>${escape(error)}</span>
      </p>`
  } else if (!result) {
    body = `
      <div class="audit-loading">
        <i data-lucide="refresh-cw" class="icon spin" aria-hidden="true"></i>
        Подключение к базе…
      </div>`
  } else if (result.rows.length === 0) {
    body = '<p class="section__note">Под этот отбор записей нет.</p>'
  } else {
    const pages = Math.max(1, Math.ceil(result.total / PAGE_SIZE))
    const from = page * PAGE_SIZE + 1
    const to = from + result.rows.length - 1
    const head = result.columns
      .map((column) => `<th scope="col">${escape(COLUMNS[column] ?? column)}</th>`)
      .join('')
    const rows = result.rows
      .map(
        (row, index) =>
          `<tr class="data-row" style="animation-delay:${Math.min(index, CASCADE_ROWS) * CASCADE_STEP_MS}ms">${result.columns
            .map((column) => `<td class="tabular">${cellText(column, row[column])}</td>`)
            .join('')}</tr>`,
      )
      .join('')
    body = `
      <div class="data-wrap">
        <table class="sheet__table data-table">
          <thead><tr>${head}</tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
      <div class="data-pager">
        <span class="section__note tabular">
          Строки ${from.toLocaleString('ru-RU')}–${to.toLocaleString('ru-RU')} из ${result.total.toLocaleString('ru-RU')},
          время минское
        </span>
        <span class="segmented">
          <button type="button" class="segmented__btn" data-page="${page - 1}"${page === 0 ? ' disabled' : ''}>Назад</button>
          <span class="data-pager__page tabular">${page + 1} из ${pages.toLocaleString('ru-RU')}</span>
          <button type="button" class="segmented__btn" data-page="${page + 1}"${page + 1 >= pages ? ' disabled' : ''}>Вперёд</button>
        </span>
      </div>`
  }

  const filterControls = filtersMarkup()
  root.innerHTML = `
    <div class="controls">
      <div class="controls__group">
        <span class="controls__label" id="table-label">Таблица</span>
        <div class="segmented" role="group" aria-labelledby="table-label">${tabs}</div>
      </div>
    </div>
    ${filterControls ? `<div class="controls data-filters">${filterControls}</div>` : ''}
    ${body}`
  lucide.createIcons()
}

async function fetchJson(url, signal) {
  const response = await fetch(url, { signal })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.error ?? 'Не удалось прочитать записи')
  return payload
}

function currentUrl() {
  const query = new URLSearchParams({ page, size: PAGE_SIZE })
  for (const [name, value] of Object.entries(filters)) {
    if (value) query.set(name, value)
  }
  return `/api/admin/data/${table}?${query}`
}

function setConnected(connected) {
  badge.classList.toggle('db-badge--pending', !connected)
  badgeText.textContent = connected ? 'forecastaudit · подключено' : 'подключение…'
}

async function load() {
  pending?.abort()
  const controller = new AbortController()
  pending = controller
  render(null)

  try {
    const result = await fetchJson(currentUrl(), controller.signal)
    setConnected(true)
    render(result)
  } catch (cause) {
    if (cause.name === 'AbortError') return
    setConnected(false)
    render(null, cause.message)
  }
}

/** Запускает появление страницы заново: анимация привязана к классу на body. */
function enterPage() {
  document.body.classList.remove('data-leaving', 'data-enter')
  void document.body.offsetWidth
  document.body.classList.add('data-enter')
}

document.getElementById('db-back').addEventListener('click', (event) => {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) return
  event.preventDefault()
  const target = event.currentTarget.href
  document.body.classList.add('data-leaving')
  setTimeout(() => location.assign(target), LEAVE_MS)
})

// При возврате кнопкой «Назад» браузер может достать страницу из кэша уже погашенной.
addEventListener('pageshow', (event) => {
  if (event.persisted) enterPage()
})

root.addEventListener('click', (event) => {
  const tab = event.target.closest('[data-table]')
  if (tab) {
    table = tab.dataset.table
    page = 0
    filters = {}
    load()
    return
  }
  const pager = event.target.closest('[data-page]')
  if (pager && !pager.disabled) {
    page = Number(pager.dataset.page)
    load()
  }
})

root.addEventListener('change', (event) => {
  const select = event.target.closest('[data-filter]')
  if (!select) return
  filters[select.dataset.filter] = select.value
  page = 0
  load()
})

// Силуэт утки в полёте: плотное тело, вытянутая шея, круглая голова с плоским клювом,
// короткий хвост. Крыло вынесено отдельно, чтобы им можно было махать.
const DUCK = `
  <path d="M19 23 11.5 22Q10 24 12 26L20 28.5Z" />
  <ellipse cx="30" cy="25" rx="13" ry="6.6" />
  <path d="M38 20.5C43 19 47 18 51 17.5L52.5 23C48 24 44 26 40 28.5Z" />
  <circle cx="53.5" cy="19.6" r="4.1" />
  <path d="M56.5 18.2 62.4 19.3Q63.6 20.3 62.3 21.1L56.5 21.8Z" />
  <path class="ducks__wing" d="M25 23C22 16.5 15.5 11.5 7 9.5Q7.5 6.5 12 6.5C23 7.5 32.5 13 38.5 22Z" />`

/** Две стаи клином: ближняя и дальняя. Смещения — в пикселях от вожака, задержка — сдвиг взмаха. */
const FLOCKS = [
  { name: 'near', size: 58, ducks: [[0, 0, 0], [-72, -27, 0.25], [-72, 27, 0.4], [-144, -54, 0.1], [-144, 54, 0.55]] },
  { name: 'far', size: 34, ducks: [[0, 0, 0.3], [-44, -17, 0], [-44, 17, 0.5]] },
]

function buildDucks() {
  const layer = document.createElement('div')
  layer.className = 'ducks'
  layer.setAttribute('aria-hidden', 'true')
  layer.innerHTML = FLOCKS.map(
    (flock) => `
      <div class="ducks__flock ducks__flock--${flock.name}">
        ${flock.ducks
          .map(
            ([x, y, delay]) => `
          <svg class="ducks__duck" viewBox="0 0 64 40" width="${flock.size}"
               style="left:${x}px;top:${y}px;--flap-delay:-${delay}s">${DUCK}</svg>`,
          )
          .join('')}
      </div>`,
  ).join('')
  document.body.prepend(layer)
}

async function start() {
  buildDucks()
  render(null)
  try {
    // Справочники для подписей и списков отбора: станции — из базы, модели — из отчёта аудита.
    const [stationPage, report] = await Promise.all([
      fetchJson('/api/admin/data/station?size=200'),
      fetchJson('/api/audit').catch(() => ({ models: [] })),
    ])
    stations = stationPage.rows
    models = report.models
  } catch (cause) {
    render(null, cause.message)
    return
  }
  load()
}

start()
