package io.descoped.lds.core;

import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GraphQL model ({@code graphql.schema}) is read from the class path when it is there, in a jar or in a folder
 * whose path is escaped in a URL, and from the file at the path otherwise.
 */
class GraphQLModelFileTest {

    private static final String MODEL = "directive @domain on OBJECT\n\ntype Bird @domain {\n  name: String\n}\n\n"
            + "type Query {\n  bird: Bird\n}\n";

    @Test
    void aModelInAJarOnTheClassPathIsRead(@TempDir Path folder) throws IOException {
        Path jar = folder.resolve("model.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry("models/bird.graphql"));
            out.write(MODEL.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            TypeDefinitionRegistry model = UndertowApplication.parseSchemaFile("models/bird.graphql", classLoader);

            assertThat(model.getType("Bird")).isPresent();
        }
    }

    @Test
    void aModelInAFolderOnTheClassPathWhosePathIsEscapedInAUrlIsRead(@TempDir Path folder) throws IOException {
        Path classes = folder.resolve("a folder with spaces");
        Files.createDirectories(classes.resolve("models"));
        Files.writeString(classes.resolve("models/bird.graphql"), MODEL);
        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) {
            TypeDefinitionRegistry model = UndertowApplication.parseSchemaFile("models/bird.graphql", classLoader);

            assertThat(model.getType("Bird")).isPresent();
        }
    }

    @Test
    void aModelNotOnTheClassPathIsReadFromTheFile(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("bird.graphql");
        Files.writeString(file, MODEL);
        try (URLClassLoader classLoader = new URLClassLoader(new URL[0], null)) {
            TypeDefinitionRegistry model = UndertowApplication.parseSchemaFile(file.toString(), classLoader);

            assertThat(model.getType("Bird")).isPresent();
        }
    }
}
