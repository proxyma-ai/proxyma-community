/**
 * Proxyma UI kit - the shared component surface, for all four frontends.
 *
 * GENERATED DIRECTORY in each consumer. Edit libs/ui-kit/react/ and run
 * `node libs/ui-kit/sync.mjs`. Edits to the copy are silently reverted by the next sync.
 *
 *     import { Button, Card, Badge } from '@/components/ui/kit'
 *
 * Named exports only. Grouped by LEVEL, since the 2026-10 rebuild: the foundations (type, layout,
 * helpers), the primitives every screen is made of, the composites built from them, and the
 * patterns - whole windows, screens and sections. A screen is built from the highest level that
 * fits; markup written by hand where a kit part exists is the drift this kit exists to end.
 */

// -- Foundations: type scale, layout, class helpers ---------------------------------------------
export { cn } from './cn'
export { TEXT, TEXT_LINK_BASE, textLink, type TextRole, type TextLinkTone } from './typeScale'
export { PAGE_COLUMN, PAGE_GUTTER, PAGE_INSET_LEFT, PAGE_INSET_RIGHT, PROSE_MEASURE } from './layout'
export { pageTitle, TITLE_SEPARATOR } from './pageTitle'
export { iconButton, ICON_BUTTON_BASE, type IconButtonSize, type IconButtonTone } from './iconButton'
export { TAP_TEXT, TAP_TEXT_FROM_SM } from './tap'
export { FIELD_BASE, FIELD_NO_NATIVE_PASSWORD_UI, fieldBorder, fieldWords, type FieldWords } from './field'
export { useModalLayer } from './useModalLayer'
export { moveIndex, typeaheadIndex, createTypeahead, isNavKey, isTypeaheadKey, PAGE_STEP, TYPEAHEAD_MS, type NavKey, type Typeahead } from './listNav'
export { useListbox, type Listbox, type ListboxOptions } from './useListbox'
export { useMenuFocus, menuItems, focusTrigger, type MenuFocusOptions } from './useMenuFocus'
export { useFloating, place, type Place } from './floating'
export {
  CHART_INK, CHART_SERIES, CHART_SERIES_OTHER, CHART_TICK, CHART_MARGIN, CHART_X_AXIS, CHART_GRID, CHART_TOOLTIP, chartYAxis,
} from './chartTheme'
export type { ModalLayerOptions } from './useModalLayer'
export { useWheelScrollX } from './useWheelScrollX'
export { useIdleRotation, IDLE_DEG_PER_S } from './useIdleRotation'
export { useHideOnScroll, useHideOnSwipe } from './useAutoHide'
export { installAppViewport } from './appViewport'
export {
  MOTION_MS,
  MOTION_READY_ATTR,
  applyAppearanceSmoothly,
  exitingProps,
  installMotion,
  motionReady,
  prefersReducedMotion,
  useExitGhost,
  useFadeOnChange,
  usePresence,
  withoutTransitions,
} from './motion'
export { FadeSwap } from './FadeSwap'
export { Presence } from './Presence'
export { applyClick, headerState, toggleAllOnPage, type HeaderState } from './selection'
export {
  APPEARANCE_PREFERENCES,
  SYSTEM_DARK_QUERY,
  applyAppearance,
  parseAppearance,
  resolveAppearance,
} from './appearance'
export type { AppearanceMode, AppearancePreference, AppearanceTarget, ResolvedAppearance } from './appearance'
export { themeColor } from './appearance'
export { appearanceInitScript, THEME_COLOR } from './appearanceScript'
export { createAppearanceStore, type AppearanceStore } from './appearanceStore'

// -- L1 primitives: text ------------------------------------------------------------------------
export { Title, PageTitle, PageHero, SectionHeading, FieldLabel, Caption, Eyebrow, InlineCode, Readout, Paragraph } from './Text'
export type { CaptionTone, ReadoutTone } from './Text'
export { MetaList } from './MetaList'
export { ExternalLink } from './ExternalLink'
export { safeExternalHref, httpUrl, mailtoHref, safeLocalPath } from './safeUrl'

// -- L1 primitives: controls --------------------------------------------------------------------
export { Button, ButtonLink, ExternalMark, type ButtonProps, type ButtonLinkProps } from './Button'
export { buttonClass, type ButtonVariant, type ButtonSize } from './buttonClass'
export { Input, Textarea, FieldHint, FieldNote } from './Input'
export { PasswordInput, type PasswordInputLabels } from './PasswordInput'
export { SearchInput } from './SearchInput'
export { SecretInput, type SecretInputLabels } from './SecretInput'
export { Select, type SelectOption } from './Select'
export { Combobox, type ComboboxStatus, type ComboboxLabels } from './Combobox'
export { DateTimePicker } from './DateTimePicker'
export type { DateTimePickerProps, DateTimePickerLabels } from './DateTimePicker'
export { Checkbox } from './Checkbox'
export { SelectBox } from './SelectBox'
export { RadioGroup, ChoiceMark, type RadioOption } from './RadioGroup'
export { SegmentedControl, type SegmentedOption } from './SegmentedControl'
export { ToggleSwitch } from './ToggleSwitch'
export { Slider, Divider } from './Slider'
export { CopyButton, copyText, COPIED_MS } from './CopyButton'
export { CopyField, OneTimeSecret } from './CopyField'
export { useShake } from './useShake'
export { IconButton, IconToggleButton, IconCountButton, MenuTrigger, type IconButtonProps } from './IconButtons'

// -- L1 primitives: marks, meters, small parts --------------------------------------------------
export { Badge, TierBadge, PlanBadge } from './Badge'
export { Chip, chipClass, type ChipVariant } from './Chip'
export { RefreshIcon, Spinner } from './Spinner'
export { Skeleton, ListRowSkeleton, SwitchRowSkeleton, FieldSkeleton, RadioGroupSkeleton } from './Skeleton'
export { StateMark, StatusMark, StatusDot, WorkingBadge, type WorkState, type HealthState } from './StateMark'
export { Avatar, AvatarStack, initials, type AvatarSize } from './Avatar'
export { Stepper, type Step, type StepState } from './Stepper'
export { ProgressBar, ProgressRow, Meter, BarList, StackedBar, LegendSwatch, UsageRing, meterTier, METER_TEXT, type MeterTone, type MeterTier, type BarListRow } from './Meter'
export { ColumnChart } from './ColumnChart'
export { axisTicks, axisGroups, AXIS_STEPS, type AxisLabel, type AxisOptions } from './chartAxis'
export { pageWindow, LIST_PAGE_SIZE, pageCount, clampPage, pageSlice } from './pageWindow'
export { usePageFor, usePagedList } from './usePaging'
export { toolFold, type ToolFold } from './toolFold'
export { workingMotion, WORKING_BLINK, WORKING_WAVE, type WorkingShape } from './working'
export { Tooltip, PointerTooltip } from './Tooltip'
export { tooltipPlace, pointerAnchor, controlAnchor, type TipAnchor, type TipPlace } from './tooltipPlace'
export { Collapse, Disclosure, DisclosureChevron, DisclosureButton } from './Disclosure'
export { HScroll } from './HScroll'
export type { HScrollProps } from './HScroll'

// -- L1 primitives: brand -----------------------------------------------------------------------
export { Logo, type LogoProps } from './Logo'
export { ProxymaIcon } from './ProxymaIcon'
export { BrandMark, MARK_PATHS, isMarkId, markIcon } from './BrandMark'
export type { GlyphComponent, GlyphProps, MarkId } from './BrandMark'
export { ProxymaBackground } from './ProxymaBackground'

// -- L2 composites: surfaces and states ---------------------------------------------------------
export { Card, CardHeader, CardBody, ChartCard, cardClass, type CardState } from './Card'
export { StatTile, StatGrid, FilterTiles, type FilterTile } from './StatTile'
export { Callout, Banner, type CalloutTone } from './Callout'
export { EmptyState, LoadingState, ErrorState, InlineNotice, LoadMoreRow, Pager, ListPager, type NoticeTone, type PagerProps } from './States'
export { ErrorBoundary, type ErrorBoundaryFallbackProps, type ErrorBoundaryProps } from './ErrorBoundary'
export { isChunkLoadError } from './errorRecovery'
export { CodeBlock, Frame, FRAME_CLASS } from './CodeBlock'
export { KeyValueList, KeyValue, KeyValueGroup, EmptyValue } from './KeyValueList'
export { PROSE_CHAT, ProseTable, SheetTable } from './Prose'
export { AudioPlayer, Lightbox } from './Media'
export { RuleChecklist } from './Checklist'
export { Toast, TOAST_DOCK, TOAST_LAYER, TOAST_STACK, type ToastTone } from './Toast'

// -- L2 composites: forms, lists, tables --------------------------------------------------------
export { Field, FormColumns, FormStack, StaticValue, type FieldAria } from './FormField'
export { List, ListRow, SwitchRow, RowActions, HoverActions, InlineConfirm, LinkRow, BulkActionBar, SELECTED_ROW } from './List'
export { FilterChips, FilterBar, Toolbar, type FilterOption } from './FilterBar'
export { PickBox, SelectableListHeader, ListEditor, StepList, StepItem, StepCard } from './Collections'
export {
  TABLE_CLASS, TABLE_FLUSH, TableCard, TR, TR_INTERACTIVE, THEAD_TR, TD, TD_COMPACT, Th, SortTh, TableSkeletonRows, TableEmptyRow, TableFooter,
  IdentityCell, type SortDir, type SortState, type SkeletonColumn,
} from './DataTable'
export { useFileDrop, useFilePicker, DropArea, DropOverlay, FilePickerButton, type FileDropHandlers } from './DropArea'

// -- L2 composites: overlays, menus -------------------------------------------------------------
export {
  DialogShell, DialogHeader, DialogTitle, DialogDescription, DialogBody, DialogFooter, DialogNotice, ConfirmDialog,
  dialogPanel, DIALOG_WIDTH, DIALOG_OVERLAY, DIALOG_SHEET_OVERLAY, MODAL_SCRIM, MODAL_SCRIM_DARK,
  type DialogShellProps, type DialogSize, type DiscardLabels, type ConfirmDialogProps,
} from './Dialog'
export { Modal } from './Modal'
export { Popover, MenuRow, MenuEmpty, ActionMenu, POPOVER_PANEL, FLOATING_Z, type Placement, type PopoverProps, type ActionMenuItem, type ActionMenuGroups } from './Popover'
export { MenuSurface, MenuGroup, MenuIdentity, menuRow, MENU_PANEL } from './menu'
export type { MenuSurfaceProps, MenuTone } from './menu'
export { SubmenuRow } from './SubmenuRow'
export type { SubmenuOption, SubmenuOptionRender, SubmenuRowProps } from './SubmenuRow'
export { SubNavTabs } from './SubNavTabs'
export type { SubNavTab } from './SubNavTabs'

// -- L2 composites: chat and sources (shared with the site's product scenes) --------------------
export {
  THREAD_COLUMN, ChatMessage, Elapsed, ActivityLine, ActivityFeed, ToolCallRow, ToolCallGroup, SubAgentPanel, PromptCard, PromptSection, ChoiceRow, NoticeCard,
  ComposerShell, ChatStartHero, ChatStartLinks, CitationsFold, CitationList, CitationRow, ChatMessageSkeleton,
} from './Chat'
export { SOURCE_LIST_COLUMNS, SOURCE_LIST_BOX, SourceListHeader, ListGroupHeading, SourceRow, SourceRowSkeleton, ItemRow, ItemRowSkeleton } from './Sources'

// -- L3 patterns: chrome, screens, windows, sections --------------------------------------------
export { AppHeader, BRAND_LINK, headerNavLink } from './AppHeader'
export type { AppHeaderProps } from './AppHeader'
export { AutoHideHeader } from './AutoHideHeader'
export type { AutoHideHeaderProps } from './AutoHideHeader'
export { LanguageMenu } from './LanguageMenu'
export type { LanguageMenuProps } from './LanguageMenu'
export { IconMenu } from './IconMenu'
export type { IconMenuProps } from './IconMenu'
export { AppearanceMenu, AppearanceIcon } from './AppearanceMenu'
export { AppearanceMenuRow, LanguageMenuRow, SidebarChoiceRow, SidebarAppearanceRow, SidebarLanguageRow } from './PreferenceRows'
export type { AppearanceMenuProps } from './AppearanceMenu'
export { MobileMenu } from './MobileMenu'
export type { MobileMenuProps } from './MobileMenu'
export { UserMenu, initialsOf } from './UserMenu'
export type { UserMenuProps } from './UserMenu'
export { GlassFrame, AppSidebar, SidebarSection, sidebarRow } from './GlassFrame'
export { WindowControls } from './WindowControls'
export { Page, PageHeader, BackLink, AuthLayout, FormAlert, OrDivider, BootScreen, StatusScreen } from './Screens'
export { SplashScreen } from './SplashScreen'
export {
  SettingsLayout, SettingsRail, SettingsSections, SettingsSection, SaveBar, SettingsWindow, SETTINGS_PANEL, SETTINGS_OVERLAY,
  type RailGroup, type RailItem,
} from './Settings'
export {
  PageSection, DisplayHeading, SplitSection, FeatureRow, RuledColumns, IncludedList, PlanCard, PlanBand, LinkCard,
  LinkColumn, FOOTER_LINK, Timeline, DownloadRow, CompareTable,
} from './Marketing'
export { FaqItem, SceneFrame, SceneControl, useInViewOnce, useScenePlayer, type SceneState } from './MarketingClient'
