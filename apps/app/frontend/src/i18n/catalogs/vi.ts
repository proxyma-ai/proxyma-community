/**
 * Vietnamese for the Proxyma app. English on the left, Vietnamese on the right.
 *
 * THIS FILE IS THE WHOLE MAPPING, and it is meant to be read and corrected by hand. There is no
 * English catalog: English is the source, written in the code, and it doubles as the lookup key.
 * A string missing from here renders its English exactly as the developer wrote it, so an
 * incomplete file degrades to a partly-English screen and never to an identifier.
 *
 * TO CORRECT A TRANSLATION: change the right-hand side. Nothing else needs touching.
 *
 * TO ADD ONE: copy the English string from the code, exactly, and put it on the left. It must
 * match character for character - it IS the key. `npm run i18n:check` from the repo root lists
 * every English string in the source that has no entry here, and every entry here that no longer
 * matches any string in the source.
 *
 * WHAT IS NOT TRANSLATED, and should not be:
 *   - URLs, route paths and `app://settings/<slug>` deep links
 *   - settings tab IDs as IDENTITY (the label is translated; the id it is compared and stored by
 *     is the same English string, and that is fine - the id never reaches a screen)
 *   - product and vendor names: Proxyma, Jira, Confluence, Bitbucket, SVN, OpenAI, Ollama
 *
 * TERMINOLOGY, settled once so it stays consistent. Vietnamese technical writing borrows heavily
 * from English, and over-translating reads as stilted or, worse, ambiguous:
 *
 *   connector   ket noi      skill    ky nang      workflow  quy trinh
 *   guardrail   rao chan     retrieval truy xuat    license   giay phep
 *   agent       tac nhan     token    left as "token" when it means an API token
 *
 * HOUSE STYLE, taken from the maintainer's own corrections rather than invented. Follow it when
 * adding entries, so the file reads as one voice:
 *
 *   & stays &          "Tro giup & Gioi thieu", not "va". Capitalise the word after it.
 *   Gloss the English  A technical term keeps its English in brackets on first use:
 *                      "tac nhan (agent)", "Vector nhung (embeddings)",
 *                      "cua so ngu canh (context window)", "(HSM)", "(EULA)".
 *   Full noun phrases  "Tep tin" over "Tep", "Bo ket noi" over "Ket noi". Terse reads clipped.
 *   Formal register    Complete sentences. "Dat cau hoi bang ngon ngu tu nhien", not a shorthand.
 *   Distinguish        Two different English strings get two different translations: "Docs" is
 *                      "Tai lieu", "Documentation" is "Tai lieu huong dan".
 *
 * WORD ORDER CAN CROSS TWO ENTRIES, and the trust strip is the example. It renders a big word
 * above a small one - "Hybrid" over "search" - and Vietnamese puts the adjective after the noun,
 * so the pair is translated as "Tim kiem" over "ket hop". Read one line at a time each entry looks
 * wrong; read the tile as it renders and it is the only correct answer. Do not "fix" it.
 */
export const vi: Record<string, string> = {
  // -- language and appearance ------------------------------------------------
  'Language': 'Ngôn ngữ',
  'Appearance': 'Giao diện',
  'Light': 'Sáng',
  'Proxyma': 'Proxyma',
  'Dark': 'Tối',

  // -- the header's nav row ---------------------------------------------------
  // These three come from NAV_ITEMS in Topbar.tsx, which maps `t` over them before rendering and
  // before handing them to MobileMenu - so the same entry serves the words above sm and the rows
  // below it. The translations are the ones apps/landing already ships, because its scenes draw
  // this exact header: two answers for one nav word would show the reader a product that does not
  // match the site that sold it.
  'Chat': 'Trò chuyện',
  'Sources': 'Nguồn dữ liệu',
  'Administration': 'Quản trị',

  // -- menus ------------------------------------------------------------------
  'Menu': 'Menu',
  // The account menu's trigger is the signed-in name above sm and nothing at all below it -
  // Topbar hides the whole control there - so this label is what a screen reader announces.
  'Account menu': 'Menu tài khoản',
  'Quit': 'Thoát',
  'Sign out': 'Đăng xuất',
  'Send feedback': 'Gửi góp ý',

  // -- settings: nav group headings -------------------------------------------
  'Workspace': 'Không gian làm việc',
  'AI & Knowledge': 'AI & Tri thức',
  'Application': 'Ứng dụng',

  // -- settings: the rail -------
  // These come from the TabKey union in SettingsModal.tsx. "Settings" is deliberately absent as
  // a separate entry: it is both a nav group heading and a tab id, one English string, so one
  // translation serves both.
  'Settings': 'Cài đặt',
  'Account': 'Tài khoản',
  'About': 'Giới thiệu',
  'Connectors': 'Bộ kết nối',
  'Skills': 'Kỹ năng',
  'Workflows': 'Quy trình',
  'API Tokens': 'Token API',
  'AI Assistants': 'Trợ lý AI',
  'Add this to .vscode/mcp.json, or run MCP: Open User Configuration from the Command Palette for every workspace:': 'Thêm đoạn này vào .vscode/mcp.json, hoặc chạy MCP: Open User Configuration từ Command Palette để áp dụng cho mọi workspace:',
  "Anthropic's coding assistant, in the terminal and in VS Code. It comes with a Claude Pro or Max subscription.": 'Trợ lý lập trình của Anthropic, trong terminal và trong VS Code. Có sẵn trong gói Claude Pro hoặc Max.',
  'ChatGPT and Gemini on the web cannot reach a server on your computer. Use the apps above instead.': 'ChatGPT và Gemini trên web không kết nối được tới máy chủ trên máy tính của bạn. Hãy dùng các ứng dụng ở trên.',
  'Claude, ChatGPT and Gemini can search your sources through Proxyma. They answer with their own model and subscription, so no API key is needed.': 'Claude, ChatGPT và Gemini có thể tìm kiếm trong các nguồn dữ liệu của bạn thông qua Proxyma. Chúng trả lời bằng mô hình và gói đăng ký của riêng chúng, nên không cần khóa API.',
  'Copilot Chat in VS Code, in agent mode.': 'Copilot Chat trong VS Code, ở chế độ agent.',
  'Each assistant needs an access token. Generate one under API Tokens and put it where the examples say {token}.': 'Mỗi trợ lý cần một token truy cập. Hãy tạo token trong mục Token API và đặt nó vào chỗ ghi {token} trong các ví dụ.',
  "Google's assistant in the terminal. Sign in with a Google account; a Gemini subscription raises its limits.": 'Trợ lý của Google trong terminal. Đăng nhập bằng tài khoản Google; gói Gemini sẽ nâng giới hạn sử dụng.',
  'In Claude, open Settings > Developer > Edit Config, and add this to the file:': 'Trong Claude, mở Settings > Developer > Edit Config, rồi thêm đoạn này vào tệp:',
  'Install Gemini CLI, then run this in a terminal:': 'Cài Gemini CLI, rồi chạy lệnh này trong terminal:',
  'Install Node.js from nodejs.org if you do not have it.': 'Cài Node.js từ nodejs.org nếu bạn chưa có.',
  'Install the Codex CLI, then run this in a terminal:': 'Cài Codex CLI, rồi chạy lệnh này trong terminal:',
  'MCP setup guide': 'Hướng dẫn thiết lập MCP',
  'Open API Tokens': 'Mở Token API',
  'Proxyma address for assistants': 'Địa chỉ Proxyma cho trợ lý',
  'Proxyma now appears in the ChatGPT desktop app, the Codex CLI and the Codex extension.': 'Proxyma giờ xuất hiện trong ứng dụng ChatGPT trên máy tính, Codex CLI và tiện ích Codex.',
  'Quit Claude completely and open it again.': 'Thoát hẳn Claude rồi mở lại.',
  'Run this in a terminal:': 'Chạy lệnh này trong terminal:',
  'Set the environment variable PROXYMA_TOKEN to your access token, then restart the app.': 'Đặt biến môi trường PROXYMA_TOKEN là token truy cập của bạn, rồi khởi động lại ứng dụng.',
  'Start Claude Code and ask it about your documents.': 'Mở Claude Code và hỏi nó về tài liệu của bạn.',
  'Start Gemini CLI and ask it about your documents.': 'Mở Gemini CLI và hỏi nó về tài liệu của bạn.',
  'The ChatGPT desktop app, the Codex CLI and the Codex extension for VS Code share one setting. Sign in with your ChatGPT account.': 'Ứng dụng ChatGPT trên máy tính, Codex CLI và tiện ích Codex cho VS Code dùng chung một thiết lập. Đăng nhập bằng tài khoản ChatGPT của bạn.',
  'The Claude app for Windows and macOS. It reaches Proxyma through a small bridge that needs Node.js 18 or later.': 'Ứng dụng Claude cho Windows và macOS. Nó kết nối tới Proxyma qua một cầu nối nhỏ cần Node.js 18 trở lên.',
  "The assistant's provider receives what it reads from your sources, as it would from any cloud model.": 'Nhà cung cấp của trợ lý sẽ nhận những gì nó đọc từ các nguồn dữ liệu của bạn, như với bất kỳ mô hình đám mây nào.',
  'You are on another machine. Assistants work here only if the server sets PROXYMA_EXPOSE_MCP=true, which lets anyone holding this token use its tools.': 'Bạn đang truy cập từ một máy khác. Trợ lý chỉ hoạt động ở đây nếu máy chủ đặt PROXYMA_EXPOSE_MCP=true, và khi đó bất kỳ ai có token này đều dùng được các công cụ của nó.',
  'AI Providers': 'Nhà cung cấp AI',
  'Agents': 'Tác nhân',
  'An agent is still working - go back to it': 'Một tác nhân vẫn đang làm việc - quay lại với nó',
  'Documents': 'Tài liệu',
  'Network': 'Mạng',
  'Data Retention': 'Lưu giữ dữ liệu',
  'License': 'Giấy phép',
  'Guardrails': 'Rào chắn',
  'Usage & Cost': 'Mức sử dụng & Chi phí',
  'Feedback': 'Góp ý',
  'Import & Export': 'Nhập & Xuất dữ liệu',
  'Updates': 'Cập nhật',
  'Reset': 'Đặt lại',

  // -- document titles ----------------------------------------------------------
  // What the browser tab reads, via usePageTitle. Four of the seven are already above - 'Chat',
  // 'Sources', 'Administration' and 'Sign In' name a page AND a control, one English string and
  // so one entry. These three name only a page.
  'Forgot password': 'Quên mật khẩu',
  'Reset password': 'Đặt lại mật khẩu',
  'Setup': 'Thiết lập',

  // -- the shell's notices ------------------------------------------------------
  // Raised by AppShell as toasts, so each is a whole sentence rather than a label, and the two
  // link labels beside them are the action the reader takes from the notice.
  'Learn more': 'Tìm hiểu thêm',
  "Don't show again": 'Không hiển thị lại',
  'Open Settings': 'Mở Cài đặt',
  // `{product}` is substituted at runtime - it is the branding's product name, not a translatable
  // word. MCP and API are wire protocol names and stay English.
  'Did you know you can query {product} from your IDE or scripts via MCP/API?':
    'Bạn có biết rằng bạn có thể truy vấn {product} từ IDE hoặc script qua MCP/API không?',
  'No AI provider is responding. Chat and search will not work until one is reachable.':
    'Không có nhà cung cấp AI nào phản hồi. Chat và tìm kiếm sẽ không hoạt động cho tới khi kết nối được tới một nhà cung cấp.',
  'Account recovered.': 'Đã khôi phục tài khoản.',
  'Failed to recover account.': 'Không thể khôi phục tài khoản.',
  'Failed to delete account.': 'Không thể xóa tài khoản.',

  // -- shared words: buttons, field labels, connectives -------------------------
  // These appear on several auth screens and will appear on many more. 'Sign In' above and
  // 'Sign in' here are TWO keys because they are two English strings - the button and the link
  // are capitalised differently - and the key IS the English. One Vietnamese answer serves both.
  'Sign in': 'Đăng nhập',
  'Cancel': 'Hủy',
  'Back': 'Quay lại',
  'Dismiss': 'Bỏ qua',
  'Email': 'Email',
  'Password': 'Mật khẩu',
  'New password': 'Mật khẩu mới',
  // "Re-enter", not "Confirm", on BOTH sides. A confirmation field asks for the password again;
  // "confirm" describes what the form does with it, not what the reader is being asked to type.
  // Renamed 2026-09-06, English included, at the maintainer's direction - which collapsed the old
  // 'Confirm password' placeholder into 'Re-enter password' below, since a key written twice is
  // one the checker fails on and one of the two can never render.
  'Display name': 'Tên hiển thị',
  // Label and placeholder are separate English strings and so separate keys, even where the
  // Vietnamese lands on the same words.
  'Enter password': 'Nhập mật khẩu',
  'Enter new password': 'Nhập mật khẩu mới',
  'Re-enter new password': 'Nhập lại mật khẩu mới',
  'Re-enter password': 'Nhập lại mật khẩu',
  'Your name (optional)': 'Tên của bạn (không bắt buộc)',
  'Admin (optional)': 'Quản trị viên (không bắt buộc)',
  // Sentence connectives. Short and generic, which is a risk worth naming: if a future screen
  // needs a different Vietnamese word for either, that call site gets its own longer key rather
  // than these being reworded underneath the screens already using them.
  'or': 'hoặc',

  // -- the legal documents, by name --------------------------------------------
  // Titles of published documents, so they match what proxyma.ai/vi serves.
  'Terms of Service': 'Điều khoản dịch vụ',
  'Privacy Policy': 'Chính sách bảo mật',
  'End User License Agreement': 'Thỏa thuận cấp phép người dùng cuối',
  'You must accept the Terms of Service, Privacy Policy and EULA to create an account.':
    'Bạn phải chấp nhận Điều khoản dịch vụ, Chính sách bảo mật và Thỏa thuận cấp phép người dùng cuối (EULA) để tạo tài khoản.',

  // -- signing in ---------------------------------------------------------------
  'Sign in to your account': 'Đăng nhập',
  'Create your account': 'Đăng ký',
  'Back to home': 'Về trang chủ',
  'Forgot password?': 'Quên mật khẩu?',
  'Forgot your password? Ask an administrator for a reset link.':
    'Quên mật khẩu? Hãy nhờ quản trị viên tạo liên kết đặt lại mật khẩu.',
  'Already have an account?': 'Bạn đã có tài khoản?',
  "Don't have an account?": 'Bạn chưa có tài khoản?',
  'Create one': 'Đăng ký ngay',
  'Create account': 'Tạo tài khoản',
  'Signing in...': 'Đang đăng nhập...',
  'Creating account...': 'Đang tạo tài khoản...',
  'Corporate SSO': 'SSO doanh nghiệp',
  'Email is required': 'Vui lòng nhập email',
  'Password is required': 'Vui lòng nhập mật khẩu',
  'Invalid credentials.': 'Thông tin đăng nhập không đúng.',
  'Registration failed.': 'Đăng ký thất bại.',
  'Could not load the current terms. Reload the page and try again.':
    'Không thể tải các điều khoản hiện hành. Hãy tải lại trang và thử lại.',
  'Sign-in failed. Please try again.': 'Đăng nhập thất bại. Vui lòng thử lại.',
  'An account with this email address already exists. Sign in the way you signed in before.':
    'Đã có một tài khoản dùng địa chỉ email này. Hãy đăng nhập theo cách bạn đã dùng trước đây.',
  'This server does not create an account for this sign-in. Ask an administrator to add you.':
    'Máy chủ này không tạo tài khoản cho lần đăng nhập này. Hãy nhờ quản trị viên thêm bạn.',
  'The system is temporarily unavailable for maintenance. Please try again later.':
    'Hệ thống tạm thời không khả dụng do đang bảo trì. Vui lòng thử lại sau.',
  // {status} and {statusText} are values off the wire, not words - see the call sites.
  'Server error ({status})': 'Lỗi máy chủ ({status})',
  'Server error (unknown status)': 'Lỗi máy chủ (không rõ mã trạng thái)',
  'Server unavailable ({status} {statusText})': 'Máy chủ không phản hồi ({status} {statusText})',

  // -- the access code (Desktop and Home Server, app/AccessGate.tsx) ---------------
  'Enter the access code': 'Nhập mã truy cập',
  'Access code': 'Mã truy cập',
  'Proxyma prints it in its log when it starts. On the computer Proxyma runs on, its window and tray icon open it without one.':
    'Proxyma ghi mã này vào nhật ký khi khởi động. Trên chính máy chạy Proxyma, cửa sổ và biểu tượng khay hệ thống mở ứng dụng mà không cần mã.',
  'That access code is not right.': 'Mã truy cập này không đúng.',
  'Proxyma did not answer. Try again.': 'Proxyma không phản hồi. Hãy thử lại.',

  // -- password reset -----------------------------------------------------------
  'Reset your password': 'Đặt lại mật khẩu',
  'Choose a new password': 'Chọn mật khẩu mới',
  'Send reset link': 'Gửi liên kết đặt lại',
  'Sending...': 'Đang gửi...',
  'Resetting...': 'Đang đặt lại...',
  'Back to sign in': 'Quay lại trang đăng nhập',
  'Request a new link': 'Yêu cầu liên kết mới',
  'Invalid or missing reset link.': 'Liên kết đặt lại mật khẩu không hợp lệ hoặc bị thiếu.',
  'Password reset. Redirecting to login...': 'Đã đặt lại mật khẩu. Đang chuyển tới trang đăng nhập...',
  'Reset failed. Please try again.': 'Đặt lại mật khẩu thất bại. Vui lòng thử lại.',
  'If an account exists for that email, a password reset link has been sent.':
    'Nếu có tài khoản ứng với email đó, một liên kết đặt lại mật khẩu đã được gửi đi.',
  'Could not send reset email. Please try again.':
    'Không thể gửi email đặt lại mật khẩu. Vui lòng thử lại.',

  // -- first-run setup ----------------------------------------------------------
  'Initial server setup': 'Thiết lập máy chủ lần đầu',
  'Create the super admin account': 'Tạo tài khoản quản trị viên cấp cao',
  'Setup token': 'Mã thiết lập',
  'The server prints it in its log when it starts. Set PROXYMA_SETUP_TOKEN to choose it.':
    'Máy chủ in mã này vào nhật ký khi khởi động. Đặt PROXYMA_SETUP_TOKEN để tự chọn mã.',
  'Enter the setup token from the server log': 'Nhập mã thiết lập trong nhật ký của máy chủ',
  'This account has full control over the server. Choose a strong password you will remember.':
    'Tài khoản này có toàn quyền kiểm soát máy chủ. Hãy chọn một mật khẩu mạnh mà bạn sẽ nhớ được.',
  'Creating...': 'Đang tạo...',
  'Super admin account created. Redirecting to login...':
    'Đã tạo tài khoản quản trị viên cấp cao. Đang chuyển tới trang đăng nhập...',
  'Setup failed. Please try again.': 'Thiết lập thất bại. Vui lòng thử lại.',

  // -- deleting an account ------------------------------------------------------
  'Delete account': 'Xóa tài khoản',
  'Account pending deletion': 'Tài khoản đang chờ xóa',
  'Start deletion': 'Bắt đầu xóa',
  'Delete my data': 'Xóa dữ liệu của tôi',
  'Delete now': 'Xóa ngay',
  'Yes, delete now': 'Có, xóa ngay',
  'Keep waiting': 'Tiếp tục chờ',
  'Recover my account': 'Khôi phục tài khoản của tôi',
  'Processing...': 'Đang xử lý...',
  'Deleting...': 'Đang xóa...',
  'This starts the deletion process. Your account is locked immediately and permanently deleted once the grace period ends.':
    'Thao tác này bắt đầu quá trình xóa. Tài khoản của bạn sẽ bị khóa ngay lập tức và bị xóa vĩnh viễn khi hết thời gian ân hạn.',
  'Your SSO account stays open, but your data is deleted immediately. This cannot be undone.':
    'Tài khoản SSO của bạn vẫn được giữ, nhưng dữ liệu của bạn bị xóa ngay lập tức. Không thể hoàn tác.',
  'Deleting now is immediate and cannot be undone. Are you sure?':
    'Xóa ngay sẽ có hiệu lực tức thì và không thể hoàn tác. Bạn có chắc chắn không?',
  // Two forms on the English side, one on the Vietnamese: the language does not inflect for
  // number. Written with the separator anyway so the entry matches its key exactly.
  '{count} day|{count} days': '{count} ngày',
  'Access is locked until you recover the account or the deletion completes.':
    'Quyền truy cập bị khóa cho tới khi bạn khôi phục tài khoản hoặc quá trình xóa hoàn tất.',
  // The rows listing what is destroyed.
  'Uploaded files': 'Tệp tin đã tải lên',
  'Any files you uploaded are deleted, if present': 'Mọi tệp tin bạn đã tải lên sẽ bị xóa, nếu có',
  'Search index data': 'Dữ liệu chỉ mục tìm kiếm',
  'Vector and keyword index data for your connectors':
    'Dữ liệu chỉ mục vector và từ khóa cho các bộ kết nối của bạn',
  'Conversations': 'Cuộc trò chuyện',
  'Your chat history and saved conversations':
    'Lịch sử trò chuyện và các cuộc trò chuyện đã lưu',
  'Login data': 'Dữ liệu đăng nhập',
  'Your account and credentials are completely deleted':
    'Tài khoản và thông tin đăng nhập của bạn sẽ bị xóa hoàn toàn',
  '{days}-day grace period': 'Thời gian ân hạn {days} ngày',
  'You can recover the account any time before it completes':
    'Bạn có thể khôi phục tài khoản bất cứ lúc nào trước khi quá trình hoàn tất',

  // -- the guest banner (server mode, signed out) --------------------------------
  '{product} needs an account here.': '{product} yêu cầu tài khoản tại đây.',
  'You are trying {product} as a guest.': 'Bạn đang dùng thử {product} với tư cách khách.',
  'You have used all {limit} free messages.': 'Bạn đã dùng hết {limit} tin nhắn miễn phí.',
  '{remaining} of {limit} free messages left.': 'Còn {remaining} trên {limit} tin nhắn miễn phí.',
  'Sign in for your own conversation history and your own sources.':
    'Đăng nhập để có lịch sử trò chuyện và nguồn dữ liệu của riêng bạn.',
  'Sign in for your own conversation history, your own sources, and a far larger allowance.':
    'Đăng nhập để có lịch sử trò chuyện, nguồn dữ liệu của riêng bạn và hạn mức lớn hơn nhiều.',

  // -- chat: the composer -------------------------------------------------------
  'Hi, {name}': 'Chào {name}',
  'Type @ to mention a source or / to use a skill': 'Gõ @ để nhắc tới một nguồn dữ liệu hoặc / để dùng một kỹ năng',
  'Send': 'Gửi',
  'Stop': 'Dừng',
  'Stopping': 'Đang dừng',
  'New conversation': 'Cuộc trò chuyện mới',
  'Start a new conversation': 'Bắt đầu một cuộc trò chuyện mới',
  'Conversation history': 'Lịch sử trò chuyện',
  'Add files, select a skill, and more': 'Thêm tệp tin, chọn kỹ năng và nhiều hơn nữa',
  'or drop or paste them here': 'hoặc thả hay dán tệp vào đây',
  'Attach files': 'Đính kèm tệp tin',
  'Attach one or more files to this message': 'Đính kèm một hoặc nhiều tệp tin vào tin nhắn này',
  'Drop files to attach': 'Thả tệp tin vào đây để đính kèm',
  'Remove {name}': 'Xóa {name}',
  'Upload failed': 'Tải lên thất bại',
  'Pick a skill to use for this message': 'Chọn một kỹ năng để dùng cho tin nhắn này',
  'Manage skills': 'Quản lý kỹ năng',
  'Open skill settings': 'Mở cài đặt kỹ năng',
  'Create a new skill': 'Tạo một kỹ năng mới',
  'Create new skill': 'Tạo kỹ năng mới',
  'Create a new workflow': 'Tạo một quy trình mới',
  'Create new workflow': 'Tạo quy trình mới',
  'No skills yet.': 'Chưa có kỹ năng nào.',
  'No matching skills or workflows': 'Không có kỹ năng hoặc quy trình nào phù hợp',
  'Shared': 'Được chia sẻ',

  // -- chat: the context window and quota meters --------------------------------
  // {pct}, {used}, {limit}, {count} and {at} are readouts and timestamps - see the call sites.
  'Condensing conversation': 'Đang cô đọng cuộc trò chuyện',
  '{count} tokens freed from context window': 'Đã giải phóng {count} token khỏi cửa sổ ngữ cảnh',
  'Condense conversation - context window {pct}% full':
    'Cô đọng cuộc trò chuyện - cửa sổ ngữ cảnh (context window) đã đầy {pct}%',
  'Context window is {pct}% full. Condense now to free up space, or start a new conversation for the best experience.':
    'Cửa sổ ngữ cảnh (context window) đã đầy {pct}%. Hãy cô đọng ngay để giải phóng chỗ trống, hoặc bắt đầu một cuộc trò chuyện mới để có trải nghiệm tốt nhất.',
  'Session (5h)': 'Phiên (5 giờ)',
  'Weekly': 'Hàng tuần',
  'Monthly': 'Hàng tháng',
  'Resets {at}': 'Đặt lại vào {at}',
  'Resets in at least {duration}': 'Đặt lại sau ít nhất {duration}',
  'Starts with your first message': 'Bắt đầu tính từ tin nhắn đầu tiên của bạn',
  'Session usage is {pct}% of quota. Click for details.':
    'Mức sử dụng của phiên là {pct}% hạn mức. Nhấn để xem chi tiết.',
  'Usage quota - session {pct}% full': 'Hạn mức sử dụng - phiên đã dùng {pct}%',
  // Compact badge labels. "5h" is a unit and stays.

  // -- chat: a message and its actions ------------------------------------------
  'Queued': 'Đang chờ',
  'Queued - read at the next step': 'Đang chờ - sẽ được đọc ở bước tiếp theo',
  'Retry': 'Thử lại',
  'Copied': 'Đã sao chép',
  'Copy message': 'Sao chép tin nhắn',
  'Copy response': 'Sao chép câu trả lời',
  'Good response': 'Câu trả lời tốt',
  'Bad response': 'Câu trả lời chưa tốt',
  'Rated helpful - click again to take it back': 'Đã đánh giá là hữu ích - nhấn lần nữa để bỏ',
  'Rated unhelpful - click again to take it back': 'Đã đánh giá là chưa hữu ích - nhấn lần nữa để bỏ',
  'Rate this response helpful. When diagnostics are sent to Proxyma, it is sent anonymously with this reply and its question.':
    'Đánh giá câu trả lời này là hữu ích. Khi bật gửi dữ liệu chẩn đoán tới Proxyma, đánh giá được gửi ẩn danh kèm câu trả lời này và câu hỏi của nó.',
  'Rate this response unhelpful. When diagnostics are sent to Proxyma, it is sent anonymously with this reply and its question.':
    'Đánh giá câu trả lời này là chưa hữu ích. Khi bật gửi dữ liệu chẩn đoán tới Proxyma, đánh giá được gửi ẩn danh kèm câu trả lời này và câu hỏi của nó.',
  'Rewind to this message': 'Tua lại tới tin nhắn này',
  'Rewind the conversation to this message and put its text back in the input, so you can edit and resend it. Everything after it is removed.':
    'Tua cuộc trò chuyện về tin nhắn này và đưa nội dung của nó trở lại ô nhập liệu, để bạn có thể sửa và gửi lại. Mọi thứ sau tin nhắn đó sẽ bị xóa.',

  // -- chat: sharing a conversation ---------------------------------------------
  'Copy share link for this conversation': 'Sao chép liên kết chia sẻ cho cuộc trò chuyện này',
  'Share link copied': 'Đã sao chép liên kết chia sẻ',
  'Copy a read-only link to this conversation, from its first message through this reply. Anyone signed in who opens it can read that much and nothing after it, and cannot reply or change anything. The link has no expiry date - it keeps working until you delete the conversation.':
    'Sao chép một liên kết chỉ đọc tới cuộc trò chuyện này, từ tin nhắn đầu tiên cho tới câu trả lời này. Bất kỳ ai đã đăng nhập và mở liên kết đều có thể đọc tới đó và không đọc được gì sau đó, đồng thời không thể trả lời hay thay đổi bất cứ điều gì. Liên kết không có ngày hết hạn - nó vẫn hoạt động cho tới khi bạn xóa cuộc trò chuyện.',
  'This shared conversation is no longer available': 'Cuộc trò chuyện được chia sẻ này không còn khả dụng',
  'The link may be incorrect, or the conversation it pointed to has been deleted. Share links stop working as soon as their conversation is removed.':
    'Liên kết có thể không đúng, hoặc cuộc trò chuyện mà nó trỏ tới đã bị xóa. Liên kết chia sẻ ngừng hoạt động ngay khi cuộc trò chuyện tương ứng bị xóa.',
  'Go to chat': 'Tới trang trò chuyện',
  'Shared by {who} {when}.': 'Được chia sẻ bởi {who} {when}.',
  'Shared by {who}.': 'Được chia sẻ bởi {who}.',
  'another user': 'một người dùng khác',
  'start your own chat': 'bắt đầu cuộc trò chuyện của riêng bạn',

  // -- chat: conversation history ------------------------------------------------
  'Your conversations are listed here, newest first, from the first question you ask.': 'Các cuộc trò chuyện của bạn được liệt kê ở đây, mới nhất ở trên, kể từ câu hỏi đầu tiên bạn đặt.',
  'Conversations are kept for 7 days after their last activity. Pin one to keep it indefinitely.':
    'Các cuộc trò chuyện được giữ trong 7 ngày kể từ lần hoạt động cuối. Hãy ghim để giữ lại vô thời hạn.',
  'Delete': 'Xóa',
  // 'Deleting' is NOT repeated here: the conversation row's in-flight label and the tool-call
  // conjugation are the same English string, so one entry serves both. The checker caught the
  // duplicate - the later entry would have won and this one been dead.
  'Pin': 'Ghim',
  'Unpin': 'Bỏ ghim',
  'Pinned': 'Đã ghim',
  '{count} message|{count} messages': '{count} tin nhắn',
  'Pin - keeps this conversation forever. Unpinned conversations are deleted after 7 days without activity':
    'Ghim - giữ cuộc trò chuyện này mãi mãi. Các cuộc trò chuyện không được ghim sẽ bị xóa sau 7 ngày không hoạt động',
  'Unpin - this conversation will then be deleted after 7 days without activity':
    'Bỏ ghim - cuộc trò chuyện này sau đó sẽ bị xóa sau 7 ngày không hoạt động',
  'Failed to delete conversation': 'Không thể xóa cuộc trò chuyện',
  'Failed to update pin': 'Không thể cập nhật trạng thái ghim',
  'just now': 'vừa xong',
  'now': 'bây giờ',
  '{value} ago': '{value} trước',

  // -- chat: tool calls -----------------------------------------------------------
  'Waiting for {model}': 'Đang chờ {model}',
  'Waiting for the model': 'Đang chờ mô hình',
  'retry {n}': 'thử lại lần {n}',
  'Failed': 'Thất bại',
  'Input': 'Đầu vào',
  'Output': 'Đầu ra',
  '{minutes}m {seconds}s': '{minutes} phút {seconds} giây',
  '{hours}h {minutes}m': '{hours} giờ {minutes} phút',
  'Sub-agent': 'Tác nhân phụ',
  'Show steps': 'Hiện các bước',
  'Hide steps': 'Ẩn các bước',
  'Show all steps': 'Hiện tất cả các bước',
  'Show only the last step': 'Chỉ hiện bước cuối',
  'Partial result': 'Kết quả chưa đầy đủ',
  // A sub-agent that works while the conversation goes on, and the two places the thread speaks
  // of it outside its own panel.
  'Background': 'Chạy nền',
  'Stop this sub-agent': 'Dừng tác nhân phụ này',
  'Could not stop the sub-agent': 'Không thể dừng tác nhân phụ',
  'From sub-agent: {title}': 'Từ tác nhân phụ: {title}',
  // A chat in a panel beside a page (ChatPanel).
  'Suggested prompts': 'Câu hỏi gợi ý',
  'Could not start this conversation. Try again.': 'Không thể bắt đầu cuộc trò chuyện này. Hãy thử lại.',
  '{title} reported back': '{title} đã báo cáo lại',
  'Stopped at the limit of {count} step.|Stopped at the limit of {count} steps.': 'Đã dừng ở giới hạn {count} bước.',
  'Step limit reached': 'Đã đạt giới hạn số bước',
  'The agent has used its {count} step and is not finished.|The agent has used its {count} steps and is not finished.':
    'Tác nhân đã dùng hết {count} bước và chưa xong việc.',
  'Continue for up to {count} more step, or stop and get an answer from what it has found.|Continue for up to {count} more steps, or stop and get an answer from what it has found.':
    'Tiếp tục thêm tối đa {count} bước, hoặc dừng lại và nhận câu trả lời từ những gì nó đã tìm được.',
  'Stop here': 'Dừng ở đây',
  'The work above may be unfinished. Send a message to continue from here. The limit is "Max agent iterations" in Settings > Agents.':
    'Phần việc ở trên có thể chưa xong. Hãy gửi một tin nhắn để tiếp tục từ đây. Giới hạn này là "Số vòng lặp tối đa của tác nhân" trong Cài đặt > Tác nhân.',
  'Show raw JSON': 'Xem JSON gốc',
  'Show as fields': 'Xem theo trường',
  'Thinking': 'Đang suy nghĩ',
  '{model} is thinking': '{model} đang suy nghĩ',
  '{count} tokens': '{count} token',
  'Accept': 'Chấp nhận',
  'Approve this change': 'Phê duyệt thay đổi này',
  'Allow once': 'Cho phép một lần',
  'Allow for this conversation': 'Cho phép trong cuộc trò chuyện này',
  'Empty': 'Trống',
  'Denied automatically in {time}': 'Tự động từ chối sau {time}',
  'Deny': 'Từ chối',
  'Sending approval': 'Đang gửi chấp thuận',
  'Sending denial': 'Đang gửi từ chối',
  'Enable': 'Bật',
  'Keep disabled': 'Vẫn tắt',
  'Enable {connector} write tools?': 'Bật công cụ ghi của {connector}?',

  // -- chat: what a tool is doing, in the tense it is in --------------------------
  // From PHRASES and CONJUGATIONS in toolActivity.ts. Present tense narrates a call in flight,
  // past tense one that has landed. Vietnamese marks aspect with a particle rather than by
  // inflecting the verb: "Dang ..." for running, "Da ..." for done.
  //
  // The CONJUGATIONS half is the fallback for a tool with no hand-written phrase, where only the
  // verb can be translated - the object comes from the tool's own name. See toolActivity.ts.
  'Searching your sources': 'Đang tìm trong nguồn dữ liệu của bạn',
  'Searched your sources': 'Đã tìm trong nguồn dữ liệu của bạn',
  'Recalling memory': 'Đang đọc lại bộ nhớ',
  'Recalled memory': 'Đã đọc lại bộ nhớ',
  'Tracing connections': 'Đang dò các mối liên hệ',
  'Knowledge graph in search': 'Đồ thị tri thức trong tìm kiếm',
  'Also rank passages by how the things they name connect. Try it on your own questions before leaving it on: it helps questions about connections and can crowd out plain lookups.':
    'Xếp hạng thêm các đoạn văn theo cách những thứ chúng nhắc tới liên hệ với nhau. Hãy thử trên chính các câu hỏi của bạn trước khi để bật: tính năng này giúp các câu hỏi về mối liên hệ nhưng có thể lấn át các tra cứu thông thường.',
  'Traced connections': 'Đã dò các mối liên hệ',
  'Finding who works on': 'Đang tìm người phụ trách',
  'Found who works on': 'Đã tìm người phụ trách',
  'Finding the main themes': 'Đang tìm các chủ đề chính',
  'Found the main themes': 'Đã tìm các chủ đề chính',
  'Remembering': 'Đang ghi nhớ',
  'Remembered': 'Đã ghi nhớ',
  'Loading skill': 'Đang tải kỹ năng',
  'Loaded skill': 'Đã tải kỹ năng',
  'Working on a sub-task': 'Đang xử lý một tác vụ con',
  'Finished a sub-task': 'Đã hoàn thành một tác vụ con',
  'Searching the web': 'Đang tìm kiếm trên web',
  'Searched the web': 'Đã tìm kiếm trên web',
  'Reading page': 'Đang đọc trang',
  'Read page': 'Đã đọc trang',
  'Opening page in a browser': 'Đang mở trang trong trình duyệt',
  'Opened page in a browser': 'Đã mở trang trong trình duyệt',
  'Reading file': 'Đang đọc tệp tin',
  'Read file': 'Đã đọc tệp tin',
  'Searching files': 'Đang tìm trong các tệp tin',
  'Searched files': 'Đã tìm trong các tệp tin',
  'Converting file': 'Đang chuyển đổi tệp tin',
  'Converted file': 'Đã chuyển đổi tệp tin',
  'Checking this deployment': 'Đang kiểm tra hệ thống này',
  'Checked this deployment': 'Đã kiểm tra hệ thống này',
  'Checking your sources': 'Đang kiểm tra nguồn dữ liệu của bạn',
  'Checked your sources': 'Đã kiểm tra nguồn dữ liệu của bạn',
  'Checking your Jira account': 'Đang kiểm tra tài khoản Jira của bạn',
  'Checked your Jira account': 'Đã kiểm tra tài khoản Jira của bạn',
  'Commenting on': 'Đang bình luận về',
  'Commented on': 'Đã bình luận về',
  'Commenting on Confluence page': 'Đang bình luận trên trang Confluence',
  'Commented on Confluence page': 'Đã bình luận trên trang Confluence',
  'Attaching a file to Confluence page': 'Đang đính kèm tệp vào trang Confluence',
  'Attached a file to Confluence page': 'Đã đính kèm tệp vào trang Confluence',
  'Labeling Confluence page': 'Đang gắn nhãn trang Confluence',
  'Labeled Confluence page': 'Đã gắn nhãn trang Confluence',
  'Moving Confluence page': 'Đang di chuyển trang Confluence',
  'Moved Confluence page': 'Đã di chuyển trang Confluence',
  'Editing a Confluence comment': 'Đang sửa bình luận Confluence',
  'Edited a Confluence comment': 'Đã sửa bình luận Confluence',
  'Deleting a Confluence comment': 'Đang xóa bình luận Confluence',
  'Deleted a Confluence comment': 'Đã xóa bình luận Confluence',
  'Creating a branch': 'Đang tạo nhánh',
  'Created a branch': 'Đã tạo nhánh',
  'Deleting a branch': 'Đang xóa nhánh',
  'Deleted a branch': 'Đã xóa nhánh',
  'Checking Jira issue types': 'Đang kiểm tra các loại issue Jira',
  'Checked Jira issue types': 'Đã kiểm tra các loại issue Jira',
  'Commenting on pull request': 'Đang bình luận trên pull request',
  'Commented on pull request': 'Đã bình luận trên pull request',
  'Editing a comment on': 'Đang sửa một bình luận về',
  'Edited a comment on': 'Đã sửa một bình luận về',
  'Deleting a comment on': 'Đang xóa một bình luận về',
  'Deleted a comment on': 'Đã xóa một bình luận về',
  'Moving': 'Đang chuyển',
  'Moved': 'Đã chuyển',
  'Logging work on': 'Đang ghi nhận công việc cho',
  'Logged work on': 'Đã ghi nhận công việc cho',
  'Opening a pull request': 'Đang mở một pull request',
  'Opened a pull request': 'Đã mở một pull request',
  'Merging pull request': 'Đang gộp pull request',
  'Merged pull request': 'Đã gộp pull request',
  'Reviewing pull request': 'Đang xem xét pull request',
  'Reviewed pull request': 'Đã xem xét pull request',
  'Opening pull request': 'Đang mở pull request',
  'Opened pull request': 'Đã mở pull request',
  'Reading the diff of': 'Đang đọc phần khác biệt của',
  'Read the diff of': 'Đã đọc phần khác biệt của',
  'Reading the commit diff': 'Đang đọc phần khác biệt của commit',
  'Read the commit diff': 'Đã đọc phần khác biệt của commit',
  'Reading commit history': 'Đang đọc lịch sử commit',
  'Read commit history': 'Đã đọc lịch sử commit',
  // The conjugation fallback.
  'Opening': 'Đang mở',
  'Opened': 'Đã mở',
  'Listing': 'Đang liệt kê',
  'Listed': 'Đã liệt kê',
  'Searching': 'Đang tìm kiếm',
  'Searched': 'Đã tìm kiếm',
  'Creating': 'Đang tạo',
  'Created': 'Đã tạo',
  'Updating': 'Đang cập nhật',
  'Updated': 'Đã cập nhật',
  'Deleting': 'Đang xóa',
  'Deleted': 'Đã xóa',
  'Adding to': 'Đang thêm vào',
  'Added to': 'Đã thêm vào',
  'Removing': 'Đang gỡ bỏ',
  'Removed': 'Đã gỡ bỏ',
  'Indexing': 'Đang lập chỉ mục',
  'Indexed': 'Đã lập chỉ mục',
  'Reading': 'Đang đọc',
  'Read': 'Đã đọc',
  'Finished reading': 'Đã đọc xong',
  'Merging': 'Đang gộp',
  'Merged': 'Đã gộp',
  'Reviewing': 'Đang xem xét',
  'Reviewed': 'Đã xem xét',
  'Logging': 'Đang ghi nhận',
  'Logged': 'Đã ghi nhận',
  'Rendering': 'Đang kết xuất',
  'Rendered': 'Đã kết xuất',
  'Converting': 'Đang chuyển đổi',
  'Converted': 'Đã chuyển đổi',
  // The file preview (FilePreviewDialog) and the attachment chips that open it.
  'Preview {name}': 'Xem trước {name}',
  'Preview of {name}': 'Bản xem trước của {name}',
  'Download': 'Tải xuống',
  'Loading preview': 'Đang tải bản xem trước',
  'Could not load this file.': 'Không thể tải tệp tin này.',
  'This file type cannot be previewed. Download it to open it.': 'Không thể xem trước loại tệp tin này. Hãy tải xuống để mở.',
  'This file could not be previewed. Download it to open it.': 'Không thể xem trước tệp tin này. Hãy tải xuống để mở.',
  'Showing the first {count} of {total} rows.': 'Đang hiển thị {count} dòng đầu tiên trong tổng số {total} dòng.',
  'Slide {number}': 'Trang chiếu {number}',
  'No text on this slide': 'Trang chiếu này không có chữ',
  // The REST passthroughs (restCallActivity.ts), the display names of the three, and the
  // Atlassian tools whose verbs derive() does not conjugate.
  'Bitbucket request': 'Yêu cầu Bitbucket',
  'Jira request': 'Yêu cầu Jira',
  'Confluence request': 'Yêu cầu Confluence',
  'Browsing repository': 'Đang duyệt kho mã',
  'Browsed repository': 'Đã duyệt kho mã',
  'Downloading repository': 'Đang tải kho mã về',
  'Downloaded repository': 'Đã tải kho mã về',
  'Deleting the downloaded copy of': 'Đang xóa bản đã tải về của',
  'Deleted the downloaded copy of': 'Đã xóa bản đã tải về của',
  'Linking Jira issues': 'Đang liên kết các issue Jira',
  'Linked Jira issues': 'Đã liên kết các issue Jira',
  'Reading the activity on pull request': 'Đang đọc hoạt động trên pull request',
  'Read the activity on pull request': 'Đã đọc hoạt động trên pull request',
  'Reading comments on pull request': 'Đang đọc bình luận trên pull request',
  'Read comments on pull request': 'Đã đọc bình luận trên pull request',
  'Editing a comment on pull request': 'Đang sửa một bình luận trên pull request',
  'Edited a comment on pull request': 'Đã sửa một bình luận trên pull request',
  'Deleting a comment on pull request': 'Đang xóa một bình luận trên pull request',
  'Deleted a comment on pull request': 'Đã xóa một bình luận trên pull request',
  'Listing the commits of pull request': 'Đang liệt kê các commit của pull request',
  'Listed the commits of pull request': 'Đã liệt kê các commit của pull request',
  'Listing the files changed in pull request': 'Đang liệt kê các tệp tin thay đổi trong pull request',
  'Listed the files changed in pull request': 'Đã liệt kê các tệp tin thay đổi trong pull request',
  'Checking the merge status of pull request': 'Đang kiểm tra khả năng gộp của pull request',
  'Checked the merge status of pull request': 'Đã kiểm tra khả năng gộp của pull request',
  'Declining pull request': 'Đang từ chối pull request',
  'Declined pull request': 'Đã từ chối pull request',
  'Reopening pull request': 'Đang mở lại pull request',
  'Reopened pull request': 'Đã mở lại pull request',
  'Approving pull request': 'Đang phê duyệt pull request',
  'Approved pull request': 'Đã phê duyệt pull request',
  'Withdrawing approval of pull request': 'Đang rút phê duyệt pull request',
  'Withdrew approval of pull request': 'Đã rút phê duyệt pull request',
  'Reading the reviewers of pull request': 'Đang đọc danh sách người review của pull request',
  'Read the reviewers of pull request': 'Đã đọc danh sách người review của pull request',
  'Updating the reviewers of pull request': 'Đang cập nhật người review của pull request',
  'Updated the reviewers of pull request': 'Đã cập nhật người review của pull request',
  'Editing pull request': 'Đang sửa pull request',
  'Edited pull request': 'Đã sửa pull request',
  'Deleting pull request': 'Đang xóa pull request',
  'Deleted pull request': 'Đã xóa pull request',
  'Listing pull requests': 'Đang liệt kê các pull request',
  'Listed pull requests': 'Đã liệt kê các pull request',
  'Opening commit': 'Đang mở commit',
  'Opened commit': 'Đã mở commit',
  'Comparing branches': 'Đang so sánh các nhánh',
  'Compared branches': 'Đã so sánh các nhánh',
  'Listing branches': 'Đang liệt kê các nhánh',
  'Listed branches': 'Đã liệt kê các nhánh',
  'Listing tags': 'Đang liệt kê các tag',
  'Listed tags': 'Đã liệt kê các tag',
  'Creating a tag': 'Đang tạo một tag',
  'Created a tag': 'Đã tạo một tag',
  'Listing files': 'Đang liệt kê các tệp tin',
  'Listed files': 'Đã liệt kê các tệp tin',
  'Reading repository hooks': 'Đang đọc các hook của kho mã',
  'Read repository hooks': 'Đã đọc các hook của kho mã',
  'Changing repository hooks': 'Đang thay đổi các hook của kho mã',
  'Changed repository hooks': 'Đã thay đổi các hook của kho mã',
  'Reading repository settings': 'Đang đọc cài đặt kho mã',
  'Read repository settings': 'Đã đọc cài đặt kho mã',
  'Changing repository settings': 'Đang thay đổi cài đặt kho mã',
  'Changed repository settings': 'Đã thay đổi cài đặt kho mã',
  'Reading repository permissions': 'Đang đọc quyền của kho mã',
  'Read repository permissions': 'Đã đọc quyền của kho mã',
  'Changing repository permissions': 'Đang thay đổi quyền của kho mã',
  'Changed repository permissions': 'Đã thay đổi quyền của kho mã',
  'Listing forks': 'Đang liệt kê các fork',
  'Listed forks': 'Đã liệt kê các fork',
  'Opening repository': 'Đang mở kho mã',
  'Opened repository': 'Đã mở kho mã',
  'Listing repositories in': 'Đang liệt kê các kho mã trong',
  'Listed repositories in': 'Đã liệt kê các kho mã trong',
  'Opening Bitbucket project': 'Đang mở dự án Bitbucket',
  'Opened Bitbucket project': 'Đã mở dự án Bitbucket',
  'Listing Bitbucket projects': 'Đang liệt kê các dự án Bitbucket',
  'Listed Bitbucket projects': 'Đã liệt kê các dự án Bitbucket',
  'Searching repositories': 'Đang tìm kho mã',
  'Searched repositories': 'Đã tìm kho mã',
  'Listing your pull requests': 'Đang liệt kê các pull request của bạn',
  'Listed your pull requests': 'Đã liệt kê các pull request của bạn',
  'Looking up Bitbucket users': 'Đang tra cứu người dùng Bitbucket',
  'Looked up Bitbucket users': 'Đã tra cứu người dùng Bitbucket',
  'Checking the build status of commit': 'Đang kiểm tra trạng thái build của commit',
  'Checked the build status of commit': 'Đã kiểm tra trạng thái build của commit',
  'Reading the watchers of': 'Đang đọc người theo dõi của',
  'Read the watchers of': 'Đã đọc người theo dõi của',
  'Adding a watcher to': 'Đang thêm người theo dõi vào',
  'Added a watcher to': 'Đã thêm người theo dõi vào',
  'Removing a watcher from': 'Đang gỡ người theo dõi khỏi',
  'Removed a watcher from': 'Đã gỡ người theo dõi khỏi',
  'Reading the links on': 'Đang đọc các liên kết trên',
  'Read the links on': 'Đã đọc các liên kết trên',
  'Removing a link from': 'Đang gỡ một liên kết khỏi',
  'Removed a link from': 'Đã gỡ một liên kết khỏi',
  'Adding a link to': 'Đang thêm một liên kết vào',
  'Added a link to': 'Đã thêm một liên kết vào',
  'Searching earlier conversations': 'Đang tìm trong các cuộc trò chuyện trước',
  'Searched earlier conversations': 'Đã tìm trong các cuộc trò chuyện trước',
  'Attaching a file to': 'Đang đính kèm tệp tin vào',
  'Attached a file to': 'Đã đính kèm tệp tin vào',
  'Reading comments on': 'Đang đọc bình luận về',
  'Read comments on': 'Đã đọc bình luận về',
  'Reading the work log of': 'Đang đọc nhật ký công việc của',
  'Read the work log of': 'Đã đọc nhật ký công việc của',
  'Checking the transitions of': 'Đang kiểm tra các chuyển trạng thái của',
  'Checked the transitions of': 'Đã kiểm tra các chuyển trạng thái của',
  'Reading the history of': 'Đang đọc lịch sử của',
  'Read the history of': 'Đã đọc lịch sử của',
  'Voting for': 'Đang bình chọn cho',
  'Voted for': 'Đã bình chọn cho',
  'Removing a vote from': 'Đang rút bình chọn khỏi',
  'Removed a vote from': 'Đã rút bình chọn khỏi',
  'Assigning': 'Đang giao',
  'Assigned': 'Đã giao',
  'Reading the editable fields of': 'Đang đọc các trường có thể sửa của',
  'Read the editable fields of': 'Đã đọc các trường có thể sửa của',
  'Creating a Jira issue': 'Đang tạo một issue Jira',
  'Created a Jira issue': 'Đã tạo một issue Jira',
  'Searching Jira issues': 'Đang tìm các issue Jira',
  'Searched Jira issues': 'Đã tìm các issue Jira',
  'Removing an issue link': 'Đang gỡ một liên kết issue',
  'Removed an issue link': 'Đã gỡ một liên kết issue',
  'Listing link types': 'Đang liệt kê các loại liên kết',
  'Listed link types': 'Đã liệt kê các loại liên kết',
  'Opening attachment': 'Đang mở tệp tin đính kèm',
  'Opened attachment': 'Đã mở tệp tin đính kèm',
  'Deleting attachment': 'Đang xóa tệp tin đính kèm',
  'Deleted attachment': 'Đã xóa tệp tin đính kèm',
  'Listing your Jira filters': 'Đang liệt kê các bộ lọc Jira của bạn',
  'Listed your Jira filters': 'Đã liệt kê các bộ lọc Jira của bạn',
  'Opening Jira filter': 'Đang mở bộ lọc Jira',
  'Opened Jira filter': 'Đã mở bộ lọc Jira',
  'Updating Jira filter': 'Đang cập nhật bộ lọc Jira',
  'Updated Jira filter': 'Đã cập nhật bộ lọc Jira',
  'Creating a Jira filter': 'Đang tạo một bộ lọc Jira',
  'Created a Jira filter': 'Đã tạo một bộ lọc Jira',
  'Looking up Jira users': 'Đang tra cứu người dùng Jira',
  'Looked up Jira users': 'Đã tra cứu người dùng Jira',
  'Listing the components of': 'Đang liệt kê các thành phần của',
  'Listed the components of': 'Đã liệt kê các thành phần của',
  'Listing the versions of': 'Đang liệt kê các phiên bản của',
  'Listed the versions of': 'Đã liệt kê các phiên bản của',
  'Listing the statuses of': 'Đang liệt kê các trạng thái của',
  'Listed the statuses of': 'Đã liệt kê các trạng thái của',
  'Reading the roles of': 'Đang đọc các vai trò của',
  'Read the roles of': 'Đã đọc các vai trò của',
  'Opening Jira project': 'Đang mở dự án Jira',
  'Opened Jira project': 'Đã mở dự án Jira',
  'Listing Jira projects': 'Đang liệt kê các dự án Jira',
  'Listed Jira projects': 'Đã liệt kê các dự án Jira',
  'Listing Jira fields': 'Đang liệt kê các trường Jira',
  'Listed Jira fields': 'Đã liệt kê các trường Jira',
  'Creating a component': 'Đang tạo một thành phần',
  'Created a component': 'Đã tạo một thành phần',
  'Creating a version': 'Đang tạo một phiên bản',
  'Created a version': 'Đã tạo một phiên bản',
  'Listing boards': 'Đang liệt kê các bảng',
  'Listed boards': 'Đã liệt kê các bảng',
  'Listing the sprints of board': 'Đang liệt kê các sprint của bảng',
  'Listed the sprints of board': 'Đã liệt kê các sprint của bảng',
  'Reading the backlog of board': 'Đang đọc backlog của bảng',
  'Read the backlog of board': 'Đã đọc backlog của bảng',
  'Listing the epics on board': 'Đang liệt kê các epic trên bảng',
  'Listed the epics on board': 'Đã liệt kê các epic trên bảng',
  'Listing the issues on board': 'Đang liệt kê các issue trên bảng',
  'Listed the issues on board': 'Đã liệt kê các issue trên bảng',
  'Opening board': 'Đang mở bảng',
  'Opened board': 'Đã mở bảng',
  'Listing the issues in sprint': 'Đang liệt kê các issue trong sprint',
  'Listed the issues in sprint': 'Đã liệt kê các issue trong sprint',
  'Moving issues into sprint': 'Đang chuyển các issue vào sprint',
  'Moved issues into sprint': 'Đã chuyển các issue vào sprint',
  'Opening sprint': 'Đang mở sprint',
  'Opened sprint': 'Đã mở sprint',
  'Deleting sprint': 'Đang xóa sprint',
  'Deleted sprint': 'Đã xóa sprint',
  'Updating sprint': 'Đang cập nhật sprint',
  'Updated sprint': 'Đã cập nhật sprint',
  'Creating a sprint': 'Đang tạo một sprint',
  'Created a sprint': 'Đã tạo một sprint',
  'Moving issues to the backlog': 'Đang chuyển các issue về backlog',
  'Moved issues to the backlog': 'Đã chuyển các issue về backlog',
  'Listing the issues in epic': 'Đang liệt kê các issue trong epic',
  'Listed the issues in epic': 'Đã liệt kê các issue trong epic',
  'Moving issues into epic': 'Đang chuyển các issue vào epic',
  'Moved issues into epic': 'Đã chuyển các issue vào epic',
  'Ranking issues': 'Đang xếp hạng các issue',
  'Ranked issues': 'Đã xếp hạng các issue',
  'Searching Confluence': 'Đang tìm trong Confluence',
  'Searched Confluence': 'Đã tìm trong Confluence',
  'Reading the labels of page': 'Đang đọc các nhãn của trang',
  'Read the labels of page': 'Đã đọc các nhãn của trang',
  'Labeling page': 'Đang gắn nhãn cho trang',
  'Labeled page': 'Đã gắn nhãn cho trang',
  'Removing a label from page': 'Đang gỡ một nhãn khỏi trang',
  'Removed a label from page': 'Đã gỡ một nhãn khỏi trang',
  'Listing the attachments of page': 'Đang liệt kê các tệp tin đính kèm của trang',
  'Listed the attachments of page': 'Đã liệt kê các tệp tin đính kèm của trang',
  'Attaching a file to page': 'Đang đính kèm tệp tin vào trang',
  'Attached a file to page': 'Đã đính kèm tệp tin vào trang',
  'Listing the child pages of page': 'Đang liệt kê các trang con của trang',
  'Listed the child pages of page': 'Đã liệt kê các trang con của trang',
  'Reading comments on page': 'Đang đọc bình luận trên trang',
  'Read comments on page': 'Đã đọc bình luận trên trang',
  'Listing the children of page': 'Đang liệt kê nội dung con của trang',
  'Listed the children of page': 'Đã liệt kê nội dung con của trang',
  'Listing the pages under page': 'Đang liệt kê các trang bên dưới trang',
  'Listed the pages under page': 'Đã liệt kê các trang bên dưới trang',
  'Reading the restrictions of page': 'Đang đọc các hạn chế của trang',
  'Read the restrictions of page': 'Đã đọc các hạn chế của trang',
  'Changing the restrictions of page': 'Đang thay đổi các hạn chế của trang',
  'Changed the restrictions of page': 'Đã thay đổi các hạn chế của trang',
  'Reading the history of page': 'Đang đọc lịch sử của trang',
  'Read the history of page': 'Đã đọc lịch sử của trang',
  'Reading the properties of page': 'Đang đọc các thuộc tính của trang',
  'Read the properties of page': 'Đã đọc các thuộc tính của trang',
  'Changing the properties of page': 'Đang thay đổi các thuộc tính của trang',
  'Changed the properties of page': 'Đã thay đổi các thuộc tính của trang',
  'Copying page': 'Đang sao chép trang',
  'Copied page': 'Đã sao chép trang',
  'Moving page': 'Đang di chuyển trang',
  'Moved page': 'Đã di chuyển trang',
  'Opening page': 'Đang mở trang',
  'Opened page': 'Đã mở trang',
  'Updating page': 'Đang cập nhật trang',
  'Updated page': 'Đã cập nhật trang',
  'Deleting page': 'Đang xóa trang',
  'Deleted page': 'Đã xóa trang',
  'Listing Confluence pages': 'Đang liệt kê các trang Confluence',
  'Listed Confluence pages': 'Đã liệt kê các trang Confluence',
  'Creating a Confluence page': 'Đang tạo một trang Confluence',
  'Created a Confluence page': 'Đã tạo một trang Confluence',
  'Watching page': 'Đang theo dõi trang',
  'Watched page': 'Đã theo dõi trang',
  'Unwatching page': 'Đang bỏ theo dõi trang',
  'Stopped watching page': 'Đã bỏ theo dõi trang',
  'Checking whether you watch page': 'Đang kiểm tra bạn có theo dõi trang',
  'Checked whether you watch page': 'Đã kiểm tra bạn có theo dõi trang',
  'Listing the pages in space': 'Đang liệt kê các trang trong không gian',
  'Listed the pages in space': 'Đã liệt kê các trang trong không gian',
  'Opening space': 'Đang mở không gian',
  'Opened space': 'Đã mở không gian',
  'Listing Confluence spaces': 'Đang liệt kê các không gian Confluence',
  'Listed Confluence spaces': 'Đã liệt kê các không gian Confluence',
  'Listing Confluence templates': 'Đang liệt kê các mẫu Confluence',
  'Listed Confluence templates': 'Đã liệt kê các mẫu Confluence',
  'Converting page content': 'Đang chuyển đổi nội dung trang',
  'Converted page content': 'Đã chuyển đổi nội dung trang',
  'Looking up Confluence users': 'Đang tra cứu người dùng Confluence',
  'Looked up Confluence users': 'Đã tra cứu người dùng Confluence',
  'Calling {product}': 'Đang gọi {product}',
  'Called {product}': 'Đã gọi {product}',
  'Reading {what} from {product}': 'Đang đọc {what} từ {product}',
  'Read {what} from {product}': 'Đã đọc {what} từ {product}',
  'Sending {what} to {product}': 'Đang gửi {what} tới {product}',
  'Sent {what} to {product}': 'Đã gửi {what} tới {product}',
  'Updating {what} in {product}': 'Đang cập nhật {what} trong {product}',
  'Updated {what} in {product}': 'Đã cập nhật {what} trong {product}',
  'Deleting {what} from {product}': 'Đang xóa {what} khỏi {product}',
  'Deleted {what} from {product}': 'Đã xóa {what} khỏi {product}',
  // Result summaries, where the count is the news.
  'Searched your sources, nothing matched': 'Đã tìm trong nguồn dữ liệu của bạn, không có kết quả phù hợp',
  'Searched {count} source|Searched {count} sources': 'Đã tìm trong {count} nguồn dữ liệu',
  '{count} source|{count} sources': '{count} nguồn',
  'Source': 'Nguồn',
  'Searched the web, nothing matched': 'Đã tìm kiếm trên web, không có kết quả phù hợp',
  'Searched the web, {count} result|Searched the web, {count} results': 'Đã tìm kiếm trên web, {count} kết quả',
  'Created {key}': 'Đã tạo {key}',
  // The one tool display-name override: "Delegate" names the mechanism, "Agent" what is seen.
  'Agent': 'Tác nhân',

  // -- the Sources page -----------------------------------------------------------
  'All': 'Tất cả',
  'Files': 'Tệp tin',
  'Web pages': 'Trang web',
  'Upload files': 'Tải tệp tin lên',
  'Local folder': 'Thư mục trên máy',
  'Jira project': 'Dự án Jira',
  'Jira projects': 'Dự án Jira',
  'Confluence space': 'Không gian Confluence',
  'Confluence spaces': 'Không gian Confluence',
  'Bitbucket repository': 'Kho mã Bitbucket',
  'SVN repository': 'Kho mã SVN',
  'Search source title': 'Tìm theo tên nguồn dữ liệu',
  'Clear search': 'Xóa từ khóa tìm kiếm',
  'Loading more...': 'Đang tải thêm...',
  'No sources yet. Add a folder, a web page or a project, and what it holds becomes searchable in chat.': 'Chưa có nguồn dữ liệu nào. Hãy thêm một thư mục, một trang web hoặc một dự án, và nội dung của nó sẽ tìm được trong trò chuyện.',
  'No sources match "{query}".': 'Không có nguồn dữ liệu nào có tên khớp với "{query}".',
  'Drop files to upload': 'Thả tệp tin vào đây để tải lên',
  'Drop a folder to index': 'Thả một thư mục vào đây để lập chỉ mục',
  // The count is rendered beside these in its own readout span, so only the noun is here and
  // `count` selects the form without being substituted into it.
  'source|sources': 'nguồn dữ liệu',
  'document|documents': 'tài liệu',
  'on disk': 'trên đĩa',
  'Index {index}, uploaded files {uploads}': 'Chỉ mục {index}, tệp đã tải lên {uploads}',
  // IDENTITY ENTRIES, and deliberately so. These are product names that must not be translated,
  // but they pass through t() as variables from TYPE_LABELS and FILTER_TABS, so without an entry
  // each would be reported as untranslated forever. Writing them out is how the catalog records
  // that somebody read them and decided they stay - a missing entry records nothing.
  'Jira': 'Jira',
  'Confluence': 'Confluence',
  'Bitbucket': 'Bitbucket',
  'SVN': 'SVN',
  'SharePoint': 'SharePoint',

  // -- configuring one source ------------------------------------------------------
  'Configure {name}': 'Cấu hình {name}',
  'Save': 'Lưu',
  'Saving...': 'Đang lưu...',
  'Save and re-sync': 'Lưu và đồng bộ lại',
  'Configuration saved - re-syncing this connector':
    'Đã lưu cấu hình - đang đồng bộ lại bộ kết nối này',
  'Failed to save configuration': 'Không thể lưu cấu hình',
  'Failed to change the knowledge-graph setting': 'Không thể thay đổi cài đặt đồ thị tri thức',
  'Saving re-syncs this connector in full and drops anything the new scope excludes.':
    'Việc lưu sẽ đồng bộ lại toàn bộ bộ kết nối này và gỡ khỏi chỉ mục mọi thứ phạm vi mới loại trừ.',
  'Scan subdirectories recursively': 'Quét cả các thư mục con',
  // Web crawl scope.
  'Starting URL': 'URL bắt đầu',
  'Crawl depth': 'Độ sâu thu thập',
  'Max pages': 'Số trang tối đa',
  'Link hops from the starting URL (max {max})': 'Số bước liên kết tính từ URL bắt đầu (tối đa {max})',
  'Hard ceiling on the crawl (max {max})': 'Giới hạn cứng cho lần thu thập (tối đa {max})',
  'Only follow URLs matching': 'Chỉ đi theo các URL khớp với',
  'Optional. Leave empty to follow every in-scope link.':
    'Không bắt buộc. Để trống để đi theo mọi liên kết trong phạm vi.',
  'Stay on the same domain': 'Chỉ ở trong cùng một tên miền',
  'Render JavaScript': 'Kết xuất JavaScript',
  'Slower. Needed for client-rendered sites.':
    'Chậm hơn. Cần thiết cho các trang web kết xuất phía trình duyệt.',
  'Crawling {pages} pages takes hours, longer with JavaScript rendering. Indexing starts with the first page found.':
    'Thu thập {pages} trang mất vài giờ, lâu hơn nếu bật kết xuất JavaScript. Việc lập chỉ mục bắt đầu ngay từ trang đầu tiên tìm được.',
  // Per-source connection override.
  'Connection': 'Kết nối',
  // The knowledge graph opt-in - the one setting here that costs money.
  'Build a knowledge graph': 'Xây dựng đồ thị tri thức',
  'Maps people, systems and how they connect. One AI call per passage, so indexing costs two to three times as much.':
    'Lập bản đồ con người, hệ thống và cách chúng liên kết. Mỗi đoạn tốn một lượt gọi AI, nên chi phí lập chỉ mục tăng gấp hai đến gấp ba.',
  'Runs in the background after you save, over what is already indexed. Search is unaffected.': 'Chạy nền sau khi bạn lưu, trên những gì đã được lập chỉ mục. Việc tìm kiếm không bị ảnh hưởng.',
  'Knowledge-graph extraction is off for this deployment. Turn it on under Settings > Search.': 'Việc trích xuất đồ thị tri thức đang tắt trên bản triển khai này. Hãy bật nó trong Cài đặt > Tìm kiếm.',
  'No model is set for knowledge-graph extraction, so nothing will be extracted. Choose one under Settings > Search.': 'Chưa đặt mô hình cho việc trích xuất đồ thị tri thức, nên sẽ không có gì được trích xuất. Hãy chọn một mô hình trong Cài đặt > Tìm kiếm.',
  'Turning this off deletes what this source has contributed to the graph. Its index and search are not affected.':
    'Tắt tùy chọn này sẽ xóa những gì nguồn dữ liệu này đã đóng góp vào đồ thị. Chỉ mục và việc tìm kiếm của nguồn không bị ảnh hưởng.',
  '{entities} entities, {relations} relationships': '{entities} thực thể, {relations} mối quan hệ',
  'Passages in the item being read': 'Các đoạn của mục đang đọc',
  'The knowledge-graph model at {host} is not answering. It is asked again at {time}.':
    'Mô hình đồ thị tri thức tại {host} không phản hồi. Hệ thống sẽ thử lại lúc {time}.',
  'The knowledge-graph model is not answering. It is asked again at {time}.':
    'Mô hình đồ thị tri thức không phản hồi. Hệ thống sẽ thử lại lúc {time}.',
  'Last error: {reason}': 'Lỗi gần nhất: {reason}',
  'The request went through the proxy - add the host to No proxy if it is on your network.':
    'Yêu cầu đã đi qua proxy - nếu máy chủ này nằm trong mạng nội bộ, hãy thêm nó vào mục Không dùng proxy.',

  // -- registering a source ---------------------------------------------------------
  'Add': 'Thêm',
  'Add a folder': 'Thêm thư mục',
  'Actions for {title}': 'Thao tác cho {title}',
  'Add a Bitbucket repository': 'Thêm kho mã Bitbucket',
  'Add a Confluence space': 'Thêm không gian Confluence',
  'Add a Jira project': 'Thêm dự án Jira',
  'Add a network share': 'Thêm thư mục chia sẻ mạng',
  'Add a web page': 'Thêm trang web',
  'Add an SVN repository': 'Thêm kho mã SVN',
  'Error message copied': 'Đã sao chép thông báo lỗi',
  'Filter sources by type': 'Lọc nguồn dữ liệu theo loại',
  'Indexed and in the knowledge graph': 'Đã lập chỉ mục và có trong đồ thị tri thức',
  'Items': 'Mục',
  'Last sync': 'Đồng bộ cuối',
  'More actions': 'Thao tác khác',
  'Network shares': 'Thư mục chia sẻ mạng',
  'Not everything was indexed': 'Chưa lập chỉ mục được tất cả',
  'Not synced yet': 'Chưa đồng bộ',
  'Processed first': 'Được xử lý trước',
  'SVN repositories': 'Kho mã SVN',
  'Status': 'Trạng thái',
  'Sync failed': 'Đồng bộ thất bại',
  'Syncing...': 'Đang đồng bộ...',
  'View {count} more': 'Xem thêm {count}',
  'in the knowledge graph': 'có trong đồ thị tri thức',
  'Add anyway': 'Vẫn thêm',
  'Failed to register': 'Không thể đăng ký',
  'Already added by someone else': 'Người khác đã thêm nguồn này',
  'Required': 'Bắt buộc',
  'Skip': 'Bỏ qua',
  '{type} added - indexing started': 'Đã thêm {type} - bắt đầu lập chỉ mục',
  // This source's own server and credential, optional at registration.
  'Server URL': 'URL máy chủ',
  'Access token': 'Access token',
  'Open Settings -> Connections': 'Mở Cài đặt -> Kết nối',
  // The per-type field labels. Placeholders beside them are format examples and stay English.
  'Project key': 'Mã dự án',
  'Space key': 'Mã không gian',
  'Repository slug': 'Slug kho mã',
  'Repository path': 'Đường dẫn kho mã',
  'Name (optional)': 'Tên (không bắt buộc)',
  "Defaults to the page's own title": 'Mặc định lấy theo tiêu đề của chính trang đó',
  'Max depth': 'Độ sâu tối đa',
  // The type names offered when registering.
  'Bitbucket repositories': 'Kho mã Bitbucket',
  // The credential links and what each connector's credential is called.

  // -- adding a local folder (desktop) ------------------------------------------------
  'Add a local folder': 'Thêm thư mục trên máy',
  'Add folder': 'Thêm thư mục',
  'Adding...': 'Đang thêm...',
  'Folder path': 'Đường dẫn thư mục',
  'Browse...': 'Duyệt...',
  'Waiting...': 'Đang chờ...',
  'Choose a folder first': 'Hãy chọn một thư mục trước',
  'Failed to add folder': 'Không thể thêm thư mục',
  'Folder added - indexing started': 'Đã thêm thư mục - bắt đầu lập chỉ mục',
  'Could not open the folder chooser. Type or paste a folder path below instead.':
    'Không thể mở hộp thoại chọn thư mục. Hãy gõ hoặc dán đường dẫn thư mục vào bên dưới.',
  'Proxyma indexes these files where they are. Nothing is copied, moved, or sent anywhere.':
    'Proxyma lập chỉ mục các tệp tin này ngay tại chỗ. Không có gì bị sao chép, di chuyển hay gửi đi đâu cả.',
  'Finding that folder on this machine...': 'Đang tìm thư mục đó trên máy này...',
  'Drop a folder rather than individual files - Proxyma indexes a whole folder where it is. Or choose one with Browse.':
    'Hãy thả một thư mục thay vì các tệp riêng lẻ - Proxyma lập chỉ mục cả thư mục ngay tại chỗ. Hoặc chọn một thư mục bằng nút Duyệt.',
  'Could not find "{name}" on this machine. Choose it with Browse, or paste its path.':
    'Không tìm thấy "{name}" trên máy này. Hãy chọn nó bằng nút Duyệt, hoặc dán đường dẫn của nó.',
  'Could not find that folder on this machine. Choose it with Browse, or paste its path.':
    'Không tìm thấy thư mục đó trên máy này. Hãy chọn nó bằng nút Duyệt, hoặc dán đường dẫn của nó.',
  'Checking folder...': 'Đang kiểm tra thư mục...',
  'No folder exists at that path.': 'Không có thư mục nào ở đường dẫn đó.',
  'That path is a file, not a folder.': 'Đường dẫn đó là một tệp tin, không phải thư mục.',
  'Proxyma cannot read that folder.': 'Proxyma không thể đọc thư mục đó.',
  'Nothing directly in this folder, but there are supported files in its subfolders.':
    'Không có gì ngay trong thư mục này, nhưng có các tệp tin được hỗ trợ trong các thư mục con của nó.',
  'No supported files found. Only the file types highlighted below are indexed.':
    'Không tìm thấy tệp tin được hỗ trợ nào. Chỉ các loại tệp tin được đánh dấu bên dưới mới được lập chỉ mục.',
  'No supported files found directly in this folder. Only the file types highlighted below are indexed.':
    'Không tìm thấy tệp tin được hỗ trợ nào ngay trong thư mục này. Chỉ các loại tệp tin được đánh dấu bên dưới mới được lập chỉ mục.',

  // -- the file-type picker -------------------------------------------------------------
  'File types to index': 'Các loại tệp tin cần lập chỉ mục',
  'Index every supported type': 'Lập chỉ mục mọi loại được hỗ trợ',
  'Other file types': 'Các loại tệp tin khác',
  'Comma-separated. Anything with no dedicated converter is indexed as plain text.':
    'Phân tách bằng dấu phẩy. Bất cứ loại nào không có bộ chuyển đổi riêng sẽ được lập chỉ mục dưới dạng văn bản thuần.',
  'Office documents': 'Tài liệu văn phòng',
  'Text and data': 'Văn bản và dữ liệu',
  'Email and e-books': 'Email và sách điện tử',
  'Code and scripts': 'Mã nguồn và script',
  'Engineering data': 'Dữ liệu kỹ thuật',

  // -- uploading files (server) ----------------------------------------------------------
  // 'Upload Files' is NOT repeated here - it is the add-source menu label above, and the upload
  // modal's own title is the same English string. One entry serves both; the checker caught the
  // duplicate, where the later would have won and this one been dead.
  'Upload ({count})': 'Tải lên ({count})',
  'Click or drop files/folders here': 'Nhấn hoặc thả tệp tin/thư mục vào đây',
  'Select or create a folder...': 'Chọn hoặc tạo một thư mục...',
  'Select or create a destination folder': 'Chọn hoặc tạo một thư mục đích',
  'Press Enter or pick a suggestion to confirm this folder':
    'Nhấn Enter hoặc chọn một gợi ý để xác nhận thư mục này',
  'Checking files...': 'Đang kiểm tra tệp tin...',
  'All files uploaded successfully': 'Đã tải lên thành công tất cả tệp tin',
  'Upload cancelled': 'Đã hủy tải lên',
  'Indexing blocked': 'Lập chỉ mục bị chặn',
  'The uploaded files have been removed from the server.':
    'Các tệp tin đã tải lên đã được gỡ khỏi máy chủ.',
  'Cancel upload': 'Hủy tải lên',
  'Continue': 'Tiếp tục',
  'Continue without the failed files': 'Tiếp tục, bỏ qua tệp tin lỗi',
  'Done': 'Xong',
  'Remove from server': 'Gỡ khỏi máy chủ',
  'Matching files found': 'Đã tìm thấy tệp tin trùng khớp',
  'Duplicate files found': 'Đã tìm thấy tệp tin trùng lặp',
  'Skip all': 'Bỏ qua tất cả',
  'Overwrite': 'Ghi đè',
  'Overwrite all': 'Ghi đè tất cả',
  'Upload anyway': 'Vẫn tải lên',
  'Upload all anyway': 'Vẫn tải lên tất cả',
  'Resolve every file above before continuing': 'Hãy xử lý mọi tệp tin ở trên trước khi tiếp tục',
  'Could not start indexing for this folder.': 'Không thể bắt đầu lập chỉ mục cho thư mục này.',
  'Failed to register folder: {reason}': 'Không thể đăng ký thư mục: {reason}',
  'Unknown error': 'Lỗi không xác định',

  // -- a source card and its detail view ---------------------------------------------------
  'Drop here': 'Thả vào đây',
  'Could not create the share link': 'Không thể tạo liên kết chia sẻ',
  'Could not enable {connector} write tools': 'Không thể bật công cụ ghi của {connector}',
  'Could not load the call history': 'Không thể tải lịch sử lệnh gọi',
  'Clear log': 'Xóa nhật ký',
  'Clear the call log?': 'Xóa nhật ký lệnh gọi?',
  'Every model call recorded so far is deleted, in every period.': 'Mọi lệnh gọi mô hình đã ghi đến nay đều bị xóa, trong mọi khoảng thời gian.',
  'The costs, charts and breakdowns on this page start again from nothing.': 'Chi phí, biểu đồ và các bảng phân tích trên trang này bắt đầu lại từ đầu.',
  'The monthly spending limit counts these calls, so this month is counted again from zero.': 'Giới hạn chi tiêu hàng tháng đếm các lệnh gọi này, nên tháng này được đếm lại từ 0.',
  "The deployment's monthly limit counts these calls, so this month is counted again from zero. Each user's own quota is not reset.": 'Giới hạn hàng tháng của hệ thống đếm các lệnh gọi này, nên tháng này được đếm lại từ 0. Hạn mức riêng của từng người dùng không bị đặt lại.',
  'Call log cleared': 'Đã xóa nhật ký lệnh gọi',
  'Could not clear the call log': 'Không thể xóa nhật ký lệnh gọi',
  'Could not send your decision': 'Không thể gửi quyết định của bạn',
  'Failed to save: {reason}': 'Lưu thất bại: {reason}',
  'Redacted by a guardrail': 'Đã được rào chắn che bớt',
  'Redacted by a guardrail: {reasons}': 'Đã được rào chắn che bớt: {reasons}',
  'This request is no longer waiting for an answer.': 'Yêu cầu này không còn chờ câu trả lời nữa.',
  'Asking you': 'Đang hỏi bạn',
  'Asked you': 'Đã hỏi bạn',
  'Could not send your answer': 'Không thể gửi câu trả lời của bạn',
  'Other - type your own answer': 'Khác - nhập câu trả lời của bạn',
  'Pick any that apply.': 'Chọn tất cả các mục phù hợp.',
  'Question from the assistant': 'Câu hỏi từ trợ lý',
  'Questions from the assistant': 'Các câu hỏi từ trợ lý',
  'The assistant has {count} questions': 'Trợ lý có {count} câu hỏi',
  'Question {number}': 'Câu hỏi {number}',
  'Send answer': 'Gửi câu trả lời',
  'Sending your answer': 'Đang gửi câu trả lời',
  'Folder': 'Thư mục',
  'Relink folder': 'Liên kết lại thư mục',
  'Moved or renamed the folder? Choose where it is now. The source keeps its settings and is indexed again from there.':
    'Đã di chuyển hoặc đổi tên thư mục? Hãy chọn vị trí hiện tại của nó. Nguồn dữ liệu giữ nguyên các thiết lập và được lập chỉ mục lại từ đó.',
  'Folder relinked - re-indexing it from its new location': 'Đã liên kết lại thư mục - đang lập chỉ mục lại từ vị trí mới',
  'Failed to relink the folder': 'Không thể liên kết lại thư mục',
  'Could not open the folder chooser.': 'Không thể mở hộp thoại chọn thư mục.',
  'Share with others': 'Chia sẻ với người khác',
  'Stop sharing': 'Ngừng chia sẻ',
  'Process first': 'Xử lý trước',
  'Remove priority': 'Bỏ ưu tiên',
  'Sync now': 'Đồng bộ ngay',
  'Retry all failed items': 'Thử lại tất cả các mục đã lỗi',
  'Re-index': 'Lập chỉ mục lại',
  'Configure file types and subfolder scanning': 'Cấu hình loại tệp tin và việc quét thư mục con',
  'Configure this source': 'Cấu hình nguồn này',
  '"{name}" is already one of your sources. Open it on the Sources page to sync it or change its settings.': '"{name}" đã là một nguồn của bạn. Hãy mở nó ở trang Nguồn dữ liệu để đồng bộ hoặc thay đổi thiết lập.',
  'Configure crawl depth and page limit': 'Cấu hình độ sâu thu thập và giới hạn số trang',
  'Delete connector': 'Xóa bộ kết nối',
  'Delete this file': 'Xóa tệp tin này',
  'Copy error message': 'Sao chép thông báo lỗi',
  'Failed to delete "{title}"': 'Không thể xóa "{title}"',
  'No items yet': 'Chưa có mục nào',
  'Discovering items...': 'Đang tìm các mục...',
  'Storage quota exceeded - processing resumes automatically once usage drops back under the limit':
    'Đã vượt hạn mức lưu trữ - quá trình xử lý sẽ tự động tiếp tục khi mức sử dụng giảm xuống dưới giới hạn',
  // One tracked item's indexing status.
  'Detected': 'Đã phát hiện',
  'Downloading': 'Đang tải xuống',
  'Embedding': 'Vector nhúng',
  'Modified': 'Đã thay đổi',
  // Connector-wide operations in progress.
  'Stopping sync...': 'Đang dừng đồng bộ...',
  'Clearing index...': 'Đang xóa chỉ mục...',
  'Deleting data...': 'Đang xóa dữ liệu...',

  // -- confirming a deletion ----------------------------------------------------------------
  'Delete source': 'Xóa nguồn dữ liệu',
  // Used as {verb} in a confirmation: "You are about to {verb}:" (DeleteConfirmModal) and "Are you sure you want to {verb}" (Settings).
  'delete': 'xóa',
  'Are you sure you want to {verb}': 'Bạn có chắc chắn muốn {verb}',
  'Type "{value}" to confirm': 'Gõ "{value}" để xác nhận',
  'The source and all its items will be permanently deleted from the server': 'Nguồn dữ liệu và mọi mục của nó sẽ bị xóa vĩnh viễn khỏi máy chủ',
  'All associated vector embeddings will be removed from the vector database':
    'Mọi vector nhúng (embeddings) liên quan sẽ bị gỡ khỏi cơ sở dữ liệu vector',
  'Your own files and folders are NOT touched - nothing is moved, changed or deleted on your disk':
    'Các tệp tin và thư mục của riêng bạn KHÔNG bị đụng tới - không có gì bị di chuyển, thay đổi hay xóa trên ổ đĩa của bạn',
  'Proxyma stops indexing this source and forgets what it had indexed from it':
    'Proxyma ngừng lập chỉ mục nguồn dữ liệu này và quên những gì đã lập chỉ mục từ nó',

  // -- Administration: the tabs and the system view ------------------------------------
  'System': 'Hệ thống',
  'Users': 'Người dùng',
  'Activity': 'Hoạt động',
  'Logs': 'Nhật ký',
  'Use Proxyma from Claude, ChatGPT or Gemini, and on Desktop in a window of its own.': 'Dùng Proxyma từ Claude, ChatGPT hoặc Gemini, và trên Desktop trong một cửa sổ riêng.',
  'Settings > AI Assistants connects the assistant you already pay for to your sources.': 'Cài đặt > Trợ lý AI kết nối trợ lý bạn đang trả phí với các nguồn dữ liệu của bạn.',
  'Desktop: Proxyma opens in its own window instead of a browser tab.': 'Desktop: Proxyma mở trong cửa sổ riêng thay vì một thẻ trình duyệt.',
  'A thinking model shows "Thinking" with a token count or the seconds so far.': 'Mô hình có suy nghĩ hiển thị "Đang suy nghĩ" kèm số token hoặc số giây đã trôi qua.',
  'Clearer costs, a faster graph view, and conversations that keep their history.': 'Chi phí rõ ràng hơn, xem đồ thị nhanh hơn, và cuộc trò chuyện giữ nguyên lịch sử.',
  'Usage & Cost charts cost over time and lists every call, cache use included.': 'Mức sử dụng & Chi phí có biểu đồ chi phí theo thời gian và liệt kê mọi lượt gọi, kể cả việc dùng bộ nhớ đệm.',
  'Condensing a conversation keeps every message on screen.': 'Thu gọn cuộc trò chuyện vẫn giữ mọi tin nhắn trên màn hình.',
  'Settings > Agents can keep an Anthropic conversation cached for 5 minutes or 1 hour.': 'Cài đặt > Tác nhân có thể giữ cuộc trò chuyện Anthropic trong bộ nhớ đệm 5 phút hoặc 1 giờ.',
  'Nothing to condense yet - the conversation needs a few more exchanges.': 'Chưa có gì để thu gọn - cuộc trò chuyện cần thêm vài lượt trao đổi.',
  'Prompt cache': 'Bộ nhớ đệm prompt',
  '5 minutes': '5 phút',
  '1 hour': '1 giờ',
  'How long the provider caches the conversation for the next turn. Auto keeps 5 minutes, and 1 hour while sub-agents work in the background or when you pause longer between turns. 1 hour costs more to write.': 'Thời gian nhà cung cấp lưu cache phần hội thoại cho lượt tiếp theo. Tự động giữ 5 phút, và 1 giờ khi tác nhân phụ đang chạy nền hoặc khi bạn nghỉ lâu hơn giữa các lượt. Ghi cache 1 giờ đắt hơn.',
  'Sub-agent prompt cache': 'Bộ nhớ đệm prompt của tác nhân phụ',
  'Auto is 5 minutes for a sub-agent, whose calls come seconds apart.': 'Với tác nhân phụ, Tự động là 5 phút, vì các lệnh gọi của nó chỉ cách nhau vài giây.',
  'Cache hit': 'Đọc từ cache',
  'Cache write': 'Ghi vào cache',
  'Cost per month': 'Chi phí theo tháng',
  'Each model call in the period, newest first. A column heading sorts by that column.': 'Từng lượt gọi mô hình trong khoảng thời gian này, mới nhất trước. Bấm tiêu đề cột để sắp xếp theo cột đó.',
  'All services': 'Tất cả dịch vụ',
  'Sort by': 'Sắp xếp theo',
  'Newest first': 'Mới nhất trước',
  'Oldest first': 'Cũ nhất trước',
  'Highest cost first': 'Chi phí cao nhất trước',
  'Most input tokens first': 'Nhiều token đầu vào nhất trước',
  'Most output tokens first': 'Nhiều token đầu ra nhất trước',
  'All models': 'Tất cả mô hình',
  'Search service, model or provider': 'Tìm dịch vụ, mô hình hoặc nhà cung cấp',
  'Search service, model, provider or who': 'Tìm dịch vụ, mô hình, nhà cung cấp hoặc người dùng',
  'Search the log': 'Tìm trong nhật ký',
  'No calls in this period match.': 'Không có lượt gọi nào trong khoảng thời gian này khớp.',
  'Search...': 'Tìm...',
  'No matches': 'Không có kết quả khớp',
  'No rate set for this model, so it counts as $0.': 'Mô hình này chưa có đơn giá nên được tính là $0.',
  'Peak': 'Cao nhất',
  // Presence, shown as a tooltip on the dot beside a user.
  // -- Administration: the danger zone ----------------------------------------------------
  'Danger Zone': 'Vùng nguy hiểm',
  'Reset Users': 'Đặt lại người dùng',
  // -- Administration: the users table -----------------------------------------------------
  'User': 'Người dùng',
  'Active time': 'Thời gian hoạt động',
  'Storage': 'Dung lượng',
  'Storage quota': 'Hạn mức dung lượng',
  'Load more': 'Tải thêm',
  // -- Administration: backups ---------------------------------------------------------------
  'Backup stopped': 'Đã dừng sao lưu',
  'Backup deleted': 'Đã xóa bản sao lưu',
  'Time': 'Thời gian',
  'File': 'Tệp tin',
  'Size': 'Kích thước',
  'Skipped': 'Đã bỏ qua',

  // -- Administration: maintenance -------------------------------------------------------------
  // -- Administration: feedback -------------------------------------------------------------
  'Conversation': 'Cuộc trò chuyện',
  'Attachments': 'Tệp tin đính kèm',
  // The three feedback moods.
  'Bad': 'Chưa tốt',
  'Neutral': 'Trung lập',
  'Good': 'Tốt',

  // -- Administration: the cost page ----------------------------------------------------------
  'Last 7 days': '7 ngày qua',
  'Last 30 days': '30 ngày qua',
  'Last 90 days': '90 ngày qua',
  'Cost per day': 'Chi phí mỗi ngày',
  'Workflow steps': 'Các bước quy trình',
  'Home-screen fact': 'Thông tin trên màn hình chính',
  // What a platform AI call was FOR. Two English wordings exist for the same idea on two
  // different screens ("Indexing & search" and "Indexing and search"); both are kept as separate
  // keys because the key IS the English, and both land on the same Vietnamese.
  'Indexing and search': 'Lập chỉ mục và tìm kiếm',
  'Reading scans': 'Đọc bản quét',
  'Memory condensation': 'Cô đọng bộ nhớ',
  'Knowledge graph': 'Đồ thị tri thức',
  'Delegated work': 'Công việc được ủy thác',
  'Conversation titles': 'Tiêu đề cuộc trò chuyện',
  'Query expansion': 'Mở rộng truy vấn',
  'Reranking': 'Xếp hạng lại',

  // -- sending feedback ---------------------------------------------------------------------
  'How was it?': 'Bạn thấy thế nào?',
  'Select how you feel first': 'Hãy chọn cảm nhận của bạn trước',
  "Tell us what's on your mind (optional)": 'Hãy cho chúng tôi biết bạn đang nghĩ gì (không bắt buộc)',
  'Additional comments (optional)': 'Bình luận thêm (không bắt buộc)',
  'Email (optional)': 'Email (không bắt buộc)',
  'Include my latest log file': 'Đính kèm tệp tin nhật ký mới nhất của tôi',
  'Include this conversation': 'Đính kèm cuộc trò chuyện này',
  'Can contain file, folder and connector names, errors, settings and the words of searches from this machine. Not the contents of your documents.':
    'Có thể chứa tên tệp tin, thư mục và bộ kết nối, các lỗi, thiết lập và từ khóa tìm kiếm trên máy này. Không bao gồm nội dung tài liệu của bạn.',
  'Your questions and the answers as written, including text quoted from your documents.':
    'Các câu hỏi của bạn và câu trả lời đúng như đã hiển thị, bao gồm cả đoạn trích từ tài liệu của bạn.',
  'How feedback data is handled': 'Cách dữ liệu góp ý được xử lý',
  'Thanks for your feedback!': 'Cảm ơn góp ý của bạn!',
  'Failed to send feedback. Please try again.': 'Không thể gửi góp ý. Vui lòng thử lại.',
  'You can attach up to {max} files.': 'Bạn có thể đính kèm tối đa {max} tệp tin.',
  'Only {max} files can be attached at a time - some were skipped.':
    'Mỗi lần chỉ đính kèm được {max} tệp tin - một số đã bị bỏ qua.',
  'Over the {limit} limit for all files together: {files}': 'Vượt giới hạn {limit} cho tất cả các tệp: {files}',
  'Never show again': 'Không hiển thị lại',
  'Submit': 'Gửi',
  'Got it': 'Đã hiểu',
  // The accessible name of one rating button: the question, then the mood.

  // -- shared controls ------------------------------------------------------------------------
  'Loading models...': 'Đang tải danh sách mô hình...',
  'Failed to load models': 'Không thể tải danh sách mô hình',
  'No models found': 'Không tìm thấy mô hình nào',
  'Show value': 'Hiện giá trị',
  'Hide value': 'Ẩn giá trị',
  'Reveal value': 'Hiện giá trị',
  'All release notes': 'Toàn bộ ghi chú phát hành',

  // -- stopping and restarting -----------------------------------------------------------------
  'Quit Proxyma?': 'Thoát Proxyma?',
  'Indexing and any chat in progress will stop until you start Proxyma again. Nothing already indexed is lost.':
    'Việc lập chỉ mục và mọi cuộc trò chuyện đang diễn ra sẽ dừng cho tới khi bạn khởi động lại Proxyma. Những gì đã lập chỉ mục không bị mất.',
  'Restarting': 'Đang khởi động lại',
  'Starting': 'Đang khởi động',
  'This is taking a little longer than usual. Please wait.': 'Quá trình này lâu hơn thường lệ một chút. Vui lòng chờ.',

  // ===========================================================================================
  // SETTINGS. The largest surface in the app - one modal, ~50 components, 4,700 lines.
  // ===========================================================================================

  // -- the settings shell -----------------------------------------------------------------
  'View only - system configuration applies to the whole deployment, so only a super admin can change it.':
    'Chỉ xem - cấu hình hệ thống áp dụng cho toàn bộ hệ thống, nên chỉ quản trị viên cấp cao mới có thể thay đổi.',
  'Settings saved. A restart is required for changes to take effect.':
    'Đã lưu thiết lập. Cần khởi động lại để các thay đổi có hiệu lực.',
  'Restart now': 'Khởi động lại ngay',
  'Restart later': 'Khởi động lại sau',
  'Restart the application now to apply the new settings immediately':
    'Khởi động lại ứng dụng ngay để áp dụng thiết lập mới lập tức',
  'Dismiss this banner. Changes will take effect on the next restart':
    'Bỏ qua thông báo này. Các thay đổi sẽ có hiệu lực ở lần khởi động lại tiếp theo',
  'Save anyway': 'Vẫn lưu',
  'Not now': 'Để sau',
  'Next': 'Tiếp theo',
  'Loading...': 'Đang tải...',
  'Checking...': 'Đang kiểm tra...',
  'Edit': 'Sửa',
  'Name': 'Tên',
  'Value': 'Giá trị',
  'Header': 'Header',
  'Model': 'Mô hình',
  'Detail': 'Chi tiết',
  'Context': 'Ngữ cảnh',
  'Mode': 'Chế độ',
  'Steps': 'Các bước',
  'Tags': 'Thẻ',
  'Tools': 'Công cụ',
  'Usage': 'Mức sử dụng',
  'History': 'Lịch sử',
  'Privacy': 'Quyền riêng tư',
  'Documentation': 'Tài liệu hướng dẫn',
  'Walkthrough': 'Hướng dẫn nhanh',
  'Display': 'Hiển thị',
  'Show a space fact on the empty chat': 'Hiện một điều thú vị về vũ trụ khi cuộc trò chuyện còn trống',
  'A short astronomy fact under the logo on a new chat. Saved in this browser only.': 'Một điều ngắn gọn về thiên văn dưới logo của cuộc trò chuyện mới. Chỉ lưu trong trình duyệt này.',
  'Proxima Centauri, a small red dwarf about 4.25 light-years away, is the nearest star to the Sun. Its Latin name means the nearest star of Centaurus.': 'Proxima Centauri, một sao lùn đỏ nhỏ cách chúng ta khoảng 4,25 năm ánh sáng, là ngôi sao gần Mặt Trời nhất. Tên Latinh của nó có nghĩa là ngôi sao gần nhất của chòm Bán Nhân Mã.',
  'Active': 'Đang hoạt động',
  'Succeeded': 'Thành công',
  'Median': 'Trung vị',
  'Slowest': 'Chậm nhất',
  'Desktop': 'Máy tính',
  'Home Server': 'Home Server',
  'Enterprise Server': 'Enterprise Server',

  // -- Account tab --------------------------------------------------------------------------
  'Signing out...': 'Đang đăng xuất...',
  'Failed to request account deletion.': 'Không thể gửi yêu cầu xóa tài khoản.',
  'Session token quota': 'Hạn mức token phiên hiện tại',
  'Weekly token quota': 'Hạn mức token hàng tuần',
  'Monthly token quota': 'Hạn mức token hàng tháng',
  'Global quota': 'Hạn mức toàn hệ thống',
  'Spending limit': 'Giới hạn chi tiêu',
  'Reset day': 'Ngày đặt lại',
  'These costs are estimates.': 'Các chi phí này là ước tính.',

  // -- About tab ----------------------------------------------------------------------------
  'About Proxyma': 'Giới thiệu về Proxyma',
  'What Proxyma does, in a few short stops, under a minute.': 'Proxyma làm được gì, qua vài điểm dừng ngắn, chưa đến một phút.',
  'Replay walkthrough': 'Xem lại hướng dẫn',
  'Skip tour': 'Bỏ qua hướng dẫn',
  'Open-source licenses': 'Giấy phép mã nguồn mở',
  'View licenses': 'Xem giấy phép',
  'The open-source software in Proxyma, the license each part is under, and how to get the source code of the GPL and LGPL parts.': 'Phần mềm mã nguồn mở có trong Proxyma, giấy phép của từng phần, và cách nhận mã nguồn của các phần theo GPL và LGPL.',
  'Every third-party component Proxyma contains or downloads, its license, and how to get the source code of the GPL and LGPL ones. The same text is THIRD-PARTY-NOTICES.txt in the installation folder.': 'Mọi thành phần của bên thứ ba mà Proxyma chứa hoặc tải về, giấy phép của từng thành phần, và cách nhận mã nguồn của các thành phần theo GPL và LGPL. Nội dung này cũng nằm trong tệp THIRD-PARTY-NOTICES.txt ở thư mục cài đặt.',
  'Could not load the notices. Try again in a moment.': 'Không tải được thông báo giấy phép. Hãy thử lại sau giây lát.',
  'This build does not include the open-source notices. A release build always does.': 'Bản dựng này không kèm thông báo giấy phép mã nguồn mở. Bản phát hành luôn có.',
  // The 2026-10 kit rebuild: strings the migrated dialogs gained.
  'Already in your connector "{name}"': 'Đã có trong nguồn "{name}" của bạn',
  'Create "{name}"': 'Tạo "{name}"',
  'No folders yet': 'Chưa có thư mục nào',
  'Not enough storage remaining - selected files total {total}, only {left} left. Remove some files to continue.': 'Không đủ dung lượng lưu trữ - các tệp đã chọn tổng cộng {total}, chỉ còn {left}. Hãy bỏ bớt một số tệp để tiếp tục.',
  'Over {shown} files to index': 'Hơn {shown} tệp cần lập chỉ mục',
  'Retry ({count})': 'Thử lại ({count})',
  'Shared by another user in "{name}"': 'Đã được người dùng khác chia sẻ trong "{name}"',
  'Upload progress': 'Tiến độ tải lên',
  '{count} done': '{count} xong',
  '{count} file|{count} files': '{count} tệp',
  '{count} skipped': '{count} bỏ qua',
  '{shown} file to index|{shown} files to index': '{shown} tệp cần lập chỉ mục',
  // The walkthrough, since 2026-10-06 (tourSteps.ts).
  'Welcome to Proxyma': 'Chào mừng đến với Proxyma',
  'Search everything your company knows, then hand the work to agents that act on it.': 'Tìm kiếm mọi thứ công ty bạn biết, rồi giao việc cho các tác nhân hành động dựa trên đó.',
  'A few short stops, under a minute': 'Vài điểm dừng ngắn, chưa đến một phút',
  'Replay it any time from Settings > About': 'Xem lại bất cứ lúc nào từ Cài đặt > Giới thiệu',
  'Not a chatbot - a team of agents': 'Không phải chatbot - mà là một đội tác nhân',
  'Give it a task, not just a question. The main agent plans the work, hands parts of it to sub-agents running in parallel, and keeps going across your systems for as many steps as the job takes.': 'Hãy giao cho nó một nhiệm vụ, không chỉ một câu hỏi. Tác nhân chính lập kế hoạch, chia phần việc cho các tác nhân phụ chạy song song, và tiếp tục làm việc trên các hệ thống của bạn qua bao nhiêu bước mà công việc cần.',
  'Every step, source and change stays visible in the thread': 'Mọi bước, nguồn và thay đổi đều hiển thị trong cuộc trò chuyện',
  'Writes to Jira, Confluence or Bitbucket wait for your approval unless you let it work alone': 'Thao tác ghi vào Jira, Confluence hoặc Bitbucket chờ bạn phê duyệt, trừ khi bạn để nó tự làm',
  'Type @ to aim it at one source, / to load a skill': 'Gõ @ để hướng vào một nguồn, / để nạp một kỹ năng',
  'Everything it knows, kept current': 'Mọi thứ nó biết, luôn được cập nhật',
  'Folders, Office files, scanned PDFs, websites, Jira, Confluence and Bitbucket - indexed for keyword and meaning search, plus a knowledge graph when you turn it on, and synced again as they change.': 'Thư mục, tệp Office, PDF quét, trang web, Jira, Confluence và Bitbucket - được lập chỉ mục để tìm theo từ khóa và theo ý nghĩa, thêm đồ thị tri thức khi bạn bật nó, và được đồng bộ lại khi chúng thay đổi.',
  'Answers cite the passage they came from': 'Câu trả lời trích dẫn đúng đoạn văn mà nó dựa vào',
  'Live systems are reached with the token of the person asking': 'Các hệ thống trực tiếp được truy cập bằng token của chính người hỏi',
  'Add a source here; chat can search it once it is indexed': 'Thêm nguồn tại đây; trò chuyện có thể tìm trong đó sau khi được lập chỉ mục',
  'One task, many systems, many agents': 'Một nhiệm vụ, nhiều hệ thống, nhiều tác nhân',
  'Key accounts puts that to work: a team reviews one important client together. Agents read the contracts, tickets, meeting minutes and client website in parallel, then propose health ratings with their evidence, risks and next moves.': 'Khách hàng trọng điểm đưa điều đó vào việc thật: một nhóm cùng đánh giá một khách hàng quan trọng. Các tác nhân đọc song song hợp đồng, ticket, biên bản họp và website của khách hàng, rồi đề xuất điểm sức khỏe kèm bằng chứng, rủi ro và bước đi tiếp theo.',
  'A person confirms every rating and decides every move': 'Mỗi điểm đánh giá đều do một người xác nhận, mỗi bước đi đều do một người quyết định',
  'Everyone on the account sees each change as it happens, and every change is logged': 'Mọi thành viên trong nhóm phụ trách khách hàng thấy từng thay đổi ngay khi nó diễn ra, và mọi thay đổi đều được ghi lại',
  'Ask the assistant panel on an account to run the review': 'Nhờ bảng trợ lý trên trang khách hàng chạy đợt đánh giá',
  'This sandbox has a fictional client already reviewed: agents read its contracts, revenue, tickets and minutes. Open it to see the ratings, risks and moves they proposed.': 'Sandbox này có sẵn một khách hàng hư cấu đã được đánh giá: các tác nhân đã đọc hợp đồng, doanh thu, ticket và biên bản họp của khách hàng đó. Hãy mở nó để xem điểm sức khỏe, rủi ro và bước đi mà chúng đề xuất.',
  'Open the demo account': 'Mở khách hàng mẫu',
  'You set the rules': 'Bạn đặt ra quy tắc',
  'Choose the model for each role, how far the agent may go on its own, what it may say and how much it may spend.': 'Chọn mô hình cho từng vai trò, mức độ tác nhân được tự làm, những gì nó được nói và số tiền nó được tiêu.',
  'Read-only, ask before each write, or work alone - chosen in the chat box': 'Chỉ đọc, hỏi trước mỗi lần ghi, hoặc tự làm - chọn ngay trong ô trò chuyện',
  'Step limits, guardrails and spending limits': 'Giới hạn bước, rào chắn nội dung và giới hạn chi tiêu',
  'Send us feedback, and replay this tour, from Settings': 'Gửi góp ý và xem lại hướng dẫn này từ Cài đặt',
  'Send us feedback from your account menu, and replay this tour from Settings > About': 'Gửi góp ý từ menu tài khoản, và xem lại hướng dẫn này từ Cài đặt > Giới thiệu',
  'Show me around': 'Dẫn tôi đi xem',
  'Release notes': 'Ghi chú phát hành',
  'Other integrations': 'Các tích hợp khác',
  'Questions and answers': 'Hỏi và đáp',
  'Proxyma Desktop manual': 'Hướng dẫn sử dụng Proxyma Desktop',
  'Proxyma Enterprise Server manual': 'Hướng dẫn sử dụng Proxyma Enterprise Server',
  'Proxyma Home Server manual': 'Hướng dẫn sử dụng Proxyma Home Server',
  'Manuals, the API and MCP reference, and answers to common questions, published on proxyma.ai for the current release.':
    'Tài liệu hướng dẫn, tài liệu tham khảo API và MCP, cùng câu trả lời cho các câu hỏi thường gặp, được đăng trên proxyma.ai cho phiên bản hiện tại.',
  'Read how your data is handled, and the terms that govern your use of this service.':
    'Đọc về cách dữ liệu của bạn được xử lý, và các điều khoản chi phối việc bạn sử dụng dịch vụ này.',
  'Read how your data is handled, and the terms for the services Proxyma runs.':
    'Đọc về cách dữ liệu của bạn được xử lý, và các điều khoản cho những dịch vụ Proxyma vận hành.',

  // -- Privacy and diagnostics ------------------------------------------------------------------
  'Diagnostics': 'Dữ liệu chẩn đoán',
  'Send diagnostics to Proxyma': 'Gửi dữ liệu chẩn đoán tới Proxyma',
  'Error types, timings and enabled features, sent once a day - never your documents, questions, file names or account. A rated answer and its question are sent too; off by default.':
    'Loại lỗi, thời gian thực hiện và những tính năng đang bật, gửi mỗi ngày một lần - không bao giờ gồm tài liệu, câu hỏi, tên tệp hay tài khoản của bạn. Câu trả lời được đánh giá và câu hỏi của nó cũng được gửi; mặc định tắt.',
  'See what would be sent': 'Xem những gì sẽ được gửi',
  'Delete collected data': 'Xóa dữ liệu đã thu thập',
  'Collected diagnostics deleted': 'Đã xóa dữ liệu chẩn đoán đã thu thập',
  'Could not delete the collected diagnostics': 'Không thể xóa dữ liệu chẩn đoán đã thu thập',
  'What would be sent': 'Những gì sẽ được gửi',
  'Copy': 'Sao chép',
  'Could not build the report. Try again in a moment.': 'Không thể tạo báo cáo. Hãy thử lại sau giây lát.',
  'Sending is on. {count} finished day is waiting to be sent.|Sending is on. {count} finished days are waiting to be sent.':
    'Đang bật gửi. Có {count} ngày đã kết thúc đang chờ được gửi.',
  'Proxyma Enterprise Server never sends diagnostics, so nothing here is stored or sent.':
    'Proxyma Enterprise Server không bao giờ gửi dữ liệu chẩn đoán, nên không có gì ở đây được lưu hay gửi đi.',
  'This installation has no diagnostics address, so nothing here is stored or sent.':
    'Bản cài đặt này không có địa chỉ nhận dữ liệu chẩn đoán, nên không có gì ở đây được lưu hay gửi đi.',
  'Sending is off, so nothing is stored or sent. This is what would be sent if you turned it on.':
    'Đang tắt gửi, nên không có gì được lưu hay gửi đi. Đây là những gì sẽ được gửi nếu bạn bật lên.',
  'Today ({day}, UTC) is still being collected and is sent only after it ends. Days are sent together, about once a day.':
    'Hôm nay ({day}, UTC) vẫn đang được thu thập và chỉ được gửi sau khi kết thúc. Các ngày được gửi cùng nhau, khoảng mỗi ngày một lần.',
  'Last sent: {time}': 'Lần gửi gần nhất: {time}',
  'Enterprise deployments never send diagnostics outside their own infrastructure, so this cannot be turned on.':
    'Các hệ thống Enterprise không bao giờ gửi dữ liệu chẩn đoán ra ngoài hạ tầng của chính họ, nên tùy chọn này không thể bật.',
  'Diagnostics are now sent to Proxyma': 'Dữ liệu chẩn đoán hiện được gửi tới Proxyma',
  'Diagnostics are no longer sent, and what was waiting has been deleted': 'Dữ liệu chẩn đoán không còn được gửi đi, và những gì đang chờ gửi đã bị xóa',
  'Could not save that preference': 'Không thể lưu tùy chọn đó',

  // -- Connections tab -----------------------------------------------------------------------------
  'Credentials': 'Thông tin đăng nhập',
  'Base URL': 'URL gốc',
  'Add and remove sources': 'Thêm và gỡ nguồn dữ liệu',
  'Search connectors by name...': 'Tìm bộ kết nối theo tên...',
  'Search tools...': 'Tìm công cụ...',
  'No matching connectors.': 'Không có bộ kết nối nào phù hợp.',
  'No tools available.': 'Không có công cụ nào.',
  'No tools match your search.': 'Không có công cụ nào khớp với tìm kiếm của bạn.',

  // -- Microsoft 365 -----------------------------------------------------------------------------
  // Its pages, sign-in and sources are Proxyma Enterprise's, translated in its own catalog
  // (ee/frontend/src/i18n/catalogs/vi.ts) since 2026-10-15. What stays here is said by Community
  // pages too: the name, and a client secret's label (an MCP server's OAuth client).
  'Microsoft 365': 'Microsoft 365',
  'Client secret': 'Khóa bí mật của ứng dụng (client secret)',

  // Network shares: the source type, its Add Source dialog, each user's own domain account on the
  // Connections tab, and the allowed file servers on the Network tab.
  'Network share': 'Thư mục chia sẻ mạng',
  'Network path': 'Đường dẫn mạng',
  'Include subfolders': 'Bao gồm cả thư mục con',
  'Files are read with your own domain account, so only what you can open is indexed. The source cannot be shared.':
    'Tệp tin được đọc bằng chính tài khoản miền của bạn, nên chỉ những gì bạn mở được mới được lập chỉ mục. Nguồn dữ liệu này không thể chia sẻ.',
  'Allowed file servers': 'Máy chủ tệp tin được phép',

  // A source type a deployment switch has turned off (the Connect menu), and two words the source
  // dialogs share.
  '{names} appears here once an administrator turns it on in Settings > Network.|{names} appear here once an administrator turns them on in Settings > Network.':
    '{names} sẽ xuất hiện ở đây khi quản trị viên bật trong Cài đặt > Mạng.',
  'Calendar': 'Lịch',
  'Failed to load': 'Không tải được',

  // What a sign-in callback reports back.
  'That sign-in link has expired. Start again.': 'Liên kết đăng nhập đó đã hết hạn. Hãy bắt đầu lại.',

  // -- API tokens tab ---------------------------------------------------------------------------
  'Generate token': 'Tạo token',
  'Generate access token': 'Tạo token truy cập',
  'Generate': 'Tạo',
  'Generating...': 'Đang tạo...',
  'Revoke': 'Thu hồi',
  'Revoke token': 'Thu hồi token',
  'Copy token': 'Sao chép token',
  'Expires in': 'Hết hạn sau',
  'No tokens yet.': 'Chưa có token nào.',
  'Token revoked': 'Đã thu hồi token',
  'Failed to generate token': 'Không thể tạo token',
  'Failed to revoke token': 'Không thể thu hồi token',
  "Copy this token now - it won't be shown again.":
    'Hãy sao chép token này ngay - nó sẽ không được hiển thị lại.',
  'Personal access tokens for MCP clients or direct API calls. A token acts as you and sees only what you can see.':
    'Personal access token cho các ứng dụng MCP hoặc để gọi API trực tiếp. Token hoạt động thay mặt bạn và chỉ thấy những gì bạn thấy.',

  // -- License tab ------------------------------------------------------------------------------
  'License activated': 'Đã kích hoạt giấy phép',

  // -- AI Providers tab (was AI Providers) ------------------------------------------------------------------------------
  'Add provider': 'Thêm nhà cung cấp',
  'Delete provider': 'Xóa nhà cung cấp',
  'Open AI Providers': 'Mở Nhà cung cấp AI',
  'Protocol': 'Giao thức',
  'API key': 'API Key',
  'Model name': 'Tên mô hình',
  'Custom headers': 'Header tùy chỉnh',
  'Add header': 'Thêm header',
  'Remove header': 'Xóa header',
  'Remove header {name}': 'Xóa header {name}',
  'Edit header': 'Sửa header',
  'Edit header {name}': 'Sửa header {name}',
  'No custom headers.': 'Chưa có header tùy chỉnh.',
  'This provider already sends a header with that name.': 'Nhà cung cấp này đã gửi một header có tên đó.',
  'Serves /v1/rerank': 'Hỗ trợ /v1/rerank',
  'API': 'API',
  'Anthropic Messages keeps Claude models in their own format, with prompt caching. OpenAI Chat Completions suits every other model.': 'Anthropic Messages giữ các mô hình Claude ở định dạng riêng của chúng, có bộ nhớ đệm prompt. OpenAI Chat Completions phù hợp với mọi mô hình khác.',
  'Streaming': 'Truyền phát',
  'Wait for whole replies': 'Chờ câu trả lời trọn vẹn',
  'For a deployment that answers with one reply instead of a stream once tools are in use. Answers then appear whole, step by step.': 'Dành cho bản triển khai trả về một câu trả lời duy nhất thay vì truyền phát khi có dùng công cụ. Câu trả lời khi đó hiện ra trọn vẹn, từng bước một.',
  'Test': 'Kiểm tra',
  'Testing...': 'Đang kiểm tra...',
  'Select a provider': 'Chọn một nhà cung cấp',
  'No providers configured': 'Chưa cấu hình nhà cung cấp nào',
  'No providers configured yet.': 'Chưa cấu hình nhà cung cấp nào.',
  'The provider this was set to no longer exists - choose another.':
    'Nhà cung cấp được đặt trước đây không còn tồn tại - hãy chọn nhà cung cấp khác.',
  'Add one on the AI Providers tab.': 'Hãy thêm một nhà cung cấp ở tab Nhà cung cấp AI.',
  'Add any service that speaks a supported protocol. Two accounts on one service are two providers.':
    'Thêm bất kỳ dịch vụ nào dùng một giao thức được hỗ trợ. Hai tài khoản trên cùng một dịch vụ là hai nhà cung cấp.',
  'A provider runs the AI models: the one that answers in chat and the one that indexes your sources. Add the service you have an API key for, or a local one such as Ollama.':
    'Nhà cung cấp chạy các mô hình AI: mô hình trả lời trong trò chuyện và mô hình lập chỉ mục các nguồn của bạn. Hãy thêm dịch vụ mà bạn có API key, hoặc một dịch vụ chạy cục bộ như Ollama.',
  'Next steps': 'Các bước tiếp theo',
  'Agents: choose the model that answers': 'Tác nhân: chọn mô hình trả lời',
  'Search: choose the embedding model that indexes your sources':
    'Tìm kiếm: chọn mô hình vector nhúng (embeddings) để lập chỉ mục các nguồn của bạn',
  'Conversion: choose a vision model to read scanned pages (optional)':
    'Chuyển đổi: chọn mô hình thị giác để đọc trang scan (tùy chọn)',
  'Model pricing: set rates to track cost (optional)':
    'Bảng giá mô hình: đặt đơn giá để theo dõi chi phí (tùy chọn)',
  'Fills in the protocol and address. Anything speaking one of these protocols works, listed here or not.':
    'Tự điền giao thức và địa chỉ. Bất cứ dịch vụ nào nói một trong các giao thức này đều dùng được, dù có trong danh sách hay không.',
  'Sent with every request. Gateways often need one - OpenRouter uses HTTP-Referer and X-Title for attribution.':
    'Được gửi kèm mọi yêu cầu. Các gateway thường cần header - OpenRouter dùng HTTP-Referer và X-Title để ghi nhận nguồn.',
  'For servers with a /v1/rerank endpoint: vLLM, llama.cpp, Jina, Cohere, Together AI and others. Leave off and reranking uses embeddings instead.':
    'Dành cho máy chủ có endpoint /v1/rerank: vLLM, llama.cpp, Jina, Cohere, Together AI và các máy chủ khác. Để tắt thì việc xếp hạng lại sẽ dùng vector nhúng thay thế.',
  // A vendor name in the quick-pick list.
  'OpenRouter': 'OpenRouter',

  // -- Chat, retrieval and documents ---------------------------------------------------------------
  'System prompt': 'Câu lệnh hệ thống',
  'System prompt saved': 'Đã lưu câu lệnh hệ thống',
  'Failed to save system prompt': 'Không thể lưu câu lệnh hệ thống',
  "Overrides the agent's entire system prompt": 'Ghi đè toàn bộ câu lệnh hệ thống của tác nhân',
  'Response tone': 'Giọng điệu câu trả lời',
  'Context window': 'Cửa sổ ngữ cảnh',
  'Max output tokens': 'Số token đầu ra tối đa',
  'Max agent iterations': 'Số vòng lặp tối đa của tác nhân',
  'Sub-agents': 'Tác nhân phụ',
  'Sub-agent context window': 'Cửa sổ ngữ cảnh của tác nhân phụ',
  'Sub-agent max output tokens': 'Số token đầu ra tối đa của tác nhân phụ',
  'Max sub-agent iterations': 'Số vòng lặp tối đa của tác nhân phụ',
  'Same as chat': 'Giống như trò chuyện',
  "The agent hands work to sub-agents and gets back only their reports. A sub-agent has the agent's tools and settings unless you choose others here; its changes need your approval like the agent's.":
    'Tác nhân giao việc cho các tác nhân phụ và chỉ nhận lại báo cáo. Tác nhân phụ dùng công cụ và thiết lập của tác nhân chính, trừ khi bạn chọn khác ở đây; mọi thay đổi của nó cần bạn phê duyệt như với tác nhân chính.',
  'Same as the agent': 'Giống tác nhân chính',
  'Model default': 'Mặc định của mô hình',
  'Provider default': 'Mặc định của nhà cung cấp',
  'Sub-agent time limit (minutes)': 'Giới hạn thời gian của tác nhân phụ (phút)',
  'Search time limit (minutes)': 'Giới hạn thời gian tìm kiếm (phút)',
  'Time limit': 'Giới hạn thời gian',
  '{provider} uses OpenAI Chat Completions, which takes no cache instructions. Choose Anthropic Messages for it in Settings > AI Providers.': '{provider} dùng OpenAI Chat Completions, vốn không nhận chỉ dẫn bộ nhớ đệm. Hãy chọn Anthropic Messages cho nó trong Cài đặt > Nhà cung cấp AI.',
  'How long one search of your sources may take before the agent is told it timed out. Other tools stop after one minute.': 'Thời gian tối đa cho một lần tìm kiếm trong các nguồn của bạn trước khi tác nhân được báo là đã hết giờ. Các công cụ khác dừng sau một phút.',
  'Out of time, a sub-agent is asked once for what it has found. Waiting for your answer to an approval or a question is not counted.':
    'Khi hết thời gian, tác nhân phụ được yêu cầu một lần báo cáo những gì đã tìm được. Thời gian chờ bạn trả lời một yêu cầu phê duyệt hoặc câu hỏi không được tính.',
  'Generate alternative phrasings to improve recall':
    'Tạo thêm các cách diễn đạt khác để tăng độ bao phủ kết quả',
  'LLM cross-encoder scoring for improved precision':
    'Chấm điểm bằng cross-encoder của LLM để tăng độ chính xác',
  'The model that turns documents and questions into vectors':
    'Mô hình chuyển tài liệu và câu hỏi thành vector',
  'Change embedding model?': 'Đổi mô hình vector nhúng?',
  'Changing the embedding model requires a full re-index of all connectors.':
    'Việc đổi mô hình vector nhúng yêu cầu lập chỉ mục lại toàn bộ mọi bộ kết nối.',
  'Changing the embedding model does not update your existing search index by itself.':
    'Việc đổi mô hình vector nhúng không tự cập nhật chỉ mục tìm kiếm hiện có của bạn.',
  'Every connector indexed with the old model needs a full re-index before it works correctly with the new model.':
    'Mọi bộ kết nối đã lập chỉ mục bằng mô hình cũ đều cần lập chỉ mục lại hoàn toàn trước khi hoạt động đúng với mô hình mới.',
  'Re-indexing is manual, per connector. Until you do it, affected connectors silently return degraded or wrong results.':
    'Lập chỉ mục lại là thao tác thủ công cho từng bộ kết nối. Cho tới khi bạn làm, các bộ kết nối bị ảnh hưởng sẽ âm thầm trả về kết quả kém hoặc sai.',
  'Re-indexing can take a long time for large connectors and consumes real embedding compute/cost.':
    'Việc lập chỉ mục lại có thể mất nhiều thời gian với các bộ kết nối lớn và tiêu tốn chi phí/tài nguyên tính toán vector nhúng thực tế.',
  'Max concurrent embedding requests': 'Số yêu cầu vector nhúng đồng thời tối đa',
  "How many batch-embed requests run at once; match your provider's capacity (e.g. Ollama's NUM_PARALLEL).":
    'Số yêu cầu nhúng theo lô chạy đồng thời; hãy đặt khớp với năng lực của nhà cung cấp (ví dụ NUM_PARALLEL của Ollama).',
  'Extract entities and relations while indexing, for questions whose answer spans documents':
    'Trích xuất thực thể và mối quan hệ trong lúc lập chỉ mục, dành cho các câu hỏi mà câu trả lời trải rộng qua nhiều tài liệu',
  'Costs one LLM call per chunk on top of embedding, which can double or triple indexing cost. Use a cheap model.':
    'Tốn một lượt gọi LLM cho mỗi đoạn, cộng thêm chi phí vector nhúng, có thể làm chi phí lập chỉ mục tăng gấp đôi hoặc gấp ba. Hãy dùng một mô hình rẻ.',
  'Gleaning rounds': 'Số vòng rà soát bổ sung',
  'Extra passes asking the model what it missed. 1 roughly doubles the cost above and 2 triples it.':
    'Các lượt bổ sung hỏi mô hình xem nó đã bỏ sót gì. 1 vòng làm chi phí ở trên tăng khoảng gấp đôi và 2 vòng tăng gấp ba.',
  // OCR.
  'OCR engine': 'Công cụ OCR',
  'Method': 'Phương pháp',
  'Method for indexing': 'Phương pháp khi lập chỉ mục',
  'Method for chat attachments': 'Phương pháp cho tệp tin đính kèm trong trò chuyện',
  'Page resolution (DPI)': 'Độ phân giải trang (DPI)',
  'Max pages per document': 'Số trang tối đa mỗi tài liệu',
  'Tesseract languages': 'Ngôn ngữ Tesseract',
  'How documents are turned into text. Scanned pages only become searchable through OCR.':
    'Cách tài liệu được chuyển thành văn bản. Trang quét chỉ tìm kiếm được thông qua OCR.',
  "Used for every connector's documents, where OCR's per-page cost adds up. Auto pays it only for documents with no text layer.":
    'Dùng cho tài liệu của mọi bộ kết nối, nơi chi phí OCR theo trang cộng dồn lại. Auto chỉ trả chi phí đó cho tài liệu không có lớp văn bản.',
  'Used for chat attachments and documents the agent fetches from a URL, where fidelity usually matters more than cost.':
    'Dùng cho tệp tin đính kèm trong trò chuyện và tài liệu tác nhân tải từ URL, nơi độ chính xác thường quan trọng hơn chi phí.',
  'Resolution pages are rendered at before recognition. 200 suits body text; higher is sharper but costs more tokens with a vision model.':
    'Độ phân giải để chuyển trang thành ảnh trước khi nhận dạng. 200 phù hợp với văn bản thường; cao hơn thì sắc nét hơn nhưng tốn nhiều token hơn với mô hình thị giác.',
  'A hard cap, so one large scan cannot exhaust the budget. Pages past it are noted in the text, not silently dropped.':
    'Một mức chặn cứng, để một bản quét lớn không thể ngốn hết ngân sách. Các trang vượt giới hạn được ghi chú trong văn bản, không bị bỏ đi âm thầm.',
  'Tesseract language codes joined with "+", e.g. eng+deu. Each needs its traineddata file on the server.':
    'Mã ngôn ngữ của tesseract nối bằng "+", ví dụ eng+deu. Mỗi mã cần có tệp traineddata trên máy chủ.',
  'Rebuild index': 'Xây dựng lại chỉ mục',
  'Rebuilding...': 'Đang xây dựng lại...',

  // -- Guardrails ------------------------------------------------------------------------------------
  'How guardrails work': 'Cách rào chắn hoạt động',
  'Check what people send': 'Kiểm tra nội dung người dùng gửi',
  'Check what the agent reads': 'Kiểm tra nội dung tác nhân đọc',
  'Check what the agent answers': 'Kiểm tra nội dung tác nhân trả lời',
  'Messages, before they are saved or sent to a provider.':
    'Tin nhắn, trước khi được lưu hoặc gửi tới nhà cung cấp.',
  'Retrieved content, before the agent sees it. Pattern checks only.': 'Nội dung được truy xuất, trước khi trợ lý nhìn thấy. Chỉ kiểm tra theo mẫu.',
  'Replies, as they are written, so nothing matching is ever shown.':
    'Câu trả lời, ngay khi đang được viết ra, để không có nội dung khớp nào từng được hiển thị.',
  'Also check answers': 'Kiểm tra cả câu trả lời',
  'Hold replies to the same policy. Doubles what this adds per turn.':
    'Áp dụng cùng chính sách cho câu trả lời. Làm tăng gấp đôi chi phí thêm vào cho mỗi lượt.',
  'Pattern checks. They recognize shapes, not meaning.':
    'Kiểm tra theo mẫu. Chúng nhận diện hình dạng, không phải ý nghĩa.',
  'Personal data': 'Dữ liệu cá nhân',
  'Prompt injection': 'Chèn chỉ dẫn',
  'Server file paths': 'Đường dẫn tệp tin máy chủ',
  'Redacted': 'Đã che',
  'Blocked': 'Đã chặn',
  'Allowed': 'Đã cho qua',
  'Would block': 'Lẽ ra đã chặn',
  'Would redact': 'Lẽ ra đã che',
  'Would allow': 'Lẽ ra đã cho qua',
  'Retrieval': 'Truy xuất',
  'Indexing...': 'Đang lập chỉ mục...',
  'Checked': 'Đã kiểm tra',
  'Watch for a pattern': 'Theo dõi một mẫu',
  'Judge meaning with a model': 'Đánh giá ý nghĩa bằng mô hình',
  'Judges the text against your rules. One model call per message.': 'Đánh giá văn bản theo các quỹ tắc của bạn. Mỗi tin nhắn tốn một lượt gọi mô hình.',
  'Rules from your documents': 'Quy tắc từ tài liệu của bạn',
  'Policy documents': 'Tài liệu chính sách',
  'Upload your policies as files. Only the clauses that bear on a message are sent.': 'Tải lên các chính sách của bạn dưới dạng tệp tin. Chỉ những điều khoản liên quan tới tin nhắn mới được gửi đi.',
  'Refuse when the model cannot be reached': 'Từ chối khi không kết nối được tới mô hình',
  'A timeout or a non-verdict currently lets the message through. This refuses it instead.':
    'Hiện tại, hết thời gian chờ hoặc không có phán quyết sẽ cho tin nhắn đi qua. Tùy chọn này sẽ từ chối thay vì cho qua.',
  'Slow down repeated attempts': 'Làm chậm các lần thử lặp lại',
  'Legitimate work can trip this - pasting several logs that each contain a token is enough.':
    'Công việc hợp lệ cũng có thể kích hoạt điều này - chỉ cần dán vài tệp tin nhật ký mà mỗi tệp đều chứa một token là đủ.',
  'Detect a leaked system prompt': 'Phát hiện câu lệnh hệ thống bị lộ',
  'When a message matches': 'Khi một tin nhắn khớp',
  'When something the agent read matches': 'Khi nội dung tác nhân đọc được khớp',
  'When an answer matches': 'Khi một câu trả lời khớp',
  'Neither ends the conversation. Prompt injection always withholds the whole source.':
    'Cả hai đều không kết thúc cuộc trò chuyện. Chèn chỉ dẫn luôn giữ lại toàn bộ nguồn.',
  'Neither reads a message.': 'Cả hai đều không đọc tin nhắn.',
  'Monitor runs every check and applies none of them. Results appear under Logs.':
    'Chế độ theo dõi chạy mọi lượt kiểm tra nhưng không áp dụng lượt nào. Kết quả xuất hiện ở mục Nhật ký.',
  'What fired': 'Quy tắc đã kích hoạt',
  'Nothing measured yet.': 'Chưa đo được gì.',
  'Rules that fired': 'Các quy tắc đã kích hoạt',
  ' ... (truncated)': ' ... (đã cắt bớt)',
  'Keep the matched text': 'Lưu lại phần nội dung khớp quy tắc',
  'Also records the characters each rule matched, to tell a real finding from a false one.': 'Ghi lại thêm các ký tự mà quy tắc đã khớp, để phân biệt một phát hiện thật với một cảnh báo nhầm.',
  'A rule that finds a credential now records it. It stays in memory only (200 decisions, 120 characters), and any administrator can read it.': 'Một quy tắc phát hiện thông tin đăng nhập giờ sẽ ghi lại chính thông tin đó. Nó chỉ nằm trong bộ nhớ (200 quyết định, 120 ký tự), và mọi quản trị viên đều đọc được.',

  // -- Skills and workflows ---------------------------------------------------------------------------
  'My skills': 'Kỹ năng của tôi',
  'My workflows': 'Quy trình của tôi',
  'Add skill': 'Thêm kỹ năng',
  'Edit skill': 'Sửa kỹ năng',
  'Delete skill': 'Xóa kỹ năng',
  'Skill deleted': 'Đã xóa kỹ năng',
  'Failed to delete skill': 'Không thể xóa kỹ năng',
  'Failed to load the default for this skill': 'Không thể tải giá trị mặc định cho kỹ năng này',
  'Add workflow': 'Thêm quy trình',
  'Edit workflow': 'Sửa quy trình',
  'Delete workflow': 'Xóa quy trình',
  'Workflow deleted': 'Đã xóa quy trình',
  'Failed to delete workflow': 'Không thể xóa quy trình',
  'No workflows yet': 'Chưa có quy trình nào',
  'Short description': 'Mô tả ngắn',
  'Shown to the agent in every system prompt': 'Hiển thị cho tác nhân trong mọi câu lệnh hệ thống',
  'Variants': 'Biến thể',
  'Step-by-step instructions the agent follows once it loads this skill...':
    'Hướng dẫn từng bước mà tác nhân làm theo sau khi tải kỹ năng này...',
  'What this step is called': 'Tên của bước này',
  'What the agent should do in this step, and what it should report back.':
    'Điều tác nhân cần làm trong bước này, và điều nó cần báo cáo lại.',
  'Check whether a release is ready to ship': 'Kiểm tra xem một bản phát hành đã sẵn sàng để giao hay chưa',
  'The release version is whatever the user named when they invoked this...':
    'Phiên bản phát hành là phiên bản mà người dùng chỉ định khi gọi quy trình này...',
  'Start from': 'Bắt đầu từ',
  'Restore default': 'Khôi phục mặc định',
  'Reset to default': 'Đặt lại về mặc định',

  // -- Data retention, network and updates ---------------------------------------------------------------
  'Proxy mode': 'Chế độ proxy',
  'HTTP proxy': 'HTTP Proxy',
  'HTTPS proxy': 'HTTPS Proxy',
  'No proxy': 'Không dùng proxy',
  'Passphrase, if the file has credentials': 'Cụm mật khẩu, nếu tệp tin có thông tin đăng nhập',
  'Comma-separated list of hosts that should bypass the proxy.':
    'Danh sách các host bỏ qua proxy, phân tách bằng dấu phẩy.',
  'Configure how external HTTP traffic (AI providers, Jira, Confluence, etc.) is routed. Changes require a restart.':
    'Cấu hình cách định tuyến lưu lượng HTTP ra ngoài (nhà cung cấp AI, Jira, Confluence, v.v.). Các thay đổi cần khởi động lại.',
  'Check for updates': 'Kiểm tra cập nhật',
  'How to update': 'Cách cập nhật',
  'Download updates automatically': 'Tự động tải bản cập nhật',
  'Could not check for updates': 'Không thể kiểm tra cập nhật',
  'Could not download the update': 'Không thể tải bản cập nhật',

  // -- Import, export and reset -------------------------------------------------------------------------
  'Export': 'Xuất',
  'Export configuration': 'Xuất cấu hình',
  'Exporting...': 'Đang xuất...',
  'Import': 'Nhập',
  'Import it': 'Nhập tệp này',
  'Imported': 'Đã nhập',
  'Importing...': 'Đang nhập...',
  'Read this file': 'Đọc tệp này',
  'Reading...': 'Đang đọc...',
  'This is what importing would change': 'Đây là những gì việc nhập sẽ thay đổi',
  'Reset settings': 'Đặt lại thiết lập',
  'Reset everything': 'Đặt lại tất cả',
  'Clearing...': 'Đang xóa...',
  'Reset triggered, but the app did not come back in time. Restart it manually.':
    'Đã kích hoạt việc đặt lại, nhưng ứng dụng không trở lại kịp thời. Hãy khởi động lại thủ công.',

  // -- Usage & Cost -------------------------------------------------------------------------------------
  'Model pricing': 'Bảng giá mô hình',
  'Add model rate': 'Thêm đơn giá mô hình',
  'Edit rate': 'Sửa đơn giá',
  'Delete pricing': 'Xóa bảng giá',
  'Pricing added': 'Đã thêm bảng giá',
  'Pricing updated': 'Đã cập nhật bảng giá',
  'Pricing deleted': 'Đã xóa bảng giá',
  'Failed to delete pricing': 'Không thể xóa bảng giá',
  'No rates set for this provider.': 'Chưa đặt đơn giá nào cho nhà cung cấp này.',
  'What this provider charges per million tokens, used for every cost figure and spend quota. A model with no rate counts as $0.':
    'Mức phí nhà cung cấp này tính cho mỗi triệu token, dùng cho mọi con số chi phí và hạn mức chi tiêu. Mô hình không có đơn giá được tính là $0.',
  'Per-model rates are set on the provider they belong to. Anything without a rate is estimated at $0 - never an error.':
    'Đơn giá theo từng mô hình được đặt tại nhà cung cấp tương ứng. Bất cứ mô hình nào không có đơn giá đều được ước tính là $0 - đây không phải là lỗi.',

  // -- Feedback tab and admin actions ---------------------------------------------------------------------
  'Logs, the conversation and files can be attached - each is a separate opt-in on the form.':
    'Có thể đính kèm nhật ký, cuộc trò chuyện và tệp tin - mỗi loại là một lựa chọn riêng trên biểu mẫu.',
  'Add a file': 'Thêm một tệp tin',
  'Uploading...': 'Đang tải lên...',
  'What to look for': 'Cần tìm gì',
  'Pause': 'Tạm dừng',
  'Play': 'Phát',
  'Restoring...': 'Đang khôi phục...',

  // -- the re-consent gate ---------------------------------------------------------------------------------
  'Our terms have changed': 'Điều khoản của chúng tôi đã thay đổi',
  'Please review our terms': 'Vui lòng xem lại điều khoản của chúng tôi',
  'Accept and continue': 'Chấp nhận và tiếp tục',
  'You last accepted version {version} on {date}.': 'Lần cuối bạn chấp nhận phiên bản {version} vào {date}.',
  "What's new in {version}": 'Có gì mới trong {version}',
  'Sign-in': 'Đăng nhập',

  // -- the last sweep -------------------------------------------------------------------------
  // Found by re-running the survey after every area was done: the checker can only report on
  // strings that REACH t(), so it can never say what was missed. These are what a full
  // filesystem sweep turned up after the count had fallen from 500 to 81 - the other 65 being
  // format examples that correctly stay English (RFC 2606 addresses, a PEM blob, proxy URLs,
  // file extensions).
  'Quit Proxyma': 'Thoát Proxyma',
  'Quitting Proxyma': 'Đang thoát Proxyma',
  'Could not quit Proxyma. Try again.': 'Không thể thoát Proxyma. Vui lòng thử lại.',
  'Proxyma is still running. Try quitting again.': 'Proxyma vẫn đang chạy. Vui lòng thử thoát lại.',
  'Approved': 'Đã chấp thuận',
  'Denied': 'Đã từ chối',
  'Other servers': 'Máy chủ khác',
  'Switching server': 'Đang chuyển máy chủ',
  'Switched server': 'Đã chuyển máy chủ',
  'Working on the file': 'Đang xử lý tệp tin',
  'Worked on the file': 'Đã xử lý tệp tin',
  'Opening a file from your computer': 'Đang mở một tệp tin từ máy tính của bạn',
  'Opened a file from your computer': 'Đã mở một tệp tin từ máy tính của bạn',
  'Saving to your computer': 'Đang lưu vào máy tính của bạn',
  'Saved to your computer': 'Đã lưu vào máy tính của bạn',
  'Wrote {files}': 'Đã tạo {files}',
  'Proxyma has stopped': 'Proxyma đã dừng',
  'You can close this page.': 'Bạn có thể đóng trang này.',
  '{seconds}s': '{seconds} giây',
  '95th pct': 'Phân vị 95',
  // "e.g." placeholders are PROSE, unlike the address and path examples beside them: the words
  // after it are an English sentence a reader is meant to understand, not a format to copy.
  'e.g. Claude Code (laptop)': 'ví dụ: Claude Code (máy tính xách tay)',
  'e.g. Jira Workflow': 'ví dụ: Quy trình Jira',
  'e.g. Release readiness': 'ví dụ: Mức sẵn sàng phát hành',
  // Money field labels. The $ and the "/1M tokens" unit stay - they are the unit, not a word.
  'Monthly limit ($)': 'Giới hạn hàng tháng ($)',
  'Input $/1M tokens': 'Đầu vào $/1 triệu token',
  'Output $/1M tokens': 'Đầu ra $/1 triệu token',
  'Cache read $/1M tokens': 'Đọc bộ đệm $/1 triệu token',
  'Cache write $/1M tokens': 'Ghi bộ đệm $/1 triệu token',
  '{price} in': '{price} đầu vào',
  '{price} out': '{price} đầu ra',
  '{price} cache read': '{price} đọc bộ đệm',
  '{price} cache write': '{price} ghi bộ đệm',
  'per 1M tokens': 'cho mỗi 1 triệu token',
  // Two textarea placeholders whose first line instructs and whose remaining lines are examples.
  // Split so the instruction translates and the example code names stay recognisable.

  // -- multi-line prose, found by a code review --------------------------------------------------
  // The first pass joined multi-line JSX text in ONE file. A review found ~110 runs untouched
  // across 36 others - and the leftover sweep used to check the work matched single lines only,
  // so it had the same blind spot and reported clean. These are those strings.
  'AI can make mistakes. Always verify important information.':
    'AI có thể mắc lỗi. Hãy luôn kiểm chứng những thông tin quan trọng.',
  'Connect': 'Kết nối',
  'Clear': 'Xóa',
  'Now': 'Bây giờ',
  'Check now': 'Kiểm tra ngay',
  'Cancelling...': 'Đang hủy...',
  'Cancel download': 'Hủy tải xuống',
  'Connect as many Jira, Confluence, Bitbucket and SVN servers as you use.': 'Kết nối bao nhiêu máy chủ Jira, Confluence, Bitbucket và SVN tùy bạn dùng.',
  'Settings > Connections lists your connections, each with its own server and token.': 'Cài đặt > Kết nối liệt kê các kết nối của bạn, mỗi kết nối có máy chủ và token riêng.',
  'Whether the assistant may read or write is set on each connection.': 'Quyền đọc hoặc ghi của trợ lý được đặt trên từng kết nối.',
  'Could not cancel the download': 'Không thể hủy việc tải xuống',
  'Could not restart into the update. Restart Proxyma manually.': 'Không thể khởi động lại vào bản cập nhật. Hãy khởi động lại Proxyma thủ công.',
  'Development builds': 'Bản dựng phát triển',
  'Also offer development builds, which may be broken. Turning this off keeps you on the dev build until a newer release.':
    'Cung cấp thêm các bản dựng phát triển, có thể bị lỗi. Tắt tùy chọn này vẫn giữ bạn ở bản dựng phát triển cho tới khi có bản phát hành mới hơn.',
  'In the install directory, set this line in .env, then run the two commands:':
    'Trong thư mục cài đặt, đặt dòng này trong .env, rồi chạy hai lệnh:',
  'Choose file': 'Chọn tệp tin',
  'or select a folder': 'hoặc chọn một thư mục',
  'Add step': 'Thêm bước',
  'Show shared sources from other users': 'Hiện các nguồn dữ liệu được chia sẻ từ người dùng khác',
  'Nothing selected - every supported file type will be indexed.':
    'Chưa chọn gì - mọi loại tệp tin được hỗ trợ đều sẽ được lập chỉ mục.',
  'Some of these only take effect after a restart.':
    'Một số thay đổi trong đây chỉ có hiệu lực sau khi khởi động lại.',
  'paused - quota exceeded': 'đã tạm dừng - vượt hạn mức',
  // Skills and workflows: the explanatory copy on each form.
  'Skills are named instructions the agent loads only when relevant, instead of always consuming context.':
    'Kỹ năng là các chỉ dẫn có tên mà tác nhân chỉ tải khi thực sự liên quan, thay vì lúc nào cũng chiếm dụng ngữ cảnh.',
  'A short, memorable name - this is what the agent and other users will see.':
    'Một cái tên ngắn gọn, dễ nhớ - đây là tên mà tác nhân và những người dùng khác sẽ nhìn thấy.',
  'Shown to the agent in every system prompt, so keep it brief.':
    'Được hiển thị cho tác nhân trong mọi câu lệnh hệ thống, nên hãy viết ngắn gọn.',
  'Full instructions, loaded by the agent only when this skill is relevant.':
    'Chỉ dẫn đầy đủ, chỉ được tác nhân tải khi kỹ năng này thực sự liên quan.',
  'Comma-separated, used to organize your skills list.':
    'Phân tách bằng dấu phẩy, dùng để sắp xếp danh sách kỹ năng của bạn.',
  'Comma-separated, used to organize your workflows list.':
    'Phân tách bằng dấu phẩy, dùng để sắp xếp danh sách quy trình của bạn.',
  'Visible and runnable by every user, not just you.':
    'Mọi người dùng đều nhìn thấy và chạy được, không chỉ riêng bạn.',
  'Visible and selectable by every user, not just you.':
    'Mọi người dùng đều nhìn thấy và chọn được, không chỉ riêng bạn.',
  "Let this skill's instructions also make these platform tools available to the agent.":
    'Cho phép chỉ dẫn của kỹ năng này đồng thời mở các công cụ nền tảng sau cho tác nhân.',
  'Prefer these connectors when the agent searches for this skill - a preference, not a restriction.':
    'Ưu tiên những bộ kết nối này khi tác nhân tìm kiếm cho kỹ năng này - là ưu tiên, không phải giới hạn.',
  'Prefer these connectors when the agent searches during this workflow - a preference, not a restriction.':
    'Ưu tiên những bộ kết nối này khi tác nhân tìm kiếm trong quy trình này - là ưu tiên, không phải giới hạn.',
  'Useful when the same job recurs in the same shape, such as a release check or a weekly summary.':
    'Hữu ích khi cùng một công việc lặp lại theo cùng một khuôn, như kiểm tra bản phát hành hay bản tóm tắt hàng tuần.',
  'What you type after "/" in the chat box to run it. Must not match a skill\'s name.':
    'Nội dung bạn gõ sau dấu "/" trong ô trò chuyện để chạy nó. Không được trùng với tên của một kỹ năng.',
  'Shown in the "/" list and to the agent, so it knows this workflow exists.':
    'Được hiển thị trong danh sách "/" và cho tác nhân, để nó biết quy trình này tồn tại.',
  'Each step is one turn, run in order, so later steps can use earlier findings. Every step also re-reads all previous ones, adding cost.':
    'Mỗi bước là một lượt, chạy theo thứ tự, nên bước sau có thể dùng kết quả của bước trước. Mỗi bước cũng đọc lại mọi bước trước đó, làm tăng chi phí.',
  'That is the maximum. Split longer procedures into two workflows, since cost grows faster than the list.':
    'Đó là mức tối đa. Hãy tách quy trình dài hơn thành hai quy trình, vì chi phí tăng nhanh hơn độ dài danh sách.',
  'Platform tools the agent may use in every step of this workflow.':
    'Các công cụ nền tảng tác nhân được dùng trong mọi bước của quy trình này.',
  'Optional. Given once before the first step and kept for every step.':
    'Không bắt buộc. Được đưa ra một lần trước bước đầu tiên và giữ cho mọi bước.',
  'Optional. Pick a cheaper model and every step runs on it, instead of costing the main chat model once per step.':
    'Không bắt buộc. Chọn một mô hình rẻ hơn và mọi bước sẽ chạy trên nó, thay vì tốn chi phí của mô hình trò chuyện chính cho mỗi bước.',
  'The provider this was set to is gone - pick another, or go back to the main chat model.':
    'Nhà cung cấp được đặt trước đây không còn nữa - hãy chọn nhà cung cấp khác, hoặc quay lại mô hình trò chuyện chính.',

  // Import, export and reset: the copy that explains what each action does.
  'Writes the chosen settings to a JSON file, except API tokens, your account and connector Read/Write switches. It is a configuration, not a backup.': 'Ghi các thiết lập bạn chọn vào một tệp JSON, trừ Token API, tài khoản của bạn và công tắc Đọc/Ghi của bộ kết nối. Đây là cấu hình, không phải bản sao lưu.',
  'Configuration imported. A restart is required for some of it to take effect.': 'Đã nhập cấu hình. Cần khởi động lại để một phần có hiệu lực.',
  'What to include': 'Nội dung đưa vào',
  'None': 'Không chọn',
  'Takes effect after a restart': 'Có hiệu lực sau khi khởi động lại',
  // The import report's column heading over each entry's settings group.
  'Group': 'Nhóm',
  'Include credentials, encrypted with a passphrase': 'Kèm theo thông tin đăng nhập, được mã hóa bằng một cụm mật khẩu',
  'The passphrase cannot be recovered. Without it, nobody can read the credentials in this file.':
    'Cụm mật khẩu không thể khôi phục. Không có nó, không ai đọc được thông tin đăng nhập trong tệp này.',
  'Adds what the file contains without deleting or resetting anything; names already in use are kept. You see the changes before they apply.':
    'Bổ sung nội dung của tệp mà không xóa hay đặt lại gì; các tên đã được dùng ở đây được giữ nguyên. Bạn sẽ thấy các thay đổi trước khi áp dụng.',
  'Nothing in this file differs from what is already configured here.':
    'Không có gì trong tệp này khác với những gì đã được cấu hình ở đây.',
  'No credentials were read from this file. Anything needing one keeps the value already configured here.':
    'Không đọc được thông tin đăng nhập nào từ tệp này. Những mục cần thông tin đăng nhập sẽ giữ nguyên giá trị đã cấu hình ở đây.',
  'AI provider, agent, conversion, search, embedding, network, retention and cost settings go back to their defaults. Your connectors, indexed documents and chat history are left alone.':
    'Các thiết lập về nhà cung cấp AI, tác nhân, chuyển đổi, tìm kiếm, vector nhúng, mạng, lưu giữ dữ liệu và chi phí sẽ trở về mặc định. Bộ kết nối, tài liệu đã lập chỉ mục và lịch sử trò chuyện của bạn được giữ nguyên.',
  'Every connector, all indexed content and chat history, plus the settings above. Files on your disk are not touched.':
    'Mọi bộ kết nối, toàn bộ nội dung đã lập chỉ mục và lịch sử trò chuyện, cùng các thiết lập ở trên. Các tệp tin trên ổ đĩa của bạn không bị đụng tới.',
  'Both actions restart Proxyma when they finish.': 'Cả hai thao tác đều khởi động lại Proxyma khi hoàn tất.',

  // Updates, network and previews.
  'Checking only asks whether a newer version exists and downloads nothing. "Never" stops the schedule but keeps the button above working.':
    'Việc kiểm tra chỉ hỏi xem có phiên bản mới hơn không và không tải về gì. "Không bao giờ" dừng lịch kiểm tra nhưng nút phía trên vẫn hoạt động.',
  'Download a new version as soon as it is found. A downloaded version is installed the next time Proxyma starts, or at once with "Restart and install".':
    'Tải phiên bản mới ngay khi tìm thấy. Phiên bản đã tải được cài vào lần khởi động Proxyma tiếp theo, hoặc ngay lập tức bằng "Khởi động lại và cài đặt".',
  'No content indexed for this item yet': 'Chưa có nội dung nào được lập chỉ mục cho mục này',
  'Failed to load preview': 'Không thể tải bản xem trước',
  'I have read this and I am responsible for reviewing every write action.':
    'Tôi đã đọc nội dung này và tôi chịu trách nhiệm xem xét mọi hành động ghi.',

  // Empty states on the admin charts.
  // -- the last review findings -------------------------------------------------------------------
  // Three-way ternaries whose FIRST branch the two-way pass could not see.
  'Yes': 'Có',
  'Expired': 'Đã hết hạn',
  'Expires {date}': 'Hết hạn {date}',
  'Downloading...': 'Đang tải xuống...',
  'Download {version}': 'Tải {version}',
  'Downloading Proxyma {version}': 'Đang tải Proxyma {version}',
  'Update download': 'Tải bản cập nhật',
  '{done} of {total}': '{done} / {total}',

  // -- what a destructive dialog says, from its CALL SITES -----------------------------------------
  // DeleteConfirmModal takes its copy as props, so this English lives in whoever renders it -
  // AdminPage, ConnectorDetailModal, ResetPage, SettingsModal, SkillsTab, WorkflowsTab. Every one of these was
  // invisible until the checker learned to read a JSX attribute: they reach t() as variables
  // inside the component, and the entry that existed covered only the component's own defaults.
  // A second review found them; the first did not.
  'permanently delete': 'xóa vĩnh viễn',
  'reset': 'đặt lại',
  'revoke': 'thu hồi',
  'Revoking...': 'Đang thu hồi...',

  // -- the rest of the second review ---------------------------------------------------------------
  'Guardrails check what people send, what the agent reads and what it answers.':
    'Rào chắn kiểm tra nội dung người dùng gửi, nội dung tác nhân đọc và nội dung tác nhân trả lời.',
  'Could not record your acceptance. Please try again.':
    'Không thể ghi nhận sự chấp thuận của bạn. Vui lòng thử lại.',
  'You must accept the terms to continue.': 'Bạn phải chấp nhận các điều khoản để tiếp tục.',
  'Failed to sign out.': 'Không thể đăng xuất.',
  'Tick the box to confirm you understand.': 'Hãy tích vào ô để xác nhận bạn đã hiểu.',
  // The performance tiles and the four retrieval stages. BM25 is an algorithm name and stays.
  'Rerank': 'Xếp hạng lại',

  // -- what a destructive action destroys, bullet by bullet -----------------------------------------
  // The `consequences` list every DeleteConfirmModal renders. Written INLINE at each call site, so
  // they belong to no named const and could not be scoped the usual way - the checker learned to
  // read a `name={[ ... ]}` attribute for these. A blanket array match had been tried first and
  // reported twenty Tailwind class lists as untranslated prose.
  'Any client currently using this token will immediately lose access.':
    'Mọi ứng dụng đang dùng token này sẽ mất quyền truy cập ngay lập tức.',
  'Any model role pointing at this provider stops working until you choose another one.':
    'Mọi vai trò mô hình đang trỏ tới nhà cung cấp này sẽ ngừng hoạt động cho tới khi bạn chọn nhà cung cấp khác.',
  'No other provider is selected in its place - that stays your choice.':
    'Không có nhà cung cấp nào được chọn thay thế - đó vẫn là lựa chọn của bạn.',
  'Calls to this model will be estimated at $0 cost going forward.':
    'Từ nay các lượt gọi tới mô hình này sẽ được ước tính với chi phí $0.',

  // -- the About tab's link descriptions, and the guardrail row captions ------------------------------
  // Both arrive as JSX attributes on a shared row component, so the titles were translated and the
  // descriptions beside them were not. Only a per-attribute pattern finds this shape.
  'Installing, configuring and operating Proxyma.':
    'Cài đặt, cấu hình và vận hành Proxyma.',
  'Reaching this instance from your own tools or coding assistant.':
    'Truy cập hệ thống này từ công cụ của riêng bạn hoặc từ trợ lý lập trình.',
  'Where data goes, who can see what, and how to get help.':
    'Dữ liệu đi đâu, ai nhìn thấy được gì, và cách nhận hỗ trợ.',
  'What changed in each version, and what needs action before you upgrade.':
    'Những gì đã thay đổi trong từng phiên bản, và những việc cần làm trước khi nâng cấp.',
  "What data is collected, how it's used, and your rights over it.":
    'Dữ liệu nào được thu thập, được dùng ra sao, và quyền của bạn đối với dữ liệu đó.',
  'The terms that govern your use of this service.':
    'Các điều khoản chi phối việc bạn sử dụng dịch vụ này.',
  'The terms for the services Proxyma runs, such as feedback and diagnostics. This edition itself is under the AGPL.':
    'Các điều khoản cho những dịch vụ Proxyma vận hành, như phản hồi và chẩn đoán. Bản thân phiên bản này theo giấy phép AGPL.',
  'API keys, tokens and private keys. All three stages.':
    'API key, token và khóa riêng tư. Cả ba giai đoạn.',
  'Emails, phone numbers and card numbers. Messages and answers only.':
    'Email, số điện thoại và số thẻ. Chỉ trong tin nhắn và câu trả lời.',
  'Full paths on this machine, appearing in an answer. Answers only.':
    'Đường dẫn đầy đủ trên máy này, xuất hiện trong một câu trả lời. Chỉ trong câu trả lời.',
  'Attempts to talk the agent out of its instructions. A speed bump, not a lock.': 'Các nỗ lực thuyết phục trợ lý từ bỏ chỉ dẫn của nó. Là gờ giảm tốc, không phải ổ khóa.',
  'Refuses any answer repeating a hidden marker. Catches quoting, not paraphrase.': 'Từ chối mọi câu trả lời lặp lại một dấu hiệu ẩn. Bắt được việc trích dẫn, không bắt được diễn đạt lại.',
  'Pauses someone refused several times in a row. Forgotten on restart.': 'Tạm dừng người bị từ chối nhiều lần liên tiếp. Được xóa khi khởi động lại.',

  // -- small words beside a readout --------------------------------------------------------------------
  'synced': 'đã đồng bộ',
  '{done}/{total} indexed': 'đã lập chỉ mục {done}/{total}',
  '{shown} item|{shown} items': '{shown} mục',
  'prioritized': 'ưu tiên',
  'reindexing': 'đang lập chỉ mục lại',
  'building the knowledge graph': 'đang dựng đồ thị tri thức',
  '{count} failed': '{count} mục lỗi',
  'shared': 'được chia sẻ',
  'shared with you': 'được chia sẻ với bạn',

  // -- licensing: the tier gate, the upgrade offer and the activation dialog -----------------------
  'Web page': 'Trang web',
  'before': 'trước khi',

  // -- the shell's gates, chat errors, connector toasts and the sign-in providers ------------------
  'Scheduled maintenance is coming up.': 'Sắp có một đợt bảo trì theo lịch.',
  'starts {time}': 'bắt đầu {time}',
  'expected back by {time}': 'dự kiến hoạt động trở lại trước {time}',
  '{product} is temporarily unavailable': '{product} tạm thời không khả dụng',
  'The system is undergoing maintenance. This will only take a moment.':
    'Hệ thống đang được bảo trì. Việc này chỉ mất một lát.',
  'Expected back by {time}': 'Dự kiến hoạt động trở lại trước {time}',
  'Unlimited': 'Không giới hạn',
  // The calendar in DateTimePicker. These were hardcoded English arrays inside the component
  // until 2026-09-08, reaching no t() at all - so they were in neither of the checker's
  // directions while a Vietnamese admin read 'January' and 'Mo'.
  'Previous month': 'Tháng trước',
  'Next month': 'Tháng sau',
  'Hour': 'Giờ',
  'Minute': 'Phút',
  'AM or PM': 'SA hoặc CH',
  'Show password': 'Hiện mật khẩu',
  'Hide password': 'Ẩn mật khẩu',
  'January': 'Tháng 1',
  'February': 'Tháng 2',
  'March': 'Tháng 3',
  'April': 'Tháng 4',
  'May': 'Tháng 5',
  'June': 'Tháng 6',
  'July': 'Tháng 7',
  'August': 'Tháng 8',
  'September': 'Tháng 9',
  'October': 'Tháng 10',
  'November': 'Tháng 11',
  'December': 'Tháng 12',
  // Monday first, matching the grid. CN is Chu Nhat - Sunday - and is two characters like the
  // rest, which is what keeps the seven columns even.
  'Mo': 'T2',
  'Tu': 'T3',
  'We': 'T4',
  'Th': 'T5',
  'Fr': 'T6',
  'Sa': 'T7',
  'Su': 'CN',
  'Select date & time': 'Chọn ngày & Giờ',
  'Stopped': 'Đã dừng',
  'Server error': 'Lỗi máy chủ',
  "You've reached your session usage limit. Try again after {time}.":
    'Bạn đã đạt giới hạn sử dụng của phiên làm việc. Hãy thử lại sau {time}.',
  "You've reached your weekly usage limit. Try again after {time}.":
    'Bạn đã đạt giới hạn sử dụng theo tuần. Hãy thử lại sau {time}.',
  "You've reached your monthly usage limit. Try again after {time}.":
    'Bạn đã đạt giới hạn sử dụng theo tháng. Hãy thử lại sau {time}.',
  "You've reached your usage limit. Try again after {time}.":
    'Bạn đã đạt giới hạn sử dụng. Hãy thử lại sau {time}.',
  'Server returned {status} ({statusText})': 'Máy chủ trả về {status} ({statusText})',
  'Server returned {status}': 'Máy chủ trả về {status}',
  'An unexpected error occurred.': 'Đã xảy ra một lỗi không mong muốn.',
  'Connection to the server was interrupted.': 'Kết nối tới máy chủ đã bị gián đoạn.',
  'Load the response': 'Tải câu trả lời',
  'Could not reach the server. Try again in a moment.': 'Không kết nối được tới máy chủ. Hãy thử lại sau giây lát.',
  'Session refreshed, resending your message...':
    'Đã làm mới phiên làm việc, đang gửi lại tin nhắn của bạn...',
  'Your session has expired. Please sign in again to continue this conversation.':
    'Phiên làm việc của bạn đã hết hạn. Vui lòng đăng nhập lại để tiếp tục cuộc trò chuyện này.',
  'Your session has expired.': 'Phiên làm việc của bạn đã hết hạn.',
  "Can't condense while a response is in progress.": 'Không thể cô đọng khi một câu trả lời đang được tạo.',
  'Failed to condense the conversation.': 'Không thể cô đọng cuộc trò chuyện.',
  'Could not reach the server to stop this response.':
    'Không thể liên lạc với máy chủ để dừng câu trả lời này.',
  "Can't rewind while a response is still in progress. Stop it first, then try again.":
    'Không thể tua lại khi một câu trả lời vẫn đang được tạo. Hãy dừng nó trước, rồi thử lại.',
  'Connection lost': 'Mất kết nối',
  'A sync is already running for this source': 'Nguồn dữ liệu này đang đồng bộ rồi',
  'Failed to start sync': 'Không thể bắt đầu đồng bộ',
  'Failed to start sync: {reason}': 'Không thể bắt đầu đồng bộ: {reason}',
  'This source will be processed first': 'Nguồn dữ liệu này sẽ được xử lý trước',
  'Priority removed': 'Đã bỏ mức ưu tiên',
  'Failed to update priority': 'Không thể cập nhật mức ưu tiên',
  'Failed to delete the source': 'Không thể xóa nguồn dữ liệu',
  'Re-indexing started': 'Đã bắt đầu lập chỉ mục lại',
  'Failed to start re-indexing': 'Không thể bắt đầu lập chỉ mục lại',
  'Retrying failed items': 'Đang thử lại các mục bị lỗi',
  'Failed to retry items': 'Không thể thử lại các mục',
  "Enjoy using {product}? Leave feedback - it's significant in helping us improve {product}.":
    'Bạn thấy {product} hữu ích chứ? Hãy để lại góp ý - điều đó giúp chúng tôi cải thiện {product} rất nhiều.',
  'Overall experience': 'Trải nghiệm tổng thể',
  'Search result quality': 'Chất lượng kết quả tìm kiếm',
  'Response speed': 'Tốc độ trả lời',
  'Ease of use': 'Mức độ dễ sử dụng',
  'Likelihood to recommend to a colleague': 'Khả năng bạn giới thiệu cho một đồng nghiệp',
  'Continue with {provider}': 'Tiếp tục với {provider}',
  'At least 8 characters': 'Ít nhất 8 ký tự',
  'At least 12 characters': 'Ít nhất 12 ký tự',
  'At least 1 uppercase letter': 'Ít nhất 1 chữ cái viết hoa',
  'At least 1 number': 'Ít nhất 1 chữ số',
  'At least 1 special character': 'Ít nhất 1 ký tự đặc biệt',
  'Passwords match': 'Mật khẩu trùng khớp',
  'Your account was created before we started recording which version of our terms each user accepted. Please review and accept them to continue.':
    'Tài khoản của bạn được tạo trước khi chúng tôi bắt đầu ghi nhận phiên bản điều khoản mà mỗi người dùng đã chấp nhận. Vui lòng xem lại và chấp nhận các điều khoản để tiếp tục.',
  'We have updated our Terms of Service, Privacy Policy and End User License Agreement. Please review and accept the current version to continue.':
    'Chúng tôi đã cập nhật Điều khoản dịch vụ, Chính sách bảo mật và Thỏa thuận cấp phép người dùng cuối (EULA). Vui lòng xem lại và chấp nhận phiên bản hiện hành để tiếp tục.',

  // -- Administration: the system tiles, the users table, backups, maintenance and cost ------------
  'Version': 'Phiên bản',
  'Failed to reset settings': 'Không thể đặt lại thiết lập',
  'Other': 'Khác',
  'Platform': 'Nền tảng',
  // -- Settings: connectors, providers, guardrails, documents, retention and network ---------------
  'Failed to save.': 'Không thể lưu.',
  'Restart failed. Please restart manually.': 'Khởi động lại thất bại. Vui lòng khởi động lại thủ công.',
  'Your data has been deleted.': 'Dữ liệu của bạn đã được xóa.',
  'Deletion requested. Your account will be permanently deleted in {days} days unless you recover it.':
    'Đã gửi yêu cầu xóa. Tài khoản của bạn sẽ bị xóa vĩnh viễn sau {days} ngày trừ khi bạn khôi phục lại.',
  'Permanently delete your data. Your account stays open since it is managed by SSO.':
    'Xóa vĩnh viễn dữ liệu của bạn. Tài khoản vẫn được giữ vì nó do SSO quản lý.',
  'Permanently delete your account and all associated data.':
    'Xóa vĩnh viễn tài khoản của bạn và toàn bộ dữ liệu liên quan.',
  'Connector Management': 'Quản lý bộ kết nối',
  'API Token': 'API Token',
  'Connection failed - check the base URL and API key.':
    'Kết nối thất bại - hãy kiểm tra URL gốc và API key.',
  'Connection failed - check the base URL.': 'Kết nối thất bại - hãy kiểm tra URL gốc.',
  'Could not save this provider.': 'Không thể lưu nhà cung cấp này.',
  'Edit provider': 'Sửa nhà cung cấp',
  'Blank': 'Để trống',
  'Leave blank for this protocol default': 'Để trống để dùng giá trị mặc định của giao thức này',
  'Stored ({masked}) - type to replace': 'Đã lưu ({masked}) - hãy nhập để thay thế',
  'Default address': 'Địa chỉ mặc định',
  'key {masked}': 'khóa {masked}',
  'no key': 'không có khóa',
  'Reachable - {count} model.|Reachable - {count} models.':
    'Kết nối được - {count} mô hình.|Kết nối được - {count} mô hình.',
  'Web search': 'Tìm kiếm web',
  'Lets the chat agent search the web and fetch URLs, with nothing configured. A Google Custom Search API key and Search Engine ID make search more reliable.':
    'Cho phép tác nhân trò chuyện tìm kiếm trên web và tải các URL mà không cần cấu hình. API key Google Custom Search và Search Engine ID giúp tìm kiếm ổn định hơn.',
  'Search engine ID': 'Search Engine ID',
  'Concise': 'Súc tích',
  'Normal': 'Bình thường',
  'Creative': 'Sáng tạo',
  'Agent provider': 'Nhà cung cấp cho tác nhân',
  'Agent model': 'Mô hình cho tác nhân',
  'Enforce': 'Áp dụng',
  'Monitor only': 'Chỉ theo dõi',
  'Block the turn': 'Chặn cả lượt',
  'Remove and continue': 'Loại bỏ và tiếp tục',
  'Withhold the whole source': 'Giữ lại toàn bộ nguồn dữ liệu',
  'Guardrail provider': 'Nhà cung cấp cho rào chắn',
  'Guardrail model': 'Mô hình cho rào chắn',
  'Nothing is checked until a model is chosen. The pattern checks above keep working either way.':
    'Không có gì được kiểm tra cho tới khi một mô hình được chọn. Các kiểm tra theo mẫu ở trên vẫn hoạt động trong cả hai trường hợp.',
  'relevant': 'có liên quan',
  'Up to {size} MB each; rebuild the index after changing the embedding model.':
    'Tối đa {size} MB mỗi tệp tin; hãy dựng lại chỉ mục sau khi đổi mô hình vector nhúng (embeddings).',
  'Nothing here yet. Until a document is added, this layer does nothing and costs nothing.':
    'Chưa có gì ở đây. Cho đến khi có tài liệu, lớp này không làm gì và không tốn chi phí.',
  'Could not add that document.': 'Không thể thêm tài liệu đó.',
  '{count} passage indexed|{count} passages indexed':
    'Đã lập chỉ mục {count} đoạn văn bản|Đã lập chỉ mục {count} đoạn văn bản',
  'Could not be indexed': 'Không thể lập chỉ mục',
  'Sub-agent provider': 'Nhà cung cấp cho tác nhân phụ',
  'Sub-agent model': 'Mô hình cho tác nhân phụ',
  "Leave the model empty to use the agent's. A cheaper, faster model here keeps long reading off the agent's price. It also titles new conversations.":
    'Để trống mô hình để dùng mô hình của tác nhân chính. Một mô hình rẻ hơn, nhanh hơn ở đây giúp việc đọc dài không tính theo giá của tác nhân chính. Nó cũng đặt tiêu đề cho các cuộc trò chuyện mới.',
  "becomes this deployment's name - keep it as it is. The section on delegating to sub-agents is added to this prompt automatically.":
    'trở thành tên của bản triển khai này - hãy giữ nguyên. Phần về việc giao việc cho tác nhân phụ được tự động thêm vào lời nhắc này.',
  'Auto (extract text, OCR if scanned)': 'Tự động (trích xuất văn bản, dùng OCR nếu là bản quét)',
  'pdfplumber (table-aware PDFs)': 'pdfplumber (PDF có nhận biết bảng)',
  'OCR (always)': 'OCR (luôn luôn)',
  'Vision model': 'Mô hình thị giác',
  'Free and offline, but flat text only. Needs tesseract, its language data and pytesseract installed on the server.':
    'Miễn phí và ngoại tuyến, nhưng chỉ trả về văn bản phẳng. Cần cài tesseract, dữ liệu ngôn ngữ của nó và pytesseract trên máy chủ.',
  'Reads layout, tables and handwriting far better, but costs one model call per page. Requires a vision model.':
    'Đọc bố cục, bảng biểu và chữ viết tay tốt hơn nhiều, nhưng tốn một lượt gọi mô hình cho mỗi trang. Yêu cầu một mô hình thị giác.',
  'OCR provider': 'Nhà cung cấp cho OCR',
  'OCR model': 'Mô hình cho OCR',
  'Must be a vision-capable model. Leave blank to leave OCR switched off.':
    'Phải là mô hình có khả năng thị giác. Để trống để tắt OCR.',
  'Embedding provider': 'Nhà cung cấp cho vector nhúng',
  'Embedding model': 'Mô hình vector nhúng (embeddings)',
  'Query expansion provider': 'Nhà cung cấp cho mở rộng truy vấn',
  'Query expansion model': 'Mô hình mở rộng truy vấn',
  'Reranking provider': 'Nhà cung cấp cho xếp hạng lại',
  'Reranking model': 'Mô hình xếp hạng lại',
  'Extraction provider': 'Nhà cung cấp cho trích xuất',
  'Extraction model': 'Mô hình trích xuất',
  'How long each kind of data is kept, swept daily.': 'Mỗi loại dữ liệu được giữ trong bao lâu, được dọn dẹp hàng ngày.',
  '0 keeps it forever': '0 nghĩa là giữ mãi mãi',
  'Idle conversations. Pinned ones are never deleted.':
    'Các cuộc trò chuyện không hoạt động. Những cuộc đã ghim thì không bao giờ bị xóa.',
  'Evaluation data (days)': 'Dữ liệu đánh giá (ngày)',
  'Per-turn and per-search records.': 'Bản ghi theo từng lượt và theo từng lần tìm kiếm.',
  'Usage records (days)': 'Bản ghi sử dụng (ngày)',
  'Submitted feedback and its attachments.': 'Góp ý đã gửi và các tệp tin đính kèm.',
  'System proxy': 'Proxy hệ thống',
  'Custom proxy': 'Proxy tùy chỉnh',
  'Uses proxy settings from environment variables:': 'Dùng cấu hình proxy từ các biến môi trường:',
  'Edit pricing for {provider} / {model}': 'Sửa bảng giá cho {provider} / {model}',
  'Delete pricing for {provider} / {model}': 'Xóa bảng giá cho {provider} / {model}',
  'Failed to update pricing': 'Không thể cập nhật bảng giá',
  'Failed to add pricing': 'Không thể thêm bảng giá',

  // -- one more save-state word --------------------------------------------------------------------
  'Saved.': 'Đã lưu.',
  'Saving {field}...': 'Đang lưu {field}...',
  '{count} of those would have been withheld, but monitor mode let them through.': '{count} trong số đó lẽ ra đã bị chặn, nhưng chế độ giám sát đã cho qua.',
  'The classifier failed and let 1 turn through. Check that its provider is reachable.|The classifier failed and let {count} turns through. Check that its provider is reachable.': 'Bộ phân loại gặp lỗi và đã cho qua {count} lượt. Hãy kiểm tra xem có kết nối được tới nhà cung cấp của nó không.',
  'Calculated from configured rates and counted tokens, not from provider billing. Check the provider dashboard for actual charges.': 'Tính từ đơn giá đã cấu hình và số token đã đếm, không phải từ hóa đơn của nhà cung cấp. Xem bảng điều khiển của nhà cung cấp để biết chi phí thực tế.',
  'This installation updates by downloading the new release, not in place.': 'Bản cài đặt này cập nhật bằng cách tải về bản phát hành mới, không cập nhật tại chỗ.',

  // -- the product tour, the What's New panel, and the last of the settings pages ------------------
  '0 = no limit': '0 = không giới hạn',
  '100% on-premise': '100% trên hạ tầng của bạn',
  "A source can point at its own server and use its own credential - set it in that source's Configure dialog.":
    'Một nguồn dữ liệu có thể trỏ tới máy chủ riêng của nó và dùng thông tin đăng nhập riêng - hãy đặt điều đó trong hộp thoại Cấu hình của nguồn dữ liệu đó.',
  'An ordered set of steps the agent runs one at a time. Type "/" in the chat box and pick it by name.':
    'Một chuỗi các bước có thứ tự mà tác nhân thực hiện lần lượt. Gõ "/" trong ô trò chuyện và chọn quy trình theo tên.',
  'A workflow needs at least one step.': 'Một quy trình cần ít nhất một bước.',
  'At least {count} characters': 'Ít nhất {count} ký tự',
  'Check now and Download on the Updates page show that they are working.':
    'Nút Kiểm tra ngay và Tải về trên trang Cập nhật đã cho thấy chúng đang hoạt động.',
  'Configuration exported without credentials.': 'Đã xuất cấu hình mà không kèm thông tin đăng nhập.',
  'Configuration exported. Keep the passphrase - nothing else can open the file.':
    'Đã xuất cấu hình. Hãy giữ cụm mật khẩu - không có gì khác mở được tệp tin này.',
  'Configuration imported.': 'Đã nhập cấu hình.',
  'Configuration imported. Some of it needs a restart to take effect.':
    'Đã nhập cấu hình. Một phần trong đó cần khởi động lại mới có hiệu lực.',
  'Conversations are named after what you asked about.':
    'Các cuộc trò chuyện được đặt tên theo nội dung bạn đã hỏi.',
  'Could not export the configuration.': 'Không thể xuất cấu hình.',
  'Could not import the configuration.': 'Không thể nhập cấu hình.',
  'Could not read that file.': 'Không thể đọc tệp tin đó.',
  'Crawl depth must be a whole number between 0 and {max}.':
    'Độ sâu thu thập phải là một số nguyên từ 0 đến {max}.',
  'Day of month (1-28)': 'Ngày trong tháng (1-28)',
  'Enable {connector} write tools': 'Bật công cụ ghi của {connector}',
  'Failed to create skill': 'Không thể tạo kỹ năng',
  'Failed to create workflow': 'Không thể tạo quy trình',
  'Failed to reset': 'Không thể đặt lại',
  'Failed to update skill': 'Không thể cập nhật kỹ năng',
  'Failed to update workflow': 'Không thể cập nhật quy trình',
  'Linux installs from a Debian package and updates itself from Settings > Updates.':
    'Bản Linux cài đặt từ một gói Debian và tự cập nhật từ Cài đặt > Cập nhật.',
  'Made by {product}': 'Do {product} tạo',
  'Main chat model': 'Mô hình trò chuyện chính',
  'Move step {number} down': 'Chuyển bước {number} xuống dưới',
  'Move step {number} up': 'Chuyển bước {number} lên trên',
  'Must be a whole number between 0 and {max}': 'Phải là một số nguyên từ 0 đến {max}',
  'Must be a whole number between 1 and {max}': 'Phải là một số nguyên từ 1 đến {max}',
  'Name is required': 'Vui lòng nhập tên',

  'Only if you want a reply.': 'Chỉ điền nếu bạn muốn nhận phản hồi.',
  'Page limit must be a whole number between 1 and {max}.':
    'Giới hạn số trang phải là một số nguyên từ 1 đến {max}.',
  'Pick a model from this provider, or set the provider back to the chat model.':
    'Hãy chọn một mô hình từ nhà cung cấp này, hoặc đặt nhà cung cấp trở lại thành mô hình trò chuyện.',
  'Proxyma is up to date': 'Proxyma đã là phiên bản mới nhất',
  'Proxyma now tells you what changed after an update.':
    'Từ nay Proxyma sẽ cho bạn biết những gì đã thay đổi sau mỗi lần cập nhật.',
  'Preparing the download...': 'Đang chuẩn bị tải xuống...',
  'Proxyma {version} is available': 'Đã có Proxyma {version}',
  'Proxyma {version} is available to download': 'Đã có Proxyma {version} để tải về',
  'Proxyma {version} is ready to install': 'Proxyma {version} đã sẵn sàng để cài đặt',
  'Rate limited - retrying in {seconds}s...': 'Bị giới hạn tần suất - sẽ thử lại sau {seconds} giây...',
  'Reindexing to a new model - old results still shown until it finishes':
    'Đang lập chỉ mục lại sang một mô hình mới - kết quả cũ vẫn được hiển thị cho tới khi hoàn tất',
  'Reindexing to {model} - old results still shown until it finishes':
    'Đang lập chỉ mục lại sang {model} - kết quả cũ vẫn được hiển thị cho tới khi hoàn tất',
  'Remove step {number}': 'Xóa bước {number}',
  'Restart and install {version}': 'Khởi động lại và cài đặt {version}',
  'Short description is required': 'Vui lòng nhập mô tả ngắn',
  'Sources that live on more than one server, and Linux gets a proper installer.':
    'Các nguồn dữ liệu nằm trên nhiều máy chủ khác nhau, và Linux đã có một bộ cài đặt đúng nghĩa.',
  'That file could not be read.': 'Không thể đọc tệp tin đó.',
  'That file is not a Proxyma configuration export.':
    'Tệp tin đó không phải là một bản xuất cấu hình của Proxyma.',
  'These files match content already indexed. Skipping saves storage quota; uploading anyway keeps your own copy.':
    'Các tệp tin này trùng với nội dung đã được lập chỉ mục. Bỏ qua sẽ tiết kiệm hạn mức lưu trữ; vẫn tải lên thì bạn giữ bản sao của riêng mình.',

  'The last check did not finish': 'Lần kiểm tra gần nhất chưa hoàn tất',
  'This panel, the first time you open a new version.':
    'Chính bảng thông tin này, vào lần đầu tiên bạn mở một phiên bản mới.',
  'You are running': 'Bạn đang chạy',
  'You must review and confirm each write action': 'Bạn phải xem lại và xác nhận từng hành động ghi',
  'all settings': 'toàn bộ thiết lập',
  'another source': 'một nguồn dữ liệu khác',
  'everything Proxyma has stored': 'toàn bộ những gì Proxyma đã lưu',
  'happens for real, immediately': 'xảy ra thật, ngay lập tức',
  'hard stop': 'giới hạn cứng',
  'last checked {time}': 'kiểm tra lần cuối lúc {time}',
  'not checked yet in this session': 'chưa kiểm tra lần nào trong phiên làm việc này',
  'you are solely responsible': 'bạn là người chịu trách nhiệm duy nhất',
  '{count} call|{count} calls': '{count} lượt gọi|{count} lượt gọi',

  // -- the last option lists, and the words that are the same in both languages --------------------
  'Admin': 'Quản trị viên',
  'Never - only when I ask': 'Không bao giờ - chỉ khi tôi yêu cầu',
  'Every 6 hours': '6 giờ một lần',
  'Once a day': 'Mỗi ngày một lần',
  'Once a week': 'Mỗi tuần một lần',
  'Providers, models and API keys return to defaults - you will need to configure them again':
    'Nhà cung cấp, mô hình và API key trở về mặc định - bạn sẽ phải cấu hình lại chúng',
  'Connectors, indexed documents and chat history are kept':
    'Bộ kết nối, tài liệu đã lập chỉ mục và lịch sử trò chuyện được giữ lại',
  'Proxyma restarts when this finishes': 'Proxyma sẽ khởi động lại khi việc này hoàn tất',
  'Every connector and everything indexed from it will be deleted':
    'Mọi bộ kết nối và toàn bộ dữ liệu đã lập chỉ mục từ chúng sẽ bị xóa',
  'All chat history and conversations will be deleted':
    'Toàn bộ lịch sử trò chuyện và các cuộc trò chuyện sẽ bị xóa',
  'All settings return to defaults': 'Toàn bộ thiết lập trở về mặc định',
  'Your own files on disk are NOT touched': 'Các tệp tin của chính bạn trên ổ đĩa KHÔNG bị đụng tới',
  'Proxyma restarts into a first-run state when this finishes':
    'Proxyma sẽ khởi động lại về trạng thái chạy lần đầu khi việc này hoàn tất',
  'Markitdown': 'Markitdown',
  'Tesseract': 'Tesseract',

  // -- the interrupted-tool placeholder and the credential-free export note ------------------------
  'Interrupted - this turn ended before the tool reported back':
    'Bị gián đoạn - lượt này đã kết thúc trước khi công cụ báo kết quả về',
  // -- the knowledge graph, seen rather than traversed (2026-09-06) ---------------------------
  // The two views the source modal offers - one item's graph, and every item in the source in one
  // picture - plus the source-wide chunk list beside them. 'do thi tri thuc' matches the settings
  // tab's own 'Knowledge Graph' entry above; 'doan' is the word this catalog already uses for a
  // chunk ('{count} passage indexed').
  'View the knowledge graph': 'Xem đồ thị tri thức',
  'View indexed chunks': 'Xem các đoạn đã lập chỉ mục',
  'What this item put into the knowledge graph.': 'Những gì mục này đã đưa vào đồ thị tri thức.',
  'Every item in this source, in one graph. A thing two files both mention is one node here.':
    'Mọi mục trong nguồn dữ liệu này, gộp trong một đồ thị. Thứ mà hai tệp tin cùng nhắc tới sẽ là một nút duy nhất ở đây.',
  'Every indexed chunk in this source. Open an item to read what it was split into.':
    'Toàn bộ các đoạn đã lập chỉ mục trong nguồn dữ liệu này. Hãy mở một mục để đọc nội dung đã được chia.',
  'The indexed text of this document, as it was split for search.': 'Nội dung đã lập chỉ mục của tài liệu này, theo cách nó được chia nhỏ để tìm kiếm.',
  '{entities} entities, {relations} relationships.': '{entities} thực thể, {relations} mối quan hệ.',
  'Showing the {count} most connected - raise the star limit to see more.':
    'Đang hiển thị {count} thực thể có nhiều liên kết nhất - hãy nâng giới hạn số sao để xem thêm.',
  'Drawing {done} of {total}...': 'Đang vẽ {done} trên {total}...',
  'Stars': 'Số sao',
  'How many of the most connected entities to draw':
    'Vẽ bao nhiêu thực thể có nhiều liên kết nhất',
  'Nothing in the knowledge graph for this yet.': 'Chưa có gì trong đồ thị tri thức cho mục này.',
  // Double-quoted on both sides: the English carries an apostrophe AND a quoted phrase, and a
  // single-quoted literal would need the apostrophe escaped - which is exactly the shape the
  // checker's parser has been wrong about before (see .claude/rules/frontend.md).
  "Turn on \"Build a knowledge graph\" in this source's settings. Items already indexed are extracted in the background.":
    'Hãy bật "Xây dựng đồ thị tri thức" trong cài đặt của nguồn dữ liệu này. Các mục đã lập chỉ mục sẽ được trích xuất ở chế độ nền.',
  'Failed to load the graph': 'Không thể tải đồ thị',
  // The gesture line. Rewritten with the 3D view: dragging now ROTATES and panning moved to
  // shift-drag, and dragging a node to move it is gone entirely - in three dimensions a
  // screen-space drag names a ray rather than a point. The old entry is deleted rather than left
  // behind, which is what the checker's orphan direction is for.
  'Drag to rotate, scroll to zoom, shift-drag to pan':
    'Kéo để xoay, cuộn để phóng to, giữ Shift và kéo để di chuyển khung nhìn',
  'Bigger and brighter means more connections': 'Càng lớn và càng sáng thì càng nhiều kết nối',
  // The view controls over the canvas.
  'Rotate automatically': 'Tự động xoay',
  'Stop rotating': 'Dừng xoay',
  'Show labels': 'Hiện nhãn',
  'Hide labels': 'Ẩn nhãn',
  'Center the view': 'Đưa khung nhìn về giữa',
  'Reset the view': 'Đặt lại khung nhìn',
  // How many relationships out from the selected node stay visible.
  'Depth': 'Độ sâu',
  'Select a node first': 'Hãy chọn một nút trước',
  // The two spacing sliders - within a cluster, and between clusters. The metaphor is kept in
  // Vietnamese rather than flattened to "nhóm": the picture is a star field, and the control
  // names are what tell a reader that the clumps mean something.
  'Star spacing': 'Khoảng cách giữa các ngôi sao',
  'Galaxy spacing': 'Khoảng cách giữa các thiên hà',
  'Click another node to follow it, or the background to clear':
    'Bấm vào một nút khác để đi tiếp, hoặc bấm nền để bỏ chọn',
  'Unclassified': 'Chưa phân loại',
  // Vietnamese does not inflect for number, so both forms are the same phrase - see the plural
  // note in libs/ui-kit/i18n/translate.ts.
  '{count} mention|{count} mentions': '{count} lần nhắc tới',
  '{count} link|{count} links': '{count} liên kết',
  '{count} chunk|{count} chunks': '{count} đoạn',
  'Chunk {number}': 'Đoạn {number}',
  // The two metadata-row buttons. Lower case because they sit in a run of lower-case metadata
  // ('synced 4 min ago', 'shared'), not in the action row above it.

  // Usage & Cost: the per-call ledger, shown in Settings on both editions and in
  // Administration on Enterprise Server. 'In'/'Out' are column headings for input and
  // output tokens, kept short because the columns are narrow and the figures carry them.
  'Your own calls only.': 'Chỉ các lượt gọi của riêng bạn.',
  'No calls in this period.': 'Không có lượt gọi nào trong khoảng thời gian này.',
  'Last 12 months': '12 tháng qua',
  'By provider': 'Theo nhà cung cấp',
  'By model': 'Theo mô hình',
  'By service': 'Theo dịch vụ',
  'By who': 'Theo người dùng',
  'When': 'Thời điểm',
  'Service': 'Dịch vụ',
  'Who': 'Người dùng',
  'In': 'Vào',
  'Out': 'Ra',
  'Cost': 'Chi phí',
  'Signed-out visitors': 'Khách chưa đăng nhập',

  // Knowledge graph: the per-chunk call timeout, surfaced in Settings > Search on
  // 2026-09-16 - three log messages had been telling operators to raise a setting no
  // screen offered.
  'Extraction timeout': 'Thời gian chờ trích xuất',
  'How long one chunk may take before it is given up on. Raise it for local or reasoning models; timed-out sources are retried later.':
    'Thời gian tối đa cho một đoạn trước khi bỏ qua. Hãy tăng lên với mô hình cục bộ hoặc mô hình suy luận; các nguồn bị quá thời gian sẽ được thử lại sau.',

  // The knowledge graph's live progress line - see ConnectorSettingsModal.
  'Building the knowledge graph - passage {done} of {total}':
    'Đang dựng đồ thị tri thức - đoạn {done} trên {total}',
  'Building the knowledge graph': 'Đang dựng đồ thị tri thức',

  // Shared dialog behavior (components/ui/ModalShell.tsx): the unsaved-changes guard.
  'Discard unsaved changes?': 'Bỏ các thay đổi chưa lưu?',
  'Your changes have not been saved.': 'Các thay đổi của bạn chưa được lưu.',
  'Keep editing': 'Tiếp tục chỉnh sửa',
  'Discard': 'Bỏ thay đổi',
  // The image lightbox's accessible name when the image has no alt text.
  'Image': 'Hình ảnh',
  // A picture in an answer that lives on another host waits for a click that names the host; a
  // sound or a video there, or a picture that did not load, is a link (MarkdownRenderer's RemoteMedia).
  'Load image from {host}': 'Tải hình ảnh từ {host}',
  'Image from {host}': 'Hình ảnh từ {host}',
  'Video from {host}': 'Video từ {host}',
  'Audio from {host}': 'Âm thanh từ {host}',
  // A render error: the whole app, one page, one chat message (components/ui/ErrorBoundaries.tsx).
  'Something went wrong on this page. Reloading usually fixes it.': 'Đã xảy ra lỗi trên trang này. Tải lại trang thường sẽ khắc phục được.',
  'Something went wrong on this page.': 'Đã xảy ra lỗi trên trang này.',
  'Reloading usually fixes it.': 'Tải lại trang thường sẽ khắc phục được.',
  'A new version is available. Reloading...': 'Đã có phiên bản mới. Đang tải lại...',
  'This message could not be shown.': 'Không thể hiển thị tin nhắn này.',
  'Reload': 'Tải lại',

  // -- Connections ------------------------------------------------------------------------------
  // Settings > Connections lists the user's connections (servers, or Microsoft 365 sign-ins), and
  // every source names the one it reads through. "Connection" is "kết nối"; the tab itself stays
  // "Bộ kết nối".
  'Add connection': 'Thêm kết nối',
  'Edit connection': 'Sửa kết nối',
  'No connections yet.': 'Chưa có kết nối nào.',
  'The servers your sources and the agent reach, each with your own credential. Choose what the agent and MCP clients may do through each.':
    'Các máy chủ mà nguồn dữ liệu của bạn và tác nhân truy cập, mỗi máy chủ với thông tin đăng nhập riêng của bạn. Chọn những gì tác nhân và ứng dụng MCP được phép làm qua từng kết nối.',
  'Let the agent register and remove sources of any type on your behalf. Removing a source deletes its index.':
    'Cho phép tác nhân thay mặt bạn đăng ký và gỡ bỏ nguồn dữ liệu thuộc bất kỳ loại nào. Gỡ bỏ một nguồn sẽ xóa chỉ mục của nó.',
  'Default': 'Mặc định',
  'Not signed in': 'Chưa đăng nhập',
  'Signed in as {account}': 'Đã đăng nhập với tư cách {account}',
  'token {masked}': 'token {masked}',
  'no token': 'chưa có token',
  'no username': 'chưa có tên người dùng',
  'no password': 'chưa có mật khẩu',
  'agent may read and write': 'tác nhân được đọc và ghi',
  'agent may read': 'tác nhân được đọc',
  'agent may write': 'tác nhân được ghi',
  'agent has no access': 'tác nhân không có quyền truy cập',
  'Sign in again': 'Đăng nhập lại',
  'Sign in again to keep its sources syncing.': 'Hãy đăng nhập lại để các nguồn dữ liệu của kết nối này tiếp tục được đồng bộ.',
  'Signed out of {name}.': 'Đã đăng xuất khỏi {name}.',
  'Could not sign out of {name}.': 'Không thể đăng xuất khỏi {name}.',
  'Could not test this connection.': 'Không thể kiểm tra kết nối này.',
  'Could not delete this connection.': 'Không thể xóa kết nối này.',
  'Could not save this connection.': 'Không thể lưu kết nối này.',
  'Delete connection': 'Xóa kết nối',
  'Its credential and Read/Write switches are deleted with it.':
    'Thông tin đăng nhập và các công tắc Đọc/Ghi của nó sẽ bị xóa cùng.',
  'A connection still used by a source cannot be deleted - remove or move those sources first.':
    'Không thể xóa một kết nối vẫn đang được nguồn dữ liệu sử dụng - hãy gỡ bỏ hoặc chuyển các nguồn đó trước.',
  // The Add/Edit connection dialog.
  'Username': 'Tên người dùng',
  'A personal access token for this server. Yours alone - it is never shared with other users.':
    'Một personal access token cho máy chủ này. Của riêng bạn - nó không bao giờ được chia sẻ với người dùng khác.',
  "The repository root. Each SVN source's path is added to it.":
    'Thư mục gốc của kho mã. Đường dẫn của mỗi nguồn SVN được nối vào sau nó.',
  "Windows Authentication takes a domain-qualified name (DOMAIN\\you); Subversion Authentication takes a name from the server's own user list.":
    'Windows Authentication dùng tên kèm miền (DOMAIN\\you); Subversion Authentication dùng một tên trong danh sách người dùng của chính máy chủ.',
  'Remove the stored credential': 'Gỡ bỏ thông tin đăng nhập đã lưu',
  'Keep the stored credential': 'Giữ thông tin đăng nhập đã lưu',
  'The stored credential is removed when you save.': 'Thông tin đăng nhập đã lưu sẽ bị gỡ bỏ khi bạn lưu.',
  'Signed in as {account}. Sign in again from the connection list to use a different account.':
    'Đã đăng nhập với tư cách {account}. Hãy đăng nhập lại từ danh sách kết nối để dùng một tài khoản khác.',
  'Not signed in yet. Use Sign in on the connection list.': 'Chưa đăng nhập. Hãy dùng Đăng nhập trong danh sách kết nối.',
  'Use as default for {service}': 'Dùng làm mặc định cho {service}',
  'Your first {service} connection is the default.': 'Kết nối {service} đầu tiên của bạn là kết nối mặc định.',
  'To change it, make another connection the default.': 'Để thay đổi, hãy đặt một kết nối khác làm mặc định.',
  'A source that names no connection, and the agent, use the default.':
    'Nguồn dữ liệu không chỉ định kết nối, và tác nhân, sẽ dùng kết nối mặc định.',
  'Allow agent to read': 'Cho phép tác nhân đọc',
  'Allow agent to write': 'Cho phép tác nhân ghi',
  'Let the agent search and read {service} through this connection, from chat and from an attached MCP client.':
    'Cho phép tác nhân tìm kiếm và đọc {service} qua kết nối này, từ cửa sổ trò chuyện và từ một ứng dụng MCP đang kết nối.',
  'Let the agent create, update and delete {service} content through this connection, on your behalf.':
    'Cho phép tác nhân tạo, cập nhật và xóa nội dung {service} qua kết nối này, thay mặt bạn.',
  // The Connection field of Add Source and of a source's settings.
  'Add a connection': 'Thêm một kết nối',
  '{name} (default)': '{name} (mặc định)',
  'You have no {service} connection yet. A source reads through one - the server and the credential it is reached with.':
    'Bạn chưa có kết nối {service} nào. Mỗi nguồn dữ liệu đọc qua một kết nối - máy chủ và thông tin đăng nhập dùng để truy cập nó.',
  'Sign in to {name} first. This source reads as the account signed in there.':
    'Hãy đăng nhập vào {name} trước. Nguồn dữ liệu này đọc với tư cách tài khoản đã đăng nhập ở đó.',
  'Sign in to {name} again. This source reads as the account signed in there.':
    'Hãy đăng nhập lại vào {name}. Nguồn dữ liệu này đọc với tư cách tài khoản đã đăng nhập ở đó.',
  'via {connection}': 'qua {connection}',
  'Configure connection': 'Cấu hình kết nối',
  'Configure connection and file types': 'Cấu hình kết nối và loại tệp tin',
  'Configure connection, file types and subfolder scanning': 'Cấu hình kết nối, loại tệp tin và việc quét thư mục con',

  // -- Connection Test verdicts (connectionTestMessage.ts) and the source dialog's item rows --------
  'Signed in as {user}': 'Đã đăng nhập với tên {user}',
  'Connected': 'Đã kết nối',
  'Set the server URL first.': 'Hãy nhập địa chỉ máy chủ trước.',
  'Enter a username and password first.': 'Hãy nhập tên đăng nhập và mật khẩu trước.',
  'Enter an access token first.': 'Hãy nhập access token trước.',
  '{service} did not accept the token.': '{service} không chấp nhận token này.',
  'Signed in to {workspace} as {user}': 'Đã đăng nhập vào {workspace} với tên {user}',
  'Signed in as {user} ({account})': 'Đã đăng nhập với tên {user} ({account})',
  '{service} did not accept the user token.': '{service} không chấp nhận user token này.',
  '{service} did not accept the username or password.': '{service} không chấp nhận tên đăng nhập hoặc mật khẩu.',
  '{service} refused this account (403).': '{service} từ chối tài khoản này (403).',
  "Nothing answered at that URL (404). Check it is the server's root address.":
    'Không có gì phản hồi tại địa chỉ đó (404). Hãy kiểm tra đó là địa chỉ gốc của máy chủ.',
  '{service} answered {status}.': '{service} phản hồi mã {status}.',
  'Could not reach {url}: {reason}': 'Không thể kết nối tới {url}: {reason}',
  "The server's certificate expired on {date}. Its administrator has to renew it.":
    'Chứng chỉ của máy chủ đã hết hạn vào {date}. Quản trị viên của máy chủ cần gia hạn chứng chỉ.',
  "The server's certificate has expired. Its administrator has to renew it.":
    'Chứng chỉ của máy chủ đã hết hạn. Quản trị viên của máy chủ cần gia hạn chứng chỉ.',
  "The server's certificate is not valid yet. Check this computer's date and time.":
    'Chứng chỉ của máy chủ chưa có hiệu lực. Hãy kiểm tra ngày giờ của máy tính này.',
  "This computer does not trust the server's certificate. If your company uses its own certificate authority, add it in Settings > Network.":
    'Máy tính này không tin cậy chứng chỉ của máy chủ. Nếu công ty bạn dùng tổ chức cấp chứng chỉ (CA) riêng, hãy thêm nó trong Cài đặt > Mạng.',
  "The server answered with a page instead of its API. Check the URL is the server's own address.":
    'Máy chủ trả về một trang web thay vì API. Hãy kiểm tra địa chỉ đúng là địa chỉ của chính máy chủ.',
  'This connection type cannot be tested here.': 'Không thể kiểm tra loại kết nối này tại đây.',
  'This connection type has no test.': 'Loại kết nối này không có chức năng kiểm tra.',
  'Sign in to Microsoft 365 first.': 'Hãy đăng nhập Microsoft 365 trước.',
  'Microsoft 365 could not be reached: {reason}': 'Không thể kết nối tới Microsoft 365: {reason}',
  'indexed {when}': 'lập chỉ mục {when}',
  'modified {when}': 'sửa đổi {when}',
  'added {when}': 'thêm vào {when}',
  'Get started': 'Bắt đầu',
  'Add a source': 'Thêm nguồn dữ liệu',
  'Set up a model': 'Thiết lập mô hình',
  'Help': 'Trợ giúp',
  'No matching sources': 'Không có nguồn nào phù hợp',
  // -- The composer's permission mode (PermissionModePicker.tsx) -----------------------------------
  'Ask': 'Hỏi trước',
  'Auto': 'Tự động',
  'Read-only': 'Chỉ đọc',
  'Asks before every change': 'Hỏi trước mỗi thay đổi',
  'Makes changes without asking': 'Thực hiện thay đổi mà không hỏi',
  'Reads only, never changes anything': 'Chỉ đọc, không bao giờ thay đổi gì',
  'Permission: {mode}': 'Quyền: {mode}',
  // -- Conversations running side by side (useWorkingAgents, ChatSessionList) ---------------------
  '{count} conversation still working - go to the latest|{count} conversations still working - go to the latest':
    '{count} cuộc trò chuyện vẫn đang chạy - đi tới cuộc mới nhất',
  'Still working': 'Vẫn đang chạy',
  // -- What's New, 0.19.0 --------------------------------------------------------------------------
  'Run conversations side by side, and choose how much the assistant may change.':
    'Chạy các cuộc trò chuyện song song, và chọn mức độ trợ lý được phép thay đổi.',
  'Leave a conversation working and start another; a badge beside Chat counts the ones still running.':
    'Để một cuộc trò chuyện tiếp tục làm việc và bắt đầu cuộc khác; huy hiệu bên cạnh Chat đếm số cuộc còn đang chạy.',
  'Beside the model, choose Ask, Auto or Read-only.': 'Bên cạnh mô hình, chọn Hỏi trước, Tự động hoặc Chỉ đọc.',
  'What you were typing in the chat is kept when you go to another page.':
    'Những gì bạn đang gõ trong khung trò chuyện được giữ lại khi bạn chuyển sang trang khác.',
  // -- Administration: Overview, Usage & Cost and the cost centers (2026-09-29) -----------------
  'Calls': 'Lượt gọi',
  'Guardrail checks': 'Kiểm tra an toàn',
  'Overview': 'Tổng quan',
  '{used} of {limit}': '{used} / {limit}',

  // -- the public Enterprise sandbox: banner and sign-in accounts ------------------
  'Public sandbox - other visitors can see what you add, and everything is deleted every night.': 'Môi trường dùng thử công khai - khách khác có thể thấy những gì bạn thêm, và mọi thứ bị xóa mỗi đêm.',
  'Do not enter real credentials or confidential data.': 'Đừng nhập thông tin đăng nhập thật hoặc dữ liệu bảo mật.',
  'Try it as': 'Dùng thử với vai trò',
  'Super admin': 'Quản trị viên cấp cao',
  'Member': 'Thành viên',
  'Sees every screen, changes nothing': 'Xem mọi màn hình, không thay đổi được gì',
  'Manages users and guardrails': 'Quản lý người dùng và rào chắn',
  'Chats, uploads and adds sources': 'Trò chuyện, tải tệp lên và thêm nguồn',
  'Password for all three: {password}. Or create your own account.': 'Mật khẩu chung cho cả ba: {password}. Hoặc tạo tài khoản riêng của bạn.',

  // -- Administration > Overview: the Feature / Tool Usage ranking ------------------
  'Search': 'Tìm kiếm',
  'Connections': 'Kết nối',
  // -- Administration, sign-in and the product tour, sentence case (app UI review 2026-09-30) ---------
  'Administration sections': 'Các mục quản trị',
  'Ask a question': 'Đặt câu hỏi',
  'Danger zone': 'Vùng nguy hiểm',
  'Never': 'Chưa bao giờ',
  'Period': 'Khoảng thời gian',
  'Role': 'Vai trò',
  '{current} of {total}': '{current}/{total}',
  // Settings, 2026-09-30 (the UI review's type scale, empty states and guides).
  'Conversion': 'Chuyển đổi',
  'Delete {name}': 'Xóa {name}',
  'Edition': 'Ấn bản',
  'Every tool the agent has, and what each one may change.': 'Mọi công cụ của tác nhân, và những gì mỗi công cụ được phép thay đổi.',
  'Generate one for each client, so each can be revoked on its own.': 'Tạo một token cho mỗi ứng dụng, để có thể thu hồi riêng từng cái.',
  'Claude Code, VS Code & Copilot': 'Claude Code, VS Code & Copilot',
  'Proxyma API': 'API của Proxyma',
  'What the agent can do': 'Tác nhân có thể làm gì',
  'Guides': 'Hướng dẫn',
  'OCR': 'OCR',
  'Searching your sources and asking the agent from a script.': 'Tìm trong nguồn dữ liệu và hỏi tác nhân từ một script.',
  'Spending': 'Chi tiêu',
  'You accepted version {version} of the terms on {date}.': 'Bạn đã chấp nhận phiên bản {version} của điều khoản vào ngày {date}.',
  'created {date}': 'tạo ngày {date}',
  'last used {date}': 'dùng lần cuối ngày {date}',

  // 2026-10-01 feedback batch
  '*.corp.example.com matches every server under that domain.': '*.corp.example.com khớp với mọi máy chủ thuộc tên miền đó.',
  'Add server': 'Thêm máy chủ',
  'File server': 'Máy chủ tệp tin',
  'Remove': 'Gỡ bỏ',
  'The instructions the agent starts every conversation with. Read-only.': 'Những chỉ dẫn tác nhân dùng khi bắt đầu mọi cuộc trò chuyện. Chỉ đọc.',
  "What the agent is given when a conversation starts. Today's date and the lists of skills and workflows are added to it.":
    'Những gì tác nhân nhận được khi một cuộc trò chuyện bắt đầu. Ngày hôm nay cùng danh sách kỹ năng và quy trình được thêm vào đó.',
  'Use from other AI assistants': 'Dùng từ các trợ lý AI khác',
  'What do you need done?': 'Bạn cần xử lý việc gì?',
  'Optional. When any are listed, Network share connections may only name these servers. Leave empty to allow any server.': 'Không bắt buộc. Khi có máy chủ trong danh sách, kết nối thư mục chia sẻ mạng chỉ được trỏ tới các máy chủ này. Để trống để cho phép mọi máy chủ.',
  'The server your network paths start with. Its folders are read as this account, and no other server is.': 'Máy chủ mà các đường dẫn mạng của bạn bắt đầu bằng. Các thư mục trên đó được đọc bằng tài khoản này, và không máy chủ nào khác được đọc.',
  'Your Windows domain account, as DOMAIN\\you or you@domain. Only files it can open are indexed.': 'Tài khoản miền Windows của bạn, dạng DOMAIN\\you hoặc you@domain. Chỉ những tệp tin tài khoản này mở được mới được lập chỉ mục.',

  // -- 2026-10-06: Settings on the rebuilt UI kit ------------------------------------------------
  'A skill is a set of instructions the agent reads only when a question needs it.': 'Kỹ năng là một bộ hướng dẫn mà tác nhân chỉ đọc khi câu hỏi cần đến.',
  '{count} step|{count} steps': '{count} bước',
  'Unchanged': 'Không thay đổi',
  'Passphrase': 'Cụm mật khẩu',
  '{created} new, {updated} changed, {skipped} skipped': '{created} mới, {updated} thay đổi, {skipped} bỏ qua',

  // -- 2026-10-03: Settings > Memory and the agents graph --------------------------------------
  'About you': 'Về bạn',
  'Category': 'Loại',
  'Clear selection': 'Bỏ chọn',
  'Delete memory entries': 'Xóa các mục bộ nhớ',
  'Delete memory entry': 'Xóa mục bộ nhớ',
  'Edit memory entry': 'Sửa mục bộ nhớ',
  'Failed to delete memory entries': 'Không xóa được các mục bộ nhớ',
  'Failed to move memory entries': 'Không chuyển được các mục bộ nhớ',
  'Failed to save the memory entry': 'Không lưu được mục bộ nhớ',
  'Filter entries': 'Lọc các mục',
  'Agents - {count} sub-agent working|Agents - {count} sub-agents working':
    'Tác nhân - {count} tác nhân phụ đang làm việc|Tác nhân - {count} tác nhân phụ đang làm việc',
  'Knowledge': 'Kiến thức',
  'Main agent': 'Tác nhân chính',
  'Who worked on this conversation, on which model, and what each cost.': 'Ai đã làm việc trong cuộc trò chuyện này, trên mô hình nào, và mỗi tác nhân tốn bao nhiêu.',
  'Total time': 'Tổng thời gian',
  'Provider / model': 'Nhà cung cấp / mô hình',
  'Show sub-agents': 'Hiện các tác nhân phụ',
  'Hide sub-agents': 'Ẩn các tác nhân phụ',
  'Go to {title} in the conversation': 'Đi tới {title} trong cuộc trò chuyện',
  'Show its steps': 'Hiện các bước của tác nhân',
  'Hide its steps': 'Ẩn các bước của tác nhân',
  'Its steps are not loaded in this conversation.': 'Các bước của tác nhân này chưa được tải trong cuộc trò chuyện này.',
  'Memory': 'Bộ nhớ',
  'Memory categories': 'Các loại bộ nhớ',
  'Move to About you': 'Chuyển sang Về bạn',
  'Move to Knowledge': 'Chuyển sang Kiến thức',
  'No entries match this filter.': 'Không có mục nào khớp với bộ lọc này.',
  'No sub-agents yet. The ones the agent starts appear here, finished ones included.': 'Chưa có tác nhân phụ nào. Những tác nhân phụ mà tác nhân khởi chạy sẽ hiện ở đây, kể cả những tác nhân đã xong.',
  'Nothing saved yet. The agent saves facts and your preferences here as it learns them.': 'Chưa lưu gì. Tác nhân lưu các thông tin và sở thích của bạn ở đây khi nó học được.',
  'Running': 'Đang chạy',
  'Select every entry shown': 'Chọn mọi mục đang hiển thị',
  'Select this entry': 'Chọn mục này',
  'The agent will no longer know this in any conversation.': 'Tác nhân sẽ không còn biết điều này trong bất kỳ cuộc trò chuyện nào.',
  'What the agent has saved to use in later conversations. It checks this before searching your sources, so correct or delete anything that is wrong or out of date.': 'Những gì tác nhân đã lưu để dùng trong các cuộc trò chuyện sau. Tác nhân xem phần này trước khi tìm trong các nguồn của bạn, vì vậy hãy sửa hoặc xóa những gì sai hoặc đã cũ.',
  'What the agent remembers': 'Điều tác nhân ghi nhớ',
  'this memory entry': 'mục bộ nhớ này',
  '{count} entries': '{count} mục',
  '{count} memory entries': '{count} mục bộ nhớ',
  '{count} memory entries deleted': 'Đã xóa {count} mục bộ nhớ',
  '{count} selected': 'Đã chọn {count}',

  // -- Key accounts (docs/architecture.html#spec-11): the list, an account's tabs, the panel ------
  'Ask about this account': 'Hỏi về khách hàng này',
  'Assistant': 'Trợ lý',
  'Description': 'Mô tả',
  'Draft next moves': 'Soạn các bước đi tiếp theo',
  'Everyone': 'Mọi người',
  'In use': 'Đang dùng',
  'Jira issue': 'Issue Jira',
  'Key': 'Trọng điểm',
  'Key accounts': 'Khách hàng trọng điểm',
  'Note': 'Ghi chú',
  'Opportunities': 'Cơ hội',
  'Optional': 'Không bắt buộc',
  'Owner': 'Chủ sở hữu',
  'Page {page} of {pages}': 'Trang {page}/{pages}',
  'People': 'Con người',
  'Plan': 'Kế hoạch',
  'Previous': 'Trước',
  'Region': 'Khu vực',
  'Renewal date': 'Ngày gia hạn',
  'Run the health review': 'Chạy đánh giá sức khỏe',
  'Select a date': 'Chọn ngày',
  'Show': 'Hiển thị',
  'Text': 'Nội dung',
  'Title': 'Chức danh',
  'Today': 'Hôm nay',
  'Type': 'Loại',
  'Viewer': 'Người xem',
  'What is still missing?': 'Còn thiếu những gì?',
  // -- Logs: Settings > Logs and Administration > Logs (2026-10-12; named Audit Logs until 2026-10-14)
  'Access': 'Truy cập',
  'Added per check': 'Thời gian thêm cho mỗi lần kiểm tra',
  'All events': 'Tất cả sự kiện',
  'All outcomes': 'Tất cả kết quả',
  'All tools': 'Tất cả công cụ',
  'Log entry': 'Mục nhật ký',
  'Logs (days)': 'Nhật ký (ngày)',
  'Check': 'Kiểm tra',
  'Classifier unreachable': 'Không kết nối được bộ phân loại',
  'Clear this log?': 'Xóa nhật ký này?',
  'Collected data deleted': 'Đã xóa dữ liệu đã thu thập',
  'Configuration': 'Cấu hình',
  'Conversation deleted': 'Đã xóa cuộc trò chuyện',
  'Conversation shared': 'Đã chia sẻ cuộc trò chuyện',
  'Could not clear the log': 'Không thể xóa nhật ký',
  'Could not load the log': 'Không thể tải nhật ký',
  'Could not load the summary': 'Không thể tải phần tổng hợp',
  'Could not load this entry': 'Không thể tải mục này',
  'Data': 'Dữ liệu',
  'Days waiting': 'Số ngày đang chờ',
  'Diagnostics sent': 'Đã gửi dữ liệu chẩn đoán',
  'Each check in the period, newest first. Open a row to see which rules fired.': 'Mỗi lần kiểm tra trong khoảng thời gian, mới nhất trước. Mở một dòng để xem quy tắc nào đã kích hoạt.',
  'Each event in the period, newest first. Open a row to see what was recorded with it.': 'Mỗi sự kiện trong khoảng thời gian, mới nhất trước. Mở một dòng để xem những gì được ghi lại cùng nó.',
  'Each send, failure, switch and deletion in the period, newest first. A send carries the report exactly as it went.': 'Mỗi lần gửi, lỗi, bật tắt và xóa trong khoảng thời gian, mới nhất trước. Mỗi lần gửi kèm báo cáo đúng như khi được gửi đi.',
  'Each tool call in the period, newest first. Open a row to see its input and result.': 'Mỗi lần gọi công cụ trong khoảng thời gian, mới nhất trước. Mở một dòng để xem đầu vào và kết quả.',
  'Error types, timings and enabled features - never your documents, questions, file names or account. The switch is in Settings > About > Privacy.': 'Loại lỗi, thời gian xử lý và các tính năng đang bật - không bao giờ có tài liệu, câu hỏi, tên tệp hay tài khoản của bạn. Công tắc nằm ở Cài đặt > Giới thiệu > Quyền riêng tư.',
  'Event': 'Sự kiện',
  'Every check - what it decided, which rules fired and what it cost in time - is recorded in Logs.': 'Mọi lần kiểm tra - quyết định gì, quy tắc nào kích hoạt và mất bao lâu - đều được ghi trong Nhật ký.',
  'Every check, including the ones that let the text through. Rule names and categories only - never the text.': 'Mọi lần kiểm tra, kể cả những lần cho văn bản đi qua. Chỉ tên quy tắc và loại - không bao giờ có văn bản.',
  'Every entry on this tab is deleted, in every period.': 'Mọi mục trên thẻ này sẽ bị xóa, trong mọi khoảng thời gian.',
  'Every model call and its cost, the LLM calls in Logs.': 'Mọi lần gọi mô hình và chi phí của nó, phần Lần gọi LLM trong Nhật ký.',
  'Every update found, downloaded, installed or failed is recorded in Logs, under Activity.': 'Mọi bản cập nhật được tìm thấy, tải xuống, cài đặt hoặc thất bại đều được ghi trong Nhật ký, mục Hoạt động.',
  'Guardrail window reset': 'Đã đặt lại cửa sổ rào chắn',
  'Guest': 'Khách',
  'Input check': 'Kiểm tra đầu vào',
  'LLM calls': 'Lần gọi LLM',
  'Last sent': 'Lần gửi gần nhất',
  'Limits': 'Giới hạn',
  'Log cleared': 'Đã xóa nhật ký',
  'Matched text': 'Văn bản khớp',
  'Median time': 'Thời gian trung vị',
  'Memory deleted': 'Đã xóa bộ nhớ',
  'Most used': 'Dùng nhiều nhất',
  'No entries in this period match.': 'Không có mục nào trong khoảng thời gian này khớp.',
  'No tool was called in this period.': 'Không có công cụ nào được gọi trong khoảng thời gian này.',
  'Not since the app started': 'Chưa từ khi ứng dụng khởi động',
  'Nothing else was recorded with this entry.': 'Không có gì khác được ghi cùng mục này.',
  'Nothing is collected while the switch is off; the preview shows what today would contain.': 'Không thu thập gì khi công tắc đang tắt; bản xem trước cho thấy hôm nay sẽ chứa những gì.',
  'Nothing was recorded in this period.': 'Không có gì được ghi lại trong khoảng thời gian này.',
  'Observed only': 'Chỉ quan sát',
  'Off': 'Tắt',
  'Off - this build has nowhere to send them': 'Tắt - bản dựng này không có nơi để gửi',
  'On - sent once a day': 'Bật - gửi mỗi ngày một lần',
  'Open Logs': 'Mở Nhật ký',
  'Outcome': 'Kết quả',
  'Output check': 'Kiểm tra đầu ra',
  'Provider rate limit': 'Giới hạn tốc độ của nhà cung cấp',
  'Recorded with it': 'Được ghi cùng',
  'Refused': 'Bị từ chối',
  'Retrieval check': 'Kiểm tra truy xuất',
  'See what is collected and sent': 'Xem những gì được thu thập và gửi đi',
  'Send failed': 'Gửi thất bại',
  'Sending': 'Đang gửi',
  'Setting changed': 'Đã thay đổi cài đặt',
  'Setting cleared': 'Đã xóa cài đặt',
  'Settings, sources, access, limits, updates, and anything cleared or deleted.': 'Cài đặt, nguồn dữ liệu, truy cập, giới hạn, cập nhật và mọi thứ đã bị xóa.',
  'Sign-ins, account changes, settings, sources, access, limits, updates and maintenance.': 'Đăng nhập, thay đổi tài khoản, cài đặt, nguồn dữ liệu, truy cập, giới hạn, cập nhật và bảo trì.',
  'Spending limit reached': 'Đã chạm giới hạn chi tiêu',
  'Switched off': 'Đã tắt',
  'Switched on': 'Đã bật',
  'Target': 'Đối tượng',
  'The clear itself is recorded under Activity.': 'Bản thân thao tác xóa được ghi lại trong mục Hoạt động.',
  'The text a rule objected to, kept in memory until restart and never written to the log. Turn off "Keep the matched text" in Guardrails to stop recording it.': 'Văn bản mà một quy tắc phản đối, chỉ giữ trong bộ nhớ đến khi khởi động lại và không bao giờ ghi vào nhật ký. Tắt "Giữ văn bản khớp" trong Rào chắn để ngừng ghi lại.',
  'Took': 'Thời gian',
  'Tool': 'Công cụ',
  'Tool call': 'Gọi công cụ',
  'Tool calls': 'Lần gọi công cụ',
  'Agents (sub-agents)': 'Tác tử (tác tử phụ)',
  'CSV': 'CSV',
  'Could not download the log': 'Không thể tải nhật ký xuống',
  'Downloaded the newest 10,000 entries. Choose a shorter period or a filter for the rest.': 'Đã tải 10.000 mục mới nhất. Chọn khoảng thời gian ngắn hơn hoặc một bộ lọc để lấy phần còn lại.',
  'Every field, for another program': 'Mọi trường, cho chương trình khác',
  'JSON': 'JSON',
  'Log downloaded': 'Đã tải nhật ký xuống',
  'Call log downloaded': 'Đã tải nhật ký lần gọi xuống',
  'No': 'Không',
  'Opens in a spreadsheet': 'Mở bằng bảng tính',
  'Search (embedding)': 'Tìm kiếm (nhúng)',
  'Search (reranking)': 'Tìm kiếm (xếp hạng lại)',
  '1 call|{count} calls': '1 lượt gọi|{count} lượt gọi',
  'Page {page}': 'Trang {page}',
  '1 entry|{count} entries': '1 mục|{count} mục',
  // -- Every list pages at twenty (2026-10-14): the counts beside each list's pager ------------
  '{count} conversation|{count} conversations': '{count} cuộc trò chuyện',
  'Tool calls, guardrail checks, diagnostics and activity in Logs.': 'Lần gọi công cụ, kiểm tra rào chắn, dữ liệu chẩn đoán và hoạt động trong Nhật ký.',
  'Unsent days deleted': 'Đã xóa các ngày chưa gửi',
  'What each call was given and what it returned, as the model saw it. Long results are cut short.': 'Mỗi lần gọi nhận gì và trả về gì, đúng như mô hình đã thấy. Kết quả dài được cắt ngắn.',
  'What has been spent, by provider, model and service, and every model call, is in Logs.': 'Số tiền đã chi, theo nhà cung cấp, mô hình và dịch vụ, cùng mọi lần gọi mô hình, nằm trong Nhật ký.',
  'Writes denied or not allowed.': 'Thao tác ghi bị từ chối hoặc không được phép.',
  'You': 'Bạn',
  'User budget reached': 'Đã chạm ngân sách người dùng',
  'Chat quota reached': 'Đã chạm hạn mức trò chuyện',
  'Storage quota reached': 'Đã chạm hạn mức lưu trữ',
  'Signed in': 'Đã đăng nhập',
  'Sign-in failed': 'Đăng nhập thất bại',
  'Account locked': 'Tài khoản bị khóa',
  'Signed out': 'Đã đăng xuất',
  'Account created': 'Đã tạo tài khoản',
  'Password reset requested': 'Đã yêu cầu đặt lại mật khẩu',
  'Password reset': 'Đã đặt lại mật khẩu',
  'User created': 'Đã tạo người dùng',
  'Role changed': 'Đã đổi vai trò',
  'Quota changed': 'Đã đổi hạn mức',
  'User deleted': 'Đã xóa người dùng',
  'Deletion requested': 'Đã yêu cầu xóa',
  'Deletion canceled': 'Đã hủy yêu cầu xóa',
  'User data deleted': 'Đã xóa dữ liệu người dùng',
  'Password reset link made': 'Đã tạo liên kết đặt lại mật khẩu',
  'Token created': 'Đã tạo token',
  'Source added': 'Đã thêm nguồn dữ liệu',
  'Source changed': 'Đã thay đổi nguồn dữ liệu',
  'Source removed': 'Đã gỡ nguồn dữ liệu',
  'Sync finished': 'Đồng bộ hoàn tất',
  'File uploaded': 'Đã tải tệp lên',
  'File removed': 'Đã gỡ tệp',
  'Connection added': 'Đã thêm kết nối',
  'Connection changed': 'Đã thay đổi kết nối',
  'Connection removed': 'Đã gỡ kết nối',
  'Connection signed in': 'Kết nối đã đăng nhập',
  'Connection signed out': 'Kết nối đã đăng xuất',
  'Connection test failed': 'Kiểm tra kết nối thất bại',
  'Provider added': 'Đã thêm nhà cung cấp',
  'Provider changed': 'Đã thay đổi nhà cung cấp',
  'Provider removed': 'Đã gỡ nhà cung cấp',
  'Rate changed': 'Đã thay đổi đơn giá',
  'Rate removed': 'Đã gỡ đơn giá',
  'License removed': 'Đã gỡ giấy phép',
  'Trial started': 'Đã bắt đầu dùng thử',
  'License check failed': 'Kiểm tra giấy phép thất bại',
  'License restored': 'Đã khôi phục giấy phép',
  'License revoked': 'Giấy phép bị thu hồi',
  'Configuration exported': 'Đã xuất cấu hình',
  'Configuration imported': 'Đã nhập cấu hình',
  'Update found': 'Có bản cập nhật',
  'Update downloaded': 'Đã tải bản cập nhật',
  'Update installed': 'Đã cài bản cập nhật',
  'Update failed': 'Cập nhật thất bại',
  'Download canceled': 'Đã hủy tải xuống',
  'Update history cleared': 'Đã xóa lịch sử cập nhật',
  'Maintenance started': 'Bắt đầu bảo trì',
  'Maintenance ended': 'Kết thúc bảo trì',
  'Backup created': 'Đã tạo bản sao lưu',
  'Backup restored': 'Đã khôi phục bản sao lưu',

  // MCP server connections (Settings > Connections)
  '1 header|{count} headers': '{count} header',
  '1 variable|{count} variables': '{count} biến',
  'A program on this computer, such as npx, uvx or docker. It starts with a minimal environment plus the variables added here.':
    'Một chương trình trên máy này, ví dụ npx, uvx hoặc docker. Chương trình chạy với môi trường tối thiểu cộng với các biến được thêm ở đây.',
  'Add variable': 'Thêm biến',
  'After saving, press Sign in on the connection to sign in to this server.':
    'Sau khi lưu, nhấn Đăng nhập trên kết nối để đăng nhập vào máy chủ này.',
  'Allow agent to use': 'Cho phép agent sử dụng',
  'Arguments': 'Tham số',
  'Authentication': 'Xác thực',
  'Bearer token': 'Bearer token',
  'Client ID': 'Client ID',
  'Command': 'Lệnh',
  'Connected to {server}. Tools available: {count}.': 'Đã kết nối tới {server}. Số công cụ khả dụng: {count}.',
  'Could not connect: {reason}': 'Không thể kết nối: {reason}',
  'Could not start the sign-in.': 'Không thể bắt đầu đăng nhập.',
  'Environment variables': 'Biến môi trường',
  'Every user on this server can use its tools through this connection.':
    'Mọi người dùng trên máy chủ này đều dùng được các công cụ của nó qua kết nối này.',
  'Finish signing in in the window that opened.': 'Hoàn tất đăng nhập trong cửa sổ vừa mở.',
  'Headers': 'Header',
  'Local command': 'Lệnh cục bộ',
  'Local command (stdio)': 'Lệnh cục bộ (stdio)',
  'Most servers register Proxyma by themselves. Enter a client only if the server asks for one; its redirect URL is {url}.':
    'Hầu hết máy chủ tự đăng ký Proxyma. Chỉ nhập client khi máy chủ yêu cầu; URL chuyển hướng của nó là {url}.',
  'No authentication': 'Không xác thực',
  'OAuth sign-in': 'Đăng nhập OAuth',
  "Offer this server's tools to the agent. Each call waits for your approval unless the conversation is in Auto.":
    'Cung cấp các công cụ của máy chủ này cho agent. Mỗi lần gọi đều chờ bạn chấp thuận, trừ khi cuộc trò chuyện ở chế độ Tự động.',
  'One per line.': 'Mỗi dòng một giá trị.',
  'Open it again': 'Mở lại',
  'Passed to the command. A saved value is kept while its box is left empty.':
    'Được truyền cho lệnh. Giá trị đã lưu được giữ nguyên khi ô để trống.',
  'Remove variable': 'Xóa biến',
  'SSE': 'SSE',
  'Saved - type to replace': 'Đã lưu - nhập để thay thế',
  'Scope': 'Phạm vi (scope)',
  'Sent to the server as a bearer token with every request.': 'Được gửi tới máy chủ dưới dạng bearer token trong mọi yêu cầu.',
  'Sent with every request. A saved value is kept while its box is left empty.':
    'Được gửi kèm mọi yêu cầu. Giá trị đã lưu được giữ nguyên khi ô để trống.',
  'Server-Sent Events (SSE)': 'Server-Sent Events (SSE)',
  'Share with everyone': 'Chia sẻ với mọi người',
  'Sign in again to use its tools.': 'Đăng nhập lại để dùng các công cụ của nó.',
  'Signed in to {name}.': 'Đã đăng nhập vào {name}.',
  'Signed in. Sign in again from the connection list to use a different account.':
    'Đã đăng nhập. Đăng nhập lại từ danh sách kết nối để dùng một tài khoản khác.',
  'Streamable HTTP': 'Streamable HTTP',
  'The address of the MCP endpoint, often ending in /mcp.': 'Địa chỉ của endpoint MCP, thường kết thúc bằng /mcp.',
  'The address of the SSE endpoint, often ending in /sse.': 'Địa chỉ của endpoint SSE, thường kết thúc bằng /sse.',
  'Transport': 'Phương thức truyền',
  'agent may use, asking first': 'agent được dùng, hỏi trước mỗi lần',
  'not signed in': 'chưa đăng nhập',
  'shared with everyone': 'chia sẻ với mọi người',
  'signed in': 'đã đăng nhập',

  // -- Custom sources and the ingestion API (IngestApiPanel, CustomSourcePlugin) -----------------
  'Add a custom source': 'Thêm nguồn tùy chỉnh',
  'Custom source': 'Nguồn tùy chỉnh',
  'Custom sources': 'Nguồn tùy chỉnh',
  'Documents your own scripts and tools push to Proxyma through the ingestion API, and notes the assistant saves. Nothing is read from elsewhere.':
    'Tài liệu do script và công cụ của bạn đẩy vào Proxyma qua API nạp dữ liệu, cùng các ghi chú trợ lý lưu lại. Không đọc dữ liệu từ nơi nào khác.',
  'What it holds, for you and for the assistant.': 'Nguồn này chứa gì, để bạn và trợ lý cùng biết.',
  'Ingestion API': 'API nạp dữ liệu',
  'Push documents to this source from a script, Zapier, Make or n8n. Each request carries one of its tokens, which opens this source and nothing else.':
    'Đẩy tài liệu vào nguồn này từ một script, Zapier, Make hoặc n8n. Mỗi yêu cầu mang theo một token của nguồn; token đó chỉ mở được nguồn này.',
  'How to push documents': 'Cách đẩy tài liệu',
  'Endpoint': 'Endpoint',
  'Tokens': 'Token',
  'No tokens yet. Create one for each script, so each can be revoked on its own.':
    'Chưa có token nào. Hãy tạo một token cho mỗi script để có thể thu hồi riêng từng cái.',
  'Token name': 'Tên token',
  'For example: n8n workflow': 'Ví dụ: quy trình n8n',
  'Create token': 'Tạo token',
  'Failed to create the token': 'Không tạo được token',
  'This source has as many tokens as it may. Revoke one you no longer use to create another.':
    'Nguồn này đã có số token tối đa. Hãy thu hồi một token không còn dùng để tạo token khác.',
  'never used': 'chưa từng dùng',
  'Nothing has been pushed to this source yet.': 'Chưa có tài liệu nào được đẩy vào nguồn này.',
  'Ingest token created': 'Đã tạo token nạp dữ liệu',
  'Ingest token revoked': 'Đã thu hồi token nạp dữ liệu',

  // -- Sources, the source dialogs, key accounts and the app shell (kit adoption, 2026-10-14) -------
  'Search source types': 'Tìm loại nguồn',
  'No source type matches "{query}".': 'Không có loại nguồn nào khớp với "{query}".',
  'This source is now shared with others': 'Nguồn dữ liệu này giờ đã được chia sẻ với người khác',
  'This source is no longer shared': 'Nguồn dữ liệu này không còn được chia sẻ',
  'Failed to change sharing': 'Không thể thay đổi việc chia sẻ',
  'This matches {name}, already shared by another user. Skipping uses that copy at no cost; registering your own uses your own sync and storage capacity.': 'Mục này trùng với {name}, vốn đã được một người dùng khác chia sẻ. Bỏ qua sẽ dùng bản đó miễn phí; đăng ký bản riêng sẽ dùng dung lượng đồng bộ và lưu trữ của chính bạn.',
  '{count} item failed|{count} items failed': '{count} mục lỗi',
  'synced {when}': 'đã đồng bộ {when}',
  'Turn on "Include subfolders" below to index them.': 'Hãy bật "Bao gồm cả thư mục con" bên dưới để lập chỉ mục cho chúng.',
  '{count} file uploaded to {folder}|{count} files uploaded to {folder}': 'Đã tải {count} tệp lên {folder}',
  '{count} file uploaded to {folder}, {skipped} skipped|{count} files uploaded to {folder}, {skipped} skipped': 'Đã tải {count} tệp lên {folder}, bỏ qua {skipped}',
  'Uploading {done}/{total} files to {folder}': 'Đang tải lên {done}/{total} tệp tin vào {folder}',
  '{done} of {total} files uploaded to {folder}, {failed} failed': 'Đã tải lên {done} trên {total} tệp tin vào {folder}, {failed} thất bại',
  'The following files already exist in {folder}. Choose to skip or overwrite each file.': 'Các tệp tin sau đã tồn tại trong {folder}. Chọn bỏ qua hoặc ghi đè cho từng tệp tin.',
  'Cancel this upload?': 'Hủy lần tải lên này?',
  'The files that did upload are removed from the server, and nothing is indexed.': 'Các tệp đã tải lên sẽ bị xóa khỏi máy chủ và không có gì được lập chỉ mục.',
  'Keep them': 'Giữ lại',
  'Knowledge graph: {entities} entities, {relations} relationships': 'Đồ thị tri thức: {entities} thực thể, {relations} mối quan hệ',
  'You are about to {verb}:': 'Bạn sắp {verb}:',
  // -- Settings, Administration, Logs and sign-in on the UI kit (adopt-settings, 2026-10-14) ------
  'Choose a provider first': 'Hãy chọn nhà cung cấp trước',
  'Could not delete this provider': 'Không xóa được nhà cung cấp này',
  'Text between {open} and {close} is included only while sub-agents are enabled.':
    'Phần văn bản nằm giữa {open} và {close} chỉ được đưa vào khi tác nhân phụ đang được bật.',
  'Could not load the default prompt': 'Không tải được câu lệnh mặc định',
  'Similarity only decides which clauses are {relevant}, and the model decides whether the message actually breaks one.':
    'Độ tương đồng chỉ quyết định những điều khoản nào là {relevant}, còn mô hình quyết định xem tin nhắn có thực sự vi phạm điều khoản nào hay không.',
  'Remove policy document': 'Gỡ bỏ tài liệu chính sách',
  'remove': 'gỡ bỏ',
  'Removing...': 'Đang gỡ bỏ...',
  'Could not remove that document': 'Không gỡ bỏ được tài liệu đó',
  'Could not rebuild the index': 'Không dựng lại được chỉ mục',
  'Its rules leave the policy index, and messages are no longer checked against them.':
    'Các quy tắc của tài liệu bị xóa khỏi chỉ mục chính sách, và tin nhắn không còn được kiểm tra theo chúng nữa.',
  'Monthly ceiling on everything this installation spends. A {hardStop}: chat, search and indexing are refused until it resets, and 0 disables it.':
    'Mức trần hàng tháng cho mọi chi tiêu của bản cài đặt này. Đây là một {hardStop}: trò chuyện, tìm kiếm và lập chỉ mục sẽ bị từ chối cho tới khi hạn mức được đặt lại, và đặt 0 để tắt giới hạn này.',
  '{count} minute|{count} minutes': '{count} phút',
  'Credentials are written as {placeholder}. Enter them by hand after importing.':
    'Thông tin đăng nhập được ghi thành {placeholder}. Sau khi nhập tệp, hãy điền lại chúng bằng tay.',
  'Delete collected data?': 'Xóa dữ liệu đã thu thập?',
  'Diagnostics waiting to be sent are deleted, and nothing already sent is affected.':
    'Dữ liệu chẩn đoán đang chờ gửi sẽ bị xóa; những gì đã gửi không bị ảnh hưởng.',
  "{product} can't guarantee the AI assistant always uses this tool correctly. Every write action - creating, editing, deleting - {real}, in your connected system. {review} before it proceeds - {responsible} for what it does, not {product}.":
    '{product} không thể bảo đảm trợ lý AI luôn dùng công cụ này một cách chính xác. Mọi hành động ghi - tạo, sửa, xóa - {real}, trong hệ thống bạn đã kết nối. {review} trước khi nó được thực hiện - {responsible} cho những gì nó làm, chứ không phải {product}.',
  'I have read and accept the {terms}, {privacy} and {eula}.':
    'Tôi đã đọc và chấp nhận {terms}, {privacy} và {eula}.',
  'By continuing you agree to the {terms}, {privacy} and {eula}.':
    'Bằng việc tiếp tục, bạn đồng ý với {terms}, {privacy} và {eula}.',
  'Your account is scheduled for deletion in {days}.': 'Tài khoản của bạn được lên lịch xóa sau {days}.',
  'Model calls with a price appear here as cost per day. Rates are set per provider in Settings > AI Providers.':
    'Các lượt gọi mô hình có giá sẽ hiện ở đây theo chi phí mỗi ngày. Giá được đặt cho từng nhà cung cấp trong Cài đặt > Nhà cung cấp AI.',
  'Model calls with a price appear here as cost per month. Rates are set per provider in Settings > AI Providers.':
    'Các lượt gọi mô hình có giá sẽ hiện ở đây theo chi phí mỗi tháng. Giá được đặt cho từng nhà cung cấp trong Cài đặt > Nhà cung cấp AI.',

  // -- What the SERVER says (serverMessages.generated.ts, read out of the Java source) -----------
  // The backend writes these in English; the page translates them as they arrive (serverText). A
  // numbered gap - {0} - is where the backend put a value in. Only sentences a person meets are
  // here: what is written for the model (a tool's result) or for a developer stays English.
  // Custom sources: refusals the ingestion API's token panel can show (IngestTokenService)
  'A source may have at most {0} tokens. Revoke one you no longer use first.':
    'Một nguồn chỉ có tối đa {0} token. Hãy thu hồi một token không còn dùng trước.',
  "Only the source's owner can do this.": 'Chỉ chủ sở hữu nguồn mới làm được việc này.',
  "The token's name is longer than {0} characters.": 'Tên token dài quá {0} ký tự.',
  'There is no custom source with this id.': 'Không có nguồn tùy chỉnh nào mang mã này.',
  'This source has no such token.': 'Nguồn này không có token đó.',
  // Connections and providers (Settings)
  'A connection needs a name.': 'Kết nối cần có tên.',
  'A connection needs a type.': 'Kết nối cần có loại.',
  'A connection name can be at most {0} characters.': 'Tên kết nối dài tối đa {0} ký tự.',
  "A connection's type cannot be changed. Add a new connection instead.":
    'Không thể đổi loại của một kết nối. Hãy thêm một kết nối mới.',
  "You already have a connection called '{0}'.": "Bạn đã có một kết nối tên '{0}'.",
  "'{0}' is used by 1 source. Remove it from Sources first.":
    "'{0}' đang được 1 nguồn dữ liệu sử dụng. Hãy xóa nguồn đó khỏi Nguồn dữ liệu trước.",
  "'{0}' is used by {1} sources. Remove them from Sources first.":
    "'{0}' đang được {1} nguồn dữ liệu sử dụng. Hãy xóa các nguồn đó khỏi Nguồn dữ liệu trước.",
  'The server URL is not a valid URL.': 'URL máy chủ không hợp lệ.',
  'The server URL must start with http:// or https://.':
    'URL máy chủ phải bắt đầu bằng http:// hoặc https://.',
  '{0} needs a server URL.': '{0} cần có URL máy chủ.',
  '{0} needs a username.': '{0} cần có tên người dùng.',
  '{0} is not available in this edition.': '{0} không có trong phiên bản này.',
  '{0} needs an Enterprise license.': '{0} cần giấy phép Enterprise.',
  'No such connection': 'Không có kết nối này',
  'No such connection: {0}': 'Không có kết nối này: {0}',
  "A network share connection needs its file server's name.":
    'Kết nối thư mục chia sẻ mạng cần có tên máy chủ tệp tin.',
  "Enter the file server's name.": 'Hãy nhập tên máy chủ tệp tin.',
  "Enter only the file server's name, such as fileserver.corp.example.com.":
    'Chỉ nhập tên máy chủ tệp tin, ví dụ fileserver.corp.example.com.',
  'A connection names one file server - a pattern such as *.corp.example.com is not allowed.':
    'Mỗi kết nối chỉ dành cho một máy chủ tệp tin - không dùng được mẫu như *.corp.example.com.',
  "File server '{0}' is not one of this deployment's allowed file servers. Ask an administrator to add it.":
    "Máy chủ tệp tin '{0}' không nằm trong danh sách máy chủ tệp tin được phép của hệ thống này. Hãy nhờ quản trị viên thêm vào.",
  'A provider needs a name.': 'Nhà cung cấp cần có tên.',
  'A provider needs a protocol.': 'Nhà cung cấp cần có giao thức.',
  'A provider name can be at most {0} characters.': 'Tên nhà cung cấp dài tối đa {0} ký tự.',
  "There is already a provider called '{0}'.": "Đã có một nhà cung cấp tên '{0}'.",
  'Azure OpenAI needs a base URL - it has no default host.':
    'Azure OpenAI cần có URL gốc - dịch vụ này không có địa chỉ mặc định.',
  'A custom header needs a name.': 'Header tùy chỉnh cần có tên.',
  'At most {0} custom headers.': 'Tối đa {0} header tùy chỉnh.',
  'Header names and values cannot contain line breaks.':
    'Tên và giá trị của header không được chứa dấu xuống dòng.',
  'Could not store custom headers: {0}': 'Không thể lưu header tùy chỉnh: {0}',
  'No such provider instance: {0}': 'Không có nhà cung cấp này: {0}',
  'No configured provider with id {0}': 'Không có nhà cung cấp nào được cấu hình với id {0}',
  "Provider '{0}' has no API key configured": "Nhà cung cấp '{0}' chưa được cấu hình API Key",
  "Provider '{0}' has no base URL configured": "Nhà cung cấp '{0}' chưa được cấu hình URL gốc",
  'LiteLLM needs the address of its proxy - it has no default host.': 'LiteLLM cần địa chỉ proxy của nó - dịch vụ này không có địa chỉ mặc định.',
  "Provider '{0}' has no base URL, so it cannot be reranked against":
    "Nhà cung cấp '{0}' chưa có URL gốc nên không thể dùng để xếp hạng lại",
  "Provider '{0}' speaks {1}, which this build cannot embed with":
    "Nhà cung cấp '{0}' dùng giao thức {1}, mà bản dựng này không tạo được vector nhúng với giao thức đó",
  'Embedding not supported for protocol: {0}': 'Giao thức này không hỗ trợ vector nhúng: {0}',
  'Model listing not supported for protocol: {0}': 'Giao thức này không hỗ trợ liệt kê mô hình: {0}',
  'Native rerank not supported by protocol: {0}': 'Giao thức này không hỗ trợ xếp hạng lại trực tiếp: {0}',
  'No embedding model is available: the provider configured for embeddings ({0} / {1}) could not be built. Check Settings > Embedding - not every provider can serve this role.':
    'Chưa có mô hình vector nhúng: không thể khởi tạo nhà cung cấp được cấu hình cho vector nhúng ({0} / {1}). Hãy kiểm tra Cài đặt > Tìm kiếm - không phải nhà cung cấp nào cũng đảm nhận được vai trò này.',
  'The reranking model \'{0}\' on \'{1}\' did not return embeddings ({2}). If that provider has a /v1/rerank endpoint, turn on "Serves /v1/rerank" for it in Settings > AI Providers; otherwise choose a chat or embedding model for reranking.':
    'Mô hình xếp hạng lại \'{0}\' trên \'{1}\' không trả về vector nhúng ({2}). Nếu nhà cung cấp đó có endpoint /v1/rerank, hãy bật "Hỗ trợ /v1/rerank" cho nhà cung cấp này trong Cài đặt > Nhà cung cấp AI; nếu không, hãy chọn một mô hình trò chuyện hoặc mô hình vector nhúng để xếp hạng lại.',
  // Adding and managing sources
  'Choose a connection for this source.': 'Hãy chọn một kết nối cho nguồn dữ liệu này.',
  'Choose the folder to relink to.': 'Hãy chọn thư mục để liên kết lại.',
  'Only a local folder can be relinked': 'Chỉ thư mục trên máy mới liên kết lại được',
  'Only a Super admin can clear the call log.': 'Chỉ Quản trị viên cấp cao mới xóa được nhật ký lệnh gọi.',
  '"{0}" is already one of your sources, so this one cannot be relinked to {1}. Remove one of the two first.':
    '"{0}" đã là một nguồn dữ liệu của bạn, nên không thể liên kết lại nguồn này tới {1}. Hãy xóa một trong hai trước.',
  'An SVN source named "{0}" already exists for {1}. Remove it first, or register this repository under a different name.':
    'Đã có một nguồn SVN tên "{0}" cho {1}. Hãy xóa nguồn đó trước, hoặc đăng ký kho này với một tên khác.',
  'That is not a connection id.': 'Đó không phải là id của một kết nối.',
  'That {0} connection does not exist, or is not yours.':
    'Kết nối {0} đó không tồn tại, hoặc không phải của bạn.',
  "The connection '{0}' is for the file server {1}, not {2}. Choose or add a connection for {3}.":
    "Kết nối '{0}' dành cho máy chủ tệp tin {1}, không phải {2}. Hãy chọn hoặc thêm một kết nối cho {3}.",
  'You have no Network share connection for the file server {0} yet. Add one in Settings > Connections, then add this folder.':
    'Bạn chưa có kết nối thư mục chia sẻ mạng nào cho máy chủ tệp tin {0}. Hãy thêm kết nối trong Cài đặt > Kết nối, rồi thêm thư mục này.',
  'You have no {0} connection yet. Add one in Settings > Connections, then add this source.':
    'Bạn chưa có kết nối {0} nào. Hãy thêm kết nối trong Cài đặt > Kết nối, rồi thêm nguồn dữ liệu này.',
  '{0} sources do not use a connection.': 'Nguồn dữ liệu {0} không dùng kết nối.',
  '{0} sources have no subfolder setting': 'Nguồn dữ liệu {0} không có thiết lập thư mục con',
  '{0} is not a valid folder path on this machine.':
    '{0} không phải là đường dẫn thư mục hợp lệ trên máy này.',
  '{0} must be between {1} and {2} (got {3}).':
    '{0} phải nằm trong khoảng từ {1} đến {2} (giá trị nhận được: {3}).',
  "Crawl bounds must be whole numbers (got '{0}').":
    "Giới hạn thu thập phải là số nguyên (giá trị nhận được: '{0}').",
  'seedUrl must be a full http:// or https:// address':
    'Địa chỉ bắt đầu phải là một địa chỉ http:// hoặc https:// đầy đủ',
  "This source was indexed with your own account's access rights and cannot be shared":
    'Nguồn dữ liệu này được lập chỉ mục bằng quyền truy cập của chính tài khoản bạn nên không thể chia sẻ',
  "This source's network path cannot be read: {0}":
    'Không đọc được đường dẫn mạng của nguồn dữ liệu này: {0}',
  'This document is being indexed right now': 'Tài liệu này đang được lập chỉ mục',
  'Document not found in this connector': 'Không tìm thấy tài liệu trong nguồn dữ liệu này',
  'Connector not found': 'Không tìm thấy nguồn dữ liệu',
  'Source not found': 'Không tìm thấy nguồn dữ liệu',
  'Connector does not belong to caller': 'Nguồn dữ liệu này không phải của bạn',
  'Configuration for {0} connectors is not editable': 'Không chỉnh sửa được cấu hình của nguồn dữ liệu {0}',
  'Unknown connector type: {0}': 'Loại nguồn dữ liệu không xác định: {0}',
  'Folder path must not be empty': 'Đường dẫn thư mục không được để trống',
  'Folder path must be inside the upload directory': 'Đường dẫn thư mục phải nằm trong thư mục tải lên',
  'Files registration requires an authenticated user': 'Cần đăng nhập để đăng ký tệp tin',
  'Network path must not be empty': 'Đường dẫn mạng không được để trống',
  'Network path has no server name': 'Đường dẫn mạng thiếu tên máy chủ',
  'Network path has no share name': 'Đường dẫn mạng thiếu tên thư mục chia sẻ',
  "Network path must not contain '..'": "Đường dẫn mạng không được chứa '..'",
  'Network path must name a server and a share, e.g. \\\\server\\share':
    'Đường dẫn mạng phải có tên máy chủ và tên thư mục chia sẻ, ví dụ \\\\server\\share',
  'Network path must start with \\\\server\\share (for example \\\\fileserver.example.com\\share\\folder)':
    'Đường dẫn mạng phải bắt đầu bằng \\\\server\\share (ví dụ \\\\fileserver.example.com\\share\\folder)',
  'Cannot reach Bitbucket at {0}: {1}': 'Không thể kết nối tới Bitbucket tại {0}: {1}',
  'Storage quota exceeded for this account': 'Tài khoản này đã vượt hạn mức dung lượng',
  'Storage quota exceeded - connector sync paused':
    'Đã vượt hạn mức dung lượng - việc đồng bộ nguồn dữ liệu tạm dừng',
  // Why a sync or a document failed
  'Could not crawl {0} because the site answered with its sign-in page, so nothing was crawled. The site needs a login this crawler does not have.':
    'Không thể thu thập {0} vì trang web trả về trang đăng nhập, nên không thu thập được gì. Trang web này cần một tài khoản đăng nhập mà trình thu thập không có.',
  'Could not fetch {0} because {1}, so nothing was crawled.':
    'Không thể tải {0} vì {1}, nên không thu thập được gì.',
  'Could not fetch {0} because {1}, so nothing was crawled. The page may be unreachable from this deployment, behind a login, or restricted to certain regions. If the site is reachable from your own network but not from here, configure an HTTP proxy in Settings > Network.':
    'Không thể tải {0} vì {1}, nên không thu thập được gì. Trang này có thể không truy cập được từ hệ thống này, cần đăng nhập, hoặc bị giới hạn theo khu vực. Nếu trang web truy cập được từ mạng của bạn nhưng không truy cập được từ đây, hãy cấu hình HTTP proxy trong Cài đặt > Mạng.',
  'Could not fetch {0}: {1}': 'Không thể tải {0}: {1}',
  'Could not read the page at {0}: {1}': 'Không đọc được trang tại {0}: {1}',
  'The page at {0} no longer exists ({1}).': 'Trang tại {0} không còn tồn tại ({1}).',
  'The page at {0} now answers with a sign-in page - it needs a login this crawler does not have.':
    'Trang tại {0} hiện trả về trang đăng nhập - trang này cần một tài khoản đăng nhập mà trình thu thập không có.',
  'The page at {0} returned nothing - it may have been removed.':
    'Trang tại {0} không trả về nội dung nào - có thể trang đã bị gỡ.',
  'The PDF at {0} is larger than the {1} MB limit and was not indexed.':
    'Tệp PDF tại {0} lớn hơn giới hạn {1} MB nên không được lập chỉ mục.',
  'The PDF at {0} returned nothing - it may have been removed.':
    'Tệp PDF tại {0} không trả về nội dung nào - có thể tệp đã bị gỡ.',
  'The address {0} no longer serves a web page - it now returns {1}.':
    'Địa chỉ {0} không còn là một trang web - hiện địa chỉ này trả về {1}.',
  'This file converted to no text{0}. It may be a scanned image, password-protected, or empty. Scanned documents are only read with OCR - see Settings > Conversion.':
    'Tệp tin này không chuyển đổi được thành văn bản{0}. Đây có thể là ảnh quét, tệp có mật khẩu, hoặc tệp rỗng. Tài liệu quét chỉ đọc được bằng OCR - xem Cài đặt > Chuyển đổi.',
  'No chunks produced from content ({0} chars)': 'Nội dung không tạo ra được đoạn nào ({0} ký tự)',
  "Could not extract text from '{0}' and OCR failed: {1}":
    "Không trích xuất được văn bản từ '{0}' và OCR thất bại: {1}",
  "Could not extract text from '{0}' and OCR is not available - {1}.":
    "Không trích xuất được văn bản từ '{0}' và OCR không khả dụng - {1}.",
  'OCR is not available - {0}.': 'OCR không khả dụng - {0}.',
  "Could not read OCR output for '{0}'": "Không đọc được kết quả OCR của '{0}'",
  "No pages could be rasterized from '{0}'": "Không chuyển được trang nào của '{0}' thành ảnh",
  'Legacy .{0} format is not supported by the document converter (no converter exists for this old binary Office format). Please re-save the file as .{1} and re-upload.':
    'Trình chuyển đổi tài liệu không hỗ trợ định dạng .{0} cũ (không có trình chuyển đổi cho định dạng Office nhị phân cũ này). Hãy lưu lại tệp tin dưới dạng .{1} rồi tải lên lại.',
  'PDF table extraction is not available - the pdfplumber package is not installed in the Python runtime.':
    'Không trích xuất được bảng trong PDF - gói pdfplumber chưa được cài trong môi trường Python.',
  'No Python interpreter is available.': 'Không có trình thông dịch Python.',
  'File does not exist or is not a regular file: {0}':
    'Tệp tin không tồn tại hoặc không phải là tệp tin thông thường: {0}',
  'Failed to open file: {0}': 'Không mở được tệp tin: {0}',
  "The file server rejected this password on an earlier attempt, so it was not tried again - update this source's Network share connection in Settings > Connections":
    'Máy chủ tệp tin đã từ chối mật khẩu này ở lần thử trước nên không thử lại - hãy cập nhật kết nối thư mục chia sẻ mạng của nguồn dữ liệu này trong Cài đặt > Kết nối',
  'This source has no usable Network share connection - add one for its file server in Settings > Connections':
    'Nguồn dữ liệu này không có kết nối thư mục chia sẻ mạng nào dùng được - hãy thêm kết nối cho máy chủ tệp tin của nó trong Cài đặt > Kết nối',
  "File server '{0}' is not the server of this source's Network share connection":
    "Máy chủ tệp tin '{0}' không phải là máy chủ trong kết nối thư mục chia sẻ mạng của nguồn dữ liệu này",
  "No network share account is configured for this source's owner":
    'Chủ sở hữu của nguồn dữ liệu này chưa cấu hình tài khoản thư mục chia sẻ mạng',
  '\\\\{0}\\{1} is not a file share': '\\\\{0}\\{1} không phải là một thư mục chia sẻ',
  'This deployment does not reach {0}: it is a private or local network address. Only public internet addresses are allowed here.':
    'Hệ thống này không truy cập tới {0}: đây là địa chỉ mạng riêng hoặc mạng nội bộ. Ở đây chỉ cho phép địa chỉ internet công khai.',
  'This deployment only reaches http and https addresses, so {0}:// is not allowed.':
    'Hệ thống này chỉ truy cập địa chỉ http và https, nên không cho phép {0}://.',
  'The address has no host name.': 'Địa chỉ thiếu tên máy chủ.',
  'Bitbucket access token is not configured. Please set your personal access token in Settings.':
    'Chưa cấu hình token truy cập Bitbucket. Hãy nhập token truy cập cá nhân của bạn trong Cài đặt.',
  'Confluence access token is not configured. Please set your personal access token in Settings.':
    'Chưa cấu hình token truy cập Confluence. Hãy nhập token truy cập cá nhân của bạn trong Cài đặt.',
  'Bitbucket did not identify the current user (no X-AUSERNAME header). The access token may be invalid, or a proxy is stripping the header.':
    'Bitbucket không xác định được người dùng hiện tại (thiếu header X-AUSERNAME). Token truy cập có thể không hợp lệ, hoặc một proxy đang loại bỏ header này.',
  'Could not read the svn listing: {0}': 'Không đọc được danh sách của svn: {0}',
  'svn command timed out after {0}s: {1}': 'Lệnh svn hết thời gian chờ sau {0} giây: {1}',
  // Uploads and attachments
  'File exceeds maximum allowed size of {0}MB': 'Tệp tin vượt quá kích thước tối đa cho phép là {0}MB',
  'Attachment exceeds maximum allowed size of {0}MB':
    'Tệp tin đính kèm vượt quá kích thước tối đa cho phép là {0}MB',
  'Attachments exceed the {0}MB allowed in total':
    'Các tệp tin đính kèm vượt quá tổng dung lượng cho phép là {0}MB',
  'Invalid file name: {0}': 'Tên tệp tin không hợp lệ: {0}',
  'Invalid folder name: {0}': 'Tên thư mục không hợp lệ: {0}',
  'Invalid relative path: {0}': 'Đường dẫn tương đối không hợp lệ: {0}',
  'Failed to create folder: {0}': 'Không tạo được thư mục: {0}',
  'Failed to delete file: {0}': 'Không xóa được tệp tin: {0}',
  'Failed to retrieve file: {0}': 'Không lấy được tệp tin: {0}',
  'Failed to store file: {0}': 'Không lưu được tệp tin: {0}',
  'Path must be absolute': 'Đường dẫn phải là đường dẫn tuyệt đối',
  // Accounts and signing in
  'Sign in to start a conversation.': 'Hãy đăng nhập để bắt đầu một cuộc trò chuyện.',
  // Chat, skills and workflows
  'Conversation not found: {0}': 'Không tìm thấy cuộc trò chuyện: {0}',
  'Conversation does not belong to caller': 'Cuộc trò chuyện này không phải của bạn',
  'No such skill: {0}': 'Không có kỹ năng này: {0}',
  'Not permitted to edit skill: {0}': 'Bạn không có quyền chỉnh sửa kỹ năng: {0}',
  'Not permitted to delete skill: {0}': 'Bạn không có quyền xóa kỹ năng: {0}',
  'This is a built-in skill and cannot be deleted. You can edit it, or use Restore default to put it back the way it shipped.':
    'Đây là kỹ năng có sẵn nên không xóa được. Bạn có thể chỉnh sửa, hoặc dùng Khôi phục mặc định để đưa nó về nguyên trạng ban đầu.',
  'No such workflow: {0}': 'Không có quy trình này: {0}',
  'Not permitted to edit workflow: {0}': 'Bạn không có quyền chỉnh sửa quy trình: {0}',
  'Not permitted to delete workflow: {0}': 'Bạn không có quyền xóa quy trình: {0}',
  'A workflow can have at most {0} steps; this one has {1}. Every step is another turn carrying all the previous ones, so a longer one costs more than it looks. Split it into two workflows instead.':
    'Một quy trình có tối đa {0} bước; quy trình này có {1} bước. Mỗi bước là thêm một lượt mang theo toàn bộ các bước trước, nên quy trình dài tốn kém hơn vẻ ngoài của nó. Hãy tách thành hai quy trình.',
  'This workflow names a provider to run on but no model. Pick a model from that provider, or clear the provider to run on the main chat model.':
    'Quy trình này chọn một nhà cung cấp để chạy nhưng chưa chọn mô hình. Hãy chọn một mô hình của nhà cung cấp đó, hoặc bỏ chọn nhà cung cấp để chạy bằng mô hình trò chuyện chính.',
  // Guardrail policy documents
  'A policy document may be at most {0} MB. This corpus is searched on every turn.':
    'Một tài liệu chính sách có dung lượng tối đa {0} MB. Bộ tài liệu này được tìm kiếm ở mỗi lượt.',
  'Could not read the upload.': 'Không đọc được tệp tin tải lên.',
  'The document converted to no text at all.': 'Tài liệu không chuyển đổi được thành văn bản nào.',
  'The file is empty.': 'Tệp tin rỗng.',
  'The stored file is missing.': 'Không tìm thấy tệp tin đã lưu.',
  'This file type cannot be converted with the current Conversion settings.':
    'Loại tệp tin này không chuyển đổi được với các thiết lập Chuyển đổi hiện tại.',
  // License and updates
  'A download is already running': 'Đang có một lượt tải xuống',
  'No download is running': 'Không có lượt tải xuống nào đang chạy',
  'No update available to download': 'Không có bản cập nhật nào để tải xuống',
  'No update staged': 'Chưa có bản cập nhật nào được chuẩn bị',
  'Nothing to download': 'Không có gì để tải xuống',
  'This installation updates by downloading the new release, not in place':
    'Bản cài đặt này cập nhật bằng cách tải bản phát hành mới, không cập nhật tại chỗ',
  'This platform updates by downloading the new release, not in place':
    'Nền tảng này cập nhật bằng cách tải bản phát hành mới, không cập nhật tại chỗ',
  'Could not download {0}: {1}': 'Không tải xuống được {0}: {1}',
  "The release's signature does not verify against the keys built into this installation":
    'Chữ ký của bản phát hành không khớp với các khóa có sẵn trong bản cài đặt này',
  // Backup, reset and configuration transfer
  'Confirmation phrase does not match': 'Cụm từ xác nhận không khớp',
  "This file's secrets block is malformed.": 'Khối thông tin bí mật trong tệp tin này bị lỗi định dạng.',
  'Unsupported key derivation in this file: {0}': 'Tệp tin này dùng cách tạo khóa không được hỗ trợ: {0}',
  'Could not derive a key from the passphrase': 'Không tạo được khóa từ cụm mật khẩu',
  'Could not seal the exported secrets': 'Không mã hóa được các thông tin bí mật khi xuất',
  // Key accounts
  'Unknown tab.': 'Tab không xác định.',
  '{0} is required.': '{0} là bắt buộc.',
  'Administrators only.': 'Chỉ dành cho quản trị viên.',
  'Logs on Enterprise Server are removed by retention only.': 'Nhật ký trên Enterprise Server chỉ được xóa theo thời hạn lưu giữ.',
  // MCP server connections
  "'{0}' is not a valid environment variable name.": "'{0}' không phải là tên biến môi trường hợp lệ.",
  "'{0}' is not a valid header name.": "'{0}' không phải là tên header hợp lệ.",
  'A local MCP server can have at most {0} arguments.': 'Máy chủ MCP cục bộ có tối đa {0} tham số.',
  'A local MCP server needs the command that starts it.': 'Máy chủ MCP cục bộ cần lệnh để khởi động nó.',
  'An MCP server can have at most {0} headers and {1} environment variables.':
    'Máy chủ MCP có tối đa {0} header và {1} biến môi trường.',
  'An MCP server connection needs its transport.': 'Kết nối máy chủ MCP cần có phương thức truyền.',
  'An MCP server needs its URL.': 'Máy chủ MCP cần có URL.',
  "The MCP server '{0}' needs you to sign in again. Use Sign in on it in Settings > Connections.":
    "Máy chủ MCP '{0}' cần bạn đăng nhập lại. Dùng nút Đăng nhập trên kết nối đó trong Cài đặt > Kết nối.",
  "The MCP server '{0}' needs you to sign in. Use Sign in on it in Settings > Connections.":
    "Máy chủ MCP '{0}' cần bạn đăng nhập. Dùng nút Đăng nhập trên kết nối đó trong Cài đặt > Kết nối.",
  'The MCP server did not register Proxyma as a client.': 'Máy chủ MCP không đăng ký Proxyma làm client.',
  'The MCP server refused to register Proxyma as a client (HTTP {0}).': 'Máy chủ MCP từ chối đăng ký Proxyma làm client (HTTP {0}).',
  "The MCP server's client registration could not be read.": 'Không đọc được thông tin đăng ký client của máy chủ MCP.',
  "The MCP server's sign-in metadata is incomplete.": 'Thông tin đăng nhập do máy chủ MCP công bố chưa đầy đủ.',
  "The MCP server's sign-in uses an address that is not http or https.": 'Trang đăng nhập của máy chủ MCP dùng địa chỉ không phải http hoặc https.',
  'This MCP server connection does not sign in with OAuth.': 'Kết nối máy chủ MCP này không đăng nhập bằng OAuth.',
  'This MCP server does not register clients itself. Enter the client ID its administrator gave you on the connection, then sign in.':
    'Máy chủ MCP này không tự đăng ký client. Nhập client ID do quản trị viên của nó cung cấp vào kết nối, rồi đăng nhập.',
  'This MCP server has no URL.': 'Máy chủ MCP này chưa có URL.',
  'This MCP server has no command.': 'Máy chủ MCP này chưa có lệnh.',
  'Too many sign-ins are in progress. Try again in a few minutes.': 'Có quá nhiều lượt đăng nhập đang diễn ra. Hãy thử lại sau vài phút.',
  'Deployment code': 'Mã triển khai',
  'Off - Proxyma Enterprise Server never sends diagnostics': 'Tắt - Proxyma Enterprise Server không bao giờ gửi dữ liệu chẩn đoán',
  'Proxyma Enterprise Server never sends diagnostics or rated answers to Proxyma.': 'Proxyma Enterprise Server không bao giờ gửi dữ liệu chẩn đoán hay câu trả lời đã đánh giá tới Proxyma.',
  'This server cannot add new accounts without an Enterprise license. Existing accounts sign in as before; ask your administrator.': 'Máy chủ này không thể thêm tài khoản mới khi không có giấy phép Enterprise. Các tài khoản hiện có vẫn đăng nhập như trước; hãy hỏi quản trị viên.',

  // THE CONNECTOR FRAMEWORK. The connector catalog's words (i18n/connectorLabels.generated.ts, read
  // out of the descriptors), the generic source and connection dialogs, and what the framework's
  // HTTP client and settings checks say when a service or a value is refused.
  "A Bitbucket repository's pull requests and commits.": 'Pull request và commit của một kho mã Bitbucket.',
  "A Bitbucket Cloud repository's pull requests, with their comments and reviews, and its documentation files if you choose.":
    'Pull request của một kho mã Bitbucket Cloud cùng bình luận và đánh giá của chúng, và các tệp tài liệu nếu bạn chọn.',
  "A Confluence space's pages and their comments.": 'Trang của một không gian Confluence cùng bình luận của chúng.',
  'A workspace, project or repository access token with read access to repositories and pull requests.':
    'Một access token của workspace, dự án hoặc kho mã, có quyền đọc kho mã và pull request.',
  'API token': 'API token',
  'Add a Bitbucket Cloud repository': 'Thêm kho mã Bitbucket Cloud',
  'Bitbucket Cloud': 'Bitbucket Cloud',
  'Bitbucket Cloud repositories': 'Kho mã Bitbucket Cloud',
  'Bitbucket Cloud repository': 'Kho mã Bitbucket Cloud',
  'Create one at id.atlassian.com under Security > API tokens, with Bitbucket scopes to read repositories and pull requests.':
    'Tạo token tại id.atlassian.com, mục Security > API tokens, với phạm vi Bitbucket để đọc kho mã và pull request.',
  'Create one at id.atlassian.com under Security > API tokens. Yours alone - it is never shared with other users.':
    'Tạo token tại id.atlassian.com, mục Security > API tokens. Token là của riêng bạn - không bao giờ được chia sẻ với người dùng khác.',
  'Data Center or Server: the address you open it at. Cloud: your site, https://your-site.atlassian.net':
    'Data Center hoặc Server: địa chỉ bạn dùng để mở nó. Cloud: site của bạn, https://your-site.atlassian.net',
  'Email address': 'Địa chỉ email',
  'Email and API token': 'Email và API token',
  'Email and API token (Cloud)': 'Email và API token (Cloud)',
  'Markdown, reStructuredText and text files on the main branch.': 'Các tệp Markdown, reStructuredText và văn bản trên nhánh chính.',
  'Personal access token (Data Center or Server)': 'Personal access token (Data Center hoặc Server)',
  'The email address you sign in to Atlassian with.': 'Địa chỉ email bạn dùng để đăng nhập Atlassian.',
  'The letters its issue numbers start with: PROJ in PROJ-123.': 'Các chữ cái đứng đầu số hiệu issue: PROJ trong PROJ-123.',
  "The repository's main branch when empty.": 'Nhánh chính của kho mã khi để trống.',
  "A GitHub repository's issues and pull requests, with their comments, and its documentation files if you choose.":
    'Issue và pull request của một kho mã GitHub cùng bình luận của chúng, và các tệp tài liệu nếu bạn chọn.',
  "A Jira project's issues, with their comments and history.": 'Issue của một dự án Jira, cùng bình luận và lịch sử của chúng.',
  'A classic token with the repo scope, or a fine-grained one that can read issues, pull requests and contents.':
    'Một token cổ điển có phạm vi repo, hoặc một token chi tiết có quyền đọc issue, pull request và nội dung.',
  'A folder of documents - PDF, Office, text and more.': 'Một thư mục tài liệu - PDF, Office, văn bản và hơn thế nữa.',
  'A folder on a Windows file server, read as your own domain account.':
    'Một thư mục trên máy chủ tệp Windows, được đọc bằng chính tài khoản miền của bạn.',
  'A website, crawled from a starting address to a depth and page count you set.':
    'Một trang web, được thu thập từ một địa chỉ bắt đầu tới độ sâu và số trang bạn đặt.',
  'API URL': 'URL API',
  'Add a GitHub repository': 'Thêm kho mã GitHub',
  'Branch': 'Nhánh',
  'CRM': 'CRM',
  'Choose one of the options': 'Hãy chọn một trong các lựa chọn',
  'Choose...': 'Chọn...',
  'Cloud storage': 'Lưu trữ đám mây',
  'Code': 'Mã nguồn',
  'Configure connection and settings': 'Cấu hình kết nối và thiết lập',
  'Days of mail': 'Số ngày thư',
  'Docs and wikis': 'Tài liệu và wiki',
  'Documentation files': 'Tệp tài liệu',
  'Domain account': 'Tài khoản miền',
  'Every source on a server or a service reads through one. Add the one you have an account on.':
    'Mọi nguồn dữ liệu trên một máy chủ hay dịch vụ đều đọc qua một kết nối. Hãy thêm kết nối tới nơi bạn có tài khoản.',
  'For GitHub Enterprise Server: https://your-host/api/v3': 'Với GitHub Enterprise Server: https://your-host/api/v3',
  'GitHub': 'GitHub',
  'GitHub repositories': 'Kho mã GitHub',
  'GitHub repository': 'Kho mã GitHub',
  'HTTP access token': 'HTTP access token',
  'Issues': 'Issue',
  'MCP server': 'Máy chủ MCP',
  'Markdown, reStructuredText and text files on the default branch.':
    'Các tệp Markdown, reStructuredText và văn bản trên nhánh mặc định.',
  'Meetings': 'Cuộc họp',
  'More options': 'Thêm tùy chọn',
  'Must be a whole number': 'Phải là số nguyên',
  'Must be a whole number between {min} and {max}': 'Phải là số nguyên từ {min} đến {max}',
  'Must be on one line': 'Phải nằm trên một dòng',
  'Must start with http:// or https://': 'Phải bắt đầu bằng http:// hoặc https://',
  'Not in the form expected': 'Không đúng định dạng',
  'Not in the form expected, for example {example}': 'Không đúng định dạng, ví dụ {example}',
  'Only addresses matching': 'Chỉ các địa chỉ khớp với',
  'Only files under': 'Chỉ các tệp nằm trong',
  'Personal access token': 'Personal access token',
  'Pull requests': 'Pull request',
  'Repository': 'Kho mã',
  'Repository root URL': 'URL gốc của kho mã',
  'Add Slack channels': 'Thêm kênh Slack',
  'Bot token': 'Bot token',
  'Channels': 'Kênh',
  'Channels to leave out': 'Các kênh bỏ qua',
  'Days of history': 'Số ngày lịch sử',
  "From your own Slack app's Basic Information page. Slack returns only to an https:// address, so paste the bot token where Proxyma is not served over https.":
    'Lấy từ trang Basic Information của ứng dụng Slack của riêng bạn. Slack chỉ quay về địa chỉ https://, nên hãy dán bot token nếu Proxyma không chạy qua https.',
  "From your own Slack app's OAuth & Permissions page, once it is installed to your workspace.":
    'Lấy từ trang OAuth & Permissions của ứng dụng Slack của riêng bạn, sau khi đã cài ứng dụng vào workspace.',
  'Leave as it is unless you reach Slack through a relay.': 'Giữ nguyên, trừ khi bạn truy cập Slack qua một máy chủ trung gian.',
  'Messages older than this are left out, and leave the index as they age. Leave empty for the whole history.':
    'Tin nhắn cũ hơn số ngày này sẽ bị bỏ qua, và rời khỏi chỉ mục khi chúng cũ đi. Để trống để lấy toàn bộ lịch sử.',
  'One per line, as #name or a channel ID. The app must be in each one. Leave empty for every public channel the app is in.':
    'Mỗi dòng một kênh, dạng #tên hoặc ID kênh. Ứng dụng phải có mặt trong từng kênh. Để trống để lấy mọi kênh công khai có ứng dụng.',
  'One per line. Useful with every public channel.': 'Mỗi dòng một kênh. Hữu ích khi lấy mọi kênh công khai.',
  'Optional. Lets private channels you are in be indexed without inviting the app to them.':
    'Không bắt buộc. Cho phép lập chỉ mục các kênh riêng tư mà bạn tham gia mà không cần mời ứng dụng vào.',
  'Sign in with Slack': 'Đăng nhập bằng Slack',
  'Slack': 'Slack',
  'Slack channels': 'Kênh Slack',
  'The messages of Slack channels, one document per thread and one per channel per day, read through your own Slack app. Never direct messages.':
    'Tin nhắn của các kênh Slack, mỗi luồng một tài liệu và mỗi kênh mỗi ngày một tài liệu, được đọc qua ứng dụng Slack của riêng bạn. Không bao giờ đọc tin nhắn trực tiếp.',
  'User token': 'User token',
  'Add Notion pages': 'Thêm trang Notion',
  'Add an Airtable base': 'Thêm base Airtable',
  'Airtable': 'Airtable',
  'Airtable base': 'Base Airtable',
  'Airtable bases': 'Base Airtable',
  'Base': 'Base',
  'Create one at airtable.com/create/tokens with the data.records:read and schema.bases:read scopes, and add the bases to index.':
    'Tạo tại airtable.com/create/tokens với các scope data.records:read và schema.bases:read, rồi thêm các base cần lập chỉ mục.',
  'From your OAuth integration at airtable.com/create/oauth. You choose the bases to share when you sign in.':
    'Lấy từ OAuth integration của bạn tại airtable.com/create/oauth. Bạn chọn các base để chia sẻ khi đăng nhập.',
  "From your internal integration's Configuration tab in Notion's developer portal. Share each page or database to index with the integration.":
    'Lấy từ thẻ Configuration của internal integration trong cổng dành cho nhà phát triển của Notion. Hãy chia sẻ từng trang hoặc cơ sở dữ liệu cần lập chỉ mục với integration.',
  "From your public Notion integration's Configuration tab. You choose the pages to share when you sign in.":
    'Lấy từ thẻ Configuration của public integration Notion của bạn. Bạn chọn các trang để chia sẻ khi đăng nhập.',
  'Integration token': 'Integration token',
  'Its name or ID. Leave empty for every table in the base.':
    'Tên hoặc ID của bảng. Để trống để lấy mọi bảng trong base.',
  'Leave as it is unless you reach Airtable through a relay.':
    'Giữ nguyên, trừ khi bạn truy cập Airtable qua một máy chủ trung gian.',
  'Leave as it is unless you reach Notion through a relay.':
    'Giữ nguyên, trừ khi bạn truy cập Notion qua một máy chủ trung gian.',
  'Notion': 'Notion',
  'Notion pages': 'Trang Notion',
  'Notion pages and database rows shared with your integration, each page one document with its properties.':
    'Các trang Notion và hàng cơ sở dữ liệu được chia sẻ với integration của bạn, mỗi trang một tài liệu kèm các thuộc tính.',
  'One per line, as a link or an ID. A page brings every page under it, a database all its rows. Leave empty for everything shared with the integration.':
    'Mỗi dòng một mục, dạng liên kết hoặc ID. Một trang kéo theo mọi trang bên dưới, một cơ sở dữ liệu kéo theo mọi hàng. Để trống để lấy mọi thứ được chia sẻ với integration.',
  'Only the records this view shows, by its name or ID. Needs a table.':
    'Chỉ các bản ghi mà chế độ xem này hiển thị, theo tên hoặc ID. Cần chọn bảng.',
  'Pages and databases': 'Trang và cơ sở dữ liệu',
  'Sign in with Airtable': 'Đăng nhập bằng Airtable',
  'Sign in with Notion': 'Đăng nhập bằng Notion',
  'Table': 'Bảng',
  "The base's ID, or a link to the base, a table or a view - copied from the address bar in Airtable.":
    'ID của base, hoặc liên kết tới base, bảng hay chế độ xem - sao chép từ thanh địa chỉ trong Airtable.',
  'The records of an Airtable base, one document per record with every field, from one table or all of them.':
    'Các bản ghi của một base Airtable, mỗi bản ghi một tài liệu với mọi trường, từ một bảng hoặc tất cả các bảng.',
  'View': 'Chế độ xem',
  'Add Box': 'Thêm Box',
  'Add Dropbox': 'Thêm Dropbox',
  'App key': 'App key',
  'App secret': 'App secret',
  'Box': 'Box',
  'Box folder': 'Thư mục Box',
  'Box folders': 'Thư mục Box',
  'Developer token': 'Developer token',
  'Dropbox': 'Dropbox',
  'Dropbox folder': 'Thư mục Dropbox',
  'Dropbox folders': 'Thư mục Dropbox',
  'Files in Box - PDF and Office files, text and Box Notes - from All Files or one folder.':
    'Các tệp tin trong Box - tệp PDF và Office, văn bản và Box Notes - từ All Files hoặc một thư mục.',
  'Files in Dropbox - PDF and Office files, text and Paper docs - from the whole Dropbox or one folder.':
    'Các tệp tin trong Dropbox - tệp PDF và Office, văn bản và tài liệu Paper - từ toàn bộ Dropbox hoặc một thư mục.',
  "From your Platform App's Configuration tab in the Box Developer Console.":
    'Lấy từ thẻ Configuration của Platform App trong Box Developer Console.',
  "From your app's Settings tab in the Dropbox App Console.":
    'Lấy từ thẻ Settings của ứng dụng trong Dropbox App Console.',
  "Generated on your Platform App's Configuration tab in the Box Developer Console. It expires after 60 minutes, so use it to try Proxyma out.":
    'Được tạo trên thẻ Configuration của Platform App trong Box Developer Console. Token hết hạn sau 60 phút, nên chỉ dùng để dùng thử Proxyma.',
  "Generated on your app's Settings tab in the Dropbox App Console. It expires after four hours, so use it to try Proxyma out.":
    'Được tạo trên thẻ Settings của ứng dụng trong Dropbox App Console. Token hết hạn sau bốn giờ, nên chỉ dùng để dùng thử Proxyma.',
  'Leave it empty to read All Files.': 'Để trống để đọc All Files.',
  'Leave it empty to read your whole Dropbox.': 'Để trống để đọc toàn bộ Dropbox của bạn.',
  'Optional: the sign-in works without it.': 'Không bắt buộc: đăng nhập vẫn hoạt động khi không có.',
  'Sign in with Box': 'Đăng nhập bằng Box',
  'Sign in with Dropbox': 'Đăng nhập bằng Dropbox',
  'Saving takes you to {service} to sign in. The sign-in is never shared with other users.':
    'Khi lưu, bạn sẽ được chuyển tới {service} để đăng nhập. Lần đăng nhập không bao giờ được chia sẻ với người dùng khác.',
  'Sign in with': 'Đăng nhập bằng',
  'Sign in with {service}': 'Đăng nhập bằng {service}',
  'Stay on the same site': 'Chỉ trong cùng trang web',
  'Support': 'Hỗ trợ',
  "The files of a Subversion repository, by path under the connection's server.":
    'Các tệp của một kho mã Subversion, theo đường dẫn trên máy chủ của kết nối.',
  'The last folder of the path, when left empty.': 'Thư mục cuối của đường dẫn, khi để trống.',
  "The repository's default branch when empty.": 'Nhánh mặc định của kho mã khi để trống.',
  'Tickets and tasks': 'Phiếu và công việc',
  'User name and password': 'Tên người dùng và mật khẩu',
  'Websites': 'Trang web',
  '{service} needs an Enterprise license.': '{service} cần giấy phép Enterprise.',

  // The GitLab and Linear connectors (ai.proxyma.connectors.gitlab, .linear): their descriptors' words
  // and what their sessions say about a source or an item.
  "A GitLab project's or group's issues and merge requests, with their comments, and its documentation files if you choose.":
    'Issue và merge request của một dự án hoặc nhóm GitLab cùng bình luận của chúng, và các tệp tài liệu nếu bạn chọn.',
  "A group's path reads every project under it, its subgroups included.":
    'Đường dẫn của một nhóm sẽ đọc mọi dự án bên trong nó, kể cả các nhóm con.',
  'A personal, group or project access token with the read_api scope.':
    'Một access token cá nhân, nhóm hoặc dự án có phạm vi read_api.',
  'Add Linear issues': 'Thêm issue Linear',
  'Add a GitLab project or group': 'Thêm dự án hoặc nhóm GitLab',
  'An OAuth application created in Linear under Settings > API.': 'Một ứng dụng OAuth được tạo trong Linear tại Settings > API.',
  'An application registered on gitlab.com with the read_api scope. For a self-managed GitLab, use an access token.':
    'Một ứng dụng đã đăng ký trên gitlab.com với phạm vi read_api. Với GitLab tự quản lý, hãy dùng access token.',
  'Application ID': 'Application ID',
  'Create one in Linear under Settings > Security & access.': 'Hãy tạo một khóa trong Linear tại Settings > Security & access.',
  "Each project's default branch when empty.": 'Nhánh mặc định của từng dự án khi để trống.',
  'For a self-managed GitLab: https://your-host': 'Với GitLab tự quản lý: https://your-host',
  'GitLab': 'GitLab',
  'GitLab URL': 'URL GitLab',
  'GitLab project': 'Dự án GitLab',
  'GitLab projects': 'Dự án GitLab',
  'Linear': 'Linear',
  'Linear issues with their descriptions and comments, from the teams you choose.':
    'Issue Linear cùng mô tả và bình luận của chúng, từ các nhóm bạn chọn.',
  'Linear source': 'Nguồn Linear',
  'Linear sources': 'Nguồn Linear',
  'Merge requests': 'Merge request',
  'Personal API key': 'Personal API key',
  'Project or group': 'Dự án hoặc nhóm',
  'Secret': 'Secret',
  'Separated by commas. Every team the connection can see when empty.':
    'Cách nhau bằng dấu phẩy. Mọi nhóm mà kết nối nhìn thấy khi để trống.',
  'Sign in with GitLab': 'Đăng nhập bằng GitLab',
  'Sign in with Linear': 'Đăng nhập bằng Linear',
  'Team keys': 'Mã nhóm',
  'A GitLab source needs the path of its project or group.': 'Nguồn GitLab cần đường dẫn của dự án hoặc nhóm.',
  'GitLab has no branch to read {0} from.': 'GitLab không có nhánh nào để đọc {0}.',
  'GitLab has no branch {0} in {1}.': 'GitLab không có nhánh {0} trong {1}.',
  'GitLab no longer has {0} in {1}.': 'GitLab không còn {0} trong {1}.',
  'Linear has archived or deleted {0}.': 'Linear đã lưu trữ hoặc xóa {0}.',
  'Linear no longer has {0}.': 'Linear không còn {0}.',
  'Linear refused the query: {0}': 'Linear từ chối truy vấn: {0}',
  'That is not an item of a GitLab project.': 'Đó không phải một mục của dự án GitLab.',
  'That is not an item of a Linear source.': 'Đó không phải một mục của nguồn Linear.',
  '{0} is larger than 1 MB, so it is not read as documentation.': '{0} lớn hơn 1 MB, nên không được đọc như tài liệu.',
  // Google: one connection for Drive, Gmail and Calendar (ai.proxyma.connectors.google).
  'Add Google Drive': 'Thêm Google Drive',
  'Add a Gmail mailbox': 'Thêm hộp thư Gmail',
  'Add a Google calendar': 'Thêm lịch Google',
  'Calendars': 'Lịch',
  'Conversations in a Gmail mailbox, under the labels you choose - the inbox by default.':
    'Các cuộc hội thoại trong một hộp thư Gmail, thuộc các nhãn bạn chọn - mặc định là hộp thư đến.',
  'Days ahead': 'Số ngày tới',
  'Days back': 'Số ngày trước',
  'Each calendar to read, by its ID: primary for your own, or the calendar ID from its settings in Google Calendar.':
    'Mỗi lịch cần đọc, theo mã lịch: primary cho lịch của bạn, hoặc mã lịch trong phần cài đặt của lịch đó trên Google Calendar.',
  'Each label to read, by its name: INBOX, SENT, or one of your own. Drafts, spam and trash are never read.':
    'Mỗi nhãn cần đọc, theo tên: INBOX, SENT hoặc một nhãn của riêng bạn. Thư nháp, thư rác và thùng rác không bao giờ được đọc.',
  'Each shared drive to read, by its ID or the link to it.': 'Mỗi bộ nhớ dùng chung cần đọc, theo mã hoặc đường liên kết tới nó.',
  'Events in Google calendars - who, when, where and what - from a year back to half a year ahead.':
    'Sự kiện trong lịch Google - ai, khi nào, ở đâu và về việc gì - từ một năm trước tới nửa năm tới.',
  'Events that ended up to this many days ago.': 'Các sự kiện đã kết thúc trong vòng chừng này ngày trước.',
  'Events that start up to this many days from now.': 'Các sự kiện bắt đầu trong vòng chừng này ngày tới.',
  'Files in Google Drive - Docs, Sheets and Slides, PDF and Office files - from My Drive, shared drives or one folder.':
    'Tệp trong Google Drive - Docs, Sheets và Slides, tệp PDF và Office - từ Drive của tôi, bộ nhớ dùng chung hoặc một thư mục.',
  'Files other people shared with you.': 'Các tệp người khác đã chia sẻ với bạn.',
  'From the OAuth client you created in Google Cloud, under APIs & Services > Credentials.':
    'Lấy từ OAuth client bạn đã tạo trong Google Cloud, tại APIs & Services > Credentials.',
  'Gmail': 'Gmail',
  'Gmail mailbox': 'Hộp thư Gmail',
  'Gmail mailboxes': 'Hộp thư Gmail',
  'Google': 'Google',
  'Google Calendar': 'Google Calendar',
  'Google Drive': 'Google Drive',
  'Google calendar': 'Lịch Google',
  'Google calendars': 'Lịch Google',
  'Labels': 'Nhãn',
  'Leave it as it is unless you are told otherwise.': 'Giữ nguyên trừ khi bạn được hướng dẫn khác.',
  'My Drive': 'Drive của tôi',
  'Only conversations with a message from this many days before today.':
    'Chỉ các cuộc hội thoại có thư trong chừng này ngày trước hôm nay.',
  'Only this folder': 'Chỉ thư mục này',
  'Read this folder and the folders inside it, instead of the choices above.':
    'Đọc thư mục này và các thư mục bên trong nó, thay cho các lựa chọn ở trên.',
  'Service account': 'Service account',
  'Service account key': 'Khóa service account',
  'Shared drives': 'Bộ nhớ dùng chung',
  'Shared with me': 'Được chia sẻ với tôi',
  'Sign in with Google': 'Đăng nhập bằng Google',
  'The Workspace user whose Drive, mail and calendar are read.': 'Người dùng Workspace có Drive, thư và lịch được đọc.',
  'The files you own.': 'Các tệp bạn sở hữu.',
  'The whole JSON key file of a service account with domain-wide delegation.':
    'Toàn bộ tệp khóa JSON của một service account có ủy quyền toàn miền (domain-wide delegation).',
  'User to act as': 'Người dùng được đại diện',

  // Zendesk and Freshdesk (ai.proxyma.connectors.zendesk, .freshdesk): their descriptors' words and
  // the Zendesk Test's verdict.
  'Add Freshdesk tickets or articles': 'Thêm phiếu hỗ trợ hoặc bài viết Freshdesk',
  'Add Zendesk tickets or articles': 'Thêm phiếu hỗ trợ hoặc bài viết Zendesk',
  'Agent email address': 'Địa chỉ email của nhân viên hỗ trợ',
  "Agents' private comments on tickets. Off: only the public conversation is read.":
    'Bình luận nội bộ của nhân viên hỗ trợ trên phiếu. Khi tắt: chỉ đọc phần trao đổi công khai.',
  'An OAuth client created in Admin Center under Apps and integrations > APIs > OAuth Clients.':
    'Một OAuth client được tạo trong Admin Center tại Apps and integrations > APIs > OAuth Clients.',
  'Article categories': 'Danh mục bài viết',
  'Article languages': 'Ngôn ngữ bài viết',
  'Article sections': 'Mục bài viết',
  "Category numbers, separated by commas - the number in a category's address. Every category when empty.":
    'Mã số danh mục, cách nhau bằng dấu phẩy - con số trong địa chỉ của danh mục. Mọi danh mục khi để trống.',
  'Client identifier': 'Mã định danh client (client identifier)',
  'Create one in Admin Center under Apps and integrations > APIs > Zendesk API, with token access turned on.':
    'Hãy tạo một token trong Admin Center tại Apps and integrations > APIs > Zendesk API, với quyền truy cập bằng token đã được bật.',
  'Domain': 'Tên miền',
  'Every ticket the agent can see, with its comments.': 'Mọi phiếu hỗ trợ mà nhân viên đó xem được, cùng bình luận của chúng.',
  'Every ticket the agent can see, with its replies and notes.':
    'Mọi phiếu hỗ trợ mà nhân viên đó xem được, cùng phản hồi và ghi chú của chúng.',
  'Freshdesk': 'Freshdesk',
  'Freshdesk address': 'Địa chỉ Freshdesk',
  'Freshdesk source': 'Nguồn Freshdesk',
  'Freshdesk sources': 'Nguồn Freshdesk',
  'Freshdesk tickets with their conversations, and knowledge base articles if you choose.':
    'Phiếu hỗ trợ Freshdesk cùng các cuộc trao đổi của chúng, và bài viết cơ sở tri thức nếu bạn chọn.',
  'Help Center articles': 'Bài viết Help Center',
  'In Freshdesk, open your profile picture > Profile settings > View API key.':
    'Trong Freshdesk, mở ảnh hồ sơ của bạn > Profile settings > View API key.',
  'Internal notes': 'Ghi chú nội bộ',
  'Knowledge base articles': 'Bài viết cơ sở tri thức',
  "Language codes, separated by commas. The Help Center's default language when empty.":
    'Mã ngôn ngữ, cách nhau bằng dấu phẩy. Ngôn ngữ mặc định của Help Center khi để trống.',
  'Notes only agents can see. Off: replies and public notes are read.':
    'Ghi chú chỉ nhân viên hỗ trợ xem được. Khi tắt: chỉ đọc phản hồi và ghi chú công khai.',
  "Only for an address other than your domain's.": 'Chỉ dùng cho một địa chỉ khác với địa chỉ theo tên miền của bạn.',
  "Only for an address other than your subdomain's, such as your own domain.":
    'Chỉ dùng cho một địa chỉ khác với địa chỉ theo tên miền phụ của bạn, chẳng hạn tên miền riêng.',
  'Private notes': 'Ghi chú riêng tư',
  'Published articles. Drafts are left out.': 'Các bài viết đã xuất bản. Bản nháp không được đọc.',
  'Published solution articles. Drafts are left out.': 'Các bài viết giải pháp đã xuất bản. Bản nháp không được đọc.',
  "Section numbers, separated by commas - the number in a section's address. Every section when empty.":
    'Mã số mục, cách nhau bằng dấu phẩy - con số trong địa chỉ của mục. Mọi mục khi để trống.',
  'Sign in with Zendesk': 'Đăng nhập bằng Zendesk',
  'Signed in as {user} on {site}': 'Đã đăng nhập với tên {user} trên {site}',
  'Subdomain': 'Tên miền phụ',
  'The email address of the agent whose access is used.': 'Địa chỉ email của nhân viên hỗ trợ có quyền truy cập được sử dụng.',
  'The first part of your Freshdesk address - yourcompany in yourcompany.freshdesk.com.':
    'Phần đầu của địa chỉ Freshdesk của bạn - yourcompany trong yourcompany.freshdesk.com.',
  'The first part of your Zendesk address - yourcompany in yourcompany.zendesk.com.':
    'Phần đầu của địa chỉ Zendesk của bạn - yourcompany trong yourcompany.zendesk.com.',
  'Tickets': 'Phiếu hỗ trợ',
  'Zendesk': 'Zendesk',
  'Zendesk address': 'Địa chỉ Zendesk',
  'Zendesk source': 'Nguồn Zendesk',
  'Zendesk sources': 'Nguồn Zendesk',
  'Zendesk tickets with their comments, and Help Center articles if you choose.':
    'Phiếu hỗ trợ Zendesk cùng bình luận của chúng, và bài viết Help Center nếu bạn chọn.',

  // The HubSpot and Salesforce connectors (ai.proxyma.connectors.hubspot, .salesforce): their
  // descriptors' words, their connections' Test verdicts, and what their sessions say.
  'A HubSpot app created in a HubSpot developer account, with the CRM read scopes.':
    'Một ứng dụng HubSpot được tạo trong tài khoản nhà phát triển HubSpot, có các phạm vi đọc CRM.',
  'API version': 'Phiên bản API',
  'Accounts': 'Tài khoản khách hàng',
  'Add HubSpot records': 'Thêm bản ghi HubSpot',
  'Add Salesforce records': 'Thêm bản ghi Salesforce',
  'Audience': 'Audience',
  'Cases': 'Case',
  'Companies': 'Công ty',
  'Consumer key': 'Consumer key',
  'Consumer secret': 'Consumer secret',
  'Contacts': 'Liên hệ',
  "Create one in HubSpot under Settings > Integrations > Service Keys, with read scopes for the records to index. A legacy private app's access token works too.":
    'Hãy tạo một khóa trong HubSpot tại Settings > Integrations > Service Keys, với phạm vi đọc cho các bản ghi cần lập chỉ mục. Access token của một private app cũ cũng dùng được.',
  'Deals': 'Giao dịch',
  'From the External Client App whose JWT bearer flow has your certificate.':
    'Lấy từ External Client App có chứng chỉ của bạn trong luồng JWT bearer.',
  "From the org's External Client App, with OAuth, PKCE and the api, id and refresh_token scopes.":
    'Lấy từ External Client App của tổ chức, có bật OAuth, PKCE và các phạm vi api, id và refresh_token.',
  'HubSpot': 'HubSpot',
  'HubSpot companies, contacts, deals and tickets, each with its notes, activity and associated records.':
    'Công ty, liên hệ, giao dịch và ticket HubSpot, mỗi mục kèm ghi chú, hoạt động và các bản ghi liên kết.',
  'HubSpot source': 'Nguồn HubSpot',
  'HubSpot sources': 'Nguồn HubSpot',
  "HubSpot's dated API version. Leave empty for the one this version of Proxyma was built on.":
    'Phiên bản API theo ngày của HubSpot. Để trống để dùng phiên bản mà bản Proxyma này được xây dựng theo.',
  'JWT bearer (integration user)': 'JWT bearer (người dùng tích hợp)',
  'Leads': 'Khách hàng tiềm năng',
  'My Domain URL': 'URL My Domain',
  'Objects': 'Đối tượng',
  'Private key': 'Khóa riêng tư',
  "Read as the connection's Salesforce user, so this source cannot be shared.":
    'Được đọc với quyền của người dùng Salesforce trong kết nối, nên nguồn này không thể chia sẻ.',
  'Records': 'Bản ghi',
  'Salesforce': 'Salesforce',
  'Salesforce accounts, contacts, opportunities, leads and cases, each with its notes, activity and related records.':
    'Tài khoản khách hàng, liên hệ, cơ hội, khách hàng tiềm năng và case Salesforce, mỗi mục kèm ghi chú, hoạt động và các bản ghi liên quan.',
  'Salesforce source': 'Nguồn Salesforce',
  'Salesforce sources': 'Nguồn Salesforce',
  "Salesforce's REST API version. Leave empty for the one this version of Proxyma was built on.":
    'Phiên bản REST API của Salesforce. Để trống để dùng phiên bản mà bản Proxyma này được xây dựng theo.',
  'Service Key': 'Service Key',
  'Service Key or private app token': 'Service Key hoặc token của private app',
  'Sign in with HubSpot': 'Đăng nhập bằng HubSpot',
  'Sign in with Salesforce': 'Đăng nhập bằng Salesforce',
  'The PEM private key of the certificate uploaded to the app, unencrypted.':
    'Khóa riêng tư PEM của chứng chỉ đã tải lên ứng dụng, không mã hóa.',
  'The connection reads the whole HubSpot account, so this source cannot be shared.':
    'Kết nối đọc toàn bộ tài khoản HubSpot, nên nguồn này không thể chia sẻ.',
  "The integration user's Salesforce user name, pre-authorized for the app.":
    'Tên người dùng Salesforce của người dùng tích hợp, đã được cấp quyền trước cho ứng dụng.',
  'Told from the address when empty: https://test.salesforce.com for a sandbox.':
    'Khi để trống sẽ được suy ra từ địa chỉ: https://test.salesforce.com cho sandbox.',
  'User name': 'Tên người dùng',
  "Your org's address. For a sandbox: https://acme--uat.sandbox.my.salesforce.com":
    'Địa chỉ tổ chức của bạn. Với sandbox: https://acme--uat.sandbox.my.salesforce.com',
  'Signed in to portal {portal}': 'Đã đăng nhập vào portal {portal}',
  'Signed in as {user} on {instance}': 'Đã đăng nhập với tên {user} trên {instance}',
  'HubSpot has archived {0}:{1}.': 'HubSpot đã lưu trữ {0}:{1}.',
  'HubSpot no longer has {0}:{1}.': 'HubSpot không còn {0}:{1}.',
  "HubSpot's daily request allowance is nearly used up, so this sync stopped to leave the rest to the account's other integrations. The next sync will try again.":
    'Hạn mức yêu cầu hằng ngày của HubSpot sắp hết, nên lần đồng bộ này đã dừng để dành phần còn lại cho các tích hợp khác của tài khoản. Lần đồng bộ sau sẽ thử lại.',
  'Salesforce answered the sign-in without a token.': 'Salesforce trả lời lần đăng nhập mà không kèm token.',
  'Salesforce named an org address that is not a URL.': 'Salesforce đưa ra một địa chỉ tổ chức không phải là URL.',
  "Salesforce named {0} as the org's address, which is not a Salesforce server, so the token was not sent there.":
    'Salesforce cho biết {0} là địa chỉ của tổ chức, nhưng đó không phải máy chủ Salesforce, nên token không được gửi tới đó.',
  'Salesforce no longer has {0}:{1}.': 'Salesforce không còn {0}:{1}.',
  'Salesforce refused the JWT sign-in ({0}). Check the consumer key, the user name, the certificate uploaded to the app, and that the user is pre-authorized for it.':
    'Salesforce đã từ chối đăng nhập JWT ({0}). Hãy kiểm tra consumer key, tên người dùng, chứng chỉ đã tải lên ứng dụng, và người dùng đã được cấp quyền trước cho ứng dụng chưa.',
  'That is not an item of a HubSpot source.': 'Đó không phải một mục của nguồn HubSpot.',
  'That is not an item of a Salesforce source.': 'Đó không phải một mục của nguồn Salesforce.',
  'The private key could not be read. Paste the whole unencrypted PEM key, BEGIN and END lines included.':
    'Không đọc được khóa riêng tư. Hãy dán toàn bộ khóa PEM không mã hóa, gồm cả dòng BEGIN và END.',
  'The private key is encrypted. Paste it without a passphrase (openssl pkcs8 -topk8 -nocrypt).':
    'Khóa riêng tư đang được mã hóa. Hãy dán khóa không có passphrase (openssl pkcs8 -topk8 -nocrypt).',
  'This connection has no My Domain URL. Add it in Settings > Connections.':
    'Kết nối này chưa có URL My Domain. Hãy thêm trong Cài đặt > Kết nối.',
  "This connection has no consumer key. Add the External Client App's in Settings > Connections.":
    'Kết nối này chưa có consumer key. Hãy thêm consumer key của External Client App trong Cài đặt > Kết nối.',
  'This connection has no private key. Add it in Settings > Connections.':
    'Kết nối này chưa có khóa riêng tư. Hãy thêm trong Cài đặt > Kết nối.',
  "This connection has no user name. Add the integration user's in Settings > Connections.":
    'Kết nối này chưa có tên người dùng. Hãy thêm tên của người dùng tích hợp trong Cài đặt > Kết nối.',
  // The work-management connectors (ai.proxyma.connectors.asana, .monday, .trello, .clickup): their
  // descriptors' words, their Test verdict and what their sessions say about a source or an item.
  'API key and token': 'API key và token',
  'Add Asana tasks': 'Thêm công việc Asana',
  'Add ClickUp tasks': 'Thêm công việc ClickUp',
  'Add Trello cards': 'Thêm thẻ Trello',
  'Add monday.com items': 'Thêm mục monday.com',
  'An app created in ClickUp under Settings > Integrations > ClickUp API.':
    'Một ứng dụng được tạo trong ClickUp tại Settings > Integrations > ClickUp API.',
  'An app created in the Asana developer console, with read scopes.':
    'Một ứng dụng được tạo trong Asana developer console, với các phạm vi chỉ đọc.',
  'An app created in the monday.com Developer Center, with read scopes.':
    'Một ứng dụng được tạo trong monday.com Developer Center, với các phạm vi chỉ đọc.',
  'Asana': 'Asana',
  'Asana source': 'Nguồn Asana',
  'Asana sources': 'Nguồn Asana',
  'Asana tasks with their notes, comments and subtasks, from the projects you choose.':
    'Công việc Asana cùng ghi chú, bình luận và công việc con của chúng, từ các dự án bạn chọn.',
  "Board IDs or links, separated by commas - the number after /boards/ in a board's address. Every board the connection can see when empty.":
    'Mã hoặc đường liên kết của bảng, cách nhau bằng dấu phẩy - là con số sau /boards/ trong địa chỉ của bảng. Mọi bảng mà kết nối nhìn thấy khi để trống.',
  "Board links or IDs, separated by commas. Every open board of the connection's member when empty.":
    'Đường liên kết hoặc mã của bảng, cách nhau bằng dấu phẩy. Mọi bảng đang mở của thành viên sở hữu kết nối khi để trống.',
  'Boards': 'Bảng',
  'ClickUp': 'ClickUp',
  'ClickUp source': 'Nguồn ClickUp',
  'ClickUp sources': 'Nguồn ClickUp',
  'ClickUp tasks with their descriptions, comments and subtasks, from a workspace or the spaces, folders and lists you choose.':
    'Công việc ClickUp cùng mô tả, bình luận và công việc con của chúng, từ một không gian làm việc hoặc các space, thư mục và danh sách bạn chọn.',
  'Create one in ClickUp under Settings > Apps. It starts with pk_.': 'Hãy tạo một token trong ClickUp tại Settings > Apps. Token bắt đầu bằng pk_.',
  'Create one in the Asana developer console, under Personal access tokens.':
    'Hãy tạo một token trong Asana developer console, tại Personal access tokens.',
  'Folder IDs, separated by commas.': 'Mã thư mục, cách nhau bằng dấu phẩy.',
  'Folders': 'Thư mục',
  'Generated from the Token link beside the API key, with read access.':
    'Được tạo từ liên kết Token bên cạnh API key, với quyền đọc.',
  'In monday.com, your avatar > Developers > API token.': 'Trong monday.com, ảnh đại diện của bạn > Developers > API token.',
  'List IDs, separated by commas.': 'Mã danh sách, cách nhau bằng dấu phẩy.',
  'Lists': 'Danh sách',
  "On your Power-Up's page in the Trello Power-Up admin portal, under API key.":
    'Trên trang Power-Up của bạn trong Trello Power-Up admin portal, tại API key.',
  'Personal API token': 'Personal API token',
  'Project IDs or names, separated by commas. Every project the connection can see in the workspace when empty.':
    'Mã hoặc tên dự án, cách nhau bằng dấu phẩy. Mọi dự án trong không gian làm việc mà kết nối nhìn thấy khi để trống.',
  'Projects': 'Dự án',
  'Sign in with Asana': 'Đăng nhập bằng Asana',
  'Sign in with ClickUp': 'Đăng nhập bằng ClickUp',
  'Sign in with monday.com': 'Đăng nhập bằng monday.com',
  'Space IDs, separated by commas. Every task of the workspace when no space, folder or list is chosen.':
    'Mã space, cách nhau bằng dấu phẩy. Mọi công việc của không gian làm việc khi không chọn space, thư mục hay danh sách nào.',
  'Spaces': 'Space',
  "The workspace's name as Asana shows it, or its ID.": 'Tên không gian làm việc như Asana hiển thị, hoặc mã của nó.',
  "The workspace's name, or its ID - the number after app.clickup.com/ in its address.":
    'Tên không gian làm việc, hoặc mã của nó - là con số sau app.clickup.com/ trong địa chỉ của nó.',
  'Token': 'Token',
  'Trello': 'Trello',
  'Trello cards with their descriptions, checklists and comments, from the boards you choose.':
    'Thẻ Trello cùng mô tả, danh sách kiểm tra và bình luận của chúng, từ các bảng bạn chọn.',
  'Trello source': 'Nguồn Trello',
  'Trello sources': 'Nguồn Trello',
  'monday.com': 'monday.com',
  'monday.com items with their columns, updates and subitems, from the boards you choose.':
    'Mục monday.com cùng các cột, cập nhật và mục con của chúng, từ các bảng bạn chọn.',
  'monday.com source': 'Nguồn monday.com',
  'monday.com sources': 'Nguồn monday.com',
  'A ClickUp source needs its workspace.': 'Nguồn ClickUp cần không gian làm việc của nó.',
  'An Asana source needs its workspace.': 'Nguồn Asana cần không gian làm việc của nó.',
  'Asana has no project {0} in the workspace {1}, or it is archived, or the connection cannot see it.':
    'Asana không có dự án {0} trong không gian làm việc {1}, hoặc dự án đã được lưu trữ, hoặc kết nối không xem được.',
  'Asana has no workspace {0}, or the connection cannot see it.': 'Asana không có không gian làm việc {0}, hoặc kết nối không xem được.',
  'Asana no longer has {0}.': 'Asana không còn {0}.',
  "Asana's task {0} is no longer in a project this source reads.": 'Công việc {0} của Asana không còn thuộc dự án nào mà nguồn này đọc.',
  "Asana's task {0} is no longer in the source's workspace.": 'Công việc {0} của Asana không còn thuộc không gian làm việc của nguồn này.',
  'ClickUp has no folder {0}, or the connection cannot see it.': 'ClickUp không có thư mục {0}, hoặc kết nối không xem được.',
  'ClickUp has no list {0}, or the connection cannot see it.': 'ClickUp không có danh sách {0}, hoặc kết nối không xem được.',
  'ClickUp has no space {0}, or the connection cannot see it.': 'ClickUp không có space {0}, hoặc kết nối không xem được.',
  'ClickUp has no workspace {0}, or the connection cannot see it.': 'ClickUp không có không gian làm việc {0}, hoặc kết nối không xem được.',
  'ClickUp no longer has {0}.': 'ClickUp không còn {0}.',
  "ClickUp's task {0} is archived.": 'Công việc {0} của ClickUp đã được lưu trữ.',
  "ClickUp's task {0} is no longer in a space, folder or list this source reads.":
    'Công việc {0} của ClickUp không còn thuộc space, thư mục hay danh sách nào mà nguồn này đọc.',
  'That is not an item of a ClickUp source.': 'Đó không phải một mục của nguồn ClickUp.',
  'That is not an item of a Trello source.': 'Đó không phải một mục của nguồn Trello.',
  'That is not an item of a monday.com source.': 'Đó không phải một mục của nguồn monday.com.',
  'That is not an item of an Asana source.': 'Đó không phải một mục của nguồn Asana.',
  'This connection has no Trello API key. Add it in Settings > Connections.': 'Kết nối này chưa có Trello API key. Hãy thêm trong Cài đặt > Kết nối.',
  'Trello has no board {0}, or the connection cannot see it.': 'Trello không có bảng {0}, hoặc kết nối không xem được.',
  'Trello no longer has {0}.': 'Trello không còn {0}.',
  "Trello's card {0} is archived.": 'Thẻ {0} của Trello đã được lưu trữ.',
  "Trello's card {0} is no longer on a board this source reads.": 'Thẻ {0} của Trello không còn nằm trên bảng nào mà nguồn này đọc.',
  'monday.com has no board {0}, or the connection cannot see it.': 'monday.com không có bảng {0}, hoặc kết nối không xem được.',
  'monday.com no longer has {0}.': 'monday.com không còn {0}.',
  'monday.com refused the query: {0}': 'monday.com từ chối truy vấn: {0}',
  "monday.com's daily limit of API calls for this account is used up. The sync continues after midnight UTC.":
    'Tài khoản này đã dùng hết hạn mức lệnh gọi API trong ngày của monday.com. Việc đồng bộ sẽ tiếp tục sau nửa đêm UTC.',
  "monday.com's item {0} is archived or deleted.": 'Mục {0} của monday.com đã được lưu trữ hoặc đã bị xóa.',
  "monday.com's item {0} is no longer on a board this source reads.": 'Mục {0} của monday.com không còn nằm trên bảng nào mà nguồn này đọc.',

  // The Zoom and IMAP connectors (ai.proxyma.connectors.zoom, .imap): their descriptors' words and
  // what their sessions say.
  'Account ID': 'ID tài khoản (Account ID)',
  'Add Zoom meetings': 'Thêm cuộc họp Zoom',
  'Add an IMAP mailbox': 'Thêm hộp thư IMAP',
  'An app password where your mail service offers them. Microsoft 365 and Outlook.com mailboxes accept no password over IMAP.':
    'Dùng mật khẩu ứng dụng (app password) nếu dịch vụ thư của bạn có cung cấp. Hộp thư Microsoft 365 và Outlook.com không chấp nhận mật khẩu qua IMAP.',
  'Days of meetings': 'Số ngày cuộc họp',
  "Each folder to read, by its full name: INBOX, Sent, Projects/2026. A folder's subfolders are read only when they are named too.":
    'Mỗi thư mục cần đọc, theo tên đầy đủ: INBOX, Sent, Projects/2026. Thư mục con chỉ được đọc khi cũng được liệt kê.',
  'From the Zoom app you created for Proxyma, under App Credentials.':
    'Lấy từ ứng dụng Zoom bạn đã tạo cho Proxyma, tại App Credentials.',
  "From the app's App Credentials page in the Zoom App Marketplace.":
    'Lấy từ trang App Credentials của ứng dụng trong Zoom App Marketplace.',
  'IMAP mail': 'Thư IMAP',
  'IMAP mailbox': 'Hộp thư IMAP',
  'IMAP mailboxes': 'Hộp thư IMAP',
  'Leave it empty for the usual port: 993 with TLS, 143 otherwise.':
    'Để trống để dùng cổng thông thường: 993 với TLS, 143 với các lựa chọn khác.',
  'Mail server': 'Máy chủ thư',
  'Messages in the folders you choose of a mailbox that accepts a password over IMAP - the inbox by default.':
    'Thư trong các thư mục bạn chọn của một hộp thư chấp nhận đăng nhập bằng mật khẩu qua IMAP - mặc định là hộp thư đến.',
  'None (this computer only)': 'Không có (chỉ máy tính này)',
  'Only meetings from this many days before today.': 'Chỉ các cuộc họp trong chừng ấy ngày tính đến hôm nay.',
  'Only messages from or to an address at one of these domains. Empty reads every message.':
    'Chỉ thư gửi từ hoặc gửi tới một địa chỉ thuộc một trong các tên miền này. Để trống sẽ đọc mọi thư.',
  'Only messages received on or after this day, as YYYY-MM-DD. Empty reads them all.':
    'Chỉ thư nhận được từ ngày này trở đi, theo dạng YYYY-MM-DD. Để trống sẽ đọc tất cả.',
  'Only these domains': 'Chỉ các tên miền này',
  'Port': 'Cổng',
  'Read attached documents - PDF, Word, Excel, PowerPoint, text - as documents of their own.':
    'Đọc các tài liệu đính kèm - PDF, Word, Excel, PowerPoint, văn bản - thành tài liệu riêng.',
  'Received since': 'Nhận từ ngày',
  'STARTTLS': 'STARTTLS',
  'Security': 'Bảo mật',
  'Server-to-Server OAuth app': 'Ứng dụng Server-to-Server OAuth',
  'Sign in with Zoom': 'Đăng nhập bằng Zoom',
  'TLS': 'TLS',
  'TLS on port 993 for almost every mail service. STARTTLS is port 143.':
    'TLS trên cổng 993 cho hầu hết dịch vụ thư. STARTTLS dùng cổng 143.',
  "The IMAP server's host name, as your mail service gives it.":
    'Tên máy chủ IMAP, theo thông tin dịch vụ thư của bạn cung cấp.',
  'Transcripts of Zoom cloud recordings and AI Companion meeting summaries.':
    'Bản chép lời của các bản ghi đám mây Zoom và bản tóm tắt cuộc họp của AI Companion.',
  'Usually your whole email address.': 'Thường là toàn bộ địa chỉ email của bạn.',
  'Whose meetings to read, by email address. Leave it empty for your own. With a Server-to-Server app, name the people, or * for everyone on the account.':
    'Cuộc họp của những ai cần đọc, theo địa chỉ email. Để trống để đọc cuộc họp của chính bạn. Với ứng dụng Server-to-Server, hãy liệt kê từng người, hoặc * để đọc của mọi người trong tài khoản.',
  'Zoom': 'Zoom',
  'Zoom meeting list': 'Danh sách cuộc họp Zoom',
  'Zoom meeting lists': 'Danh sách cuộc họp Zoom',
  'A connection without TLS is allowed only to a mail server on this computer. Choose TLS or STARTTLS.':
    'Kết nối không dùng TLS chỉ được phép tới máy chủ thư trên chính máy tính này. Hãy chọn TLS hoặc STARTTLS.',
  'That is not an item of a Zoom source.': 'Đó không phải một mục của nguồn Zoom.',
  'That is not an item of an IMAP mailbox.': 'Đó không phải một mục của hộp thư IMAP.',
  'The attachment is too large to read.': 'Tệp đính kèm quá lớn để đọc.',
  'The folder {0} holds no messages.': 'Thư mục {0} không chứa thư.',
  'The folder {0} was rebuilt on the server, so its old messages are gone.':
    'Thư mục {0} đã được tạo lại trên máy chủ, nên các thư cũ của nó không còn nữa.',
  'The mail server must be a host name such as imap.example.com, without imap:// or a port.':
    'Máy chủ thư phải là một tên máy chủ như imap.example.com, không kèm imap:// hay cổng.',
  'The mail server stopped sending the message: {0}': 'Máy chủ thư đã ngừng gửi thư: {0}',
  "The mail server's port must be a number, such as 993.": 'Cổng của máy chủ thư phải là một số, ví dụ 993.',
  'The mailbox has no folder called {0}.': 'Hộp thư không có thư mục nào tên {0}.',
  'The mailbox no longer has a folder called {0}.': 'Hộp thư không còn thư mục nào tên {0}.',
  "The meeting's transcript is too large to read.": 'Bản chép lời của cuộc họp quá lớn để đọc.',
  'The message is no longer in {0}.': 'Thư không còn trong {0}.',
  'The message no longer has that attachment.': 'Thư không còn tệp đính kèm đó.',
  'The structure of a message could not be read: {0}': 'Không đọc được cấu trúc của một thư: {0}',
  "This connection has no Zoom account ID. Add the app's in Settings > Connections.":
    'Kết nối này chưa có Zoom account ID. Hãy thêm ID của ứng dụng trong Cài đặt > Kết nối.',
  "This connection has no client ID or client secret. Add the app's in Settings > Connections.":
    'Kết nối này chưa có client ID hoặc client secret. Hãy thêm của ứng dụng trong Cài đặt > Kết nối.',
  'This connection has no mail server. Add it in Settings > Connections.':
    'Kết nối này chưa có máy chủ thư. Hãy thêm trong Cài đặt > Kết nối.',
  'This connection has no user name or password. Add them in Settings > Connections.':
    'Kết nối này chưa có tên người dùng hoặc mật khẩu. Hãy thêm trong Cài đặt > Kết nối.',
  'Zoom has no user {0} on this account.': 'Zoom không có người dùng {0} trong tài khoản này.',
  'Zoom answered the sign-in without a token.': 'Zoom trả lời lần đăng nhập mà không kèm token.',
  "Zoom named a transcript address outside Zoom ({0}), so the connection's token was not sent there.":
    'Zoom đưa ra một địa chỉ bản chép lời nằm ngoài Zoom ({0}), nên token của kết nối không được gửi tới đó.',
  'Zoom named a transcript address that is not a URL.': 'Zoom đưa ra một địa chỉ bản chép lời không phải là URL.',
  'Zoom no longer has a transcript or a summary of the meeting {0}.':
    'Zoom không còn bản chép lời hay bản tóm tắt nào của cuộc họp {0}.',
  'Zoom refused the Server-to-Server sign-in ({0}). Check the account ID, the client ID and the client secret, and that the app is activated.':
    'Zoom đã từ chối đăng nhập Server-to-Server ({0}). Hãy kiểm tra account ID, client ID và client secret, và ứng dụng đã được kích hoạt chưa.',
  "Zoom stopped sending the meeting's transcript: {0}": 'Zoom đã ngừng gửi bản chép lời của cuộc họp: {0}',

  // A connection's OAuth2 sign-in, as its callback reports it (features/settings/utils/connectionSignIn.ts).
  'Signed in. The connection is ready to use.': 'Đã đăng nhập. Kết nối đã sẵn sàng để dùng.',
  'The sign-in was declined, so the connection is not signed in.': 'Lần đăng nhập đã bị từ chối, nên kết nối chưa được đăng nhập.',
  'Signing in failed. Try again.': 'Đăng nhập thất bại. Hãy thử lại.',
  "This service's sign-in is not set up. Give the connection its OAuth client, or ask an administrator to set one up.":
    'Chưa thiết lập đăng nhập cho dịch vụ này. Hãy cung cấp OAuth client cho kết nối, hoặc nhờ quản trị viên thiết lập.',

  // -- chat, the app shell, tour and feedback (the 2026-10 kit adoption) --
  // What a screen reader is told in the thread, and the names of the thread's own controls.
  'The assistant is waiting for your answer.': 'Trợ lý đang chờ câu trả lời của bạn.',
  'The answer is ready.': 'Câu trả lời đã sẵn sàng.',
  'Back to the question': 'Quay lại câu hỏi',
  'Loading conversation': 'Đang tải cuộc trò chuyện',
  'Loading conversations': 'Đang tải các cuộc trò chuyện',
  'You said:': 'Bạn đã nói:',
  'The assistant said:': 'Trợ lý đã nói:',
  'Leave feedback': 'Gửi góp ý',
  'Open image': 'Mở ảnh',
  'Open image: {name}': 'Mở ảnh: {name}',
  'Copy code': 'Sao chép mã',
  // A sub-agent's or the agent's request to switch write tools on, in one sentence with the tool's
  // name as a slot, and why enabling could not go ahead.
  "{tool} needs {connector} write tools, which you haven't enabled yet. Enable them now, or keep this action blocked?":
    '{tool} cần các công cụ ghi của {connector}, mà bạn chưa bật. Bật chúng ngay bây giờ, hay tiếp tục chặn hành động này?',
  'The connection to enable was not found.': 'Không tìm thấy kết nối cần bật.',
  'There is no setting to enable for this tool.': 'Không có cài đặt nào để bật cho công cụ này.',
  // The shared conversation's footer: one sentence, the link a slot in it.
  'It cannot be continued from here - {link}.': 'Không thể tiếp tục cuộc trò chuyện từ đây - {link}.',
  'Shared conversation': 'Cuộc trò chuyện được chia sẻ',
  // -- end of the chat adoption block --

  // What the server says about a source, a connection or a value (serverMessages.generated.ts).
  '{0} sources are available on Enterprise Server only': 'Nguồn dữ liệu {0} chỉ có trên Enterprise Server',
  'A GitHub source needs its repository as owner/repository.': 'Nguồn GitHub cần kho mã ở dạng owner/repository.',
  'Choose a type of source.': 'Hãy chọn một loại nguồn dữ liệu.',
  'Each connection brings its own OAuth client in this edition.': 'Trong phiên bản này, mỗi kết nối dùng OAuth client của riêng nó.',
  'Fill in what identifies the source.': 'Hãy điền thông tin xác định nguồn dữ liệu.',
  "GitHub did not say which branch is the repository's default.": 'GitHub không cho biết nhánh mặc định của kho mã.',
  'GitHub no longer has {0} in {1}.': 'GitHub không còn {0} trong {1}.',
  // Google's sentences (ai.proxyma.connectors.google).
  'Gmail has no label called {0}.': 'Gmail không có nhãn nào tên {0}.',
  'Gmail no longer has the conversation {0}.': 'Gmail không còn cuộc hội thoại {0}.',
  'Google Calendar no longer has the event {0}.': 'Google Calendar không còn sự kiện {0}.',
  'Google Drive did not say where its changes start.': 'Google Drive không cho biết các thay đổi bắt đầu từ đâu.',
  'Google Drive does not export {0} as text.': 'Google Drive không xuất {0} dưới dạng văn bản.',
  'Google Drive exports files of up to 10 MB, and this one is larger, so it was not indexed.':
    'Google Drive chỉ xuất tệp tối đa 10 MB, tệp này lớn hơn nên đã không được lập chỉ mục.',
  'Google Drive has moved {0} to the trash.': 'Google Drive đã chuyển {0} vào thùng rác.',
  'Google Drive no longer has {0}.': 'Google Drive không còn {0}.',
  'That is not a Google Drive folder ID or link.': 'Đó không phải mã hoặc đường liên kết của một thư mục Google Drive.',
  'That is not an item of Google Drive.': 'Đó không phải một mục của Google Drive.',
  'That is not an item of a Gmail mailbox.': 'Đó không phải một mục của hộp thư Gmail.',
  'That is not an item of a Google calendar.': 'Đó không phải một mục của lịch Google.',
  // Zendesk's and Freshdesk's sentences (ai.proxyma.connectors.zendesk, .freshdesk).
  'Freshdesk article {0} is a draft now.': 'Bài viết Freshdesk {0} hiện là bản nháp.',
  'Freshdesk article {0} is no longer in the chosen categories.': 'Bài viết Freshdesk {0} không còn thuộc các danh mục đã chọn.',
  'Freshdesk has deleted ticket {0}.': 'Freshdesk đã xóa phiếu hỗ trợ {0}.',
  'Freshdesk has marked ticket {0} as spam.': 'Freshdesk đã đánh dấu phiếu hỗ trợ {0} là thư rác.',
  'Freshdesk no longer has {0}.': 'Freshdesk không còn {0}.',
  'That is not an item of a Freshdesk source.': 'Đó không phải một mục của nguồn Freshdesk.',
  'That is not an item of a Zendesk source.': 'Đó không phải một mục của nguồn Zendesk.',
  'This connection has no API key. Add it in Settings > Connections.': 'Kết nối này chưa có API key. Hãy thêm trong Cài đặt > Kết nối.',
  'This connection has no Freshdesk domain. Add it in Settings > Connections.':
    'Kết nối này chưa có tên miền Freshdesk. Hãy thêm trong Cài đặt > Kết nối.',
  'This connection has no Zendesk subdomain. Add it in Settings > Connections.':
    'Kết nối này chưa có tên miền phụ Zendesk. Hãy thêm trong Cài đặt > Kết nối.',
  'This connection has no agent email address or API token. Add them in Settings > Connections.':
    'Kết nối này chưa có địa chỉ email của nhân viên hỗ trợ hoặc API token. Hãy thêm trong Cài đặt > Kết nối.',
  'Zendesk article {0} is a draft now.': 'Bài viết Zendesk {0} hiện là bản nháp.',
  'Zendesk article {0} is no longer in the chosen sections.': 'Bài viết Zendesk {0} không còn thuộc các mục đã chọn.',
  'Zendesk did not say which language its Help Center is in.': 'Zendesk không cho biết Help Center của nó dùng ngôn ngữ nào.',
  'Zendesk has deleted ticket {0}.': 'Zendesk đã xóa phiếu hỗ trợ {0}.',
  'Zendesk no longer has {0}.': 'Zendesk không còn {0}.',
  "The conversation {0} is no longer under this source's labels and dates.":
    'Cuộc hội thoại {0} không còn thuộc các nhãn và khoảng ngày của nguồn dữ liệu này.',
  "The event {0} is no longer inside this source's dates.": 'Sự kiện {0} không còn nằm trong khoảng ngày của nguồn dữ liệu này.',
  'The event {0} was cancelled.': 'Sự kiện {0} đã bị hủy.',
  'This file is larger than 100 MB, so it was not indexed.': 'Tệp này lớn hơn 100 MB nên đã không được lập chỉ mục.',
  '{0} is no longer in this part of Google Drive.': '{0} không còn nằm trong phần Google Drive này.',
  'A Bitbucket Cloud source needs its repository as workspace/repository.':
    'Nguồn Bitbucket Cloud cần kho mã ở dạng workspace/repository.',
  "Bitbucket did not say which branch is the repository's main one.": 'Bitbucket không cho biết nhánh chính của kho mã.',
  'Bitbucket did not say which commit the branch {0} is at.': 'Bitbucket không cho biết nhánh {0} đang ở commit nào.',
  'Bitbucket no longer has {0} in {1}.': 'Bitbucket không còn {0} trong {1}.',
  'Issue {0} no longer exists': 'Issue {0} không còn tồn tại',
  'Page {0} no longer exists': 'Trang {0} không còn tồn tại',
  'This connection has no email address or API token. Add them in Settings > Connections.':
    'Kết nối này chưa có địa chỉ email hoặc API token. Hãy thêm chúng trong Cài đặt > Kết nối.',
  'The Confluence connection has no server URL. Set it in Settings > Connections.':
    'Kết nối Confluence chưa có URL máy chủ. Hãy đặt nó trong Cài đặt > Kết nối.',
  'No OAuth client is set up for {0} any more.': 'Không còn OAuth client nào được thiết lập cho {0}.',
  'No OAuth client is set up for {0} yet.': 'Chưa có OAuth client nào được thiết lập cho {0}.',
  "Only a Super admin can set up a service's sign-in.": 'Chỉ Super admin mới có thể thiết lập đăng nhập cho một dịch vụ.',
  'Slack answered with a server error. The next sync will try again.': 'Slack đã phản hồi bằng lỗi máy chủ. Lần đồng bộ tiếp theo sẽ thử lại.',
  'Slack did not accept the token. Replace it, or sign in again, in Settings > Connections.':
    'Slack không chấp nhận token này. Hãy thay token, hoặc đăng nhập lại, trong Cài đặt > Kết nối.',
  'Slack has no such conversation, or the app cannot see it.': 'Slack không có cuộc trò chuyện đó, hoặc ứng dụng không xem được.',
  'Slack no longer has messages of that day in the channel.': 'Slack không còn tin nhắn của ngày đó trong kênh.',
  'Slack no longer has that conversation.': 'Slack không còn cuộc trò chuyện đó.',
  'Slack refused the request ({0}).': 'Slack đã từ chối yêu cầu ({0}).',
  'Airtable has no base {0}, or the token cannot read it. Add the base to the token, with the schema.bases:read scope.':
    'Airtable không có base {0}, hoặc token không đọc được base đó. Hãy thêm base vào token, kèm scope schema.bases:read.',
  'Airtable no longer has that record.': 'Airtable không còn bản ghi đó.',
  "An Airtable source needs its base's ID, or a link to it.":
    'Nguồn dữ liệu Airtable cần ID của base, hoặc liên kết tới base.',
  'Choose a table to read a view of.': 'Hãy chọn một bảng để đọc chế độ xem của bảng đó.',
  'Notion no longer has that page, or no longer shares it with the integration.':
    'Notion không còn trang đó, hoặc không còn chia sẻ trang đó với integration.',
  'That Notion page is in the trash.': 'Trang Notion đó đang nằm trong thùng rác.',
  'That is not an item of a Notion source.': 'Đó không phải một mục của nguồn Notion.',
  'That is not an item of an Airtable source.': 'Đó không phải một mục của nguồn Airtable.',
  'That page is not under a page or database this Notion source reads.':
    'Trang đó không nằm dưới trang hay cơ sở dữ liệu nào mà nguồn Notion này đọc.',
  'That record is not in a table this Airtable source reads.':
    'Bản ghi đó không nằm trong bảng nào mà nguồn Airtable này đọc.',
  'The Airtable base has no table {0}.': 'Base Airtable không có bảng {0}.',
  'The table {0} has no view {1}.': 'Bảng {0} không có chế độ xem {1}.',
  'Box did not give this Box Note in a form Proxyma reads, so it was not indexed.':
    'Box không cung cấp Box Note này ở dạng Proxyma đọc được, nên tệp không được lập chỉ mục.',
  'Box did not say where its events start.': 'Box không cho biết các sự kiện bắt đầu từ đâu.',
  'Box has moved {0} to the trash.': 'Box đã chuyển {0} vào thùng rác.',
  'Box is still preparing this file. The next sync will try again.':
    'Box vẫn đang chuẩn bị tệp này. Lần đồng bộ tiếp theo sẽ thử lại.',
  'Box no longer has {0}.': 'Box không còn {0}.',
  'Box stopped sending this file part way. The next sync will try again.':
    'Box ngừng gửi tệp này giữa chừng. Lần đồng bộ tiếp theo sẽ thử lại.',
  'Dropbox does not export {0} as text.': 'Dropbox không xuất {0} dưới dạng văn bản.',
  "Dropbox has no folder {0}, or the connection's account cannot see it.":
    'Dropbox không có thư mục {0}, hoặc tài khoản của kết nối không nhìn thấy thư mục đó.',
  'Dropbox no longer has {0}.': 'Dropbox không còn {0}.',
  'That is a file in Dropbox, not a folder.': 'Đó là một tệp trong Dropbox, không phải thư mục.',
  'That is not a Box folder ID or link.': 'Đó không phải ID hay liên kết của một thư mục Box.',
  'That is not an item of Box.': 'Đó không phải một mục của Box.',
  'That is not an item of Dropbox.': 'Đó không phải một mục của Dropbox.',
  "The Dropbox app does not have the {0} permission. Turn it on on the app's Permissions tab in the Dropbox App Console, then sign in again.":
    'Ứng dụng Dropbox chưa có quyền {0}. Hãy bật quyền này trên thẻ Permissions của ứng dụng trong Dropbox App Console, rồi đăng nhập lại.',
  'The request could not be written as JSON': 'Không thể ghi yêu cầu dưới dạng JSON',
  'This Box Note is larger than 20 MB, so it was not indexed.': 'Box Note này lớn hơn 20 MB, nên không được lập chỉ mục.',
  '{0} is no longer in this Box folder.': '{0} không còn nằm trong thư mục Box này.',
  '{0} is no longer in this Dropbox folder.': '{0} không còn nằm trong thư mục Dropbox này.',
  'That conversation is not in a channel this Slack source reads.': 'Cuộc trò chuyện đó không nằm trong kênh mà nguồn Slack này đọc.',
  'That is not an item of a Slack source.': 'Đó không phải một mục của nguồn Slack.',
  'The Slack app is missing the {0} permission. Add it under OAuth & Permissions, reinstall the app, and update the token in Settings > Connections.':
    'Ứng dụng Slack thiếu quyền {0}. Hãy thêm quyền trong OAuth & Permissions, cài lại ứng dụng và cập nhật token trong Cài đặt > Kết nối.',
  'The Slack app is not in that channel any more. Invite it back with /invite, or remove the channel from the source.':
    'Ứng dụng Slack không còn trong kênh đó. Hãy mời lại bằng /invite, hoặc bỏ kênh khỏi nguồn dữ liệu.',
  'Proxyma cannot index that type of source here.': 'Proxyma không thể lập chỉ mục loại nguồn dữ liệu đó tại đây.',
  'Sign in to {0} again in Settings > Connections.': 'Hãy đăng nhập lại {0} trong Cài đặt > Kết nối.',
  "That address climbs out of the service's API, so it was not asked for.": 'Địa chỉ đó vượt ra ngoài API của dịch vụ, nên đã không được gọi.',
  'That address is not a valid URL.': 'Địa chỉ đó không phải URL hợp lệ.',
  'That service does not sign in with OAuth.': 'Dịch vụ đó không đăng nhập bằng OAuth.',
  'That would make it a different source. Add it as a new source instead.': 'Như vậy sẽ thành một nguồn dữ liệu khác. Hãy thêm nó như một nguồn mới.',
  'The answer was larger than {0} MB, so it was not read.': 'Phản hồi lớn hơn {0} MB, nên đã không được đọc.',
  "The connection's server address is not a valid URL.": 'Địa chỉ máy chủ của kết nối không phải URL hợp lệ.',
  "The connection's server address must start with http:// or https://.": 'Địa chỉ máy chủ của kết nối phải bắt đầu bằng http:// hoặc https://.',
  'The service account key could not be read. Paste the whole JSON key again.': 'Không đọc được khóa service account. Hãy dán lại toàn bộ khóa JSON.',
  'The service answered the sign-in without a token.': 'Dịch vụ đã phản hồi lần đăng nhập mà không có token.',
  'The service answered with something that is not JSON.': 'Dịch vụ đã phản hồi bằng nội dung không phải JSON.',
  'This connection does not sign in.': 'Kết nối này không đăng nhập.',
  'This connection has no client id and secret. Add them in Settings > Connections.': 'Kết nối này chưa có client id và secret. Hãy thêm trong Cài đặt > Kết nối.',
  'This connection has no server address. Add one in Settings > Connections.': 'Kết nối này chưa có địa chỉ máy chủ. Hãy thêm trong Cài đặt > Kết nối.',
  'This connection has no service account key. Add it in Settings > Connections.': 'Kết nối này chưa có khóa service account. Hãy thêm trong Cài đặt > Kết nối.',
  'This connection has no token. Add one in Settings > Connections.': 'Kết nối này chưa có token. Hãy thêm trong Cài đặt > Kết nối.',
  "This source's connection no longer exists.": 'Kết nối của nguồn dữ liệu này không còn tồn tại.',
  "This source's settings are too long to keep.": 'Thiết lập của nguồn dữ liệu này quá dài để lưu.',
  'You have several sign-ins in progress already. Finish one, or try again in a few minutes.':
    'Bạn đang có nhiều lần đăng nhập dang dở. Hãy hoàn tất một lần, hoặc thử lại sau vài phút.',
  '{0} answered with a server error ({1}). The next sync will try again.': '{0} đã phản hồi bằng lỗi máy chủ ({1}). Lần đồng bộ tiếp theo sẽ thử lại.',
  '{0} answered without a token.': '{0} đã phản hồi mà không có token.',
  '{0} could not be reached: {1}': 'Không kết nối được tới {0}: {1}',
  '{0} does not sign in that way.': '{0} không đăng nhập theo cách đó.',
  '{0} does not sign in this way.': '{0} không đăng nhập theo cách này.',
  '{0} has nothing at that address ({1}).': '{0} không có gì tại địa chỉ đó ({1}).',
  '{0} is limiting how often it is asked. The next sync will try again.': '{0} đang giới hạn tần suất truy vấn. Lần đồng bộ tiếp theo sẽ thử lại.',
  '{0} is longer than {1} characters.': '{0} dài hơn {1} ký tự.',
  '{0} is not a known type of connection.': '{0} không phải loại kết nối đã biết.',
  '{0} is not a valid URL.': '{0} không phải URL hợp lệ.',
  '{0} must be a whole number.': '{0} phải là số nguyên.',
  '{0} must be between {1} and {2}.': '{0} phải nằm trong khoảng {1} đến {2}.',
  '{0} must be one of the choices offered.': '{0} phải là một trong các lựa chọn được đưa ra.',
  '{0} must be true or false.': '{0} phải là true hoặc false.',
  '{0} must start with http:// or https://.': '{0} phải bắt đầu bằng http:// hoặc https://.',
  '{0} no longer signs in this way.': '{0} không còn đăng nhập theo cách này.',
  "{0} pointed to another server ({1}), which the connection's credential is not sent to.":
    '{0} đã chuyển hướng tới một máy chủ khác ({1}), nơi thông tin xác thực của kết nối không được gửi tới.',
  "{0} refused access with the connection's credential. Check that its account may read this.":
    '{0} đã từ chối truy cập với thông tin xác thực của kết nối. Hãy kiểm tra tài khoản đó có quyền đọc nội dung này.',
  "{0} refused the connection's credential. Replace it, or sign in again, in Settings > Connections.":
    '{0} đã từ chối thông tin xác thực của kết nối. Hãy thay thế, hoặc đăng nhập lại, trong Cài đặt > Kết nối.',
  '{0} refused the request ({1}).': '{0} đã từ chối yêu cầu ({1}).',
  '{0} refused the sign-in of {1}. Sign in again in Settings > Connections.': '{0} đã từ chối lần đăng nhập của {1}. Hãy đăng nhập lại trong Cài đặt > Kết nối.',
  '{0} sources have no file-type filter': 'Nguồn dữ liệu {0} không có bộ lọc loại tệp',
  // The connector framework follow-ups (2026-10-14): Box client credentials, the token exchange's
  // refusals, and the sentences a connector returns rather than throws.
  'Client credentials (server app)': 'Thông tin xác thực ứng dụng (ứng dụng máy chủ)',
  'Enterprise or user ID': 'ID doanh nghiệp hoặc người dùng',
  'From a Platform App with Server Authentication (Client Credentials Grant), authorized by your Box admin.':
    'Từ một Platform App dùng Server Authentication (Client Credentials Grant), đã được quản trị viên Box của bạn cấp quyền.',
  'One user': 'Một người dùng',
  'Read as': 'Đọc với tư cách',
  "The app's service account": 'Tài khoản dịch vụ của ứng dụng',
  "Your enterprise ID for the service account, else the user's ID. Both are in the Box Admin Console.":
    'ID doanh nghiệp của bạn cho tài khoản dịch vụ, nếu không thì ID của người dùng. Cả hai đều có trong Box Admin Console.',
  'The sign-in service refused the request ({0}).': 'Dịch vụ đăng nhập đã từ chối yêu cầu ({0}).',
  'The sign-in service refused the request.': 'Dịch vụ đăng nhập đã từ chối yêu cầu.',
  'This connection is missing a setting its sign-in needs ({0}). Add it in Settings > Connections.':
    'Kết nối này thiếu một thiết lập cần cho việc đăng nhập ({0}). Hãy thêm trong Cài đặt > Kết nối.',
  'A pull request number is a positive whole number.': 'Số của pull request là một số nguyên dương.',
  'Bitbucket Cloud is not available on this installation.': 'Bitbucket Cloud không khả dụng trên bản cài đặt này.',
  'Name a file, such as docs/setup.md.': 'Hãy nêu tên một tệp, chẳng hạn docs/setup.md.',
  'Name the repository as workspace/repository, e.g. acme/website.':
    'Hãy nêu kho mã theo dạng workspace/repository, ví dụ acme/website.',
  'That Bitbucket Cloud connection is not yours.': 'Kết nối Bitbucket Cloud đó không phải của bạn.',
  'That is not a branch name.': 'Đó không phải là tên nhánh.',
  'A Server-to-Server app reads the people you name. Add their email addresses under People, or * for everyone on the account.':
    'Ứng dụng Server-to-Server đọc những người bạn nêu tên. Hãy thêm địa chỉ email của họ trong mục People, hoặc * cho mọi người trong tài khoản.',
  'A sign-in needs the org\'s own My Domain URL, such as https://acme.my.salesforce.com, not the login server.':
    'Đăng nhập cần URL My Domain riêng của tổ chức, chẳng hạn https://acme.my.salesforce.com, không phải máy chủ đăng nhập.',
  'Airtable did not accept the token. Replace it, or sign in again, in Settings > Connections.':
    'Airtable không chấp nhận token. Hãy thay thế, hoặc đăng nhập lại, trong Cài đặt > Kết nối.',
  'Asana did not accept the token.': 'Asana không chấp nhận token.',
  'Asana did not accept the token. Replace it in Settings > Connections.':
    'Asana không chấp nhận token. Hãy thay thế trong Cài đặt > Kết nối.',
  'Bitbucket did not accept the access token - it may be wrong, expired or revoked. Check it in Settings > Connections > Bitbucket.':
    'Bitbucket không chấp nhận access token - token có thể sai, đã hết hạn hoặc đã bị thu hồi. Hãy kiểm tra trong Cài đặt > Kết nối > Bitbucket.',
  'Bitbucket did not accept the credential.': 'Bitbucket không chấp nhận thông tin xác thực.',
  'Bitbucket did not accept the credential. Replace it in Settings > Connections.':
    'Bitbucket không chấp nhận thông tin xác thực. Hãy thay thế trong Cài đặt > Kết nối.',
  'Bitbucket has no repository {0}, or the credential cannot see it.':
    'Bitbucket không có kho mã {0}, hoặc thông tin xác thực không xem được kho mã đó.',
  'Box did not accept the connection\'s sign-in. Sign in again, or replace the developer token, in Settings > Connections.':
    'Box không chấp nhận lần đăng nhập của kết nối. Hãy đăng nhập lại, hoặc thay developer token, trong Cài đặt > Kết nối.',
  'Choose at least one calendar to read.': 'Hãy chọn ít nhất một lịch để đọc.',
  'Choose at least one folder to read.': 'Hãy chọn ít nhất một thư mục để đọc.',
  'Choose at least one label to read.': 'Hãy chọn ít nhất một nhãn để đọc.',
  'Choose tickets, Help Center articles or both.': 'Hãy chọn ticket, bài viết Help Center hoặc cả hai.',
  'Choose tickets, knowledge base articles or both.': 'Hãy chọn ticket, bài viết cơ sở tri thức hoặc cả hai.',
  'Choose what to read: My Drive, files shared with you, a shared drive or a folder.':
    'Hãy chọn nội dung cần đọc: My Drive, tệp được chia sẻ với bạn, một bộ nhớ dùng chung hoặc một thư mục.',
  'ClickUp did not accept the token.': 'ClickUp không chấp nhận token.',
  'ClickUp did not accept the token. Replace it in Settings > Connections.':
    'ClickUp không chấp nhận token. Hãy thay thế trong Cài đặt > Kết nối.',
  'Confluence did not accept the email address and API token.':
    'Confluence không chấp nhận địa chỉ email và API token.',
  'Confluence refused this account (403).': 'Confluence đã từ chối tài khoản này (403).',
  'Folder not found on \\\\{0}\\{1}': 'Không tìm thấy thư mục trên \\\\{0}\\{1}',
  'Freshdesk did not accept the API key.': 'Freshdesk không chấp nhận API key.',
  'Freshdesk did not accept the API key. Replace it in Settings > Connections.':
    'Freshdesk không chấp nhận API key. Hãy thay thế trong Cài đặt > Kết nối.',
  'Freshdesk has no account at that domain. Check the domain.':
    'Freshdesk không có tài khoản nào ở domain đó. Hãy kiểm tra domain.',
  'Freshdesk refused the API key\'s agent. Tickets and articles are read as an agent.':
    'Freshdesk đã từ chối agent của API key. Ticket và bài viết được đọc với tư cách một agent.',
  'GitHub did not accept the token.': 'GitHub không chấp nhận token.',
  'GitHub did not accept the token. Replace it in Settings > Connections.':
    'GitHub không chấp nhận token. Hãy thay thế trong Cài đặt > Kết nối.',
  'GitHub has no repository {0}, or the token cannot see it.':
    'GitHub không có kho mã {0}, hoặc token không xem được kho mã đó.',
  'GitHub listed only part of the repository\'s files, so some documentation files were left out.':
    'GitHub chỉ liệt kê một phần tệp của kho mã, nên một số tệp tài liệu đã bị bỏ sót.',
  'GitLab did not accept the token.': 'GitLab không chấp nhận token.',
  'GitLab did not accept the token. Replace it in Settings > Connections.':
    'GitLab không chấp nhận token. Hãy thay thế trong Cài đặt > Kết nối.',
  'GitLab has no project or group {0}, or the token cannot see it.':
    'GitLab không có dự án hoặc nhóm {0}, hoặc token không xem được.',
  'Gmail drafts, spam and trash are never read. Choose another label.':
    'Thư nháp, thư rác và thùng rác của Gmail không bao giờ được đọc. Hãy chọn nhãn khác.',
  'Google Calendar has no calendar {0}, or the connection\'s account cannot see it.':
    'Google Calendar không có lịch {0}, hoặc tài khoản của kết nối không xem được lịch đó.',
  'Google Drive has no folder {0}, or the connection\'s account cannot see it.':
    'Google Drive không có thư mục {0}, hoặc tài khoản của kết nối không xem được thư mục đó.',
  'Google Drive has no shared drive {0}, or the connection\'s account cannot see it.':
    'Google Drive không có bộ nhớ dùng chung {0}, hoặc tài khoản của kết nối không xem được.',
  'Google did not accept the connection\'s sign-in or service account key.':
    'Google không chấp nhận lần đăng nhập hoặc khóa tài khoản dịch vụ của kết nối.',
  'Google refused access. Check that the Google Drive, Gmail and Google Calendar APIs are enabled in the Google Cloud project.':
    'Google đã từ chối truy cập. Hãy kiểm tra các API Google Drive, Gmail và Google Calendar đã được bật trong dự án Google Cloud.',
  'HubSpot did not accept the token. Replace it in Settings > Connections.':
    'HubSpot không chấp nhận token. Hãy thay thế trong Cài đặt > Kết nối.',
  'HubSpot will not let this connection read {0}. Give its key the read scope for {1}.':
    'HubSpot không cho kết nối này đọc {0}. Hãy cấp cho khóa của nó quyền đọc {1}.',
  'Jira refused this account (403).': 'Jira đã từ chối tài khoản này (403).',
  'Linear did not accept the key.': 'Linear không chấp nhận khóa.',
  'Linear did not accept the key. Replace it in Settings > Connections.':
    'Linear không chấp nhận khóa. Hãy thay thế trong Cài đặt > Kết nối.',
  'Linear has no team {0}, or the connection cannot see it.':
    'Linear không có nhóm {0}, hoặc kết nối không xem được nhóm đó.',
  'More than 10,000 HubSpot records changed at the same moment, so some are left for the next full sync.':
    'Hơn 10.000 bản ghi HubSpot thay đổi cùng một thời điểm, nên một số được để lại cho lần đồng bộ đầy đủ tiếp theo.',
  'Network path is missing': 'Thiếu đường dẫn mạng',
  'No Bitbucket access token is configured. Add one in Settings > Connections > Bitbucket, then register the repository again.':
    'Chưa cấu hình access token Bitbucket. Hãy thêm trong Cài đặt > Kết nối > Bitbucket, rồi đăng ký lại kho mã.',
  'No Bitbucket server URL is configured. Add one in Settings > Connections > Bitbucket, then register the repository again.':
    'Chưa cấu hình URL máy chủ Bitbucket. Hãy thêm trong Cài đặt > Kết nối > Bitbucket, rồi đăng ký lại kho mã.',
  'No Confluence space key was given for this source.': 'Nguồn dữ liệu này chưa có khóa không gian Confluence.',
  'No Jira project key was given.': 'Chưa có khóa dự án Jira.',
  'No SVN repository URL was given.': 'Chưa có URL kho SVN.',
  'No folder was chosen - pick the folder you want indexed.':
    'Chưa chọn thư mục - hãy chọn thư mục bạn muốn lập chỉ mục.',
  'No page is shared with the Notion integration yet. In Notion, open a page\'s ... menu, choose Connections, and add the integration.':
    'Chưa có trang nào được chia sẻ với tích hợp Notion. Trong Notion, mở menu ... của một trang, chọn Connections và thêm tích hợp.',
  'Notion did not accept the token. Replace it, or sign in again, in Settings > Connections.':
    'Notion không chấp nhận token. Hãy thay thế, hoặc đăng nhập lại, trong Cài đặt > Kết nối.',
  'Notion did not let the integration read {0}, so nothing under it was removed. Share it with the integration again, or leave it out.':
    'Notion không cho tích hợp đọc {0}, nên không có gì bên dưới bị xóa. Hãy chia sẻ lại với tích hợp, hoặc bỏ nó ra.',
  'Notion listed only part of the pages, so nothing was removed.':
    'Notion chỉ liệt kê một phần các trang, nên không có gì bị xóa.',
  'Only a Super admin\'s MCP server may run a command on this server.':
    'Chỉ máy chủ MCP của Super admin mới được chạy lệnh trên máy chủ này.',
  'Proxyma is not allowed to read {0} - check the folder\'s permissions.':
    'Proxyma không được phép đọc {0} - hãy kiểm tra quyền của thư mục.',
  'Salesforce did not accept the connection\'s sign-in. Sign in again, or check its keys, in Settings > Connections.':
    'Salesforce không chấp nhận lần đăng nhập của kết nối. Hãy đăng nhập lại, hoặc kiểm tra các khóa, trong Cài đặt > Kết nối.',
  'Salesforce has no object {0}, or the connection\'s user cannot see it.':
    'Salesforce không có đối tượng {0}, hoặc người dùng của kết nối không xem được.',
  'Set the file server first.': 'Hãy đặt máy chủ tệp trước.',
  'Slack did not let the app read {0}, so nothing of it was removed. Invite the app back, or leave the channel out.':
    'Slack không cho ứng dụng đọc {0}, nên không có gì của nó bị xóa. Hãy mời lại ứng dụng, hoặc bỏ kênh đó ra.',
  'That is a file in Google Drive, not a folder.': 'Đó là một tệp trong Google Drive, không phải thư mục.',
  'That is not a shared drive ID or link: {0}': 'Đó không phải ID hoặc liên kết của bộ nhớ dùng chung: {0}',
  'The Help Center has no articles in {0}. It has {1}.': 'Help Center không có bài viết nào bằng {0}. Nó có {1}.',
  'The Help Center has no section {0}.': 'Help Center không có mục {0}.',
  'The Notion integration cannot read {0}. Share each page or database with it under ... > Connections, or check the links.':
    'Tích hợp Notion không đọc được {0}. Hãy chia sẻ từng trang hoặc cơ sở dữ liệu với nó trong ... > Connections, hoặc kiểm tra các liên kết.',
  'The Slack app cannot read {0}. Invite it to each channel with /invite, or check the names.':
    'Ứng dụng Slack không đọc được {0}. Hãy mời nó vào từng kênh bằng /invite, hoặc kiểm tra các tên.',
  'The Slack app is not in any public channel yet. Invite it to the channels to index with /invite.':
    'Ứng dụng Slack chưa ở trong kênh công khai nào. Hãy mời nó vào các kênh cần lập chỉ mục bằng /invite.',
  'The knowledge base has no category {0}.': 'Cơ sở tri thức không có danh mục {0}.',
  'The mail server signed in but lists no folders.': 'Máy chủ thư đã đăng nhập nhưng không liệt kê thư mục nào.',
  'The repository must be given as <projectKey>/<repoSlug>.': 'Kho mã phải được nêu theo dạng <projectKey>/<repoSlug>.',
  'The start address is not a web address.': 'Địa chỉ bắt đầu không phải là địa chỉ web.',
  'The token was accepted. Its repository is checked when you add it.':
    'Token đã được chấp nhận. Kho mã của nó được kiểm tra khi bạn thêm.',
  'There is no folder at {0} - check the path and try again.':
    'Không có thư mục nào tại {0} - hãy kiểm tra đường dẫn rồi thử lại.',
  'This Confluence source has no space key, so there is nothing to list.':
    'Nguồn Confluence này không có khóa không gian, nên không có gì để liệt kê.',
  'This Jira source has no project key.': 'Nguồn Jira này không có khóa dự án.',
  'This source does not point at an upload folder Proxyma owns, so it cannot be indexed. Re-create it and upload the files again.':
    'Nguồn dữ liệu này không trỏ tới thư mục tải lên mà Proxyma quản lý, nên không thể lập chỉ mục. Hãy tạo lại và tải các tệp lên lần nữa.',
  'This source has no Network share connection with a user name and password - add one in Settings > Connections':
    'Nguồn dữ liệu này không có kết nối thư mục mạng với tên người dùng và mật khẩu - hãy thêm trong Cài đặt > Kết nối',
  'This source has no upload folder - re-create it and upload the files again.':
    'Nguồn dữ liệu này không có thư mục tải lên - hãy tạo lại và tải các tệp lên lần nữa.',
  'This source\'s upload folder is not a folder. Re-create it and upload the files again.':
    'Thư mục tải lên của nguồn dữ liệu này không phải là thư mục. Hãy tạo lại và tải các tệp lên lần nữa.',
  'This source\'s upload folder is not a usable path on this server.':
    'Thư mục tải lên của nguồn dữ liệu này không phải là đường dẫn dùng được trên máy chủ này.',
  'Trello did not accept the API key and token.': 'Trello không chấp nhận API key và token.',
  'Trello did not accept the API key and token. Replace them in Settings > Connections.':
    'Trello không chấp nhận API key và token. Hãy thay thế trong Cài đặt > Kết nối.',
  'Zendesk did not accept the connection\'s credential. Replace it in Settings > Connections.':
    'Zendesk không chấp nhận thông tin xác thực của kết nối. Hãy thay thế trong Cài đặt > Kết nối.',
  'Zendesk has no account at that subdomain. Check the subdomain.':
    'Zendesk không có tài khoản nào ở subdomain đó. Hãy kiểm tra subdomain.',
  'Zendesk refused the connection\'s agent. Tickets and the Help Center\'s articles are read as an agent or an administrator.':
    'Zendesk đã từ chối agent của kết nối. Ticket và bài viết Help Center được đọc với tư cách agent hoặc quản trị viên.',
  'Zoom did not let the connection read meeting summaries, so only transcripts were listed. Add the meeting summary scopes to the Zoom app.':
    'Zoom không cho kết nối đọc tóm tắt cuộc họp, nên chỉ bản ghi lời thoại được liệt kê. Hãy thêm các scope tóm tắt cuộc họp vào ứng dụng Zoom.',
  'monday.com did not accept the token.': 'monday.com không chấp nhận token.',
  'monday.com did not accept the token. Replace it in Settings > Connections.':
    'monday.com không chấp nhận token. Hãy thay thế trong Cài đặt > Kết nối.',
  '{0} is not a link to a Notion page or database, or its ID.':
    '{0} không phải liên kết tới một trang hoặc cơ sở dữ liệu Notion, cũng không phải ID của chúng.',

  // Settings > About: Community's source offer and license (SourceCodeSection.desktop.tsx).
  'Source code': 'Mã nguồn',
  'Proxyma Community is free software: you can use, study, change and share it under the GNU Affero General Public License, version 3. The source code of this version is public.':
    'Proxyma Community là phần mềm tự do: bạn có thể dùng, tìm hiểu, sửa đổi và chia sẻ nó theo Giấy phép Công cộng GNU Affero, phiên bản 3. Mã nguồn của bản này được công khai.',
  'Proxyma Community is free software: you can use, study, change and share it under the GNU Affero General Public License, version 3. This is a development build: its own source code is not published, and the source code of each release is.':
    'Proxyma Community là phần mềm tự do: bạn có thể dùng, tìm hiểu, sửa đổi và chia sẻ nó theo Giấy phép Công cộng GNU Affero, phiên bản 3. Đây là bản phát triển: mã nguồn riêng của bản này không được công bố, còn mã nguồn của mỗi bản phát hành thì được công khai.',
  'Proxyma Community is free software: you can use, study, change and share it under the GNU Affero General Public License, version 3. The source code of each release is public.':
    'Proxyma Community là phần mềm tự do: bạn có thể dùng, tìm hiểu, sửa đổi và chia sẻ nó theo Giấy phép Công cộng GNU Affero, phiên bản 3. Mã nguồn của mỗi bản phát hành được công khai.',
  'Proxyma Community {version} on GitHub': 'Proxyma Community {version} trên GitHub',
  'Proxyma Community on GitHub': 'Proxyma Community trên GitHub',
  'The source code of this version, to read, build or change.': 'Mã nguồn của bản này, để đọc, tự build hoặc sửa đổi.',
  'The source code of each release, to read, build or change.': 'Mã nguồn của mỗi bản phát hành, để đọc, tự build hoặc sửa đổi.',
  'GNU Affero General Public License v3': 'Giấy phép Công cộng GNU Affero v3',
  'The license this edition is under, and what it lets you do.': 'Giấy phép áp dụng cho phiên bản này và những gì nó cho phép bạn làm.',
};
