/** antd Grid.useBreakpoint / 响应式依赖 matchMedia。desktop=true 时所有查询都命中（含 lg=992）。 */
export function setViewport(desktop: boolean) {
  window.matchMedia = ((q: string) =>
    ({
      matches: desktop,
      media: q,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as unknown as MediaQueryList) as typeof window.matchMedia
}
