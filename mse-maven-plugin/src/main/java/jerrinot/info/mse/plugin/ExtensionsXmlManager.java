package jerrinot.info.mse.plugin;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ExtensionsXmlManager {
    static final String GROUP_ID = "info.jerrinot";
    static final String ARTIFACT_ID = "maven-silent-extension";

    /**
     * Installs or updates the MSE extension entry in .mvn/extensions.xml.
     *
     * @return true if the file was modified
     */
    public boolean installExtension(Path projectDir, String version)
            throws ParserConfigurationException, IOException, SAXException, TransformerException {
        Path mvnDir = projectDir.resolve(".mvn");
        Path extensionsFile = mvnDir.resolve("extensions.xml");

        DocumentBuilder db = newSafeDocumentBuilder();
        Document doc;

        if (Files.exists(extensionsFile)) {
            doc = db.parse(extensionsFile.toFile());
            doc.getDocumentElement().normalize();

            Element existing = findMseExtension(doc);
            if (existing != null) {
                String currentVersion = getChildText(existing, "version");
                if (version.equals(currentVersion)) {
                    return false;
                }
                setChildText(existing, "version", version);
                writeDocument(doc, extensionsFile);
                return true;
            }
        } else {
            Files.createDirectories(mvnDir);
            doc = db.newDocument();
            Element root = doc.createElement("extensions");
            doc.appendChild(root);
        }

        Element ext = doc.createElement("extension");

        Element gid = doc.createElement("groupId");
        gid.setTextContent(GROUP_ID);
        ext.appendChild(gid);

        Element aid = doc.createElement("artifactId");
        aid.setTextContent(ARTIFACT_ID);
        ext.appendChild(aid);

        Element ver = doc.createElement("version");
        ver.setTextContent(version);
        ext.appendChild(ver);

        doc.getDocumentElement().appendChild(ext);
        writeDocument(doc, extensionsFile);
        return true;
    }

    /**
     * Removes the MSE extension entry from .mvn/extensions.xml.
     *
     * @return true if the entry was found and removed
     */
    public boolean uninstallExtension(Path projectDir)
            throws ParserConfigurationException, IOException, SAXException, TransformerException {
        Path extensionsFile = projectDir.resolve(".mvn").resolve("extensions.xml");
        if (!Files.exists(extensionsFile)) {
            return false;
        }

        DocumentBuilder db = newSafeDocumentBuilder();
        Document doc = db.parse(extensionsFile.toFile());
        doc.getDocumentElement().normalize();

        Element existing = findMseExtension(doc);
        if (existing == null) {
            return false;
        }

        existing.getParentNode().removeChild(existing);
        writeDocument(doc, extensionsFile);
        return true;
    }

    private static DocumentBuilder newSafeDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return dbf.newDocumentBuilder();
    }

    private Element findMseExtension(Document doc) {
        NodeList extensions = doc.getElementsByTagName("extension");
        for (int i = 0; i < extensions.getLength(); i++) {
            Element ext = (Element) extensions.item(i);
            String gid = getChildText(ext, "groupId");
            String aid = getChildText(ext, "artifactId");
            if (GROUP_ID.equals(gid) && ARTIFACT_ID.equals(aid)) {
                return ext;
            }
        }
        return null;
    }

    private static String getChildText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private static void setChildText(Element parent, String tagName, String value) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
                child.setTextContent(value);
                return;
            }
        }
        Element newChild = parent.getOwnerDocument().createElement(tagName);
        newChild.setTextContent(value);
        parent.appendChild(newChild);
    }

    private static void writeDocument(Document doc, Path file)
            throws TransformerException {
        // Remove whitespace-only text nodes to avoid extra blank lines
        removeWhitespaceNodes(doc.getDocumentElement());

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.transform(new DOMSource(doc), new StreamResult(file.toFile()));
    }

    private static void removeWhitespaceNodes(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().trim().isEmpty()) {
                element.removeChild(child);
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                removeWhitespaceNodes((Element) child);
            }
        }
    }
}
