package org.haoemr.archive;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev-synthetic")
final class SyntheticArchiveDocumentValidator implements ArchiveDocumentValidator {
    @Override
    public ValidationResult validate(byte[] content, String mediaType, String filename) {
        boolean valid = false;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
            valid = "ClinicalDocument".equals(document.getDocumentElement().getLocalName());
        } catch (Exception invalid) {
            valid = false;
        }
        return new ValidationResult(valid, "synthetic-secure-cda-structure-validator", sha256(content));
    }

    private static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
