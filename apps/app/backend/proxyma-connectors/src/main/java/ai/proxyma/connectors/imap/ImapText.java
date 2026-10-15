package ai.proxyma.connectors.imap;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * What a message says, for the index: its addresses as a person reads them, its text - the plain
 * part where there is one, the HTML part as Markdown where there is not - and its attachments,
 * found by walking its MIME structure.
 *
 * <p>HTML goes through flexmark's converter, the one HtmlContentExtractor uses for web pages, after
 * jsoup drops what is not text (scripts, styles, the head, images - a mail's images are tracking
 * pixels and cid: links). Not HtmlContentExtractor itself: its Readability step picks an ARTICLE out
 * of a page, and a short mail is not one - it can come back empty or cut.
 */
final class ImapText {

    private static final FlexmarkHtmlConverter HTML = FlexmarkHtmlConverter.builder(
            new MutableDataSet().set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)).build();
    // The most of one body part read as text; a larger one is not a message somebody wrote.
    static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;

    private ImapText() {}

    /** One attachment: where it is in the message (indexes into nested multiparts), its name and size. */
    record Attachment(String path, String name, long size) {}

    // -- addresses -----------------------------------------------------------------------------

    /** "Ana Lee <ana@example.com>", decoded, comma-separated; empty for none. */
    static String addresses(Address[] addresses) {
        if (addresses == null) return "";
        List<String> out = new ArrayList<>();
        for (Address a : addresses) {
            String s = a instanceof InternetAddress ia ? ia.toUnicodeString() : a.toString();
            if (s != null && !s.isBlank()) out.add(s.strip());
        }
        return String.join(", ", out);
    }

    /** The sender's name, else their address. */
    static String sender(Message m) throws MessagingException {
        Address[] from = m.getFrom();
        if (from == null || from.length == 0) return null;
        if (from[0] instanceof InternetAddress ia) {
            String name = ia.getPersonal();
            return name != null && !name.isBlank() ? name.strip() : ia.getAddress();
        }
        return from[0].toString();
    }

    /** The domain of every From, To and Cc address, lower case. */
    static List<String> domains(Message m) throws MessagingException {
        List<String> out = new ArrayList<>();
        collect(m.getFrom(), out);
        collect(m.getRecipients(Message.RecipientType.TO), out);
        collect(m.getRecipients(Message.RecipientType.CC), out);
        return out;
    }

    private static void collect(Address[] addresses, List<String> out) {
        if (addresses == null) return;
        for (Address a : addresses) {
            if (!(a instanceof InternetAddress ia) || ia.getAddress() == null) continue;
            int at = ia.getAddress().lastIndexOf('@');
            if (at >= 0) out.add(ia.getAddress().substring(at + 1).toLowerCase(Locale.ROOT));
        }
    }

    // -- the message ---------------------------------------------------------------------------

    static String render(Message m, String folder, String mailbox, List<String> attachmentNames) throws MessagingException, IOException {
        StringBuilder md = new StringBuilder();
        String subject = m.getSubject();
        md.append("# ").append(subject == null || subject.isBlank() ? "(no subject)" : subject.strip()).append("\n\n");
        line(md, "From", addresses(m.getFrom()));
        line(md, "To", addresses(m.getRecipients(Message.RecipientType.TO)));
        line(md, "Cc", addresses(m.getRecipients(Message.RecipientType.CC)));
        Instant sent = m.getSentDate() != null ? m.getSentDate().toInstant()
                : m.getReceivedDate() != null ? m.getReceivedDate().toInstant() : null;
        if (sent != null) line(md, "Date", sent.toString());
        line(md, "Folder", folder);
        line(md, "Mailbox", mailbox);
        if (!attachmentNames.isEmpty()) line(md, "Attachments", String.join(", ", attachmentNames));
        String body = body(m);
        md.append('\n').append(body.isBlank() ? "(no text)" : body.strip()).append('\n');
        return md.toString();
    }

    private static void line(StringBuilder md, String label, String value) {
        if (value != null && !value.isBlank()) md.append("- ").append(label).append(": ").append(value).append('\n');
    }

    /** The message's text: its first plain part that is not an attachment, else its HTML part as Markdown. */
    static String body(Part message) throws MessagingException, IOException {
        Part plain = find(message, "text/plain");
        if (plain != null) return text(plain);
        Part html = find(message, "text/html");
        return html == null ? "" : htmlToMarkdown(text(html));
    }

    private static Part find(Part part, String type) throws MessagingException, IOException {
        if (isAttachment(part)) return null;
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                Part found = find(mp.getBodyPart(i), type);
                if (found != null) return found;
            }
            return null;
        }
        return part.isMimeType(type) ? part : null;
    }

    // A text part's words. A charset Java does not know is read as UTF-8 rather than lost.
    private static String text(Part part) throws MessagingException, IOException {
        try {
            Object content = part.getContent();
            if (content instanceof String s) return s.length() > MAX_TEXT_BYTES ? s.substring(0, MAX_TEXT_BYTES) : s;
        } catch (UnsupportedEncodingException e) {
            // read below
        }
        try (InputStream in = part.getInputStream()) {
            return new String(in.readNBytes(MAX_TEXT_BYTES), StandardCharsets.UTF_8);
        }
    }

    static String htmlToMarkdown(String html) {
        if (html == null || html.isBlank()) return "";
        Document doc = Jsoup.parse(html);
        doc.select("script, style, head, title, noscript, template, svg, iframe, img").remove();
        String md = HTML.convert(doc.body() == null ? doc.outerHtml() : doc.body().outerHtml());
        return md.replace('\u00a0', ' ').replaceAll("(?m)[ \\t]+$", "").replaceAll("\n{3,}", "\n\n").strip();
    }

    // -- attachments ---------------------------------------------------------------------------

    /** Whether a part is an attachment: one with a file name, or one its disposition says is. */
    static boolean isAttachment(Part part) throws MessagingException {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) return true;
        return name(part) != null && !part.isMimeType("multipart/*");
    }

    /** A part's file name, decoded - null for none. */
    static String name(Part part) {
        try {
            String name = part.getFileName();
            if (name == null || name.isBlank()) return null;
            try {
                name = MimeUtility.decodeText(name);
            } catch (UnsupportedEncodingException ignored) {
                // keep it as written
            }
            name = name.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1).strip();
            return name.isEmpty() ? null : name;
        } catch (MessagingException e) {
            return null;
        }
    }

    /** Every attachment of a message, depth first, with where each one is. */
    static List<Attachment> attachments(Part message) throws MessagingException, IOException {
        List<Attachment> out = new ArrayList<>();
        walk(message, "", (path, part) -> {
            String name = name(part);
            long size = -1;
            try {
                size = part.getSize();
            } catch (MessagingException ignored) {
                // unknown
            }
            out.add(new Attachment(path, name == null ? "attachment" : name, size));
        });
        return out;
    }

    private static void walk(Part part, String path, BiConsumer<String, Part> attachment) throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                walk(mp.getBodyPart(i), path.isEmpty() ? String.valueOf(i) : path + "." + i, attachment);
            }
            return;
        }
        if (!path.isEmpty() && isAttachment(part)) attachment.accept(path, part);
    }

    /** The part at {@code path} ("1", "1.0"), or null when the message has none there. */
    static Part at(Part message, String path) throws MessagingException, IOException {
        Part part = message;
        for (String step : path.split("\\.")) {
            if (!part.isMimeType("multipart/*")) return null;
            Multipart mp = (Multipart) part.getContent();
            int i;
            try {
                i = Integer.parseInt(step);
            } catch (NumberFormatException e) {
                return null;
            }
            if (i < 0 || i >= mp.getCount()) return null;
            BodyPart next = mp.getBodyPart(i);
            part = next;
        }
        return part;
    }
}
