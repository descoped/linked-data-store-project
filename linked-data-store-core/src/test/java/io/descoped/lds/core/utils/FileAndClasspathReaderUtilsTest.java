package io.descoped.lds.core.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A file or a resource of the class path read as text: a name that is a file is read as the file and any other name
 * from the class path, a missing resource is null, a missing file is an error, and bytes that are not UTF-8 are an
 * error.
 */
class FileAndClasspathReaderUtilsTest {

    @TempDir
    Path dir;

    @Test
    void aFileIsReadAsUtf8() throws Exception {
        Path file = Files.writeString(dir.resolve("a.txt"), "blåbær", StandardCharsets.UTF_8);

        assertThat(FileAndClasspathReaderUtils.readFileOrClasspathResource(file.toString())).isEqualTo("blåbær");
        assertThat(FileAndClasspathReaderUtils.readFileAsUtf8(file.toString())).isEqualTo("blåbær");
    }

    @Test
    void aNameThatIsNoFileIsReadFromTheClassPath() {
        String text = FileAndClasspathReaderUtils.readFileOrClasspathResource("logback-test.xml");

        assertThat(text).contains("<configuration");
    }

    @Test
    void aResourceThatIsNotThereIsNull() {
        assertThat(FileAndClasspathReaderUtils.readFileOrClasspathResource("no/such/resource.txt")).isNull();
    }

    @Test
    void aFileThatIsNotThereIsAnError() {
        String missing = dir.resolve("missing.txt").toString();

        assertThatThrownBy(() -> FileAndClasspathReaderUtils.readFileAsUtf8(missing))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(NoSuchFileException.class);
    }

    @Test
    void bytesThatAreNotUtf8AreAnError() throws Exception {
        Path file = Files.write(dir.resolve("latin1.txt"), new byte[]{'b', (byte) 0xE5, 'r'});

        assertThatThrownBy(() -> FileAndClasspathReaderUtils.readFileAsUtf8(file.toString()))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(MalformedInputException.class);
    }

    @Test
    void theCurrentPathIsAbsoluteAndNormalized() {
        Path current = FileAndClasspathReaderUtils.currentPath();

        assertThat(current.isAbsolute()).isTrue();
        assertThat(current).isEqualTo(current.normalize());
    }
}
