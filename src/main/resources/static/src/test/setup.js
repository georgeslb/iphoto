import '@testing-library/jest-dom/vitest';

// jsdom doesn't implement IntersectionObserver; PhotoGrid uses it for infinite scroll.
global.IntersectionObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
