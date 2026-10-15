import type { Translate } from '@/i18n'
import { serverText } from '@/i18n/serverText'
import type { ConnectionTestResult } from './connectionsApi'

/**
 * A connection Test's verdict in the reader's language.
 *
 * The backend sends its English sentence as `message` and the same sentence with placeholders as
 * `template`, plus the values. Every template it can send is named here as a literal, which is what
 * lets the catalog checker see them: a template reaching the lookup as a variable would be in
 * neither of its reports. A template not listed - a raw error from a library - stays the English
 * `message`, which is also what an older backend sends.
 */
export function connectionTestMessage(result: ConnectionTestResult, t: Translate): string {
  const p = result.params ?? {}
  switch (result.template) {
    case 'Signed in as {user}': return t('Signed in as {user}', p)
    case 'Signed in as {user} ({account})': return t('Signed in as {user} ({account})', p)
    case 'Signed in to {workspace} as {user}': return t('Signed in to {workspace} as {user}', p)
    case 'Signed in as {user} on {site}': return t('Signed in as {user} on {site}', p)
    case 'Signed in to portal {portal}': return t('Signed in to portal {portal}', p)
    case 'Signed in as {user} on {instance}': return t('Signed in as {user} on {instance}', p)
    case 'Connected': return t('Connected')
    case 'Set the server URL first.': return t('Set the server URL first.')
    case 'Enter a username and password first.': return t('Enter a username and password first.')
    case 'Enter an access token first.': return t('Enter an access token first.')
    case '{service} did not accept the token.': return t('{service} did not accept the token.', p)
    case '{service} did not accept the user token.': return t('{service} did not accept the user token.', p)
    case '{service} did not accept the username or password.':
      return t('{service} did not accept the username or password.', p)
    case '{service} refused this account (403).': return t('{service} refused this account (403).', p)
    case "Nothing answered at that URL (404). Check it is the server's root address.":
      return t("Nothing answered at that URL (404). Check it is the server's root address.")
    case '{service} answered {status}.': return t('{service} answered {status}.', p)
    case 'Could not reach {url}: {reason}': return t('Could not reach {url}: {reason}', p)
    case "The server's certificate expired on {date}. Its administrator has to renew it.":
      return t("The server's certificate expired on {date}. Its administrator has to renew it.", p)
    case "The server's certificate has expired. Its administrator has to renew it.":
      return t("The server's certificate has expired. Its administrator has to renew it.")
    case "The server's certificate is not valid yet. Check this computer's date and time.":
      return t("The server's certificate is not valid yet. Check this computer's date and time.")
    case "This computer does not trust the server's certificate. If your company uses its own certificate authority, add it in Settings > Network.":
      return t("This computer does not trust the server's certificate. If your company uses its own certificate authority, add it in Settings > Network.")
    case "The server answered with a page instead of its API. Check the URL is the server's own address.":
      return t("The server answered with a page instead of its API. Check the URL is the server's own address.")
    case 'This connection type cannot be tested here.': return t('This connection type cannot be tested here.')
    case 'This connection type has no test.': return t('This connection type has no test.')
    case 'Sign in to Microsoft 365 first.': return t('Sign in to Microsoft 365 first.')
    case 'Microsoft 365 could not be reached: {reason}': return t('Microsoft 365 could not be reached: {reason}', p)
    case 'Connected to {server}. Tools available: {count}.': return t('Connected to {server}. Tools available: {count}.', p)
    case 'Could not connect: {reason}': return t('Could not connect: {reason}', p)
    default: return serverText(result.message)
  }
}
