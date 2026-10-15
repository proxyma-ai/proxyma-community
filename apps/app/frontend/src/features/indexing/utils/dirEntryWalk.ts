// Recursively walks dropped/selected files and folders, preserving the relative path within
// the drop so the server can mirror the exact folder structure on disk.

export interface DroppedFile {
  file: File
  relativePath: string
}

export interface WalkResult {
  files: DroppedFile[]
  // Detected name of the dropped folder, set only when the drop consists of exactly one
  // top-level directory (as opposed to loose files or multiple folders) - used to
  // auto-populate the destination folder name so the connector is registered under the
  // same name as what the user dropped.
  folderName?: string
}

// Minimal typings for the non-standard File System Entry API used by drag-and-drop.
interface FileSystemEntryLike {
  isFile: boolean
  isDirectory: boolean
  fullPath: string
  file?(successCallback: (file: File) => void, errorCallback?: (err: unknown) => void): void
  createReader?(): FileSystemDirectoryReaderLike
}

interface FileSystemDirectoryReaderLike {
  readEntries(
    successCallback: (entries: FileSystemEntryLike[]) => void,
    errorCallback?: (err: unknown) => void,
  ): void
}

function entryName(entry: FileSystemEntryLike): string {
  return entry.fullPath.split('/').filter(Boolean).pop() ?? ''
}

function readAllEntries(reader: FileSystemDirectoryReaderLike): Promise<FileSystemEntryLike[]> {
  return new Promise((resolve, reject) => {
    const all: FileSystemEntryLike[] = []
    const readBatch = () => {
      reader.readEntries(entries => {
        if (entries.length === 0) {
          resolve(all)
          return
        }
        all.push(...entries)
        readBatch()
      }, reject)
    }
    readBatch()
  })
}

function readFile(entry: FileSystemEntryLike): Promise<File> {
  return new Promise((resolve, reject) => {
    entry.file?.(resolve, reject)
  })
}

// Walks entry's contents, appending files to `out`. `dirPath` is the relative directory path
// (not including any file's own name) that `entry`'s children live under - '' at the root of
// a dropped folder (the folder's own name is consumed by the caller as the destination folder,
// not part of relativePath).
async function walkEntry(entry: FileSystemEntryLike, dirPath: string, out: DroppedFile[]): Promise<void> {
  if (entry.isFile) {
    const file = await readFile(entry)
    out.push({ file, relativePath: dirPath })
  } else if (entry.isDirectory && entry.createReader) {
    const entries = await readAllEntries(entry.createReader())
    for (const child of entries) {
      if (child.isDirectory) {
        const childDirPath = dirPath ? `${dirPath}/${entryName(child)}` : entryName(child)
        await walkEntry(child, childDirPath, out)
      } else {
        await walkEntry(child, dirPath, out)
      }
    }
  }
}

// Walks a DataTransferItemList from a drop event, resolving nested folders via the
// webkitGetAsEntry() File System Entry API. Falls back to flat files (relativePath = '')
// for items that don't expose an entry (older browsers, non-file drags).
//
// A single dropped top-level directory is treated as "the destination folder itself" -
// its own name is stripped from relativePath (returned separately as `folderName` so the
// caller can auto-select/create that destination folder) and files directly inside it get
// relativePath=''. But when MULTIPLE top-level entries are dropped together (e.g. several
// sibling folders dropped at once onto an already-chosen destination folder), none of them
// is "the" destination - each one's own name must be preserved as a relativePath prefix,
// or files from different sibling folders collapse into the same flat destination and
// same-named files across folders silently collide.
export async function walkDataTransferItems(items: DataTransferItemList): Promise<WalkResult> {
  const out: DroppedFile[] = []
  const tasks: Promise<void>[] = []
  const topLevelEntries: FileSystemEntryLike[] = []
  const looseFiles: DroppedFile[] = []
  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    const getAsEntry = (item as unknown as { webkitGetAsEntry?: () => FileSystemEntryLike | null }).webkitGetAsEntry
    const entry = getAsEntry?.call(item);
    if (entry) {
      topLevelEntries.push(entry)
    } else {
      const file = item.getAsFile()
      if (file) looseFiles.push({ file, relativePath: '' })
    }
  }

  const isSingleFolderDrop = topLevelEntries.length === 1 && topLevelEntries[0].isDirectory && looseFiles.length === 0
  for (const entry of topLevelEntries) {
    const rootDirPath = isSingleFolderDrop || entry.isFile ? '' : entryName(entry)
    tasks.push(walkEntry(entry, rootDirPath, out))
  }
  await Promise.all(tasks)
  out.push(...looseFiles)

  const folderName = isSingleFolderDrop ? entryName(topLevelEntries[0]) : undefined
  return { files: out, folderName }
}

export interface DroppedFolderFingerprint {
  // The dropped folder's own name.
  name: string
  // The names directly inside it - files and subdirectories alike, one level only.
  children: string[]
}

// Identifies a single dropped folder well enough for the backend to find it on disk.
//
// The child names are the whole point. Desktop cannot learn a dropped folder's absolute path from
// the browser (see LocalPathController), so the backend resolves it by searching for a directory
// with this name whose immediate contents are exactly these entries. A name on its own matches too
// many folders in a home directory to act on; a name plus its contents identifies exactly one.
//
// MUST be called synchronously from the drop handler. A DataTransferItemList is only valid for the
// duration of the event that carried it, so the items are read into FileSystemEntry objects before
// this function's first await; those entries stay valid afterwards, which is what makes the async
// child read below legal. Only ONE level is read, never the tree - this runs while the user waits,
// and the immediate contents already identify the folder.
//
// Says WHY when there is no fingerprint, because the two reasons need different words from the
// modal: a drop that was not one folder (loose files, several items) is the user's to redo, while
// a folder that could not be read is ours to report.
export type DroppedFolderRead =
  | { kind: 'folder'; fingerprint: DroppedFolderFingerprint }
  | { kind: 'not-a-folder' }
  | { kind: 'unreadable' }

// The dropped folder's absolute path, when the page runs in Proxyma's own window - which can supply
// it (desktop-shell/preload.js) where a browser never can. Undefined in a browser, and for any drop
// that is not exactly one folder; the caller then falls back to fingerprintDroppedFolder.
//
// MUST be called synchronously from the drop handler, like fingerprintDroppedFolder.
export function droppedFolderPath(dataTransfer: DataTransfer): string | undefined {
  const bridge = (window as unknown as { proxymaWindow?: { pathForFile?: (f: File) => string | null } }).proxymaWindow
  if (!bridge?.pathForFile || dataTransfer.items.length !== 1 || dataTransfer.files.length !== 1) return undefined
  const getAsEntry = (dataTransfer.items[0] as unknown as { webkitGetAsEntry?: () => FileSystemEntryLike | null }).webkitGetAsEntry
  if (!getAsEntry?.call(dataTransfer.items[0])?.isDirectory) return undefined
  return bridge.pathForFile(dataTransfer.files[0]) ?? undefined
}

export function fingerprintDroppedFolder(items: DataTransferItemList): Promise<DroppedFolderRead> {
  const entries: FileSystemEntryLike[] = []
  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    const getAsEntry = (item as unknown as { webkitGetAsEntry?: () => FileSystemEntryLike | null }).webkitGetAsEntry
    const entry = getAsEntry?.call(item)
    if (entry) entries.push(entry)
  }
  if (entries.length !== 1 || !entries[0].isDirectory) return Promise.resolve({ kind: 'not-a-folder' })
  return readFingerprint(entries[0]).then(fingerprint =>
    fingerprint ? { kind: 'folder', fingerprint } : { kind: 'unreadable' })
}

async function readFingerprint(entry: FileSystemEntryLike): Promise<DroppedFolderFingerprint | undefined> {
  const name = entryName(entry)
  if (!name || !entry.createReader) return undefined
  try {
    const children = await readAllEntries(entry.createReader())
    return { name, children: children.map(entryName).filter(Boolean) }
  } catch {
    // Reported as 'unreadable' by the caller; the modal then says it could not find the folder.
    return undefined
  }
}

// Converts a FileList from a <input webkitdirectory> selection (files carry
// webkitRelativePath already) or a plain flat <input multiple> selection into the same
// shape used by the drag-drop path. For a directory selection, the leading path segment
// (the picked folder's own name) is stripped from relativePath and returned as folderName,
// matching the convention used by walkDataTransferItems.
export function filesFromFileList(fileList: ArrayLike<File>): WalkResult {
  const files = Array.from(fileList)
  const webkitPaths = files.map(f => (f as File & { webkitRelativePath?: string }).webkitRelativePath)
  const allHaveWebkitPath = files.length > 0 && webkitPaths.every(p => !!p)
  const firstSegment = allHaveWebkitPath ? webkitPaths[0]!.split('/')[0] : undefined
  const folderName = allHaveWebkitPath && webkitPaths.every(p => p!.split('/')[0] === firstSegment)
    ? firstSegment
    : undefined

  const dropped: DroppedFile[] = files.map((file, i) => {
    const webkitPath = webkitPaths[i]
    if (webkitPath) {
      const parts = webkitPath.split('/')
      const withoutRoot = folderName ? parts.slice(1) : parts
      const relativePath = withoutRoot.slice(0, -1).join('/')
      return { file, relativePath }
    }
    return { file, relativePath: '' }
  })

  return { files: dropped, folderName }
}
