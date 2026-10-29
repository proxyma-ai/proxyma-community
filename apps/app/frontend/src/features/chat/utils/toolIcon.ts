import {
  MessageCircleQuestion, AlarmClock, Hourglass, Layers, SlidersHorizontal, Boxes, ShieldCheck, Scale, Gavel, FileOutput,
  AppWindow, ArrowRightLeft, Bot, BotMessageSquare, MessageSquareReply, Brain, BrainCog, Database, Download, FileCheck, FilePen,
  FilePlus, FileSearch, FileText, FileType, FileX, Files, FolderOpen, FolderTree, Save, GitBranch, GitCommitHorizontal,
  GitCompare, GitMerge, GitPullRequest, GitPullRequestArrow, GitPullRequestDraft, Globe, Info,
  History, List, MessageSquare, Paperclip, MessageSquarePlus, MessageSquareX, Pencil, Plug, Plus, Search, Tag,
  Tags, Terminal, Timer, Trash2, UserCheck, Users, Waypoints, Wrench, Shapes, type LucideIcon,
} from 'lucide-react'
import { markIcon, type GlyphComponent } from '@/components/ui/kit'

// The glyph on a tool-call row, so a reader scanning a long turn can tell a web search from a
// Jira write without reading either line.
//
// SAME THREE LAYERS AS toolActivity.ts, and deliberately so - that file decides what a row SAYS,
// this one what it SHOWS, and a maintainer who has learned one has learned the other:
//
//   1. TOOL_ICONS - the tool by name, where its verb picks the wrong picture. `webSearch` is a
//      "search" that goes to the public internet, and is drawn as the web rather than as the
//      search of the reader's own sources.
//   2. VERB_ICONS - keyed on the FIRST camelCase word, exactly like toolActivity's CONJUGATIONS.
//      This is the layer that matters for maintenance: a tool added to the backend tomorrow gets
//      a sensible icon with no entry here, and there are 70 of them today.
//   3. Wrench - a tool we have no opinion about is still visibly a tool.
//
// THE GLYPH IS ALSO THE ROW'S STATE, since 2026-10-03 (maintainer's request): a row no longer
// carries a spinner, a green check or a red cross in front of this. The glyph is in caption
// ink while the call runs - as the brain does while the model thinks - and settles in the brand ink
// when it worked, red when it failed (the kit StateMark, which the kit ToolCallRow draws). So it is
// no longer the quiet second mark of a row; it is the only one.
//
// NOTHING HERE IS TRANSLATED and nothing here should be. An icon is not a string; the row's words
// come from toolActivity and go through `t` there.
//
// WHERE A CONCEPT ALREADY HAS AN ICON IN THIS APP, IT KEEPS IT. Skills are FileText, connectors
// are Plug, the web is Globe and search is Search - all four are the settings rail's own marks
// (TAB_ICONS in SettingsModal.tsx). One icon per concept wherever it appears; a tool row inventing
// a second mark for "skill" would be the reader's problem, not a designer's preference.
//
// THE VERB, NOT THE SYSTEM - with one exception, decided 2026-09-30 when the source marks came in
// from the kit. A row's glyph shows what its WORDS do not. "Searching Jira issues", "Commented on
// PROX-12", "Merged pull request #7" already name the system, so their glyph keeps the act - a
// comment, a merge, a transition - which is what tells two adjacent Jira rows apart. A REST
// PASSTHROUGH row is the opposite case: restCallActivity words it from the method and path
// ("Listing pull requests - web") and never names the system, so its glyph is the SYSTEM's mark -
// the one thing the words leave out. The generic Terminal it replaced said "raw call", which the
// detail dialog still shows in full.

const TOOL_ICONS: Record<string, LucideIcon> = {
  // The everyday handful. These are most of what any turn does, so they are the ones worth
  // arguing about - and every one of them would take the wrong icon from its verb.
  // Search, since 2026-10-03 (maintainer's request). It was Library - "the corpus, not the
  // internet" - which told it apart from webSearch and told nobody it was a search.
  searchIndexedSources: Search,
  webSearch:            Globe,        // Network's own mark, and the web is what it means
  webFetch:             FileText,     // "Reading page" - a page, arrived at and read
  renderWebFetch:       AppWindow,    // "Opening page in a browser" - the browser IS the point
  searchKnowledgeGraph: Waypoints,    // nodes joined by edges; the same mark the graph view uses
  findGraphPath: Waypoints,
  findGraphPeople: Users,
  findGraphTimeline: History,
  findGraphThemes: Shapes,
  recall:               Brain,
  remember:             BrainCog,     // writing to memory, not reading from it
  readSkill:            FileText,     // Skills' own icon in the settings rail
  askUser:              MessageCircleQuestion, // the assistant asking you
  // The tree of agents (since 2026-10-28): every agent can deploy agents of its own.
  deployAgent:          Bot,          // a panel of its own in the thread, with this robot
  tellAgent:            BotMessageSquare, // a message to another agent of the tree
  awaitAgents:          Hourglass,    // waiting on the reports of agents it deployed
  controlAgent:         SlidersHorizontal, // pausing, resuming, stopping or listing them
  alarm:                AlarmClock,
  background:           Layers,       // a tool left running behind the agent's back
  // Since 2026-10-29: one agent per item of a list, a report read whole, a report checked, a panel
  // argued before, its decision recorded, and a file handed to another agent.
  deployAgents:         Boxes,
  readAgentReport:      FileText,
  verifyReport:         ShieldCheck,
  deliberate:           Scale,
  recordDecision:       Gavel,
  handFile:             FileOutput,
  // Tools that no longer exist, kept so a conversation stored before then still reads.
  delegate:             Bot,
  steerSubAgent:        BotMessageSquare,
  messageMainAgent:     MessageSquareReply,
  read:                 FileText,
  grep:                 FileSearch,   // searching INSIDE files, not for them
  convertDocument:      FileType,
  runPython:            FilePen,      // making or changing a document
  openLocalFile:        FolderOpen,
  saveLocalFile:        Save,

  getPlatformInfo:      Info,         // not a document, despite the get*
  getConnectorStatus:   Plug,         // Connectors' own icon
  getJiraCurrentUser:   UserCheck,

  // Comments. add*/update*/delete* would give Plus/Pencil/Trash, which describe the edit and hide
  // that the thing edited is a conversation.
  addJiraComment:        MessageSquarePlus,
  addConfluenceComment:  MessageSquarePlus,
  addBitbucketPrComment: MessageSquarePlus,
  updateJiraComment:     MessageSquare,
  deleteJiraComment:     MessageSquareX,

  // Work tracking rather than editing.
  transitionJiraIssue:  ArrowRightLeft,
  logJiraWork:          Timer,
  updateJiraEstimate:   Timer,

  // Git. The verb layer would call every one of these a create/get/list; what a reader needs is
  // which git object it touched.
  createBitbucketPr:      GitPullRequestArrow,
  getBitbucketPr:         GitPullRequest,
  getBitbucketCloudPr:    GitPullRequest,
  readBitbucketCloudFile: FileText,
  reviewBitbucketPr:      GitPullRequestDraft,
  mergeBitbucketPr:       GitMerge,
  getBitbucketPrDiff:     GitCompare,
  getBitbucketCommitDiff: GitCompare,
  getBitbucketCommits:    GitCommitHorizontal,
  listBitbucketBranches:  GitBranch,
  createBitbucketTag:     Tag,
  createBitbucketBranch:  GitBranch,
  deleteBitbucketBranch:  Trash2,
  attachJiraFile:         Paperclip,
  searchConversations:    History,
  attachConfluenceFile:   Paperclip,
  updateConfluenceLabels: Tags,
  moveConfluencePage:     FolderTree,
  updateConfluenceComment: Pencil,
  deleteConfluenceComment: MessageSquareX,
  listBitbucketTags:      Tags,
  browseBitbucketPath:    FolderTree,
  downloadBitbucketRepo:  Download,

  // Confluence pages are documents; the page verbs should look like document verbs.
  createConfluencePage: FilePlus,
  updateConfluencePage: FilePen,
  deleteConfluencePage: FileX,
  getConfluencePage:    FileText,

  readSvnFile:  FileText,
  listSvnFiles: Files,
}

// The escape hatches - the REST passthroughs - drawn as their system (see above). A new
// passthrough with no entry here falls to the `call` verb, which is still Terminal.
const SYSTEM_ICONS: Record<string, GlyphComponent> = {
  callJiraApi:       markIcon('jira'),
  callConfluenceApi: markIcon('confluence'),
  callBitbucketApi:  markIcon('bitbucket'),
}

// Keyed by the first word of a camelCase tool name, mirroring toolActivity's CONJUGATIONS so the
// two files stay legible side by side. `register`/`remove` are this product's words for "index
// this from now on" and "stop indexing it", which is a database act rather than a file one.
const VERB_ICONS: Record<string, LucideIcon> = {
  get:      FileText,
  list:     List,
  search:   Search,
  create:   Plus,
  update:   Pencil,
  delete:   Trash2,
  add:      Plus,
  remove:   Trash2,
  register: Database,
  read:     FileText,
  merge:    GitMerge,
  review:   FileCheck,
  log:      Timer,
  render:   AppWindow,
  convert:  FileType,
  // Not in CONJUGATIONS - those tools fall through to derive()'s humanized fallback for their
  // wording. An icon is cheaper to be right about than a verb, so they get one anyway.
  browse:   FolderTree,
  call:     Terminal,
  download: Download,
}

/** The leading word of a camelCase name: searchJiraIssues -> "search". */
function leadingVerb(name: string): string {
  const match = /^[a-z]+/.exec(name)
  return match ? match[0] : ''
}

/**
 * The icon for one tool call. Never null - an unknown tool is still visibly a tool, and a row
 * that sometimes has a glyph and sometimes does not reads as a rendering bug rather than as a
 * distinction anyone meant.
 */
export function toolIcon(name: string): GlyphComponent {
  return SYSTEM_ICONS[name] ?? TOOL_ICONS[name] ?? VERB_ICONS[leadingVerb(name)] ?? Wrench
}
