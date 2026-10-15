import React from 'react'
import ReactDOM from 'react-dom/client'
import { Providers } from '@/app/Providers'
import { installAppViewport, installMotion } from '@/components/ui/kit'
import '@/index.css'

// Before the first render, so the shell is the size of the visible screen from its first frame.
installAppViewport()
// Transitions start two frames after the first paint, so nothing eases into place while it loads.
installMotion()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Providers />
  </React.StrictMode>,
)
