package ai.proxyma.connectors.imap;

import ai.proxyma.core.connector.AccessCheck;
import ai.proxyma.core.connector.ConnectorException;
import ai.proxyma.core.connector.ConnectorType;
import ai.proxyma.core.connector.ItemGoneException;
import ai.proxyma.core.connector.ItemMetadata;
import ai.proxyma.core.connector.files.FileTypeSupport;
import ai.proxyma.core.connector.spi.ConnectorAuthException;
import ai.proxyma.core.connector.spi.ConnectorSession;
import ai.proxyma.core.connector.spi.ListRequest;
import ai.proxyma.core.connector.spi.Listing;
import ai.proxyma.core.connector.spi.Listings;
import ai.proxyma.core.connector.spi.SourceContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessageRemovedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One IMAP source, opened for one pass or one batch of fetches - ONE sign-in to the server, held
 * until {@link #close()}. Folders are opened READ-ONLY (EXAMINE), so nothing is marked read, moved or
 * deleted, and closed when done: a listing closes each folder once it has walked it, and a batch of
 * fetches keeps the one it last used.
 *
 * <p>Items are {@code message:<uidvalidity>:<uid>:<folder>} and
 * {@code attachment:<uidvalidity>:<uid>:<part>:<folder>} - the folder last, since a folder name may
 * hold a ":". A fetch whose folder now has another UIDVALIDITY, or no message with that UID, is a gone
 * item.
 *
 * <p>A listing asks the server for the messages received since the day ({@code SEARCH SINCE}) and
 * reads their UID, envelope and, for attachments, their MIME structure (no body) 200 at a time. The
 * domains are checked HERE, against the envelope's addresses: {@code SEARCH FROM} is a substring
 * match by RFC 3501, but servers differ - Gmail's and a full-text-indexed Dovecot's tokenize it,
 * GreenMail's compares whole addresses - and a filter that finds different messages on different
 * servers is no filter. The envelope is read for every message anyway.
 */
final class ImapSession implements ConnectorSession {

    static final int BATCH = 200;
    // An attachment larger than this is listed by name only; the converters would not take it either.
    static final long MAX_ATTACHMENT_BYTES = 50L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourceContext context;
    private final SSLSocketFactory sockets;
    private final List<String> folders;
    private final LocalDate since;
    private final Set<String> domains;
    private final boolean attachments;
    private ImapAccount.Settings account;
    private Store store;
    // The folder the last fetch used, kept open for the next one.
    private Folder current;
    // Per folder, "uidvalidity:last uid" - the cursor.
    private final Map<String, String> next = new LinkedHashMap<>();

    ImapSession(SourceContext context, SSLSocketFactory sockets) {
        this.context = context;
        this.sockets = sockets;
        this.folders = ImapConnectorPlugin.folders(context.config());
        this.since = ImapConnectorPlugin.since(context.config());
        this.domains = ImapConnectorPlugin.domains(context.config());
        this.attachments = ImapConnectorPlugin.attachments(context.config());
    }

    // -- the server ----------------------------------------------------------------------------

    private Store store() {
        if (store != null && store.isConnected()) return store;
        try {
            account = ImapAccount.of(context.connection());
            store = ImapAccount.connect(account, sockets);
            return store;
        } catch (MessagingException e) {
            throw new ConnectorException(ImapAccount.sentence(e, account), e);
        }
    }

    private Folder openFolder(String name) throws MessagingException {
        Folder f = store().getFolder(name);
        if (!f.exists()) throw new ConnectorException("The mailbox has no folder called " + name + ".");
        if ((f.getType() & Folder.HOLDS_MESSAGES) == 0) throw new ConnectorException("The folder " + name + " holds no messages.");
        f.open(Folder.READ_ONLY);
        return f;
    }

    @Override
    public void close() {
        closeQuietly(current);
        current = null;
        closeQuietly(store);
        store = null;
    }

    static void closeQuietly(Folder folder) {
        if (folder == null) return;
        try {
            if (folder.isOpen()) folder.close(false);
        } catch (MessagingException | RuntimeException ignored) {
            // closing is best effort
        }
    }

    static void closeQuietly(Store store) {
        if (store == null) return;
        try {
            store.close();
        } catch (MessagingException | RuntimeException ignored) {
            // closing is best effort
        }
    }

    // -- the probe -----------------------------------------------------------------------------

    @Override
    public AccessCheck verifyAccess() {
        if (folders.isEmpty()) return AccessCheck.failed("Choose at least one folder to read.");
        try {
            for (String name : folders) {
                Folder f = store().getFolder(name);
                if (!f.exists()) return AccessCheck.failed("The mailbox has no folder called " + name + ".");
                if ((f.getType() & Folder.HOLDS_MESSAGES) == 0) {
                    return AccessCheck.failed("The folder " + name + " holds no messages.");
                }
            }
            return AccessCheck.verified();
        } catch (MessagingException | ConnectorAuthException e) {
            return AccessCheck.failed(ImapAccount.sentence(e, account));
        } catch (ConnectorException e) {
            return AccessCheck.failed(e.getMessage());
        }
    }

    // -- listing -------------------------------------------------------------------------------

    @Override
    public Listing list(ListRequest request) {
        Map<String, String> prior = request.isFull() ? Map.of() : readCursor(request.cursor());
        List<Supplier<Iterator<ItemMetadata>>> parts = new ArrayList<>();
        for (String name : folders) parts.add(() -> folder(name, prior.get(name)));
        return Listings.withCursor(Listings.concat(parts), () -> writeCursor(next));
    }

    // One folder's matching messages - all of them, or only the UIDs after the cursor's where its
    // UIDVALIDITY is still the same.
    private Iterator<ItemMetadata> folder(String name, String prior) {
        Folder f;
        try {
            f = openFolder(name);
            UIDFolder uids = (UIDFolder) f;
            long validity = uids.getUIDValidity();
            long last = lastUid(prior, validity);
            long uidNext = uids.getUIDNext();
            SearchTerm term = term();
            Message[] messages;
            if (last < 0) {
                messages = term == null ? f.getMessages() : f.search(term);
            } else {
                // "n:*" always answers the newest message, even below n: kept only above the cursor.
                Message[] newer = uids.getMessagesByUID(last + 1, UIDFolder.LASTUID);
                List<Message> above = new ArrayList<>();
                for (Message m : newer) {
                    if (m != null && uids.getUID(m) > last) above.add(m);
                }
                messages = above.toArray(new Message[0]);
                if (term != null && messages.length > 0) messages = f.search(term, messages);
            }
            long known = Math.max(last, uidNext > 0 ? uidNext - 1 : -1);
            return new FolderItems(f, name, validity, known, messages);
        } catch (MessagingException e) {
            throw new ConnectorException(ImapAccount.sentence(e, account), e);
        }
    }

    // SINCE the day (the server's own date of arrival, by day) - or null for every message.
    private SearchTerm term() {
        if (since == null) return null;
        return new ReceivedDateTerm(ComparisonTerm.GE, Date.from(since.atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    // The domain filter: a From, To or Cc address AT one of the domains or under it.
    private boolean domainMatches(Message m) throws MessagingException {
        if (domains.isEmpty()) return true;
        for (String d : ImapText.domains(m)) {
            for (String wanted : domains) {
                if (d.equals(wanted) || d.endsWith("." + wanted)) return true;
            }
        }
        return false;
    }

    /** A folder's messages as items, read 200 at a time; closes the folder and records the cursor when done. */
    private final class FolderItems implements Iterator<ItemMetadata> {
        private final Folder folder;
        private final String name;
        private final long validity;
        private long highest;
        private final Message[] messages;
        private int at;
        private final List<ItemMetadata> ready = new ArrayList<>();
        private boolean done;

        FolderItems(Folder folder, String name, long validity, long known, Message[] messages) {
            this.folder = folder;
            this.name = name;
            this.validity = validity;
            this.highest = known;
            this.messages = messages;
        }

        @Override
        public boolean hasNext() {
            while (ready.isEmpty() && !done) {
                if (at >= messages.length) {
                    finish();
                    break;
                }
                Message[] batch = Arrays.copyOfRange(messages, at, Math.min(messages.length, at + BATCH));
                at += batch.length;
                try {
                    read(batch);
                } catch (MessagingException e) {
                    closeQuietly(folder);
                    done = true;
                    throw new ConnectorException(ImapAccount.sentence(e, account), e);
                }
            }
            return !ready.isEmpty();
        }

        @Override
        public ItemMetadata next() {
            if (!hasNext()) throw new NoSuchElementException();
            return ready.remove(0);
        }

        private void read(Message[] batch) throws MessagingException {
            FetchProfile profile = new FetchProfile();
            profile.add(UIDFolder.FetchProfileItem.UID);
            profile.add(FetchProfile.Item.ENVELOPE);
            if (attachments) profile.add(FetchProfile.Item.CONTENT_INFO);
            folder.fetch(batch, profile);
            UIDFolder uids = (UIDFolder) folder;
            for (Message m : batch) {
                try {
                    long uid = uids.getUID(m);
                    if (uid < 0) continue;
                    highest = Math.max(highest, uid);
                    if (!domainMatches(m)) continue;
                    items(m, name, validity, uid, ready);
                } catch (MessageRemovedException e) {
                    // expunged while the listing ran - the next pass does not see it either
                }
            }
        }

        private void finish() {
            done = true;
            closeQuietly(folder);
            next.put(name, validity + ":" + highest);
        }
    }

    private void items(Message m, String folder, long validity, long uid, List<ItemMetadata> out) throws MessagingException {
        Instant received = m.getReceivedDate() != null ? m.getReceivedDate().toInstant()
                : m.getSentDate() != null ? m.getSentDate().toInstant() : context.clock().instant();
        String sender = ImapText.sender(m);
        ItemMetadata item = new ItemMetadata();
        item.setId("message:" + validity + ":" + uid + ":" + folder);
        String subject = m.getSubject();
        item.setTitle(subject == null || subject.isBlank() ? "(no subject)" : subject.strip());
        item.setSourceType(ConnectorType.IMAP);
        item.setLastModified(received);
        item.setContentType("text/markdown");
        if (m.getSize() > 0) item.setSizeBytes((long) m.getSize());
        if (sender != null) item.putExtra(ItemMetadata.AUTHOR, sender);
        out.add(item);
        if (!attachments) return;
        try {
            for (ImapText.Attachment a : ImapText.attachments(m)) {
                if (!FileTypeSupport.isSupportedFileName(a.name(), null) || a.size() > MAX_ATTACHMENT_BYTES) continue;
                ItemMetadata file = new ItemMetadata();
                file.setId("attachment:" + validity + ":" + uid + ":" + a.path() + ":" + folder);
                file.setTitle(a.name());
                file.setSourceType(ConnectorType.IMAP);
                file.setLastModified(received);
                file.setContentType(FileTypeSupport.resolveMimeType(a.name()));
                if (a.size() > 0) file.setSizeBytes(a.size());
                if (sender != null) file.putExtra(ItemMetadata.AUTHOR, sender);
                out.add(file);
            }
        } catch (IOException e) {
            throw new MessagingException("The structure of a message could not be read: " + e.getMessage(), e);
        }
    }

    // -- cursor --------------------------------------------------------------------------------

    static long lastUid(String prior, long validity) {
        if (prior == null) return -1;
        String[] parts = prior.split(":");
        if (parts.length != 2) return -1;
        try {
            return Long.parseLong(parts[0]) == validity ? Long.parseLong(parts[1]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static Map<String, String> readCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return Map.of();
        try {
            Map<String, String> map = JSON.readValue(cursor, new TypeReference<LinkedHashMap<String, String>>() {});
            return map == null ? Map.of() : map;
        } catch (IOException e) {
            return Map.of();
        }
    }

    static String writeCursor(Map<String, String> folders) {
        if (folders.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(folders);
        } catch (IOException e) {
            return null;
        }
    }

    // -- fetching ------------------------------------------------------------------------------

    /** An item's id, read back: what it is, the folder's UIDVALIDITY, the UID, the part, the folder. */
    record ItemId(boolean attachment, long validity, long uid, String part, String folder) {
        static ItemId parse(String id) {
            if (id == null) return null;
            boolean attachment = id.startsWith("attachment:");
            if (!attachment && !id.startsWith("message:")) return null;
            String[] parts = id.substring(id.indexOf(':') + 1).split(":", attachment ? 4 : 3);
            if (parts.length != (attachment ? 4 : 3)) return null;
            try {
                long validity = Long.parseLong(parts[0]);
                long uid = Long.parseLong(parts[1]);
                String part = attachment ? parts[2] : null;
                String folder = parts[attachment ? 3 : 2];
                if (folder.isEmpty() || (attachment && !part.matches("\\d+(\\.\\d+)*"))) return null;
                return new ItemId(attachment, validity, uid, part, folder);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    @Override
    public InputStream fetch(ItemMetadata item) {
        ItemId id = ItemId.parse(item.getId());
        if (id == null) throw new ConnectorException("That is not an item of an IMAP mailbox.");
        try {
            Folder f = fetchFolder(id.folder());
            if (((UIDFolder) f).getUIDValidity() != id.validity()) {
                throw new ItemGoneException("The folder " + id.folder() + " was rebuilt on the server, so its old messages are gone.");
            }
            Message m = ((UIDFolder) f).getMessageByUID(id.uid());
            if (m == null || m.isExpunged()) throw new ItemGoneException("The message is no longer in " + id.folder() + ".");
            if (!id.attachment()) {
                List<String> names = new ArrayList<>();
                for (ImapText.Attachment a : ImapText.attachments(m)) names.add(a.name());
                String mailbox = account == null ? null : account.username() + " on " + account.host();
                return new ByteArrayInputStream(ImapText.render(m, id.folder(), mailbox, names).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Part part = ImapText.at(m, id.part());
            if (part == null || !ImapText.isAttachment(part)) {
                throw new ItemGoneException("The message no longer has that attachment.");
            }
            try (InputStream in = part.getInputStream()) {
                byte[] bytes = in.readNBytes((int) MAX_ATTACHMENT_BYTES + 1);
                if (bytes.length > MAX_ATTACHMENT_BYTES) throw new ConnectorException("The attachment is too large to read.");
                return new ByteArrayInputStream(bytes);
            }
        } catch (MessageRemovedException e) {
            throw new ItemGoneException("The message is no longer in " + id.folder() + ".");
        } catch (MessagingException e) {
            throw new ConnectorException(ImapAccount.sentence(e, account), e);
        } catch (IOException e) {
            throw new ConnectorException("The mail server stopped sending the message: " + e.getMessage(), e);
        }
    }

    // The folder a fetch reads - the one the last fetch used, while it is the same. A folder that is
    // gone takes its messages with it.
    private Folder fetchFolder(String name) throws MessagingException {
        if (current != null && current.isOpen() && current.getFullName().equals(name)) return current;
        closeQuietly(current);
        current = null;
        Folder f = store().getFolder(name);
        if (!f.exists()) throw new ItemGoneException("The mailbox no longer has a folder called " + name + ".");
        f.open(Folder.READ_ONLY);
        current = f;
        return f;
    }
}
