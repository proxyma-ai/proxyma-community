import {
  Archive, ArrowDownUp, BadgeCheck, Bot, Brain, Cpu, DollarSign, FileText, Globe, Info, KeyRound, ListChecks,
  MessageSquare, History, Plug, Puzzle, RefreshCw, RotateCcw, ScanText, Search, ShieldCheck, User,
  type LucideIcon,
} from 'lucide-react'
import type { TabKey } from '../components/SettingsModal'

/**
 * The glyph each Settings tab goes by in the Settings rail. It was shared with the header menus'
 * shortcut rows until those went on 2026-09-30 (F26 of that day's review).
 */
export const TAB_ICONS: Record<TabKey, LucideIcon> = {
  'Account':                 User,
  // Info, and it now stands for the whole of what used to be three tabs - the version readout,
  // the links out to the manuals, and the legal documents and privacy switches.
  'About':                   Info,
  'Connections':             Plug,
  'Skills':                  FileText,
  // What the agent keeps across conversations - see MemoryTab.
  'Memory':                  Brain,
  // The same mark the "/" dropdown gives a workflow, and the same one the step table uses - one
  // icon for one concept, wherever it appears.
  'Workflows':               ListChecks,
  'API Tokens':              KeyRound,
  // The assistant the user already has, reaching in through MCP - see AssistantsTab.
  'AI Assistants':           Bot,
  'AI Providers':            Cpu,
  'Agents':                  MessageSquare,
  // ShieldCheck rather than Shield, which is Privacy's. The two are adjacent in meaning and not
  // the same thing: one is what we do with your data, the other is what the product refuses to
  // pass along.
  'Guardrails':              ShieldCheck,
  'Conversion':              ScanText,
  // Search, not Boxes: the pair this merges (Embedding + Search Engine) is one pipeline, and
  // what a reader is looking for when they come here is how answers are found.
  'Search':                  Search,
  'Network':                 Globe,
  'Data Retention':          Archive,
  'License':                 BadgeCheck,
  // A DOLLAR SIGN, not a receipt. This tab is about what you are spending and what it costs
  // per million tokens; a receipt is the record of a purchase, which is not what is in here and
  // is also what the License tab would be if anything were. The currency mark is the one glyph
  // that reads as money at 16px without a caption.
  'Usage & Cost':            DollarSign,
  'Logs':                    History,
  'Import & Export':         ArrowDownUp,
  // The add-ons a Desktop or Home Server built with them can activate, each with its own key - a
  // piece that fits into the product. Only that build lists the tab (index.addons.tsx in ee/).
  'Add-ons':                 Puzzle,
  // The same arrow the desktop app's own restart uses. Not Download: this tab is about the
  // version you run, and downloading is one step in the middle of it.
  'Updates':                 RefreshCw,
  'Reset':                   RotateCcw,
}
