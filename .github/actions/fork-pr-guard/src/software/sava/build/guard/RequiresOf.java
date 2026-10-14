package software.sava.build.guard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The requires directives of a module-info.java read from standard input, one per line,
 * sorted and without duplicates, for action.yml to compare between two commits. Comments
 * are removed in one left-to-right pass; one that holds only "runtime" ({@code /*runtime*&#47;}
 * or {@code //runtime}), which the module plugin reads as a runtime-only dependency, becomes a
 * modifier. Whitespace and modifiers are normalized and JDK modules left out.
 */
public final class RequiresOf {

  private static final Pattern COMMENT = Pattern.compile("//[^\n]*|/\\*.*?\\*/", Pattern.DOTALL);
  private static final Pattern REQUIRES = Pattern.compile(
      "(?<![\\w$.])requires\\s+((?:(?:static|transitive|runtime)\\s+)*)([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*;",
      Pattern.UNICODE_CHARACTER_CLASS);
  private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  private RequiresOf() {
  }

  public static void main(String[] args) throws IOException {
    final String source = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    for (final String directive : directives(source)) {
      System.out.println(directive);
    }
  }

  static List<String> directives(String source) {
    final Matcher comments = COMMENT.matcher(source);
    final StringBuilder stripped = new StringBuilder();
    while (comments.find()) {
      final String text = comments.group();
      final String inner = text.startsWith("//") ? text.substring(2) : text.substring(2, text.length() - 2);
      comments.appendReplacement(stripped, inner.strip().equals("runtime") ? " runtime " : " ");
    }
    comments.appendTail(stripped);
    final TreeSet<String> found = new TreeSet<>();
    final Matcher m = REQUIRES.matcher(stripped);
    while (m.find()) {
      final String name = SPACES.matcher(m.group(2)).replaceAll("");
      if (name.startsWith("java.") || name.startsWith("jdk.")) {
        continue;
      }
      final TreeSet<String> modifiers = new TreeSet<>();
      for (final String modifier : SPACES.split(m.group(1).strip())) {
        if (!modifier.isEmpty()) {
          modifiers.add(modifier);
        }
      }
      final List<String> words = new ArrayList<>(modifiers);
      words.add(name);
      found.add(String.join(" ", words));
    }
    return new ArrayList<>(found);
  }
}
