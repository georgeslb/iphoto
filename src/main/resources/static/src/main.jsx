import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.jsx'
import { BrowserRouter } from 'react-router-dom'
import { LocaleProvider } from './LocaleContext.jsx'; // Import LocaleProvider
import { initAuthInterceptor } from './utils/authInterceptor';

initAuthInterceptor();

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <LocaleProvider> {/* Wrap App with LocaleProvider */}
        <App />
      </LocaleProvider>
    </BrowserRouter>
  </React.StrictMode>,
)