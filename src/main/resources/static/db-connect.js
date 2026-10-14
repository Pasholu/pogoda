// Переход «Импульс соединения»: пока идёт запрос к базе, точка бежит от ссылки к значку базы,
// после ответа карточка раскрывается на весь экран, и поверх неё открывается страница записей.
const dbConnect = (() => {
  const LAP_MS = 900
  const START_DELAY_MS = 250
  const PULSE_MS = 200
  const EXPAND_AT_MS = 1000
  const DONE_AT_MS = 1400
  const FAILURE_HOLD_MS = 1600

  const ICON_LINK =
    '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"></path><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"></path></svg>'
  const ICON_DATABASE =
    '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><ellipse cx="12" cy="5" rx="9" ry="3"></ellipse><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path></svg>'

  let current = null

  function reduced() {
    return matchMedia('(prefers-reduced-motion: reduce)').matches
  }

  function easeInOutCubic(t) {
    return t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2
  }

  function nextFrame(callback) {
    requestAnimationFrame(() => requestAnimationFrame(callback))
  }

  /** Убирает модалку и останавливает всё, что она запустила. */
  function clear() {
    if (!current) return
    current.timers.forEach(clearTimeout)
    cancelAnimationFrame(current.frame)
    current.root.remove()
    current = null
  }

  /**
   * Показывает модалку и ждёт request. Обещание выполняется, когда карточка раскрыта на весь экран
   * (модалка при этом остаётся — её снимает вызывающий через clear), и отклоняется, если запрос не удался.
   */
  function play(request) {
    clear()
    const root = document.createElement('div')
    root.className = 'dbc'
    root.innerHTML = `
      <div class="dbc__veil"></div>
      <div class="dbc__card" role="status" aria-live="polite">
        <div class="dbc__content">
          <div class="dbc__row">
            <div class="dbc__tile dbc__tile--link">${ICON_LINK}</div>
            <div class="dbc__track">
              <span class="dbc__dash"></span>
              <span class="dbc__trail"></span>
              <span class="dbc__dot"></span>
            </div>
            <div class="dbc__tile dbc__tile--db">${ICON_DATABASE}</div>
          </div>
          <div class="dbc__status">
            <span class="dbc__wait">Подключение к базе данных…</span>
            <span class="dbc__ok">Соединение установлено</span>
          </div>
        </div>
      </div>`
    document.body.append(root)

    const state = { root, timers: [], frame: 0 }
    current = state
    const later = (callback, ms) => state.timers.push(setTimeout(callback, ms))
    const dot = root.querySelector('.dbc__dot')
    const trail = root.querySelector('.dbc__trail')
    const wait = root.querySelector('.dbc__wait')

    // Позиция точки меняется напрямую в стиле: перерисовывать всю карточку на каждый кадр незачем.
    const setDot = (progress) => {
      dot.style.left = `${progress * 100}%`
      trail.style.width = `${progress * 100}%`
    }

    let outcome = null
    request.then(
      () => (outcome = 'ok'),
      () => (outcome = 'failed'),
    )

    return new Promise((resolve, reject) => {
      const connected = () => {
        root.classList.add('dbc--connected', 'dbc--pulse')
        later(() => root.classList.remove('dbc--pulse'), PULSE_MS)
        later(() => root.classList.add('dbc--expanded'), EXPAND_AT_MS)
        later(resolve, DONE_AT_MS)
      }

      const failed = () => {
        root.classList.add('dbc--failed')
        wait.textContent = 'База данных не отвечает'
        later(() => root.classList.remove('dbc--in'), FAILURE_HOLD_MS)
        later(() => {
          if (current === state) clear()
          reject(new Error('База данных не отвечает'))
        }, FAILURE_HOLD_MS + 250)
      }

      const runDot = () => {
        let lapStart = performance.now()
        let lap = 0
        const step = (now) => {
          const t = Math.min(1, (now - lapStart) / LAP_MS)
          setDot(easeInOutCubic(t))
          if (t < 1) {
            state.frame = requestAnimationFrame(step)
            return
          }
          // К исходу круга ответ уже есть — переходим; иначе точка идёт на следующий круг.
          if (outcome === 'ok') return connected()
          if (outcome === 'failed') return failed()
          lap++
          if (lap === 1) wait.textContent = 'Ожидание ответа сервера…'
          lapStart = now
          state.frame = requestAnimationFrame(step)
        }
        state.frame = requestAnimationFrame(step)
      }

      nextFrame(() => {
        setDot(0)
        root.classList.add('dbc--in')
      })
      later(runDot, START_DELAY_MS)
    })
  }

  /** Ссылки на страницу записей открываются через анимацию соединения. */
  function wireLinks() {
    document.addEventListener('click', (event) => {
      const link = event.target.closest('a[data-db-link]')
      if (!link) return
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      if (reduced()) return
      event.preventDefault()
      const request = fetch('/api/admin/data/station?size=1').then((response) => {
        if (!response.ok) throw new Error('База данных не отвечает')
      })
      play(request).then(
        () => location.assign(link.href),
        () => {},
      )
    })

    // Возврат кнопкой «Назад» достаёт страницу из кэша браузера вместе с раскрытой карточкой.
    addEventListener('pageshow', (event) => {
      if (event.persisted) clear()
    })
  }

  wireLinks()
  return { play, clear, reduced }
})()
