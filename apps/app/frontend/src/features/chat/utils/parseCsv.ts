// RFC 4180 CSV for the file preview: quoted fields, "" as an escaped quote, commas and line breaks
// inside quotes, CRLF or LF. The delimiter is sniffed from the first line - a tab-separated file
// named .csv is common, and so is a semicolon one from a European spreadsheet - by picking whichever
// of the three occurs most there outside quotes.
//
// `maxRows` stops early: a preview shows the first rows, so a 25 MB export is not parsed whole.
// Plain .ts with no imports, so `node --test` reaches it (parseCsv.test.ts).

export function sniffDelimiter(text: string): string {
  const end = text.search(/\r?\n/)
  const firstLine = end === -1 ? text : text.slice(0, end)
  let inQuotes = false
  const counts: Record<string, number> = { ',': 0, '\t': 0, ';': 0 }
  for (const ch of firstLine) {
    if (ch === '"') inQuotes = !inQuotes
    else if (!inQuotes && ch in counts) counts[ch]++
  }
  const [best, count] = Object.entries(counts).sort((a, b) => b[1] - a[1])[0]
  return count > 0 ? best : ','
}

export function parseCsv(text: string, maxRows = Infinity, delimiter = sniffDelimiter(text)): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let inQuotes = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i++ } else inQuotes = false
      } else field += ch
      continue
    }
    if (ch === '"' && field === '') inQuotes = true
    else if (ch === delimiter) { row.push(field); field = '' }
    else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i++
      row.push(field); field = ''
      rows.push(row); row = []
      if (rows.length >= maxRows) return rows
    } else field += ch
  }
  if (field !== '' || row.length > 0) { row.push(field); rows.push(row) }
  return rows
}
