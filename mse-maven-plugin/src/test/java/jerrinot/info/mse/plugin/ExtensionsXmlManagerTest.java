package jerrinot.info.mse.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionsXmlManagerTest {

    @TempDir
    Path tempDir;

    private final ExtensionsXmlManager manager = new ExtensionsXmlManager();

    @Test
    void installCreatesFromScratch() throws Exception {
        boolean modified = manager.installExtension(tempDir, "1.0.0");

        assertTrue(modified);
        Path extensionsFile = tempDir.resolve(".mvn/extensions.xml");
        assertTrue(Files.exists(extensionsFile));
        String content = Files.readString(extensionsFile);
        assertTrue(content.contains("<groupId>" + ExtensionsXmlManager.GROUP_ID + "</groupId>"));
        assertTrue(content.contains("<artifactId>" + ExtensionsXmlManager.ARTIFACT_ID + "</artifactId>"));
        assertTrue(content.contains("<version>1.0.0</version>"));
    }

    @Test
    void installAddsToExistingExtensions() throws Exception {
        Path mvnDir = tempDir.resolve(".mvn");
        Files.createDirectories(mvnDir);
        Files.writeString(mvnDir.resolve("extensions.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<extensions>\n"
                + "    <extension>\n"
                + "        <groupId>com.example</groupId>\n"
                + "        <artifactId>other-extension</artifactId>\n"
                + "        <version>2.0.0</version>\n"
                + "    </extension>\n"
                + "</extensions>\n");

        boolean modified = manager.installExtension(tempDir, "1.0.0");

        assertTrue(modified);
        String content = Files.readString(mvnDir.resolve("extensions.xml"));
        assertTrue(content.contains("<artifactId>other-extension</artifactId>"));
        assertTrue(content.contains("<artifactId>" + ExtensionsXmlManager.ARTIFACT_ID + "</artifactId>"));
    }

    @Test
    void installUpdatesVersionWhenDifferent() throws Exception {
        manager.installExtension(tempDir, "1.0.0");

        boolean modified = manager.installExtension(tempDir, "2.0.0");

        assertTrue(modified);
        String content = Files.readString(tempDir.resolve(".mvn/extensions.xml"));
        assertTrue(content.contains("<version>2.0.0</version>"));
        assertFalse(content.contains("<version>1.0.0</version>"));
    }

    @Test
    void installNoOpWhenAlreadyAtCorrectVersion() throws Exception {
        manager.installExtension(tempDir, "1.0.0");

        boolean modified = manager.installExtension(tempDir, "1.0.0");

        assertFalse(modified);
    }

    @Test
    void uninstallRemovesEntry() throws Exception {
        // Install MSE and another extension
        Path mvnDir = tempDir.resolve(".mvn");
        Files.createDirectories(mvnDir);
        Files.writeString(mvnDir.resolve("extensions.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<extensions>\n"
                + "    <extension>\n"
                + "        <groupId>com.example</groupId>\n"
                + "        <artifactId>other-extension</artifactId>\n"
                + "        <version>2.0.0</version>\n"
                + "    </extension>\n"
                + "    <extension>\n"
                + "        <groupId>" + ExtensionsXmlManager.GROUP_ID + "</groupId>\n"
                + "        <artifactId>" + ExtensionsXmlManager.ARTIFACT_ID + "</artifactId>\n"
                + "        <version>1.0.0</version>\n"
                + "    </extension>\n"
                + "</extensions>\n");

        boolean removed = manager.uninstallExtension(tempDir);

        assertTrue(removed);
        String content = Files.readString(mvnDir.resolve("extensions.xml"));
        assertTrue(content.contains("<artifactId>other-extension</artifactId>"));
        assertFalse(content.contains("<artifactId>" + ExtensionsXmlManager.ARTIFACT_ID + "</artifactId>"));
    }

    @Test
    void uninstallNoOpWhenNotPresent() throws Exception {
        Path mvnDir = tempDir.resolve(".mvn");
        Files.createDirectories(mvnDir);
        Files.writeString(mvnDir.resolve("extensions.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<extensions>\n"
                + "    <extension>\n"
                + "        <groupId>com.example</groupId>\n"
                + "        <artifactId>other-extension</artifactId>\n"
                + "        <version>2.0.0</version>\n"
                + "    </extension>\n"
                + "</extensions>\n");

        boolean removed = manager.uninstallExtension(tempDir);

        assertFalse(removed);
    }

    @Test
    void uninstallNoOpWhenFileDoesNotExist() throws Exception {
        boolean removed = manager.uninstallExtension(tempDir);

        assertFalse(removed);
    }

    @Test
    void uninstallCountIsCorrect() throws Exception {
        // Verify only one extension element with MSE coordinates is removed
        manager.installExtension(tempDir, "1.0.0");
        Path extensionsFile = tempDir.resolve(".mvn/extensions.xml");
        String beforeContent = Files.readString(extensionsFile);
        long beforeCount = countOccurrences(beforeContent, "<extension>");
        assertEquals(1, beforeCount);

        boolean removed = manager.uninstallExtension(tempDir);
        assertTrue(removed);

        String afterContent = Files.readString(extensionsFile);
        long afterCount = countOccurrences(afterContent, "<extension>");
        assertEquals(0, afterCount);
    }

    @Test
    void installRejectsDoctype() throws Exception {
        Path mvnDir = tempDir.resolve(".mvn");
        Files.createDirectories(mvnDir);
        Files.writeString(mvnDir.resolve("extensions.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE extensions SYSTEM \"http://example.com/evil.dtd\">\n"
                + "<extensions/>\n");

        assertThrows(Exception.class, () -> manager.installExtension(tempDir, "1.0.0"));
    }

    @Test
    void uninstallRejectsDoctype() throws Exception {
        Path mvnDir = tempDir.resolve(".mvn");
        Files.createDirectories(mvnDir);
        Files.writeString(mvnDir.resolve("extensions.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE extensions SYSTEM \"http://example.com/evil.dtd\">\n"
                + "<extensions/>\n");

        assertThrows(Exception.class, () -> manager.uninstallExtension(tempDir));
    }

    @Test
    void installAddsVersionWhenMissing() throws Exception {
        Path mvnDir = tempDir.resolve(".mvn");
        Files.createDirectories(mvnDir);
        Files.writeString(mvnDir.resolve("extensions.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<extensions>\n"
                + "    <extension>\n"
                + "        <groupId>" + ExtensionsXmlManager.GROUP_ID + "</groupId>\n"
                + "        <artifactId>" + ExtensionsXmlManager.ARTIFACT_ID + "</artifactId>\n"
                + "    </extension>\n"
                + "</extensions>\n");

        boolean modified = manager.installExtension(tempDir, "1.0.0");

        assertTrue(modified);
        String content = Files.readString(mvnDir.resolve("extensions.xml"));
        assertTrue(content.contains("<version>1.0.0</version>"));
    }

    private static long countOccurrences(String text, String search) {
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }
}
