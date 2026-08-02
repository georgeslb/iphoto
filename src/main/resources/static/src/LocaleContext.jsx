import React, { createContext, useState, useContext } from 'react';
import { allMessages } from './i18n';

const LocaleContext = createContext();

export const LocaleProvider = ({ children }) => {
  const [locale, setLocale] = useState('en'); // Default locale is English

  const messages = allMessages[locale] || allMessages.en; // Fallback to English

  return (
    <LocaleContext.Provider value={{ locale, setLocale, messages }}>
      {children}
    </LocaleContext.Provider>
  );
};

export const useLocale = () => useContext(LocaleContext);