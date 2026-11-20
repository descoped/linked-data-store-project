package io.descoped.lds.core.domain.resource;

import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.json.JsonTools;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElementType;
import io.descoped.lds.core.specification.SpecificationBuilder;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static io.descoped.lds.api.persistence.json.JsonTools.mapper;
import static io.descoped.lds.core.specification.SpecificationBuilder.arrayRefNode;
import static io.descoped.lds.core.specification.SpecificationBuilder.objectNode;
import static io.descoped.lds.core.specification.SpecificationBuilder.refNode;
import static io.descoped.lds.core.specification.SpecificationBuilder.stringNode;

public class ResourceContextTest {

    public static Specification specification() {
        return SpecificationBuilder.createSpecificationAndRoot(Set.of(
                objectNode(SpecificationElementType.MANAGED, "SomeEntity", Set.of(
                        stringNode("aString"),
                        refNode("ref", Set.of("OtherEntity")),
                        arrayRefNode("refs", Set.of("OtherEntity")),
                        objectNode("object1", Set.of(
                                stringNode("aString"),
                                refNode("ref", Set.of("OtherEntity")),
                                arrayRefNode("refs", Set.of("OtherEntity")),
                                objectNode("object2", Set.of(
                                        stringNode("aString"),
                                        refNode("ref", Set.of("OtherEntity")),
                                        arrayRefNode("refs", Set.of("OtherEntity"))
                                ))
                        ))
                )),
                objectNode(SpecificationElementType.MANAGED, "OtherEntity", Set.of(
                        stringNode("aString")
                ))
        ));
    }

    @Test
    public void thatCreateResourceContextOnManagedResourceWorks() {
        Specification specification = specification();
        ResourceContext context = ResourceContext.createResourceContext(specification, "/ns/SomeEntity/123", ZonedDateTime.now(ZoneId.of("Etc/UTC")));
        Assert.assertTrue(context.isManaged());
    }

    @DataProvider
    public static Object[][] embeddedResourcePaths() {
        return new Object[][]{
                {"/ns/SomeEntity/123/object1"},
                {"/ns/SomeEntity/123/ref"},
                {"/ns/SomeEntity/123/object1/object2"},
        };
    }

    @Test(dataProvider = "embeddedResourcePaths")
    public void thatCreateResourceContextOnEmbeddedResourceWorks(String path) {
        Specification specification = specification();
        ResourceContext context = ResourceContext.createResourceContext(specification, path, ZonedDateTime.now(ZoneId.of("Etc/UTC")));
        Assert.assertTrue(context.isEmbedded());
    }

    @DataProvider
    public static Object[][] referenceResourcePaths() {
        return new Object[][]{
                {"/ns/SomeEntity/123/ref/OtherEntity/456"},
                {"/ns/SomeEntity/123/object1/ref/OtherEntity/456"},
                {"/ns/SomeEntity/123/object1/object2/ref/OtherEntity/456"},
                {"/ns/SomeEntity/123/refs/OtherEntity/456"},
                {"/ns/SomeEntity/123/object1/refs/OtherEntity/456"},
                {"/ns/SomeEntity/123/object1/object2/refs/OtherEntity/456"},
        };
    }

    @Test(dataProvider = "referenceResourcePaths")
    public void thatCreateResourceContextOnReferenceResourceWorks(String path) {
        Specification specification = specification();
        ResourceContext context = ResourceContext.createResourceContext(specification, path, ZonedDateTime.now(ZoneId.of("Etc/UTC")));
        Assert.assertTrue(context.isReference());
    }

    @Test
    public void thatNavigateOnNestedNestedArrayReferenceResourceWorksOnEmptyDocument() {
        Specification specification = specification();
        ResourceContext context = ResourceContext.createResourceContext(specification, "/ns/SomeEntity/123/object1/object2/refs/OtherEntity/456", ZonedDateTime.now(ZoneId.of("Etc/UTC")));
        JsonNode documentRoot = mapper.createObjectNode();
        context.navigateAndCreateJson(documentRoot, t -> {
            Assert.assertEquals(t.resourceElement.getSpecificationElement().getSpecificationElementType(), SpecificationElementType.REF);
            Assert.assertEquals(JsonNavigationPath.from(t.resourceElement.getSpecificationElement()).serialize(), "$.object1.object2.refs");
            Assert.assertEquals(JsonTools.toJson(t.jsonObject), "{}");
            return null;
        });
        Assert.assertEquals(JsonTools.toJson(documentRoot), "{\"object1\":{\"object2\":{}}}");
    }

    @Test
    public void thatNavigateOnNestedNestedArrayReferenceResourceWorksOnPopulatedDocument() {
        Specification specification = specification();
        ResourceContext context = ResourceContext.createResourceContext(specification, "/ns/SomeEntity/123/object1/object2/refs/OtherEntity/456", ZonedDateTime.now(ZoneId.of("Etc/UTC")));
        ObjectNode documentRoot = mapper.createObjectNode();
        documentRoot.putObject("object1").putObject("object2").putArray("refs").add("/OtherEntity/1");
        context.navigateAndCreateJson(documentRoot, t -> {
            Assert.assertEquals(t.resourceElement.getSpecificationElement().getSpecificationElementType(), SpecificationElementType.REF);
            Assert.assertEquals(JsonNavigationPath.from(t.resourceElement.getSpecificationElement()).serialize(), "$.object1.object2.refs");
            Assert.assertEquals(JsonTools.toJson(t.jsonObject), "{\"refs\":[\"/OtherEntity/1\"]}");
            return null;
        });
        Assert.assertEquals(JsonTools.toJson(documentRoot), "{\"object1\":{\"object2\":{\"refs\":[\"/OtherEntity/1\"]}}}");
    }

    @Test
    public void thatNavigateOnNestedNestedArrayReferenceResourceWorksOnPartlyPopulatedDocument() {
        Specification specification = specification();
        ResourceContext context = ResourceContext.createResourceContext(specification, "/ns/SomeEntity/123/object1/object2/refs/OtherEntity/456", ZonedDateTime.now(ZoneId.of("Etc/UTC")));
        ObjectNode documentRoot = mapper.createObjectNode();
        documentRoot.putObject("object1");
        context.navigateAndCreateJson(documentRoot, t -> {
            Assert.assertEquals(t.resourceElement.getSpecificationElement().getSpecificationElementType(), SpecificationElementType.REF);
            Assert.assertEquals(JsonNavigationPath.from(t.resourceElement.getSpecificationElement()).serialize(), "$.object1.object2.refs");
            Assert.assertEquals(JsonTools.toJson(t.jsonObject), "{}");
            return null;
        });
        Assert.assertEquals(JsonTools.toJson(documentRoot), "{\"object1\":{\"object2\":{}}}");
    }

    // A path does not navigate into the elements of an array, so that has no test.
}
