package it.iorfino.s3forge.xml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Minimal helper around the JDK's DOM parser for reading S3 request bodies.
 *
 * <p>Provides a few convenience methods for extracting text content of child elements, which covers
 * the vast majority of S3 request payloads. The parser is hardened against XXE (external entity
 * expansion) attacks, since S3Forge may be exposed on a local network during integration tests.
 *
 * @since 0.1.0
 */
public final class XmlReader {

    private final Document doc;

    private XmlReader(Document doc) {
        this.doc = doc;
    }

    /**
     * Parses the given XML bytes into a reader.
     *
     * @param bytes the XML document; must not be {@code null}
     * @return a {@link XmlReader} over the parsed document
     * @throws IOException if the XML is malformed or cannot be parsed
     */
    public static XmlReader parse(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // Harden against XXE
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);

            DocumentBuilder builder = f.newDocumentBuilder();
            Document d = builder.parse(new ByteArrayInputStream(bytes));
            d.getDocumentElement().normalize();
            return new XmlReader(d);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Malformed XML: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the root element of the document.
     *
     * @return the document element; never {@code null}
     */
    public Element root() {
        return doc.getDocumentElement();
    }

    /**
     * Returns the direct child elements of the given parent whose tag name matches the given name.
     *
     * @param parent the parent element; must not be {@code null}
     * @param name the tag name to match; must not be {@code null}
     * @return a list of matching child elements; never {@code null}, possibly empty
     */
    public static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    /**
     * Returns the text content of the first direct child with the given tag name, or {@code null}
     * if not present.
     *
     * @param parent the parent element; must not be {@code null}
     * @param name the tag name to match; must not be {@code null}
     * @return the trimmed text content, or {@code null}
     */
    public static String text(Element parent, String name) {
        List<Element> found = children(parent, name);
        if (found.isEmpty()) return null;
        String s = found.get(0).getTextContent();
        return s == null ? null : s.trim();
    }
}
