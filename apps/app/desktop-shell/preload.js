// The one thing the window adds to the page: the absolute path of a file or folder the USER dragged
// in. A browser can never give a page that (see LocalPathController), so a folder dropped on
// Sources used to be found by searching the disk for its name and contents - which could not reach
// a folder outside the home directory at all, and took its whole search budget to say so.
//
// Deliberately this narrow. It answers only for a File object the page already holds, and a page
// only ever holds one because the user dropped or picked it; it reads nothing and lists nothing.
// Sandboxed preloads may use webUtils (Electron 29+), so the renderer still gets no Node.
'use strict'

const { contextBridge, webUtils } = require('electron')

contextBridge.exposeInMainWorld('proxymaWindow', {
  pathForFile: file => {
    try {
      return webUtils.getPathForFile(file) || null
    } catch {
      return null
    }
  },
})
