import { useId } from 'react'
import { Crosshair, Pause, Play, RotateCcw, Tag, Tags } from 'lucide-react'
import {
  Caption, Card, Divider, Eyebrow, IconButton, IconToggleButton, Presence, SegmentedControl, Slider, Tooltip,
} from '@/components/ui/kit'
import { useTranslation } from '@/i18n'
import type { GraphView } from './GraphCanvas'

/**
 * The controls over the graph: what to show, how far apart, and how to get back.
 *
 * <p>A FLOATING BAR ON THE CANVAS, not a panel beside it. The canvas is the content and it wants
 * every pixel; a fixed rail would take a fifth of the width to hold six controls that are used
 * once each and then not again.
 *
 * <p>Everything here changes what is DRAWN or where the camera is. Nothing here changes the data,
 * which is why none of it is persisted: reopening a source should show that source, not the last
 * reader's viewing preferences.
 */

interface Props {
  view: GraphView
  onChange: (view: GraphView) => void
  onReset: () => void
  onCenter: () => void
  /** Depth only means something with a node selected - it is measured FROM that node. */
  hasSelection: boolean
}

const DEPTHS = ['0', '1', '2', '3'] as const
type Depth = typeof DEPTHS[number]

export function GraphViewOptions({ view, onChange, onReset, onCenter, hasSelection }: Props) {
  const { t } = useTranslation()
  const set = (patch: Partial<GraphView>) => onChange({ ...view, ...patch })
  const ids = { stars: useId(), nodes: useId(), groups: useId() }

  return (
    <div className="absolute left-3 top-3 flex flex-col gap-2 pointer-events-none">
      {/* pointer-events-auto on the GROUPS rather than the container, so the gaps between them
          still pass a drag through to the canvas underneath. A toolbar that swallows a rotation
          started a few pixels to its left is the kind of thing that reads as the graph being
          stuck. Both groups are the kit's solid card - the one for a card over a picture. */}
      <Card variant="solid" padding="none" className="pointer-events-auto flex items-center gap-1 p-1">
        <IconToggleButton
          icon={view.autoRotate ? Pause : Play}
          pressed={view.autoRotate}
          onToggle={() => set({ autoRotate: !view.autoRotate })}
          label={view.autoRotate ? t('Stop rotating') : t('Rotate automatically')}
          side="bottom"
        />
        <IconToggleButton
          icon={view.showLabels ? Tags : Tag}
          pressed={view.showLabels}
          onToggle={() => set({ showLabels: !view.showLabels })}
          label={view.showLabels ? t('Hide labels') : t('Show labels')}
          side="bottom"
        />
        {/* A rule between the two pairs: the two toggles, then the two one-off camera moves. */}
        <Divider vertical className="h-5 self-center mx-0.5" />
        {/* The two one-off camera moves: plain icon buttons, no pressed state. */}
        <IconButton icon={Crosshair} onClick={onCenter} label={t('Center the view')} side="bottom" />
        <IconButton icon={RotateCcw} onClick={onReset} label={t('Reset the view')} side="bottom" />
      </Card>

      <Card variant="solid" padding="sm" className="pointer-events-auto w-48 space-y-3">
        {/* THE ONLY CONTROL HERE THAT CHANGES WHAT IS FETCHED. Everything else in this panel
            re-draws what has already arrived; this one decides how much of the source is asked
            for, most connected first. It is a slider rather than a set of presets because the
            right number depends on the graph in front of the reader - a source of 300 entities
            wants all of them and one of 12,000 wants the head of the list. */}
        <Tooltip label={t('How many of the most connected entities to draw')} side="bottom" fullWidth>
          <Slider
            id={ids.stars}
            label={t('Stars')}
            min={100}
            max={1500}
            step={100}
            value={view.maxNodes}
            onChange={maxNodes => set({ maxNodes })}
            className="w-full"
          />
        </Tooltip>

        {/* DEPTH IS DISABLED WITHOUT A SELECTION, and says so rather than disappearing. It counts
            hops FROM the selected node, so with nothing selected there is no origin to count from
            and every option would mean the same thing. Hiding it instead would make the control
            appear and vanish as the reader clicks around, which reads as a glitch. */}
        <div>
          <Eyebrow
            action={(
              <SegmentedControl<Depth>
                ariaLabel={t('Depth')}
                options={DEPTHS.map(d => ({ value: d, label: d === '0' ? t('All') : d }))}
                value={String(view.maxDepth) as Depth}
                onChange={d => set({ maxDepth: Number(d) })}
                disabled={!hasSelection}
              />
            )}
          >
            {t('Depth')}
          </Eyebrow>
          <Presence show={!hasSelection}>
            <Caption className="mt-1">{t('Select a node first')}</Caption>
          </Presence>
        </div>

        {/* TWO SPREADS, because they answer different questions. `nodeSpread` is the natural length
            of an edge - how far apart two RELATED things sit - and `groupSpread` is the distance
            between the communities the layout found. Turning one up gives a looser constellation;
            turning the other up pushes the galaxies apart without stretching anything inside them.

            0.6x to 2.2x, in steps. A range rather than buttons, because this is a continuum and a
            reader adjusting it is looking at the result rather than at the control. It rebuilds the
            layout on change, which is why the step is coarse: every value is a fresh simulation,
            and a smooth 100-step slider would start one per pixel of travel. */}
        <Slider
          id={ids.nodes}
          label={t('Star spacing')}
          min={0.6}
          max={2.2}
          step={0.2}
          value={view.spread.nodeSpread}
          readout={`${view.spread.nodeSpread.toFixed(1)}x`}
          onChange={nodeSpread => set({ spread: { ...view.spread, nodeSpread } })}
        />
        <Slider
          id={ids.groups}
          label={t('Galaxy spacing')}
          min={0.6}
          max={2.2}
          step={0.2}
          value={view.spread.groupSpread}
          readout={`${view.spread.groupSpread.toFixed(1)}x`}
          onChange={groupSpread => set({ spread: { ...view.spread, groupSpread } })}
        />
      </Card>
    </div>
  )
}
