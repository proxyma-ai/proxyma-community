package ai.proxyma.core.connector.networkshare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance tests 1 and 2 of docs/architecture.html#spec-05-11.
 *
 * <p>The normalization is load-bearing beyond tidiness: the registered form is what duplicate
 * detection compares AND what the allow-list reads a host out of, so two spellings of one path
 * reducing to two different values would let the same folder register twice and - more seriously -
 * would mean the string the allow-list checked was not the string that was connected to.
 */
class UncPathTest {

    @Nested
    @DisplayName("every spelling a person might paste reduces to one form")
    class Normalization {

        @Test
        void backslashForwardAndSmbSchemeAllAgree() {
            String expected = "\\\\host\\Share\\a\\b";

            assertThat(UncPath.parse("\\\\HOST\\Share\\a\\b\\").toUnc()).isEqualTo(expected);
            assertThat(UncPath.parse("//host/Share/a/b").toUnc()).isEqualTo(expected);
            assertThat(UncPath.parse("smb://host/Share/a/b").toUnc()).isEqualTo(expected);
        }

        @Test
        void hostIsLowercasedAndShareKeepsItsCase() {
            UncPath path = UncPath.parse("\\\\FileServer.CORP.example\\Engineering\\Specs");

            // DNS is case-insensitive, so the host is a canonical key. A share name is a label the
            // server chose and Windows shows it back with the case the administrator gave it.
            assertThat(path.host()).isEqualTo("fileserver.corp.example");
            assertThat(path.share()).isEqualTo("Engineering");
            assertThat(path.path()).isEqualTo("Specs");
        }

        @Test
        void schemePrefixIsCaseInsensitive() {
            assertThat(UncPath.parse("SMB://host/share/x").toUnc()).isEqualTo("\\\\host\\share\\x");
        }

        @Test
        void mixedAndRepeatedSeparatorsCollapse() {
            assertThat(UncPath.parse("\\\\host\\share//a\\\\b//c").toUnc())
                    .isEqualTo("\\\\host\\share\\a\\b\\c");
        }

        @Test
        void currentDirectorySegmentsAreDropped() {
            assertThat(UncPath.parse("\\\\host\\share\\.\\a\\.\\b").toUnc())
                    .isEqualTo("\\\\host\\share\\a\\b");
        }

        @Test
        void aShareRootHasNoTrailingSeparator() {
            assertThat(UncPath.parse("\\\\host\\share").toUnc()).isEqualTo("\\\\host\\share");
            assertThat(UncPath.parse("\\\\host\\share\\").toUnc()).isEqualTo("\\\\host\\share");
        }
    }

    @Nested
    @DisplayName("the item id round-trips and reads as somewhere else")
    class SourceIds {

        @Test
        void sourceIdIsUriShapedAndParsesBack() {
            UncPath path = UncPath.parse("\\\\host\\Share\\a\\b\\report.docx");

            // URI-shaped so a citation does not read as a path on the reader's own machine.
            assertThat(path.toSourceId()).isEqualTo("smb://host/Share/a/b/report.docx");
            assertThat(UncPath.parse(path.toSourceId()).toUnc()).isEqualTo(path.toUnc());
        }

        @Test
        void nameIsTheLastSegmentOrTheShareAtARoot() {
            assertThat(UncPath.parse("\\\\host\\share\\a\\report.docx").name()).isEqualTo("report.docx");
            assertThat(UncPath.parse("\\\\host\\share").name()).isEqualTo("share");
        }

        @Test
        void resolveDescendsOneLevel() {
            UncPath root = UncPath.parse("\\\\host\\share\\docs");

            assertThat(root.resolve("q3.pdf").toUnc()).isEqualTo("\\\\host\\share\\docs\\q3.pdf");
            assertThat(UncPath.parse("\\\\host\\share").resolve("top.pdf").toUnc())
                    .isEqualTo("\\\\host\\share\\top.pdf");
        }
    }

    @Nested
    @DisplayName("what is refused, and what the person is told")
    class Rejections {

        @Test
        void parentSegmentsAreRejectedRatherThanResolved() {
            // Resolving it would let a registered path climb out of the folder its owner chose,
            // and the allow-list only ever saw the host.
            assertThatThrownBy(() -> UncPath.parse("\\\\host\\share\\..\\x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not contain '..'");
        }

        @Test
        void blankIsRefused() {
            assertThatThrownBy(() -> UncPath.parse("   "))
                    .hasMessageContaining("must not be empty");
            assertThatThrownBy(() -> UncPath.parse(null))
                    .hasMessageContaining("must not be empty");
        }

        @Test
        void somethingThatIsNotAUncPathNamesTheShapeWanted() {
            // The message is read in the registration dialog by the person who just typed it, so
            // it shows the form rather than quoting the rule.
            assertThatThrownBy(() -> UncPath.parse("C:\\Users\\me\\Documents"))
                    .hasMessageContaining("\\\\server\\share");
            assertThatThrownBy(() -> UncPath.parse("https://sharepoint.example/sites/x"))
                    .hasMessageContaining("\\\\server\\share");
        }

        @Test
        void aServerWithNoShareIsRefused() {
            assertThatThrownBy(() -> UncPath.parse("\\\\host"))
                    .hasMessageContaining("must name a server and a share");
            assertThatThrownBy(() -> UncPath.parse("\\\\host\\"))
                    .hasMessageContaining("must name a server and a share");
        }
    }
}
