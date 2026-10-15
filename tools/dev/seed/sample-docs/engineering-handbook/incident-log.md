# Incident log 2026

Newest first. Every incident, whether or not anyone outside noticed.

## 2026-08-26 - a release published an unverified image

The build pushed the container image before the checks ran, so the check could report a bad image
and never prevent one. On one release the check failed against an image already tagged as the
current one. It turned out to be a race in the assertion rather than a bad image, but nothing
about the ordering would have told us the difference in time.

Fixed by building to the local daemon only, asserting against that copy, and pushing those same
bytes afterwards. The published image is now by construction the one that was verified.

## 2026-08-19 - telemetry had never been sent

Both halves of the feature were correct and had never been connected: the destination address
was not set anywhere, so the shipper returned early on a blank-address check every time. Nothing
had ever been sent from any installation, and no error was logged because there was no error.

## 2026-07-14 - an index rebuilt itself from scratch

A column rename left the old column in place. It was declared as required with no default, so
every insert into that table failed from then on. The failure was silent at the point it
mattered; the symptom was an index that appeared to be empty a day later.
