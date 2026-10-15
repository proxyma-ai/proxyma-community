package ai.proxyma.core.tools;

import ai.proxyma.core.common.conversation.ConversationPaths;
import ai.proxyma.core.search.domain.SearchContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

// Shared path-safety helper for tools that read files previously offloaded into the
// conversations directory (ReadTextFileTool, SearchTextFileTool). Public: also used by
// tenant-specific tool implementations (e.g. a timesheet
// tool), which live outside this package.
public final class OffloadFileAccess {

    private static final Logger log = LoggerFactory.getLogger(OffloadFileAccess.class);

    private OffloadFileAccess() {
    }

    // How much text `read` and `grep` return in one call, whatever was asked for. Under the size at
    // which an agent loop offloads a tool result, less room for the note that says how to continue:
    // these are the tools an offloaded file is read back with, so a page of one must never be
    // offloaded itself. SmartChatMemory.PAGING_TOOLS has the loop that made this a rule.
    public static final int MAX_PAGE_CHARS =
            ai.proxyma.core.chat.service.SmartChatMemory.MAX_INLINE_RESULT_CHARS - 1_000;

    // The single place a denial is turned into a log line and an agent-facing message. Both halves
    // were missing and both cost real debugging time.
    //
    // The log line prints the requested path AND the root it was checked against, because the two
    // are derived from DIFFERENT sources for the same user id: the write side
    // (SmartChatMemory.resolveConversationDir) reads it off the Conversation row, this side reads
    // it off the SearchContext ThreadLocal. When they disagree, a file is written successfully and
    // then refused on read - and with no logging at all there was no way to tell that apart from an
    // agent simply passing a path it was never given. Printing both makes the mismatch visible at a
    // glance. Server-side log only; nothing here goes to a client (see SensitivePaths).
    //
    // The message is directive because a bare "access denied" was actively misleading to an agent:
    // a sub-agent handed an offloaded path took it to mean the document itself was unavailable,
    // reported "not indexed in any registered connector", and abandoned the entire task rather than
    // retrying with the path it had been given.
    public static String denied(String toolName, Path target, Path allowedRoot) {
        log.warn("[{}] access denied: path={} is outside allowedRoot={}", toolName, target, allowedRoot);
        return "Error: access denied - '" + target.getFileName() + "' is outside the conversations "
                + "directory (on Enterprise Server, this user's own part of it). Use the exact, unmodified "
                + "path you were given - the "
                + "\"filePath\" of a \"resultOffloaded\" result, or the path in an "
                + "'[Attached file(s): ...]' message. A re-typed, shortened or '.../'-abbreviated "
                + "path fails this check even when the file exists. This says nothing about whether "
                + "the document is indexed or available - do not report it as missing.";
    }

    // The caller's own part of the conversations root - ConversationPaths.userRoot, which throws for
    // a user id that is not one plain name. A BLANK user id is a visitor's, users/_anonymous: it used
    // to resolve to users/ itself, which is every user's folder.
    public static Path resolveAllowedRoot(String conversationsDir, boolean multiUser) {
        return new ConversationPaths(conversationsDir, multiUser).userRoot(SearchContext.getUserId());
    }

    public static boolean isWithinAllowedRoot(Path target, Path allowedRoot) {
        return target.toAbsolutePath().normalize().startsWith(allowedRoot);
    }
}
