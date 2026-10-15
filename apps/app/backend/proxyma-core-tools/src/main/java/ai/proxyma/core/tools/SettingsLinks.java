package ai.proxyma.core.tools;

// In-app destinations a tool error message can hand the user, as markdown links.
//
// "app://settings/<tab-slug>[/<focus>]" is not a web URL: the chat UI's MarkdownRenderer
// recognizes the scheme and renders the link as a button that opens the Settings modal on that tab
// (see MarkdownRenderer.tsx's SETTINGS_TAB_BY_SLUG - an unknown slug degrades to plain text there,
// so only the slugs listed in that map are usable). It exists because "configure your token in
// Settings > Connections" is a breadcrumb the user has to go hunting for, while a link puts them
// one click from the field they need to fill.
//
// The optional trailing segment is a focus target within the tab. On the Connections tab it is a
// connector key, and the UI expands that connector's (collapsed-by-default) section and focuses
// its token input - so the click lands the cursor in the box rather than on a list of collapsed
// connector names. An unrecognized focus segment is ignored and the tab still opens, so this can
// never render as a link that goes nowhere.
//
// Tool error messages reach the model verbatim as the tool result (see ChatService), so the
// message text is what tells it to reproduce these links as links; the system prompt's
// ACTIONABLE LINKS rules back that up.
final class SettingsLinks {

    // Base URL, access token and per-connector tool permissions, all on one page. Visible to every
    // signed-in user regardless of role, though only a super admin can change the base URL.
    //
    // The slug was "connector-credentials" until the page grew past credentials. MarkdownRenderer
    // still resolves that older slug, because it is baked into the stored history of every
    // conversation where a tool ever reported a missing token - changing it here without keeping
    // the alias there would quietly turn each of those buttons back into plain text.
    static final String CONNECTORS = "app://settings/connectors";

    // AI providers. Admin-only. (The Google Custom Search credential behind webSearch/webFetch is on
    // Settings > Search, which has no link of its own here.)
    static final String PROVIDERS = "app://settings/providers";

    // The Connections tab, opened on one connector's section. `connectorKey` is that connector's
    // settings prefix - one identifier names the credential, its section and this link, so no
    // mapping table has to be kept in sync on either side.
    static String connectors(String connectorKey) {
        if (connectorKey == null || connectorKey.isBlank()) {
            return CONNECTORS;
        }
        return CONNECTORS + "/" + connectorKey;
    }

    private SettingsLinks() {}
}
