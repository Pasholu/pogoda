// Знак в шапке: по нажатию прогнозы сходятся к факту, и только потом открывается главная.
const PRESS_MS = 520

document.querySelectorAll('.brand').forEach((link) => {
  link.addEventListener('animationend', () => link.classList.remove('brand--pressed'))

  link.addEventListener('click', (event) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return
    event.preventDefault()

    // Сброс класса с пересчётом стилей перезапускает анимацию при повторном нажатии.
    link.classList.remove('brand--pressed')
    void link.offsetWidth
    link.classList.add('brand--pressed')

    const onHome = location.pathname === '/' || location.pathname.endsWith('/index.html')
    if (onHome) {
      window.scrollTo({ top: 0, behavior: 'smooth' })
    } else {
      setTimeout(() => location.assign(link.href), PRESS_MS)
    }
  })
})
