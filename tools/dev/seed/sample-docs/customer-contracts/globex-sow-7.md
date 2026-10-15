# Globex - statement of work 7

**Fictional. Sample data for local development.**

Issued under the master services agreement dated 2026-02-02. Where this document and that one
disagree, that one wins.

## Scope

Migrate the reporting pipeline off the retiring scheduler and onto the shared one, keeping the
existing report contents byte-identical through the switch.

## Milestones

| # | Deliverable | Due |
|---|---|---|
| 1 | Inventory of every scheduled job, with owners | 2026-09-15 |
| 2 | Shadow run: both schedulers, outputs compared daily | 2026-10-15 |
| 3 | Cutover, old scheduler decommissioned | 2026-11-30 |

## Acceptance

A milestone is accepted when the outputs match for five consecutive business days. Silence is not
acceptance; each milestone is signed off in writing.
