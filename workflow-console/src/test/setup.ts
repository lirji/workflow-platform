import '@testing-library/jest-dom'

// jsdom 未实现带伪元素参数的 getComputedStyle;antd 量测滚动条会以伪元素调用它 → 吞掉该噪声(不影响断言)。
const _getComputedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = ((elt: Element) => _getComputedStyle(elt)) as typeof window.getComputedStyle

// antd 的 Grid.useBreakpoint / 响应式依赖 matchMedia;jsdom 未实现,补最小桩(默认不命中任何断点)。
if (typeof window !== 'undefined' && !window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }) as unknown as MediaQueryList
}
