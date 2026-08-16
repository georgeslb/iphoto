import React from 'react';
import { LocaleProvider } from '../LocaleContext.jsx';

export function localeWrapper({ children }) {
  return <LocaleProvider>{children}</LocaleProvider>;
}
