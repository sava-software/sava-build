package software.sava.build.guard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Prints the values of the given keys of a properties file as {@link Properties#load(InputStream)}
 * reads it, which is how Gradle reads gradle.properties: the last definition of a key, '=', ':' or
 * whitespace between key and value, escapes, and a line ending in a backslash continued on the
 * next. One {@code key=value} line per key the file defines, in the order the keys were asked
 * for; a value's line breaks become spaces, so a line here is one key. Exits 2 on misuse. Run by
 * notice.sh through the JDK's source launcher.
 */
public final class PropertyValues {

  private PropertyValues() {}

  public static void main(String... args) throws IOException {
    if (args.length < 2) {
      System.err.println("Usage: PropertyValues <properties file> <key>...");
      System.exit(2);
    }
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
      properties.load(in);
    }
    for (int i = 1; i < args.length; i++) {
      String value = properties.getProperty(args[i]);
      if (value != null) {
        System.out.println(args[i] + '=' + value.replace('\r', ' ').replace('\n', ' '));
      }
    }
  }
}
