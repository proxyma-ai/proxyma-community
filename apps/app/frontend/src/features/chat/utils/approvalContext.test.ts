import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  approvalContextRows, commentCard, foldCommentAge, foldContextLinks, isCommentContext, labeledByItsId, parseApprovalContext, timeAgo,
} from './approvalContext.ts'

const youtube = {
  title: 'YouTube',
  fields: [
    { key: 'video', value: 'Biet Tri Ky - dizi cover', href: 'https://www.youtube.com/watch?v=v2&lc=c3', kind: 'LINK' },
    { key: 'viewer', value: 'Minh', href: 'https://www.youtube.com/channel/UCfan2', kind: 'LINK' },
    { key: 'comment', value: 'Sheet music please', href: null, kind: 'QUOTE' },
    { key: 'reply', value: 'The sheet is on camamtieudao.com.', href: null, kind: 'QUOTE' },
  ],
}

const words: Record<string, string> = { 'Video': 'Video', 'Viewer': 'Nguoi xem', 'Their comment': 'Binh luan', 'Reply to post': 'Tra loi se dang' }
const t = (s: string) => words[s] ?? s

test('a YouTube reply: video, viewer, comment and the reply to be posted, in the server\'s order', () => {
  const rows = approvalContextRows(parseApprovalContext(youtube), t)
  assert.deepEqual(rows.map(r => [r.key, r.label, r.kind]), [
    ['video', 'Video', 'LINK'],
    ['viewer', 'Nguoi xem', 'LINK'],
    ['comment', 'Binh luan', 'QUOTE'],
    ['reply', 'Tra loi se dang', 'QUOTE'],
  ])
  assert.equal(rows[0].href, 'https://www.youtube.com/watch?v=v2&lc=c3')
  assert.equal(rows[2].href, null)
  assert.equal(rows[3].value, 'The sheet is on camamtieudao.com.')
})

test('the channel a reply is posted as is a "Reply as" row, linked, kept just before the reply when the viewer takes the links', () => {
  // 2026-11-05: a team switched from @lesyhau to @camamtieudao had drafts for both channels waiting.
  const context = parseApprovalContext({ fields: [
    ...youtube.fields.slice(0, 3),
    { key: 'replyAs', value: 'Cam Am Tieu Dao', href: 'https://www.youtube.com/@camamtieudao', kind: 'LINK' },
    youtube.fields[3],
  ] })
  const tr = (s: string) => ({ ...words, 'Reply as': 'Tra loi voi tu cach' } as Record<string, string>)[s] ?? s
  const rows = foldContextLinks(approvalContextRows(context, tr), tr)
  assert.deepEqual(rows.map(r => r.key), ['viewer', 'comment', 'replyAs', 'reply'])
  const replyAs = rows[2]
  assert.equal(replyAs.label, 'Tra loi voi tu cach')
  assert.equal(replyAs.kind, 'LINK')
  assert.equal(replyAs.href, 'https://www.youtube.com/@camamtieudao')
  assert.deepEqual(replyAs.links, [])
})

test('a chat event carries the context as JSON; the same rows come out', () => {
  assert.deepEqual(approvalContextRows(parseApprovalContext(JSON.stringify(youtube)), t),
    approvalContextRows(parseApprovalContext(youtube), t))
})

test('an unknown key is shown as given', () => {
  const rows = approvalContextRows(parseApprovalContext({ fields: [{ key: 'playlist', value: 'Lessons', kind: 'TEXT' }] }), t)
  assert.deepEqual(rows.map(r => [r.label, r.value]), [['playlist', 'Lessons']])
})

test('a link is only a whole http(s) address; anything else is text', () => {
  const rows = approvalContextRows(parseApprovalContext({ fields: [
    { key: 'video', value: 'Clip', href: 'javascript:alert(1)', kind: 'LINK' },
    { key: 'viewer', value: 'Minh', href: '/relative', kind: 'link' },
    { key: 'other', value: 'Shout', kind: 'SHOUT' },
  ] }), t)
  assert.deepEqual(rows.map(r => [r.kind, r.href]), [['TEXT', null], ['TEXT', null], ['TEXT', null]])
})

test('nothing usable is no context: missing, blank, unreadable, or fields with no key or value', () => {
  assert.equal(parseApprovalContext(null), null)
  assert.equal(parseApprovalContext(undefined), null)
  assert.equal(parseApprovalContext(''), null)
  assert.equal(parseApprovalContext('{not json'), null)
  assert.equal(parseApprovalContext({ fields: 'video' }), null)
  assert.equal(parseApprovalContext({ fields: [{ key: '', value: 'x' }, { key: 'reply', value: '  ' }, null] }), null)
  assert.deepEqual(approvalContextRows(null, t), [])
})

test('a field naming an argument is the one a person may edit; a link or an odd name offers none', () => {
  const rows = approvalContextRows(parseApprovalContext({ fields: [
    { key: 'reply', value: 'Thanks', kind: 'QUOTE', argument: 'text' },
    { key: 'comment', value: 'Hi', kind: 'QUOTE' },
    { key: 'video', value: 'Clip', href: 'https://www.youtube.com/watch?v=v2', kind: 'LINK', argument: 'text' },
    { key: 'note', value: 'x', kind: 'TEXT', argument: '../text' },
  ] }), t)
  assert.deepEqual(rows.map(r => [r.key, r.argument]), [['reply', 'text'], ['comment', null], ['video', null], ['note', null]])
})

test('the video, the comment and a posted reply are links beside the viewer, and the video row goes', () => {
  const rows = foldContextLinks(approvalContextRows(parseApprovalContext({ fields: [
    { key: 'video', value: 'Hoa Tau', href: 'https://www.youtube.com/watch?v=v9', kind: 'LINK' },
    { key: 'viewer', value: 'Minh', href: 'https://www.youtube.com/channel/UCfan2', kind: 'LINK' },
    { key: 'comment', value: 'Sheet music please', href: 'https://www.youtube.com/watch?v=v9&lc=c3', kind: 'QUOTE' },
    { key: 'reply', value: 'Thanks', href: 'https://www.youtube.com/watch?v=v9&lc=c3.r1', kind: 'QUOTE', argument: 'text' },
  ] }), t), t)
  assert.deepEqual(rows.map(r => r.key), ['viewer', 'comment', 'reply'])
  assert.deepEqual(rows[0].links, [
    { key: 'video', label: 'Hoa Tau', href: 'https://www.youtube.com/watch?v=v9' },
    { key: 'comment', label: 'View comment', href: 'https://www.youtube.com/watch?v=v9&lc=c3' },
    { key: 'reply', label: 'View reply', href: 'https://www.youtube.com/watch?v=v9&lc=c3.r1' },
  ])
  assert.equal(rows[2].label, 'Reply posted')
  assert.deepEqual(rows[1].links, [])
})

test('a reply not posted yet keeps its label and adds no link; with no viewer nothing is folded', () => {
  const waiting = foldContextLinks(approvalContextRows(parseApprovalContext(youtube), t), t)
  assert.deepEqual(waiting.map(r => r.key), ['viewer', 'comment', 'reply'])
  assert.deepEqual(waiting[0].links?.map(l => l.key), ['video'])
  assert.equal(waiting[2].label, 'Tra loi se dang')
  const alone = foldContextLinks(approvalContextRows(parseApprovalContext({ fields: [
    { key: 'video', value: 'Clip', href: 'https://www.youtube.com/watch?v=v2', kind: 'LINK' },
  ] }), t), t)
  assert.deepEqual(alone.map(r => [r.key, r.links]), [['video', []]])
})

test('a video an older build named only by its id is offered by what it is, never as the id', () => {
  // 2026-11-05: cards on the Approvals page showed "8QFVNhzw5GE" where the video's title belongs.
  const rows = foldContextLinks(approvalContextRows(parseApprovalContext({ fields: [
    { key: 'video', value: '8QFVNhzw5GE', href: 'https://www.youtube.com/watch?v=8QFVNhzw5GE', kind: 'LINK' },
    { key: 'viewer', value: 'Minh', href: 'https://www.youtube.com/@minh', kind: 'LINK' },
  ] }), t), t)
  assert.deepEqual(rows[0].links, [{ key: 'video', label: 'Open video', href: 'https://www.youtube.com/watch?v=8QFVNhzw5GE' }])
  assert.equal(labeledByItsId('8QFVNhzw5GE', 'https://www.youtube.com/watch?v=8QFVNhzw5GE'), true)
  assert.equal(labeledByItsId('Biet Tri Ky', 'https://www.youtube.com/watch?v=8QFVNhzw5GE'), false)
  assert.equal(labeledByItsId('Hoa', 'https://www.youtube.com/watch?v=8QFVNhzw5GE'), false)
  assert.equal(labeledByItsId('@minh', 'https://www.youtube.com/@minh'), false)
  assert.equal(labeledByItsId('v2', null), false)
})

test('a quote keeps a whole http(s) address and nothing else', () => {
  const rows = approvalContextRows(parseApprovalContext({ fields: [
    { key: 'comment', value: 'Hi', href: 'https://www.youtube.com/watch?v=v2&lc=c3', kind: 'QUOTE' },
    { key: 'reply', value: 'Yo', href: 'javascript:alert(1)', kind: 'QUOTE' },
    { key: 'note', value: 'x', href: 'https://example.com', kind: 'TEXT' },
  ] }), t)
  assert.deepEqual(rows.map(r => r.href), ['https://www.youtube.com/watch?v=v2&lc=c3', null, null])
})

// 2026-11-03: the YouTube team answers old comments too, and the person approving sees how old each one is.
const NOW = Date.parse('2026-11-03T12:00:00Z')
test('the comment\'s date is an age beside its label, in the reader\'s language, and its own row goes', () => {
  const context = parseApprovalContext({ fields: [
    { key: 'viewer', value: 'Minh', kind: 'TEXT' },
    { key: 'comment', value: 'Sheet music please', kind: 'QUOTE' },
    { key: 'commentedAt', value: '2024-10-01T08:30:00Z', kind: 'TEXT' },
    { key: 'reply', value: 'Thanks', kind: 'QUOTE', argument: 'text' },
  ] })
  const rows = foldCommentAge(foldContextLinks(approvalContextRows(context, t), t), 'en', NOW)
  assert.deepEqual(rows.map(r => r.key), ['viewer', 'comment', 'reply'])
  // The label stays the label: the age is drawn beside it, so it never widens every card's label column.
  assert.equal(rows[1].label, 'Binh luan')
  assert.equal(rows[1].age, '2 years ago')
  assert.equal(rows[1].value, 'Sheet music please')
  assert.equal(rows[0].age, undefined)
  const vi = foldCommentAge(approvalContextRows(context, t), 'vi', NOW)
  assert.equal(vi[1].label, 'Binh luan')
  assert.equal(vi[1].age, '2 n\u0103m tr\u01b0\u1edbc')
})

test('an age is the largest whole unit that fits, and now is now', () => {
  assert.equal(timeAgo('2026-11-03T11:59:30Z', 'en', NOW), 'now')
  assert.equal(timeAgo('2026-11-03T09:00:00Z', 'en', NOW), '3 hours ago')
  assert.equal(timeAgo('2026-11-02T12:00:00Z', 'en', NOW), '1 day ago')
  assert.equal(timeAgo('2026-10-20T12:00:00Z', 'en', NOW), '2 weeks ago')
  assert.equal(timeAgo('2026-06-01T12:00:00Z', 'en', NOW), '5 months ago')
  assert.equal(timeAgo('2025-11-01T12:00:00Z', 'en', NOW), '1 year ago')
  assert.equal(timeAgo('yesterday', 'en', NOW), null)
})

test('a date that is not one stays as it was; without a comment the date keeps its row, as an age', () => {
  const odd = approvalContextRows(parseApprovalContext({ fields: [
    { key: 'comment', value: 'Hi', kind: 'QUOTE' },
    { key: 'commentedAt', value: 'long ago', kind: 'TEXT' },
  ] }), t)
  assert.deepEqual(foldCommentAge(odd, 'en', NOW).map(r => [r.key, r.label, r.value]),
    [['comment', 'Binh luan', 'Hi'], ['commentedAt', 'Commented', 'long ago']])
  const alone = approvalContextRows(parseApprovalContext({ fields: [
    { key: 'commentedAt', value: '2026-11-01T12:00:00Z', kind: 'TEXT' },
  ] }), t)
  assert.deepEqual(foldCommentAge(alone, 'en', NOW).map(r => r.value), ['2 days ago'])
})

// 2026-11-05, the maintainer: the comment is the card's title, its date, viewer and video one caption line,
// then the agent's draft and the note - and no "reply as" row, a reply being always the channel owner's.
const tv = (s: string, params?: Record<string, string | number>) =>
  (({ ...words, "{name}'s draft": 'Ban nhap cua {name}', 'Reply posted': 'Da dang', 'View reply': 'Xem tra loi' } as Record<string, string>)[s] ?? s)
    .replace(/\{(\w+)\}/g, (m, k: string) => (params && k in params ? String(params[k]) : m))
const server = {
  fields: [
    { key: 'video', value: 'Biet Tri Ky - dizi cover', href: 'https://www.youtube.com/watch?v=v2', kind: 'LINK' },
    { key: 'viewer', value: 'Minh', href: 'https://www.youtube.com/@minh', kind: 'LINK' },
    { key: 'comment', value: '  Sheet music please\n', href: 'https://www.youtube.com/watch?v=v2&lc=c3', kind: 'QUOTE' },
    { key: 'commentedAt', value: '2026-09-01T08:30:00Z', kind: 'TEXT' },
    { key: 'replyAs', value: 'Cam Am Tieu Dao', href: 'https://www.youtube.com/@camamtieudao', kind: 'LINK' },
    { key: 'reply', value: 'The sheet is on camamtieudao.com.', kind: 'QUOTE', argument: 'text' },
  ],
}

test('a comment\'s card: the comment as its title, when, who and which video under it, the draft named by its agent, no "reply as"', () => {
  const card = commentCard(parseApprovalContext(server), tv, 'en', { author: 'Reply Writer', now: NOW, timeZone: 'UTC' })
  assert.ok(card)
  assert.deepEqual(card.comment, { text: 'Sheet music please', href: 'https://www.youtube.com/watch?v=v2&lc=c3' })
  // Some ICU versions put a narrow no-break space before AM: any space is one here.
  assert.deepEqual({ ...card.written, at: card.written?.at.replace(/\s/g, ' ') },
    { at: 'Sep 1, 2026, 8:30 AM', ago: '2 months ago', iso: '2026-09-01T08:30:00Z' })
  assert.deepEqual(card.viewer, { name: 'Minh', href: 'https://www.youtube.com/@minh' })
  assert.deepEqual(card.video, { title: 'Biet Tri Ky - dizi cover', href: 'https://www.youtube.com/watch?v=v2' })
  assert.equal(card.reply?.label, 'Ban nhap cua Reply Writer')
  assert.equal(card.reply?.value, 'The sheet is on camamtieudao.com.')
  assert.equal(card.reply?.argument, 'text')
  assert.deepEqual(card.reply?.links, [])
  // The channel a reply is posted as is never drawn: the server refuses one for another channel.
  assert.deepEqual(card.rest, [])
})

test('with no agent named the reply keeps its label; a posted one says so and links to itself', () => {
  const unnamed = commentCard(parseApprovalContext(server), tv, 'en', { now: NOW })
  assert.equal(unnamed?.reply?.label, 'Tra loi se dang')
  const posted = commentCard(parseApprovalContext({ fields: [
    ...server.fields.slice(0, 5),
    { key: 'reply', value: 'Thanks', href: 'https://www.youtube.com/watch?v=v2&lc=c3.r1', kind: 'QUOTE' },
  ] }), tv, 'en', { author: 'Reply Writer', now: NOW })
  assert.equal(posted?.reply?.label, 'Da dang')
  assert.deepEqual(posted?.reply?.links, [{ key: 'reply', label: 'Xem tra loi', href: 'https://www.youtube.com/watch?v=v2&lc=c3.r1' }])
})

test('the date is the reader\'s own: Vietnamese words, in the reader\'s time zone', () => {
  const card = commentCard(parseApprovalContext(server), tv, 'vi', { now: NOW, timeZone: 'Asia/Ho_Chi_Minh' })
  assert.equal(card?.written?.ago, '2 th\u00e1ng tr\u01b0\u1edbc')
  assert.match(card?.written?.at ?? '', /15:30/)
  assert.match(card?.written?.at ?? '', /2026/)
})

test('a dashboard\'s comment has no reply: the title and caption line alone; a viewer without a channel is not a link', () => {
  const card = commentCard(parseApprovalContext({ fields: [
    { key: 'viewer', value: '@lan', kind: 'TEXT' },
    { key: 'comment', value: 'More tutorials', href: 'https://www.youtube.com/watch?v=v9&lc=c1', kind: 'QUOTE' },
  ] }), tv, 'en', { now: NOW })
  assert.deepEqual(card?.viewer, { name: '@lan', href: null })
  assert.equal(card?.video, null)
  assert.equal(card?.written, null)
  assert.equal(card?.reply, null)
})

test('a video known only by its id is "Open video"; a date that is not one and an unknown key stay rows of their own', () => {
  const card = commentCard(parseApprovalContext({ fields: [
    { key: 'video', value: '8QFVNhzw5GE', href: 'https://www.youtube.com/watch?v=8QFVNhzw5GE', kind: 'LINK' },
    { key: 'comment', value: 'Hi', kind: 'QUOTE' },
    { key: 'commentedAt', value: 'long ago', kind: 'TEXT' },
    { key: 'playlist', value: 'Lessons', kind: 'TEXT' },
  ] }), tv, 'en', { now: NOW })
  assert.deepEqual(card?.video, { title: 'Open video', href: 'https://www.youtube.com/watch?v=8QFVNhzw5GE' })
  assert.deepEqual(card?.comment, { text: 'Hi', href: null })
  assert.deepEqual(card?.rest.map(r => [r.key, r.value]), [['commentedAt', 'long ago'], ['playlist', 'Lessons']])
})

test('only a context quoting a comment is a comment\'s: a card made from arguments, or a comment that is not a quote, is not', () => {
  assert.equal(isCommentContext(parseApprovalContext(server)), true)
  assert.equal(isCommentContext(parseApprovalContext({ fields: [
    { key: 'detailsUnavailable', value: 'Details unavailable', kind: 'TEXT' },
    { key: 'reply', value: 'Thanks', kind: 'QUOTE' },
  ] })), false)
  assert.equal(isCommentContext(parseApprovalContext({ fields: [{ key: 'comment', value: 'Hi', kind: 'TEXT' }] })), false)
  assert.equal(isCommentContext(null), false)
  assert.equal(commentCard(null, tv, 'en'), null)
})

test('a reply\'s card names the comment that opened its thread, and only a reply\'s does', () => {
  // 2026-11-10, the maintainer: "which comment belong to which conversation".
  const threaded = commentCard(parseApprovalContext({ fields: [
    ...server.fields.slice(0, 2),
    { key: 'threadAuthor', value: '@vanthien', href: 'https://www.youtube.com/@vanthien', kind: 'LINK' },
    { key: 'threadStart', value: ' How much is that flute? ', href: 'https://www.youtube.com/watch?v=v2&lc=t1', kind: 'QUOTE' },
    ...server.fields.slice(2),
  ] }), tv, 'en', { now: NOW })
  assert.deepEqual(threaded?.thread, { author: '@vanthien', text: 'How much is that flute?', href: 'https://www.youtube.com/watch?v=v2&lc=t1' })
  assert.deepEqual(threaded?.rest, [], 'not drawn again as rows')
  assert.equal(commentCard(parseApprovalContext(server), tv, 'en', { now: NOW })?.thread, null)
})
