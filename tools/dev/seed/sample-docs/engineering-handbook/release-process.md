# Release process

## Cutting a release

1. Branch `release/vX.Y.Z` from `dev`.
2. Bump the version in the one place it is written - the parent POM. Everything downstream reads
   it from there, including the version shown in the UI and the update check.
3. Write the release notes. Short, in the reader's language, and only things that change what
   they can see or do. An internal refactor is not a release note.
4. Open the promotion pull request to `main`. Merging it deploys.
5. Tag `vX.Y.Z`. The tag is what starts the release workflows.

## When a release does not appear

A release that stays in draft means the finalize job never ran. The usual cause is a build budget
that ran out mid-run, which fails no step and simply stops scheduling new ones.

Check the tag before re-running anything: a tag runs the workflow file **as it existed at that
tag**, so fixing the workflow afterwards does nothing for a tag already pushed.

## Postmortems

Every incident gets an entry in the incident log, whether or not a customer noticed. The log is
the input to this document; when a release goes wrong twice the same way, the fix belongs here
rather than in somebody's memory.
