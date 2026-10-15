package ai.proxyma.desktop.system;

import ai.proxyma.core.connector.files.FileTypeSupport;
import ai.proxyma.core.system.parts.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

// Desktop-only bridge between the browser-hosted UI and the local filesystem.
//
// Desktop mode registers a REFERENCE to a folder the user already has on disk (nothing is copied,
// see ConnectorService.registerFiles) - but the UI runs in the user's default browser
// (DesktopIntegrationService.openBrowser), and a browser can never hand the page an absolute
// path: File.path is Electron-only, webkitRelativePath/FileSystemEntry.fullPath are relative to
// the drop root, and showDirectoryPicker() yields a handle with only a name. So the path has to
// come from this side, which in desktop mode runs on the very machine the user is sitting at.
//
// Deliberately lives in proxyma-desktop, NOT in any proxyma-core-* module: server mode must never
// expose an endpoint that browses or inspects the host filesystem.
//
// THAT IS NO LONGER ENOUGH ON ITS OWN, hence the condition below. Living in this module kept it out
// of the server build, on the reasoning that the desktop build only ever runs on the machine its
// user is sitting at. The home server is this same build, in a container, answering other machines
// on the LAN - so "browses the host filesystem" there means an endpoint that walks the container
// for anyone who can reach the port. It offers no file source anyway once uploads are on, so it is
// gated on exactly the mode that makes it meaningful: FileSourceMode.localFoldersEnabled().
@RestController
@RequestMapping("/api/v1/desktop")
@org.springframework.context.annotation.Conditional(
        ai.proxyma.core.connector.files.FileSourceMode.LocalFoldersEnabled.class)
public class LocalPathController {

    private static final Logger log = LoggerFactory.getLogger(LocalPathController.class);

    // The HTTP request stays open for as long as the native chooser is on screen, so this is a
    // backstop against a request hanging forever (e.g. the dialog opened off-screen and the user
    // never saw it), not an expected outcome. The frontend has no axios timeout, by design.
    private static final long DIALOG_TIMEOUT_MINUTES = 10;

    // inspect-folder walks the real tree, which can be arbitrarily large (a user is free to point
    // at C:\). Stop counting past this and report the total as truncated rather than making the
    // modal's "how much is in here" preview an unbounded walk.
    private static final int MAX_INSPECTED_FILES = 200_000;

    // Bounds for resolve-dropped-folder's search. It runs while the user waits on a drop and its
    // roots are whole home directories, so it is capped in three independent directions: how deep
    // below a root it descends, how many directories it looks at at all, and how many candidates it
    // collects before giving up as ambiguous.
    private static final int DROP_SEARCH_MAX_DEPTH = 6;
    private static final int DROP_SEARCH_MAX_DIRS = 20_000;
    private static final int DROP_SEARCH_MAX_CANDIDATES = 24;

    // Directory names never descended into while resolving a drop. Not a security control - the
    // endpoint only ever returns a directory the user just dragged in - purely a way to keep the
    // walk off the trees that make a home directory expensive to search and that nobody drags a
    // documents folder out of.
    private static final Set<String> DROP_SEARCH_SKIP = Set.of(
            "node_modules", ".git", ".svn", ".hg", ".gradle", ".m2", ".cache", ".venv", "venv",
            "appdata", "application data", "$recycle.bin", "windows", "program files",
            "program files (x86)", "programdata", "target", "build", "dist", ".next", ".idea",
            ".vscode", "library", "system volume information");

    // One native dialog at a time - a second concurrent request (double-clicked Browse, or a
    // stale tab) would otherwise stack modal choosers the user has to dismiss one by one.
    private final AtomicBoolean chooserOpen = new AtomicBoolean(false);

    // Opens a native directory chooser on the local machine and returns what the user picked.
    // Blocks until they answer - "cancelled" is a normal, expected outcome, not an error, so it
    // comes back as a 200 with cancelled=true rather than a 4xx.
    @PostMapping("/browse-folder")
    public Mono<ResponseEntity<Map<String, Object>>> browseFolder(
            @RequestBody(required = false) Map<String, String> body) {
        String suggestedName = body != null ? body.get("suggestedName") : null;
        return Mono.fromCallable(() -> {
            if (GraphicsEnvironment.isHeadless()) {
                return cancelled("No desktop session is available to show a folder chooser");
            }
            if (!chooserOpen.compareAndSet(false, true)) {
                return cancelled("A folder chooser is already open");
            }
            try {
                // Native Explorer first; the Swing chooser is the fallback for non-Windows hosts
                // and for any host where the PowerShell shell-out doesn't work. A real user
                // cancel comes back as answered-with-null and must NOT fall through, or the user
                // would get a second dialog for a question they just declined.
                ChooserOutcome outcome = showExplorerChooser(suggestedName);
                File chosen = outcome.answered() ? outcome.folder() : showChooser(suggestedName);
                if (chosen == null) return cancelled(null);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("path", chosen.getAbsolutePath());
                out.put("name", chosen.getName());
                return ResponseEntity.ok(out);
            } finally {
                chooserOpen.set(false);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // Read-only metadata about a candidate folder, so the modal can confirm what registering it
    // would actually pick up before anything is persisted. `recursive` and `fileTypes` mirror the
    // registration inputs so the count matches what LocalFolderSession.list() will really discover -
    // an absent/blank fileTypes means "every supported type", exactly as it does at registration.
    @GetMapping("/inspect-folder")
    public Mono<ResponseEntity<Map<String, Object>>> inspectFolder(
            @RequestParam String path,
            @RequestParam(defaultValue = "true") boolean recursive,
            @RequestParam(required = false) String fileTypes) {
        return Mono.fromCallable(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            Path dir;
            try {
                dir = Path.of(path);
                // Must be absolute. toAbsolutePath() would otherwise resolve a blank or relative
                // input against the BACKEND's working directory - which reported the server's own
                // install/build directory back as a perfectly good folder to index, and would
                // persist a connector config that silently means a different directory the next
                // time the app starts from somewhere else.
                if (path.isBlank() || !dir.isAbsolute()) {
                    throw new IllegalArgumentException("Path must be absolute");
                }
                dir = dir.toAbsolutePath().normalize();
            } catch (Exception e) {
                // Blank, relative, or not syntactically valid on this platform (e.g. a
                // Windows-reserved character) - report it as unusable rather than failing the
                // request, since this is a live preview of whatever the user has typed so far.
                out.put("exists", false);
                out.put("isDirectory", false);
                out.put("readable", false);
                return ResponseEntity.ok(out);
            }
            Path fileName = dir.getFileName();
            out.put("path", dir.toString());
            out.put("name", fileName != null ? fileName.toString() : dir.toString());
            boolean isDirectory = Files.isDirectory(dir);
            boolean readable = isDirectory && Files.isReadable(dir);
            out.put("exists", Files.exists(dir));
            out.put("isDirectory", isDirectory);
            out.put("readable", readable);
            if (readable) {
                java.util.Set<String> exts = FileTypeSupport.parseFileTypes(fileTypes);
                FolderStats stats = countSupportedFiles(dir, recursive, exts);
                out.put("supportedFileCount", stats.count);
                out.put("totalBytes", stats.bytes);
                out.put("truncated", stats.truncated);
                // "No files of a supported type found directly in this folder" was true and useless:
                // it did not say whether the files were one level down (the usual answer - people
                // drag the folder that CONTAINS their documents) or genuinely of the wrong type, and
                // the two need opposite actions from the user. When a non-recursive look finds
                // nothing, say whether looking deeper would have.
                if (!recursive && stats.count == 0) {
                    out.put("hasSubdirectoryFiles", anySupportedFileDeeper(dir, exts));
                }
                log.debug("[Desktop] Inspected '{}' recursive={} fileTypes={} -> {} file(s){}",
                        dir, recursive, fileTypes, stats.count,
                        Boolean.TRUE.equals(out.get("hasSubdirectoryFiles"))
                                ? " (but there are some in subdirectories)" : "");
            }
            return ResponseEntity.ok(out);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Resolves a folder the user DRAGGED onto the page back to its absolute path on this machine.
     *
     * A drop is the clearest "index this folder" intent the product has, and it was the one that
     * asked the most of the user: the browser hands the page a folder NAME and nothing else, so
     * desktop answered a drop by opening a native chooser and making the user find the same folder
     * again by hand - typically starting from Documents, wherever the folder actually was.
     *
     * This closes that gap where it can be closed HONESTLY. The name alone cannot name a path - a
     * home directory has more than one "docs" - so the browser also sends the names of the folder's
     * immediate children, which it does have, and a candidate is accepted only when that set
     * matches EXACTLY and it is the only candidate that matches. Anything less certain returns
     * nothing and the caller falls back to the chooser: silently registering the wrong directory
     * would be far worse than the extra click this removes.
     *
     * Read-only. It resolves a path and returns it; registering is still an explicit action in the
     * modal, with the resolved path visible in the field first.
     *
     * THE FALLBACK SINCE 2026-09-27. Proxyma's own window hands the page a dropped folder's real path
     * (desktop-shell/preload.js), so this runs only for a page in a plain browser - an exec jar run
     * by hand. It searches the home directory only; a folder on another drive is not found.
     */
    @PostMapping("/resolve-dropped-folder")
    public Mono<ResponseEntity<Map<String, Object>>> resolveDroppedFolder(@RequestBody DropResolveRequest body) {
        return Mono.fromCallable(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            String name = body == null ? null : body.name();
            if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")) {
                return ResponseEntity.ok(out);
            }
            Set<String> expected = new HashSet<>();
            if (body.children() != null) {
                for (String c : body.children()) {
                    if (c != null && !c.isBlank()) expected.add(c.toLowerCase(Locale.ROOT));
                }
            }
            // An empty folder gives nothing to distinguish it by, so it is never auto-resolved.
            if (expected.isEmpty()) {
                log.debug("[Desktop] Dropped folder '{}' carried no child names - falling back to the chooser", name);
                return ResponseEntity.ok(out);
            }

            List<Path> matches = findFoldersNamed(name, expected);
            if (matches.size() == 1) {
                Path resolved = matches.get(0);
                log.info("[Desktop] Resolved dropped folder '{}' to {}", name, resolved);
                out.put("path", resolved.toString());
                out.put("name", name);
            } else {
                log.info("[Desktop] Could not resolve dropped folder '{}' ({} candidate(s)) - the chooser will open",
                        name, matches.size());
            }
            return ResponseEntity.ok(out);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** name: the dropped folder's own name. children: the names directly inside it. */
    public record DropResolveRequest(String name, List<String> children) {}

    // Breadth-first from the user's home and its obvious document roots, stopping at the first
    // bound it hits. Breadth-first on purpose: a dragged folder is almost always shallow (Desktop,
    // Documents, a project root), and depth-first would spend the whole budget inside the first
    // deep subtree it happened to wander into.
    private List<Path> findFoldersNamed(String name, Set<String> expectedChildren) {
        List<Path> matches = new ArrayList<>();
        Deque<Path> queue = new ArrayDeque<>();
        Set<Path> visited = new HashSet<>();
        Map<Path, Integer> depth = new LinkedHashMap<>();
        for (Path root : dropSearchRoots()) {
            if (visited.add(root)) {
                queue.add(root);
                depth.put(root, 0);
            }
        }
        int examined = 0;
        while (!queue.isEmpty() && examined < DROP_SEARCH_MAX_DIRS && matches.size() < DROP_SEARCH_MAX_CANDIDATES) {
            Path dir = queue.poll();
            int d = depth.getOrDefault(dir, 0);
            examined++;
            try (Stream<Path> children = Files.list(dir)) {
                for (Path child : (Iterable<Path>) children::iterator) {
                    Path fileName = child.getFileName();
                    if (fileName == null || !Files.isDirectory(child)) continue;
                    String childName = fileName.toString();
                    if (childName.equalsIgnoreCase(name) && childrenMatch(child, expectedChildren)) {
                        matches.add(child);
                    }
                    if (d + 1 < DROP_SEARCH_MAX_DEPTH
                            && !DROP_SEARCH_SKIP.contains(childName.toLowerCase(Locale.ROOT))
                            && !childName.startsWith(".")
                            && !Files.isSymbolicLink(child)
                            && visited.add(child)) {
                        queue.add(child);
                        depth.put(child, d + 1);
                    }
                }
            } catch (Exception e) {
                // Unreadable directory - skip it. A home directory always has a few.
                log.trace("[Desktop] Skipping '{}' while resolving a drop: {}", dir, e.getMessage());
            }
        }
        return matches;
    }

    // EXACT set equality, not "contains". A folder and a copy of it two levels down would both
    // CONTAIN the dropped names; only the one holding exactly those entries and no others is the
    // folder that was actually dragged. Case-insensitive, because Windows paths are.
    private boolean childrenMatch(Path dir, Set<String> expected) {
        try (Stream<Path> entries = Files.list(dir)) {
            Set<String> actual = new HashSet<>();
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Path n = entry.getFileName();
                if (n != null) actual.add(n.toString().toLowerCase(Locale.ROOT));
                if (actual.size() > expected.size()) return false;
            }
            return actual.equals(expected);
        } catch (Exception e) {
            return false;
        }
    }

    // Where a dragged folder plausibly lives. The user's home covers the overwhelming majority; the
    // OneDrive variants are listed explicitly because a Windows profile with Known Folder Move
    // enabled has its real Desktop and Documents UNDER OneDrive - exactly the layout the chooser
    // kept landing in the wrong half of.
    private List<Path> dropSearchRoots() {
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return roots;
        Path h = Path.of(home);
        addIfDirectory(roots, h);
        for (String sub : new String[] {"Desktop", "Documents", "Downloads"}) {
            addIfDirectory(roots, h.resolve(sub));
        }
        // OneDrive, and "OneDrive - <Tenant>" for a work account.
        try (Stream<Path> entries = Files.list(h)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Path n = entry.getFileName();
                if (n == null) continue;
                if (n.toString().toLowerCase(Locale.ROOT).startsWith("onedrive")) {
                    addIfDirectory(roots, entry);
                    for (String sub : new String[] {"Desktop", "Documents"}) {
                        addIfDirectory(roots, entry.resolve(sub));
                    }
                }
            }
        } catch (Exception ignored) {
            // No home listing - the explicit roots above are still searched.
        }
        return roots;
    }

    private static void addIfDirectory(List<Path> roots, Path candidate) {
        if (Files.isDirectory(candidate) && !roots.contains(candidate)) roots.add(candidate);
    }

    private static ResponseEntity<Map<String, Object>> cancelled(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cancelled", true);
        if (reason != null) out.put("reason", reason);
        return ResponseEntity.ok(out);
    }

    // answered=false means the native dialog never ran (not Windows, no PowerShell, script
    // failed) and the caller should fall back. answered=true with a null folder is a real user
    // cancel and is final.
    private record ChooserOutcome(boolean answered, File folder) {
        static ChooserOutcome unavailable() { return new ChooserOutcome(false, null); }
        static ChooserOutcome cancelled()   { return new ChooserOutcome(true, null); }
        static ChooserOutcome picked(File f) { return new ChooserOutcome(true, f); }
    }

    // The real Windows Explorer folder picker, rather than Swing's JFileChooser - the Swing one
    // has none of the shell chrome users navigate by (quick access, OneDrive/This PC tree, the
    // address bar, search), and on a Windows desktop app it reads as a different, older product.
    //
    // Implemented as an OpenFileDialog restricted to a filter that matches no real file, which is
    // the standard way to get the modern IFileDialog-backed shell browser in folder-selection
    // mode from .NET without dropping to COM interop: the file list shows directories only, and
    // "Open" returns "<the folder the user is standing in>\<sentinel>". WinForms'
    // FolderBrowserDialog would be simpler but renders the legacy tree-view dialog under the
    // .NET Framework that Windows PowerShell hosts, which is exactly the dialog this replaces.
    //
    // Shelling out to PowerShell rather than adding a JNI/JNA dependency: this is a Windows-only
    // nicety on the one code path where a native dialog is wanted, and it degrades to the
    // existing Swing chooser everywhere and whenever it doesn't work.
    private ChooserOutcome showExplorerChooser(String suggestedName) {
        if (!Platform.windows()) {
            return ChooserOutcome.unavailable();
        }
        Path script = null;
        try {
            script = Files.createTempFile("proxyma-pick-folder", ".ps1");
            Files.writeString(script, EXPLORER_CHOOSER_SCRIPT, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-STA",
                    "-ExecutionPolicy", "Bypass", "-File", script.toString())
                    .redirectErrorStream(false);
            pb.environment().put(TITLE_ENV, chooserTitle(suggestedName));
            Process proc = pb.start();
            String stdout;
            try (var in = proc.getInputStream()) {
                // Read before waitFor: a dialog left open for the whole timeout would otherwise
                // fill the pipe buffer and deadlock. The stream ends when the process exits.
                stdout = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!proc.waitFor(DIALOG_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                log.warn("[Desktop] Explorer folder chooser was not answered within {} minutes",
                        DIALOG_TIMEOUT_MINUTES);
                return ChooserOutcome.cancelled();
            }
            if (proc.exitValue() != 0 || stdout.isEmpty()) return ChooserOutcome.unavailable();
            if (stdout.startsWith("CANCEL")) return ChooserOutcome.cancelled();
            if (!stdout.startsWith("OK|")) return ChooserOutcome.unavailable();

            File folder = resolveChosenFolder(stdout.substring(3).trim());
            return folder != null ? ChooserOutcome.picked(folder) : ChooserOutcome.cancelled();
        } catch (Exception e) {
            log.warn("[Desktop] Explorer folder chooser unavailable, falling back to the Java chooser: {}",
                    e.getMessage());
            return ChooserOutcome.unavailable();
        } finally {
            if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { }
        }
    }

    // The dialog returns the sentinel file name joined onto whatever directory the user was
    // standing in, so the answer is that path's parent. Typing a real file name instead (or
    // double-clicking a file, which the filter makes hard but not impossible) is treated the same
    // way - the folder containing it is what the user was pointing at.
    private File resolveChosenFolder(String reported) {
        if (reported.isEmpty()) return null;
        File f = new File(reported);
        if (f.isDirectory()) return f;
        File parent = f.getParentFile();
        return parent != null && parent.isDirectory() ? parent : null;
    }

    private static final String TITLE_ENV = "PROXYMA_CHOOSER_TITLE";

    private static String chooserTitle(String suggestedName) {
        if (suggestedName == null || suggestedName.isBlank()) return "Select a folder for Proxyma to index";
        // Control characters out: a title is one line.
        String name = suggestedName.trim().replaceAll("\\p{Cntrl}", " ");
        return "Select the folder '" + name + "' for Proxyma to index";
    }

    // THE TITLE REACHES THE SCRIPT THROUGH THE ENVIRONMENT, never inside it, since 2026-09-29. It
    // used to be spliced into a single-quoted literal, and that broke on source names nobody
    // thought of: Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI, where the UTF-8 bytes of
    // Vietnamese letters such as "o" with a hat and a grave (E1 BB 91..94) decode to the curly
    // quotes 0x91-0x94 - and PowerShell treats curly quotes as string delimiters too. The script
    // failed to parse, and every Relink of such a folder silently fell back to the Java chooser
    // instead of Explorer. Add Folder rarely passes a name, which is why only Relink showed it.
    // With the value in $env, there is no quoting and no encoding for a name to get wrong.
    private static final String EXPLORER_CHOOSER_SCRIPT = """
            Add-Type -AssemblyName System.Windows.Forms
            Add-Type -AssemblyName System.Drawing
            $dlg = New-Object System.Windows.Forms.OpenFileDialog
            $dlg.Title = $env:PROXYMA_CHOOSER_TITLE
            # No real file can match this, so the browser lists directories only.
            $dlg.Filter = 'Folders|*.proxyma-pick-this-folder'
            $dlg.ValidateNames = $false
            $dlg.CheckFileExists = $false
            $dlg.CheckPathExists = $true
            $dlg.Multiselect = $false
            $dlg.FileName = 'Select this folder'
            # Without an owner the dialog opens behind the browser window that asked for it,
            # with nothing but a taskbar flash to say so - same reason the Swing fallback
            # builds a 1x1 always-on-top frame.
            $owner = New-Object System.Windows.Forms.Form
            $owner.TopMost = $true
            $owner.ShowInTaskbar = $false
            $owner.StartPosition = 'CenterScreen'
            $owner.Size = New-Object System.Drawing.Size(1, 1)
            $owner.Show()
            $owner.Activate()
            try {
              if ($dlg.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) {
                # UTF-8 out, so a chosen folder with a Vietnamese name reaches Java intact.
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                Write-Output ('OK|' + $dlg.FileName)
              } else {
                Write-Output 'CANCEL'
              }
            } finally {
              $owner.Close()
            }
            """;

    private File showChooser(String suggestedName) {
        CompletableFuture<File> picked = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            JFrame owner = null;
            try {
                // A chooser with no owner opens BEHIND the browser window that triggered it, with
                // nothing but a taskbar flash to say so. A 1x1 undecorated always-on-top frame
                // gives the modal something to be raised above and to be centered on.
                // Deliberately sized to nothing rather than made transparent - setOpacity(0f)
                // throws UnsupportedOperationException wherever the platform has no TRANSLUCENT
                // window support, which would take the whole chooser down with it.
                owner = new JFrame();
                owner.setUndecorated(true);
                owner.setSize(1, 1);
                owner.setAlwaysOnTop(true);
                owner.setLocationRelativeTo(null);
                owner.setVisible(true);
                owner.toFront();

                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Select a folder for Proxyma to index");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setMultiSelectionEnabled(false);
                chooser.setAcceptAllFileFilterUsed(false);
                // Pre-fills the name field with the folder the user dropped, so the chooser opens
                // already describing what they asked for - it cannot navigate to it (the browser
                // never told us where it lives), but it does make the match obvious once they get
                // to the right parent directory.
                if (suggestedName != null && !suggestedName.isBlank()) {
                    chooser.setSelectedFile(new File(suggestedName.trim()));
                }
                int outcome = chooser.showDialog(owner, "Index this folder");
                picked.complete(outcome == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile() : null);
            } catch (Exception e) {
                picked.completeExceptionally(e);
            } finally {
                if (owner != null) owner.dispose();
            }
        });
        try {
            return picked.get(DIALOG_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.warn("[Desktop] Folder chooser was not answered within {} minutes - reporting as cancelled",
                    DIALOG_TIMEOUT_MINUTES);
            return null;
        } catch (Exception e) {
            log.warn("[Desktop] Folder chooser failed: {}", e.getMessage());
            return null;
        }
    }

    // Would a RECURSIVE scan have found anything? Asked only when the non-recursive one found
    // nothing, and answered as a yes/no rather than a count, which makes it cheap AND honest: it
    // stops at the FIRST match, so a mistyped path like C:\\ costs one hit rather than a full-drive
    // walk, and the UI never has to quote a number that a ceiling had quietly truncated.
    private boolean anySupportedFileDeeper(Path root, java.util.Set<String> exts) {
        boolean[] found = {false};
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (FileTypeSupport.isSupportedFileType(file, exts)) {
                                found[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.debug("[Desktop] Could not probe subdirectories of '{}': {}", root, e.getMessage());
        }
        return found[0];
    }

    private record FolderStats(int count, long bytes, boolean truncated) {}

    // Counts exactly what LocalFolderSession.list() would pick up for this folder - same extension
    // filter, same depth rule - so the modal never promises a number the indexer won't deliver.
    private FolderStats countSupportedFiles(Path root, boolean recursive, java.util.Set<String> exts) {
        int[] count = {0};
        long[] bytes = {0L};
        boolean[] truncated = {false};
        int maxDepth = recursive ? Integer.MAX_VALUE : 1;
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (FileTypeSupport.isSupportedFileType(file, exts)) {
                                count[0]++;
                                bytes[0] += attrs.size();
                                if (count[0] >= MAX_INSPECTED_FILES) {
                                    truncated[0] = true;
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            // An unreadable file or a broken junction must not abort the whole
                            // preview - skip it, the same way the real sync walk tolerates it.
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.warn("[Desktop] Failed to inspect '{}': {}", root, e.getMessage());
        }
        return new FolderStats(count[0], bytes[0], truncated[0]);
    }
}
