# Engineering onboarding

Everything a new engineer needs in their first week, in the order they will need it.

## Day one: the toolchain

Nothing this project builds with is installed system-wide. That is deliberate - the versions are
pinned per project rather than per machine - but it means a fresh checkout does not build until
three things are on PATH:

- A JDK 21 or newer. Check with `javac -version`, not `java -version`: a JRE satisfies the second
  and not the first, and that mismatch has cost people an afternoon.
- Maven. There is no installer; it is an unzip in your home directory.
- Node, which the backend build provisions for itself on first run. Do not install it separately.

## Day two: the branching model

Work branches off `dev`. It merges back to `dev` by pull request. It reaches production through a
`dev` to `main` promotion pull request, and merging that pull request is what deploys.

Nothing deploys from `dev`. It is the batching point, not a preview environment - so it runs
nowhere and drifts from production between promotions. The answer to that is to promote often,
not to deploy from it.

## Day three: who to ask

Ask in the open channel rather than by direct message. The answer is almost always useful to
somebody else, and a direct message makes it findable by exactly one person.
