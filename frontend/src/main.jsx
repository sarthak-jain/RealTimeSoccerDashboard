import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.jsx'
import './App.css'
import { AuthProvider } from './context/AuthContext.jsx'
import { datadogRum } from '@datadog/browser-rum'

// Initialize Datadog RUM (only if configured)
if (import.meta.env.VITE_DD_APPLICATION_ID && import.meta.env.VITE_DD_CLIENT_TOKEN) {
  datadogRum.init({
    applicationId: import.meta.env.VITE_DD_APPLICATION_ID,
    clientToken: import.meta.env.VITE_DD_CLIENT_TOKEN,
    site: import.meta.env.VITE_DD_SITE || 'us5.datadoghq.com',
    service: 'soccer-dashboard-frontend',
    env: import.meta.env.VITE_DD_ENV || 'dev',
    version: '1.0.0',
    sessionSampleRate: 100,
    sessionReplaySampleRate: 0,
    trackUserInteractions: true,
    trackResources: true,
    trackLongTasks: true,
    allowedTracingUrls: [{ match: /\/api\//, propagatorTypes: ['tracecontext', 'datadog'] }],
  })
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <AuthProvider>
      <App />
    </AuthProvider>
  </React.StrictMode>,
)
