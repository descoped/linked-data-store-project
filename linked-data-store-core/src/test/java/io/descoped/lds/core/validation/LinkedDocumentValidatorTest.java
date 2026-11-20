package io.descoped.lds.core.validation;

import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.core.specification.JsonSchemaBasedSpecification;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class LinkedDocumentValidatorTest {

    @Test
    public void thatValidLinkedDocumentPassesSchemaValidation() {
        JsonSchemaBasedSpecification specification = JsonSchemaBasedSpecification.create("spec/schemas/contact.json", "spec/schemas/provisionagreement.json");
        LinkedDocumentValidator validator = new LinkedDocumentValidator(specification, specification);

        String linkedDocument = "{\"id\":\"r13\",\"name\":\"pa-test-name\",\"friend\":\"/contact/f1\",\"support\":{\"technicalSupport\":[\"/contact/s1\",\"/contact/s2\"],\"businessSupport\":[\"/contact/b1\"]}}";
        validator.validate("provisionagreement", JsonTools.toJsonNode(linkedDocument));
    }

    @DataProvider
    public static Object[][] invalidLinkedDocuments() {
        return new Object[][]{
                // a single link to an entity the field may not link to
                {"{\"id\":\"r13\",\"name\":\"pa-test-name\",\"friend\":\"/baddomain/f1\",\"support\":{\"technicalSupport\":[\"/contact/s1\",\"/contact/s2\"],\"businessSupport\":[\"/contact/b1\"]}}"},
                // a link in an array to an entity the field may not link to
                {"{\"id\":\"r13\",\"name\":\"pa-test-name\",\"friend\":\"/contact/f1\",\"support\":{\"technicalSupport\":[\"/contact/s1\",\"/baddomain/s2\"],\"businessSupport\":[\"/contact/b1\"]}}"},
                // a single link that is not a link
                {"{\"id\":\"r13\",\"name\":\"pa-test-name\",\"friend\":\"contact\",\"support\":{\"technicalSupport\":[\"/contact/s1\",\"/contact/s2\"],\"businessSupport\":[\"/contact/b1\"]}}"},
                // a link in an array that is not a link
                {"{\"id\":\"r13\",\"name\":\"pa-test-name\",\"friend\":\"/contact/f1\",\"support\":{\"technicalSupport\":[\"/contact/s1\",\"contact\"],\"businessSupport\":[\"/contact/b1\"]}}"},
        };
    }

    @Test(dataProvider = "invalidLinkedDocuments", expectedExceptions = LinkedDocumentValidationException.class)
    public void thatInvalidLinkFailsSchemaValidation(String linkedDocument) {
        JsonSchemaBasedSpecification specification = JsonSchemaBasedSpecification.create("spec/schemas/contact.json", "spec/schemas/provisionagreement.json");
        LinkedDocumentValidator validator = new LinkedDocumentValidator(specification, specification);

        validator.validate("provisionagreement", JsonTools.toJsonNode(linkedDocument));
    }
}
