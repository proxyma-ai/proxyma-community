// A fake AI provider: everything Proxyma asks a model to do, answered instantly and identically
// every time.
//
// WHY THIS EXISTS. A functional test has to drive chat, retrieval, tool calling, reranking and
// cost metering, and NONE of that is a question about whether a model is clever. Ollama can serve
// those paths but it answers in seconds, differently each time, and a suite built on it measures
// the model instead of the product. This serves the same wire protocol in under a millisecond and
// returns the SAME bytes on every run, so a red test means the product changed.
//
// It is not a mock. It is a real HTTP server speaking the real OpenAI-compatible protocol, reached
// through the real adapter, registered through the same /api/v1/providers/instances call a user
// makes in Settings. Nothing in the product knows it is under test - there is no test profile, no
// injected stub and no branch that behaves differently. That is the whole point: the code path
// under test is the shipped one.
//
//   node tools/testing/fake-llm.mjs --port 8799
//
// Then register it in Settings > Providers as an OpenAI-compatible instance with base URL
// http://localhost:8799 and any key, and pick model `fake-chat` for Chat, `fake-embed` for
// Embedding, `fake-rerank` for Reranking.
//
// FLAGS
//   --port <n>          default 8799. 0 picks a free port and prints it as `listening <port>`,
//                       which is how the Java harness learns where it landed.
//   --dim <n>           embedding dimension, default 384. Must match nothing in particular - the
//                       vector store takes whatever it is given - but changing it between runs
//                       against the same index is exactly the model-changed reindex case.
//   --latency <ms>      add this delay to every response, for watching a spinner or exercising a
//                       timeout. Default 0.
//   --seed <text>       salt for the deterministic generators. Same seed, same bytes, forever.
//   --log               print every request line to stderr.
//
// ENDPOINTS - the product-facing half, all exactly as OpenAiCompatibleAdapter expects them
//   GET  /v1/models              the four models below
//   POST /v1/chat/completions    streaming (SSE) and blocking, tool calls, usage
//   POST /v1/embeddings          deterministic vectors
//   POST /v1/rerank              Cohere-shaped, the shape the adapter's rerank() parses
//
// ENDPOINTS - the control half, which no product code ever calls
//   GET    /_control/requests    every request received, newest last, as JSON
//   DELETE /_control/requests    forget them
//   POST   /_control/script      queue scripted replies (see SCRIPTING)
//   DELETE /_control/script      drop the queue
//   GET    /_control/health      { ok: true } once listening
//
// SCRIPTING, and why most tests will not need it.
//
// The default reply is derived from the request, so a test can assert on it without arranging
// anything: the model answers with a stable sentence that quotes the user's question back and
// lists the context documents it was given. That last part is what makes retrieval testable
// without a real model - if the grounding pipeline did not put a document in the prompt, the
// answer cannot mention it, and the assertion fails for the right reason.
//
// Beyond that, a test steers the model by writing DIRECTIVES into the message it sends. They are
// read from any message in the request and stripped before the echo, so they never pollute the
// answer:
//
//   @@text <rest of line>        reply with exactly this
//   @@tool <name> <json args>    emit a tool call; repeatable for a parallel multi-tool turn
//   @@say <text>                 text sent in the SAME reply as the tool calls, before them - the
//                                line a model writes on its way to a tool
//   @@after-tools <text>         what to say once the tool results come back (default: a summary)
//   @@chunks <n>                 split a streamed reply into n chunks instead of one per word
//   @@tokens <in>/<out>          report exactly this usage instead of the counted estimate
//   @@slow <ms>                  delay this reply
//   @@pace <ms>                  wait this long between the chunks of a streamed reply, so a
//                                browser test can act while the answer is still arriving
//   @@status <code>              answer with this HTTP status and an OpenAI-shaped error body
//   @@drop                       accept the request, then close the socket mid-stream
//   @@empty                      a well-formed reply with no content at all
//   @@straddle                   stream the tool calls so that one delta ends a call and opens the
//                                next - two entries in one `tool_calls` array
//   @@reasoning <text>           THINK this first, streamed as `reasoning` deltas (vLLM, Ollama /v1)
//   @@reasoning-content <text>   the same as `reasoning_content` deltas (DeepSeek, llama.cpp)
//
// A directive typed into the real chat box works, which is the point: a browser test drives a
// tool-calling turn by typing one line, with no control-plane call and no fixture.
//
// /_control/script is for what a directive cannot reach - the turns the PRODUCT originates, where
// no test wrote the prompt: memory condensation, graph extraction, query expansion, sub-agents.
// POST an array of replies and they are consumed in order, oldest first, by any chat request that
// carries no directive; when the queue empties the default behavior returns.
//
//   POST /_control/script  [{ "text": "..." }, { "status": 429 }, { "toolCalls": [...] }]
//
// A scripted reply is sent as written, after a tool round too - `{ "say": "...", "toolCalls":
// [{ "name": "...", "arguments": "{}" }] }` then `{ "text": "..." }` is a two-round turn. A
// directive in the conversation still overrides the field it names.
//
// A reply may say WHICH request it is for: `"model": "fake-chat-mini"` is taken only by a request
// for that model, and `"when": "some text"` only by one whose messages contain that text. Each
// request takes the oldest reply it qualifies for. That is what makes several agents at once
// scriptable - the main agent and two sub-agents call in whatever order the scheduler gives, and
// each still gets its own lines (`when` the sub-agent's task, say). `"offers": "<tool name>"` is
// taken only by a request that offers that tool - which keeps a reply scripted for an agent turn
// away from the calls the product makes beside it with no tools at all, a conversation's title.

import { createHash, randomUUID } from 'node:crypto'
import { createServer } from 'node:http'

const args = process.argv.slice(2)
const flag = (name, fallback) => {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : fallback
}
const has = name => args.includes(`--${name}`)

const PORT = Number(flag('port', '8799'))
const DIM = Number(flag('dim', '384'))
const LATENCY = Number(flag('latency', '0'))
const SEED = flag('seed', 'proxyma')
const LOG = has('log')

// The four models this server serves, and the only ones /v1/models advertises. Named for the ROLE
// rather than for a vendor, because a test that reads `fake-chat` in an assertion should not have
// to know which imaginary company made it.
const MODELS = ['fake-chat', 'fake-chat-mini', 'fake-embed', 'fake-rerank']

// ---------------------------------------------------------------------------------------------
// Determinism
//
// Every generator here is a pure function of its input plus --seed. Nothing uses Math.random, a
// clock, or a counter that survives a request - two runs of the same suite produce byte-identical
// vectors and scores. Where an id genuinely has to be unique within a response (a tool call id)
// it is derived from the content, not generated, so even that is stable.
// ---------------------------------------------------------------------------------------------

const digest = text => createHash('sha256').update(`${SEED}:${text}`).digest()

// A feature-hashing embedding: each distinct word is hashed to a handful of dimensions and adds a
// fixed weight there, then the vector is L2-normalized.
//
// THIS IS CRUDE AND IT IS DELIBERATELY NOT RANDOM. A random vector per document would make every
// retrieval assertion vacuous - cosine similarity between unrelated random vectors is noise, so
// "the right document came back" could never be asserted and a broken retriever would pass. Under
// feature hashing two texts sharing words score high and two sharing none score near zero, which
// is a real (if shallow) semantic signal: it is bag-of-words cosine. That is enough to pin the
// behavior the product owns - that the query reaches the store, that the top-k and min-score
// filters bite, that the right chunk is the one that comes back - without asserting anything about
// how good a real embedding model would be.
function embed(text) {
  const vector = new Float64Array(DIM)
  const words = tokenize(text)
  for (const word of words) {
    const h = digest(word)
    // Four dimensions per word. One collides too often at DIM=384; more just blurs everything
    // toward the same vector, which costs exactly the discrimination this is here to provide.
    for (let i = 0; i < 4; i++) {
      const slot = h.readUInt32BE(i * 4) % DIM
      // The sign comes from the hash too, so unrelated words cancel instead of accumulating into
      // a shared positive bias that drags every pair's cosine upward.
      const sign = (h[16 + i] & 1) === 0 ? 1 : -1
      vector[slot] += sign
    }
  }
  let norm = 0
  for (const v of vector) norm += v * v
  norm = Math.sqrt(norm)
  // An empty or all-cancelled text has no direction. Return a zero vector rather than dividing by
  // zero: a store that rejects it is telling the truth about an empty document.
  if (norm === 0) return Array.from(vector)
  return Array.from(vector, v => v / norm)
}

// Lowercased word characters, length 2+. Not a real tokenizer and does not need to be - it only
// has to agree with ITSELF between the document and the query.
const tokenize = text => (String(text).toLowerCase().match(/[a-z0-9_]{2,}/g) ?? [])

// Rerank scores by term overlap, in [0, 1]. Same reasoning as embed(): a real cross-encoder is a
// black box, but "shares more of the query's words" is a monotone stand-in, so a test can assert
// that reranking REORDERED the list toward the query without asserting anything about quality.
function rerankScore(query, document) {
  const q = new Set(tokenize(query))
  if (q.size === 0) return 0
  const d = new Set(tokenize(document))
  let hits = 0
  for (const term of q) if (d.has(term)) hits++
  return hits / q.size
}

// A token estimate, not a tokenizer. Four characters to a token is the conventional rough ratio
// and it is stable, which is all a cost-metering assertion needs: the product's job is to record
// what the provider reported, and this reports something non-zero and reproducible. A test that
// needs an exact number writes @@tokens.
const estimateTokens = text => Math.max(1, Math.ceil(String(text ?? '').length / 4))

// ---------------------------------------------------------------------------------------------
// Directives
// ---------------------------------------------------------------------------------------------

// The value runs to the end of the line OR to the next @@, whichever comes first.
//
// It was the rest of the line, and that made a directive swallow every directive after it:
// `@@tool search {...} @@after-tools I looked it up.` parsed as ONE tool call whose arguments
// were `{...} @@after-tools I looked it up.`, and the after-tools instruction vanished.
// SILENTLY is the word - the turn still worked, the tool was still called, and the model just
// answered with the default summary instead of what the test asked for. Found on the host, by
// the one journey that could not run on a laptop with no Docker.
//
// AND IT IS ONLY FREE TEXT FOR THE DIRECTIVES THAT WANT FREE TEXT. `@@chunks 30` takes a number,
// but a rest-of-line value swallowed the sentence after it, so
// `@@chunks 30 what are our payment terms?` reached the model as a chunk count and NO QUESTION.
// The reply - "FAKE ANSWER to: (no question)" - still looked enough like an answer to read past.
// So: rest of the line for the three that are prose, one token for the four that are scalars, and
// nothing at all for the three that are flags.
const DIRECTIVE = /@@(?:(text|tool|after-tools|say|reasoning-content|reasoning)(?:[ \t]+((?:(?!@@)[^\n])*))?|(chunks|tokens|slow|pace|status)(?:[ \t]+(\S+))?|(drop|empty|straddle))/g

// Reads the directives out of every message in the request, last writer wins, and returns both the
// plan and the messages with the directive text removed.
//
// SCANS EVERY MESSAGE, not just the newest. A directive typed into the chat box arrives on the
// first turn; by the time the tool results come back the product has appended more messages, and
// the turn that has to honor @@after-tools is that later one. Reading only the last message would
// lose the instruction exactly when it is needed.
function readDirectives(messages) {
  const plan = { toolCalls: [] }
  for (const message of messages) {
    const content = textOf(message.content)
    if (!content) continue
    for (const match of content.matchAll(DIRECTIVE)) {
      // One of the three alternatives matched, so exactly one name group and at most one value
      // group is set.
      const name = match[1] ?? match[3] ?? match[5]
      const value = (match[2] ?? match[4] ?? '').trim()
      switch (name) {
        case 'text': plan.text = value; break
        case 'after-tools': plan.afterTools = value; break
        case 'say': plan.say = value; break
        // Longer name first in the regex, or `reasoning` would match its prefix.
        case 'reasoning': plan.reasoning = { field: 'reasoning', text: value }; break
        case 'reasoning-content': plan.reasoning = { field: 'reasoning_content', text: value }; break
        case 'chunks': plan.chunks = Number(value) || undefined; break
        case 'slow': plan.slow = Number(value) || 0; break
        case 'pace': plan.pace = Number(value) || 0; break
        case 'status': plan.status = Number(value) || 500; break
        case 'drop': plan.drop = true; break
        case 'empty': plan.empty = true; break
        case 'straddle': plan.straddle = true; break
        case 'tokens': {
          const [input, output] = value.split('/').map(Number)
          if (Number.isFinite(input)) plan.tokens = { input, output: output || 0 }
          break
        }
        case 'tool': {
          // `@@tool search_documents {"query":"invoices"}` - name, then the rest of the line as
          // JSON. Unparseable arguments are passed through verbatim rather than dropped, because
          // a test asserting that the product REJECTS malformed tool arguments needs them to
          // arrive malformed.
          const space = value.indexOf(' ')
          const name = space < 0 ? value : value.slice(0, space)
          const rest = space < 0 ? '{}' : value.slice(space + 1).trim()
          if (name) plan.toolCalls.push({ name, arguments: rest || '{}' })
          break
        }
      }
    }
  }
  return plan
}

const stripDirectives = text => String(text ?? '').replace(DIRECTIVE, '').replace(/[ \t]+\n/g, '\n').trim()

// A message's content is a string on a plain turn and an array of typed parts once an image or a
// file is attached. Both shapes have to flatten to text here, or an attachment turn would look
// like an empty message and lose its directives with it.
function textOf(content) {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content.map(part => (typeof part === 'string' ? part : (part?.text ?? ''))).join('\n')
  }
  return ''
}

// ---------------------------------------------------------------------------------------------
// The default reply
// ---------------------------------------------------------------------------------------------

// What the model says when nothing scripted it. Two jobs, and the second is the useful one:
//
//  1. Quote the question, so a test can assert the turn reached the model at all and that the
//     message survived whatever the product did to it on the way.
//  2. NAME THE CONTEXT DOCUMENTS the prompt carried. Retrieval-augmented generation is the
//     product's central claim, and the only externally visible evidence that grounding worked is
//     that the model was shown the right documents. Listing them here turns that into an
//     assertion a test can make on the ANSWER - which is where a user would notice it too.
function defaultReply(messages) {
  const question = stripDirectives(textOf(lastUserMessage(messages)?.content)) || '(no question)'
  const documents = contextDocuments(messages)
  const head = `FAKE ANSWER to: ${truncate(question, 200)}`
  if (documents.length === 0) return `${head}\nCONTEXT: none`
  return `${head}\nCONTEXT: ${documents.join(' | ')}`
}

const lastUserMessage = messages => [...messages].reverse().find(m => m.role === 'user')

// Pulls document titles out of whatever the product put in the prompt. Matches the two shapes the
// RAG prompt builders emit - a markdown heading and a `Title:` line - and is deliberately
// permissive: this is evidence-gathering for an assertion, not parsing a contract. A shape it
// misses shows up as a missing title in a failing test, which is a readable way to find out that
// the prompt format moved.
function contextDocuments(messages) {
  const titles = new Set()
  for (const message of messages) {
    if (message.role === 'user' || message.role === 'system' || message.role === 'tool') {
      for (const match of textOf(message.content).matchAll(/^(?:#{1,6}\s+|Title:\s*)(.+)$/gm)) {
        const title = match[1].trim()
        if (title) titles.add(truncate(title, 80))
      }
    }
  }
  return [...titles]
}

const truncate = (text, max) => (text.length <= max ? text : `${text.slice(0, max - 3)}...`)

// ---------------------------------------------------------------------------------------------
// State the control plane owns
// ---------------------------------------------------------------------------------------------

// Every request, in arrival order. Unbounded on purpose: a suite that runs long enough to exhaust
// memory here is one that never clears between tests, and a test asserting on a request it cannot
// find is a worse failure than a big array. The harness clears it per test.
const requests = []
const scripted = []

const record = (path, body, extra = {}) => {
  requests.push({ at: new Date().toISOString(), path, body, ...extra })
  if (LOG) process.stderr.write(`[fake-llm] ${path} ${JSON.stringify(extra)}\n`)
}

// ---------------------------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------------------------

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

const json = (response, status, body) => {
  const payload = JSON.stringify(body)
  response.writeHead(status, {
    'content-type': 'application/json',
    'content-length': Buffer.byteLength(payload),
    // Every response is CORS-open. This server is only ever reached by the backend, never by a
    // browser, but a permissive header costs nothing and removes one way for a test to fail for a
    // reason that has nothing to do with what it is testing.
    'access-control-allow-origin': '*',
  })
  response.end(payload)
}

const readBody = request => new Promise((resolve, reject) => {
  const chunks = []
  request.on('data', chunk => chunks.push(chunk))
  request.on('error', reject)
  request.on('end', () => {
    const raw = Buffer.concat(chunks).toString('utf8')
    if (!raw) return resolve({})
    try { resolve(JSON.parse(raw)) } catch { resolve({ _unparseable: raw }) }
  })
})

const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://localhost')
  const path = url.pathname

  if (request.method === 'OPTIONS') {
    response.writeHead(204, {
      'access-control-allow-origin': '*',
      'access-control-allow-headers': '*',
      'access-control-allow-methods': 'GET,POST,DELETE,OPTIONS',
    })
    return response.end()
  }

  try {
    if (path.startsWith('/_control/')) return await control(request, response, path)
    if (LATENCY > 0) await sleep(LATENCY)

    if (path === '/v1/models' && request.method === 'GET') {
      record(path, null, { method: 'GET' })
      return json(response, 200, {
        object: 'list',
        data: MODELS.map(id => ({ id, object: 'model', created: 0, owned_by: 'proxyma-test' })),
      })
    }
    if (path === '/v1/embeddings' && request.method === 'POST') return await embeddings(request, response)
    if (path === '/v1/rerank' && request.method === 'POST') return await rerank(request, response)
    if (path === '/v1/chat/completions' && request.method === 'POST') return await chat(request, response)

    // A 404 here means the product asked for an endpoint this server does not implement, which is
    // worth seeing rather than guessing at - record it so the failing test can print it.
    record(path, null, { method: request.method, unmatched: true })
    return json(response, 404, { error: { message: `fake-llm has no ${request.method} ${path}`, type: 'not_found' } })
  } catch (error) {
    return json(response, 500, { error: { message: String(error?.stack ?? error), type: 'fake_llm_error' } })
  }
})

async function control(request, response, path) {
  if (path === '/_control/health') return json(response, 200, { ok: true, port: server.address()?.port, dim: DIM })
  if (path === '/_control/requests') {
    if (request.method === 'DELETE') { requests.length = 0; return json(response, 200, { cleared: true }) }
    return json(response, 200, requests)
  }
  if (path === '/_control/script') {
    if (request.method === 'DELETE') { scripted.length = 0; return json(response, 200, { cleared: true }) }
    const body = await readBody(request)
    const entries = Array.isArray(body) ? body : [body]
    scripted.push(...entries)
    return json(response, 200, { queued: scripted.length })
  }
  return json(response, 404, { error: { message: `no control endpoint ${path}` } })
}

async function embeddings(request, response) {
  const body = await readBody(request)
  // `input` is a string on a single embed and an array on a batch. Spring AI batches, but the
  // single shape is what a curl by hand sends, and rejecting it would make the server harder to
  // poke at than the thing it stands in for.
  const inputs = Array.isArray(body.input) ? body.input : [body.input ?? '']
  record('/v1/embeddings', body, { method: 'POST', model: body.model, count: inputs.length })
  const promptTokens = inputs.reduce((sum, text) => sum + estimateTokens(text), 0)
  return json(response, 200, {
    object: 'list',
    model: body.model ?? 'fake-embed',
    data: inputs.map((text, index) => ({ object: 'embedding', index, embedding: embed(text) })),
    usage: { prompt_tokens: promptTokens, total_tokens: promptTokens },
  })
}

async function rerank(request, response) {
  const body = await readBody(request)
  const documents = Array.isArray(body.documents) ? body.documents : []
  record('/v1/rerank', body, { method: 'POST', model: body.model, count: documents.length })

  const scored = documents
    .map((document, index) => ({ index, relevance_score: rerankScore(body.query ?? '', document) }))
    // Descending by score, then by original index so ties keep the order the caller sent. The
    // adapter's rerank() preserves server order rather than re-sorting, so whatever comes out of
    // here IS the ranking the product uses - an unstable sort would make the assertion flap.
    .sort((a, b) => b.relevance_score - a.relevance_score || a.index - b.index)

  const topN = Number(body.top_n) > 0 ? Number(body.top_n) : scored.length
  const promptTokens = estimateTokens(body.query) + documents.reduce((s, d) => s + estimateTokens(d), 0)
  return json(response, 200, {
    results: scored.slice(0, topN),
    usage: { prompt_tokens: promptTokens, total_tokens: promptTokens },
  })
}

async function chat(request, response) {
  const body = await readBody(request)
  const messages = Array.isArray(body.messages) ? body.messages : []
  const fromScript = takeScripted(body, messages)
  const plan = { ...(fromScript ?? {}), ...planFromDirectives(messages) }

  record('/v1/chat/completions', body, {
    method: 'POST',
    model: body.model,
    stream: body.stream === true,
    tools: (body.tools ?? []).map(t => t?.function?.name).filter(Boolean),
    messageCount: messages.length,
  })

  if (plan.slow > 0) await sleep(plan.slow)
  if (plan.status) {
    return json(response, plan.status, {
      error: { message: `fake-llm was asked for HTTP ${plan.status}`, type: 'fake_llm_scripted', code: plan.status },
    })
  }

  // A turn carrying tool RESULTS is the second half of a tool call, so the tool directives that
  // produced it must not fire again - that would loop until max-iterations and look like a product
  // bug. @@after-tools (or a summary) answers instead.
  //
  // A SCRIPTED reply is the exception: it says what THIS call returns, so its tool calls and its
  // text are sent as written even after a tool round. That is what lets a test script a turn of
  // several rounds, or a sub-agent that calls a tool and then reports.
  const afterTools = toolsAnsweredThisTurn(messages)
  const toolCalls = afterTools && !fromScript?.toolCalls ? [] : (plan.toolCalls ?? [])
  const content = plan.empty ? ''
    : toolCalls.length > 0 ? (plan.say ?? '')
    : typeof fromScript?.text === 'string' ? plan.text
    : afterTools ? (plan.afterTools ?? toolSummary(messages))
    : (plan.text ?? defaultReply(messages))

  const usage = buildUsage(plan, messages, content)
  return body.stream === true
    ? streamCompletion(response, body, content, toolCalls, usage, plan)
    : blockingCompletion(response, body, content, toolCalls, usage)
}

// Scripted entries and directives both produce a plan; directives win field by field, so a test
// can queue a default with /_control/script and still override one turn from the prompt.
// The oldest scripted reply this request qualifies for - see SCRIPTING on `model` and `when`.
function takeScripted(body, messages) {
  if (scripted.length === 0) return null
  let text = null
  const at = scripted.findIndex(entry => {
    if (entry.model && entry.model !== body.model) return false
    if (entry.offers && !(body.tools ?? []).some(t => t?.function?.name === entry.offers)) return false
    if (!entry.when) return true
    text ??= messages.map(m => textOf(m.content)).join('\n')
    return text.includes(entry.when)
  })
  return at < 0 ? null : scripted.splice(at, 1)[0]
}

function planFromDirectives(messages) {
  const plan = readDirectives(messages)
  // @@tool belongs to the TURN it was typed in, so it is read from the newest user message only.
  // Read from every message, as the other directives are, a tool asked for three turns ago fired
  // again on every later turn whose history held no tool result for it - which is also exactly
  // what a turn stopped mid-tool looked like before its results were kept, so the fake looped
  // where a real provider answers 400.
  const current = lastUserMessage(messages)
  plan.toolCalls = current ? readDirectives([current]).toolCalls : []
  // An empty toolCalls array must not blank a scripted one, so drop the key entirely when no
  // @@tool was written.
  if (plan.toolCalls.length === 0) delete plan.toolCalls
  return plan
}

// Whether this request is the second half of THIS turn's tool call: results after the newest user
// message. Results from an earlier turn are history, not an answer to anything asked now.
function toolsAnsweredThisTurn(messages) {
  let lastUser = -1
  messages.forEach((m, i) => { if (m.role === 'user') lastUser = i })
  return messages.slice(lastUser + 1).some(m => m.role === 'tool')
}

// What the model says after its tools have answered. Lists the tools by name so a test can assert
// the round trip completed and that the product fed the RESULTS back rather than only the calls.
function toolSummary(messages) {
  const names = messages.filter(m => m.role === 'tool').map(m => m.name ?? 'tool')
  return `FAKE ANSWER after tools: ${names.join(', ') || 'none'}`
}

function buildUsage(plan, messages, content) {
  if (plan.tokens) {
    const { input, output } = plan.tokens
    return { prompt_tokens: input, completion_tokens: output, total_tokens: input + output }
  }
  const promptTokens = messages.reduce((sum, m) => sum + estimateTokens(textOf(m.content)), 0)
  const completionTokens = estimateTokens(content)
  return { prompt_tokens: promptTokens, completion_tokens: completionTokens, total_tokens: promptTokens + completionTokens }
}

// A tool call id has to be unique within a response and stable across runs. Derived from the call
// itself plus its position, never randomUUID - an id that changed per run would make every
// recorded-request assertion a moving target.
const toolCallId = (call, index) => `call_${digest(`${index}:${call.name}:${call.arguments}`).toString('hex').slice(0, 24)}`

const toolCallPayload = (call, index) => ({
  id: toolCallId(call, index),
  type: 'function',
  function: { name: call.name, arguments: call.arguments },
})

function blockingCompletion(response, body, content, toolCalls, usage) {
  return json(response, 200, {
    id: `chatcmpl-${digest(JSON.stringify(body.messages ?? [])).toString('hex').slice(0, 24)}`,
    object: 'chat.completion',
    created: 0,
    model: body.model ?? 'fake-chat',
    choices: [{
      index: 0,
      message: {
        role: 'assistant',
        content: content || null,
        ...(toolCalls.length > 0 ? { tool_calls: toolCalls.map(toolCallPayload) } : {}),
      },
      finish_reason: toolCalls.length > 0 ? 'tool_calls' : 'stop',
    }],
    usage,
  })
}

async function streamCompletion(response, body, content, toolCalls, usage, plan) {
  const id = `chatcmpl-${digest(JSON.stringify(body.messages ?? [])).toString('hex').slice(0, 24)}`
  response.writeHead(200, {
    'content-type': 'text/event-stream',
    'cache-control': 'no-cache',
    connection: 'keep-alive',
    'access-control-allow-origin': '*',
  })

  const frame = delta => {
    response.write(`data: ${JSON.stringify({
      id, object: 'chat.completion.chunk', created: 0, model: body.model ?? 'fake-chat',
      choices: [{ index: 0, delta, finish_reason: null }],
    })}\n\n`)
  }

  frame({ role: 'assistant' })

  // Thinking streams first, in its own deltas and under the field the directive named - the two
  // names real servers use for one thing. A turn that is the second half of a tool call does not
  // think again, as with @@tool.
  if (plan.reasoning?.text && !toolsAnsweredThisTurn(body.messages ?? [])) {
    for (const piece of split(plan.reasoning.text, plan.chunks)) frame({ [plan.reasoning.field]: piece })
  }

  // Text first, then the tool calls: with tool calls the text is only what @@say gave, the line a
  // model writes before it reaches for a tool.
  for (const piece of split(content, plan.chunks)) {
    if (plan.drop) {
      // Mid-stream socket death: the client must surface a failed turn rather than keeping a
      // half-written answer and calling it complete.
      response.destroy()
      return
    }
    frame({ content: piece })
    if (plan.pace > 0) await sleep(plan.pace)
  }
  // Each tool call arrives complete in one delta rather than split across chunks. A real provider
  // may fragment the arguments string and the aggregator has to reassemble it - that behavior is
  // already pinned by StreamChunkAggregationTest against recorded fragments, which is the right
  // place for it. Fragmenting here too would add a second, differently-shaped source of truth.
  //
  // @@straddle is the one exception, and it is a shape rather than a fragmentation: each delta
  // after the first carries the END of one call and the START of the next, two entries in one
  // `tool_calls` array. That is what ended turns inside Spring AI (see ParallelToolCallDeltas), and
  // nothing but a server sending it can show the product surviving it.
  if (plan.straddle && toolCalls.length > 1) {
    const halves = toolCalls.map((call, index) => {
      const payload = toolCallPayload(call, index)
      const cut = Math.ceil(payload.function.arguments.length / 2)
      return {
        open: { index, ...payload, function: { name: call.name, arguments: payload.function.arguments.slice(0, cut) } },
        rest: { index, function: { arguments: payload.function.arguments.slice(cut) } },
      }
    })
    halves.forEach((half, i) => frame({ tool_calls: i === 0 ? [half.open] : [halves[i - 1].rest, half.open] }))
    frame({ tool_calls: [halves[halves.length - 1].rest] })
  } else {
    toolCalls.forEach((call, index) => {
      frame({ tool_calls: [{ index, ...toolCallPayload(call, index) }] })
    })
  }

  response.write(`data: ${JSON.stringify({
    id, object: 'chat.completion.chunk', created: 0, model: body.model ?? 'fake-chat',
    choices: [{ index: 0, delta: {}, finish_reason: toolCalls.length > 0 ? 'tool_calls' : 'stop' }],
  })}\n\n`)

  // The usage-only final chunk, sent when the request asked for it with stream_options. Emitting
  // it unconditionally would hide the bug this protocol detail caused: without
  // `stream_options.include_usage` a real server sends NO usage on a stream, which cost this
  // project zero-token turns, $0 costs, dead quotas and - least obviously - condensation that
  // never ran. A fake that always reports usage would make that regression invisible, so this one
  // only reports it when asked, exactly as the real thing does.
  if (body.stream_options?.include_usage === true) {
    response.write(`data: ${JSON.stringify({
      id, object: 'chat.completion.chunk', created: 0, model: body.model ?? 'fake-chat',
      choices: [], usage,
    })}\n\n`)
  }

  response.write('data: [DONE]\n\n')
  response.end()
}

// Word-by-word by default, so a streaming client has many frames to render and a test can watch
// them arrive; @@chunks n forces n roughly-equal pieces instead.
function split(content, chunks) {
  if (!content) return []
  if (!chunks || chunks < 1) return content.match(/\S+\s*/g) ?? [content]
  const size = Math.ceil(content.length / chunks)
  const pieces = []
  for (let i = 0; i < content.length; i += size) pieces.push(content.slice(i, i + size))
  return pieces
}

server.listen(PORT, '127.0.0.1', () => {
  // The Java harness starts this with --port 0 and reads the real port from this line, so the
  // format is a contract: `listening <port>`. Tests run in parallel and a fixed port would make
  // two suites fight over it.
  process.stdout.write(`listening ${server.address().port}\n`)
})

// SIGTERM is how the harness stops it. Without a handler Node exits on it anyway, but doing it
// explicitly keeps the exit code at 0, which matters because the harness treats a non-zero exit
// from a support process as a suite failure.
for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => server.close(() => process.exit(0)))
}
