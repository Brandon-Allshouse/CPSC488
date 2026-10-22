import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { applyTheme, getStoredTheme } from './lib/theme';
import './styles/index.css';

// Set the saved theme before the first render so the page doesn't flash the other one.
applyTheme(getStoredTheme());

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
