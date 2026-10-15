// The custom CA field on the Network tab, DESKTOP edition: there isn't one.
//
// A custom certificate authority is a managed-fleet concern. It exists for a corporate middlebox
// that re-signs TLS with an internal root, or for an internal service signed by a private one -
// and on the desktop machine that sits behind such a proxy, the OS trust store ALREADY carries
// that root, because whatever put the machine on the network installed it. The JVM reads the
// system trust store, so those connections already verify.
//
// What the field could still do there is break things: a desktop user has no reason to hold a PEM,
// and pasting the wrong one into a box labelled "only needed if your network inspects TLS traffic"
// is a plausible way to spend an evening on outbound calls that used to work. An operator
// configuring a server deployment is a different reader with a different set of things to hand.
//
// Returns nothing rather than being conditionally rendered by NetworkTab: this build does not
// contain the field at all. See ./types.ts for why the two editions are two modules.
export function CaCertificateSection() {
  return null
}
