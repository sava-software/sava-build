package software.sava.build.guard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The conventional-commit grammar release-please 17.6.0 reads commits with. Scanner and
 * grammar are a function-for-function port of {@code @conventional-commits/parser} 0.4.1
 * (lib/scanner.js, lib/parser.js), kept faithful to which inputs make it throw and to the
 * message it throws with, since release-please drops a commit at debug level when that
 * parser throws. The rest is the part of release-please's own src/commit.ts the guard
 * needs: {@code splitMessages}, the header a parsed message gets, and its Release-As
 * footer. The golden corpus under the build's test resources was recorded from those
 * JavaScript originals.
 */
final class ConventionalCommits {

  private ConventionalCommits() {
  }

  /** A message whose parser throws, with the parser's own message. */
  static class ParseException extends Exception {
    private static final long serialVersionUID = 1L;

    ParseException(String message) {
      super(message);
    }
  }

  /**
   * A message this guard stops parsing at its own limit, where release-please's parser may
   * or may not read on: never taken for a grammar failure, since a footer re-read as a commit
   * that fails the grammar is "not a commit" to release-please, but one that fails the limit
   * may be a commit it reads, Release-As footer included.
   */
  static final class LimitException extends ParseException {
    private static final long serialVersionUID = 1L;

    LimitException(String message) {
      super(message);
    }
  }

  /** A parsed message: the header line release-please rebuilds, and its Release-As footer. */
  record Parsed(String header, String releaseAs) {
  }

  /**
   * Parses a message as release-please does, trimmed as JavaScript trims.
   *
   * @throws ParseException with the parser's message when release-please would drop it
   */
  static Parsed parse(String commitText) throws ParseException {
    final Node message = message(commitText);
    return new Parsed(header(message), releaseAs(message));
  }

  /** Whether the header line alone is read as a conventional commit header. */
  static boolean headerParses(String header) {
    try {
      message(header);
      return true;
    } catch (ParseException e) {
      return false;
    }
  }

  // The split release-please applies to one commit message before parsing each part
  // (src/commit.ts splitMessages): nested commit blocks, then the main message at the blank
  // line before each conventional header of the listed types.
  private static final Pattern CONVENTIONAL_SPLIT = Pattern.compile(
      "\r?\n\r?\n(?=(?:feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(?:\\([^\n\r  ]*?\\))?: )");

  static List<String> splitMessages(String message) {
    final List<String> parts = new ArrayList<>(Arrays.asList(message.split(Pattern.quote("BEGIN_NESTED_COMMIT"), -1)));
    final List<String> messages = new ArrayList<>();
    messages.add(parts.remove(0));
    for (final String part : parts) {
      final String[] split = part.split(Pattern.quote("END_NESTED_COMMIT"), -1);
      messages.add(split[0]);
      messages.set(0, messages.get(0) + String.join("END_NESTED_COMMIT", Arrays.copyOfRange(split, 1, split.length)));
    }
    final List<String> result = new ArrayList<>();
    for (final String conventional : CONVENTIONAL_SPLIT.split(messages.get(0), -1)) {
      if (!conventional.isEmpty()) {
        result.add(conventional);
      }
    }
    result.addAll(messages.subList(1, messages.size()));
    return result;
  }

  /** JavaScript's String.prototype.trim: WhiteSpace and LineTerminator code points. */
  static String jsTrim(String text) {
    int start = 0;
    int end = text.length();
    while (start < end && isJsSpace(text.charAt(start))) {
      start++;
    }
    while (end > start && isJsSpace(text.charAt(end - 1))) {
      end--;
    }
    return text.substring(start, end);
  }

  static boolean isJsSpace(char c) {
    return switch (c) {
      case '\t', '\n', '\u000B', '\f', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ',
          '　', '﻿' -> true;
      default -> c >= ' ' && c <= ' ';
    };
  }

  /** JavaScript's {@code \s} class, for messages that are reported on one line. */
  static final String JS_SPACE_CLASS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";

  // ---- scanner.js

  private static final String CR = "\r";
  private static final String LF = "\n";

  private static boolean isWhitespace(String token) {
    return token.equals("﻿") || token.equals("\t") || token.equals("\u000B") || token.equals("\f")
        || token.equals(" ") || token.equals(" ");
  }

  private static boolean isNewline(String token) {
    return !token.isEmpty() && (token.charAt(0) == '\r' || token.charAt(0) == '\n');
  }

  private static boolean isParens(String token) {
    return token.equals("(") || token.equals(")");
  }

  record Position(int line, int column, int offset) {
  }

  /** A node of the parse tree: a value node holds text, a container node holds children. */
  static final class Node {
    final String type;
    String value;
    final List<Node> children;
    final Position start;
    Position end;

    private Node(String type, String value, List<Node> children, Position start) {
      this.type = type;
      this.value = value;
      this.children = children;
      this.start = start;
    }
  }

  /** What a grammar function returns when it does not match: the error the scanner built. */
  record Err(String message) {
  }

  static final class Scanner {
    private final String text;
    private int line = 1;
    private int column = 1;
    private int offset = 0;

    Scanner(String text) {
      this.text = text;
    }

    boolean eof() {
      return offset >= text.length();
    }

    private String charAt(int index) {
      return index < text.length() ? String.valueOf(text.charAt(index)) : "";
    }

    String peek() {
      String token = charAt(offset);
      // Consume <CR>? <LF>
      if (token.equals(CR) && charAt(offset + 1).equals(LF)) {
        token += LF;
      }
      return token;
    }

    String next() {
      return consume(peek());
    }

    String next(int n) {
      return consume(text.substring(offset, Math.min(text.length(), offset + n)));
    }

    private String consume(String token) {
      offset += token.length();
      column += token.length();
      if (isNewline(token)) {
        line++;
        column = 1;
      }
      return token;
    }

    boolean peekLiteral(String literal) {
      return text.startsWith(literal, offset);
    }

    Position position() {
      return new Position(line, column, offset);
    }

    void rewind(Position position) {
      line = position.line();
      column = position.column();
      offset = position.offset();
    }

    Node enterValue(String type) {
      return new Node(type, "", null, position());
    }

    Node enterChildren(String type) {
      return new Node(type, null, new ArrayList<>(), position());
    }

    Node exit(Node node) {
      node.end = position();
      return node;
    }

    Err abort(Node node) {
      return abort(node, null);
    }

    /** The error for the token at the scanner, then the scanner back at the node's start. */
    Err abort(Node node, List<String> expectedTokens) {
      final String position = line + ":" + column;
      final String validTokens = expectedTokens != null
          ? String.join(", ", expectedTokens.stream().filter(Objects::nonNull).filter(t -> !t.isEmpty()).toList())
          : "<" + node.type + ">";
      final Err error = eof()
          ? new Err("unexpected token EOF at " + position + ", valid tokens [" + validTokens + "]")
          : new Err("unexpected token '" + peek() + "' at " + position + ", valid tokens [" + validTokens + "]");
      rewind(node.start);
      return error;
    }
  }

  // ---- parser.js. Each function returns a Node or an Err, exactly where the original
  // returns a node or an Error, and throws exactly where the original throws.

  // The grammar recurses once per line of body text (a run of blank lines is one newline
  // token, and a footer's continuation lines are read in a loop). release-please's parser
  // does the same on Node's stack and fails past about 5,600 such lines, dropping the
  // commit; the JVM's main thread takes about 6,400. This port stops well below both, so
  // a body that deep is refused by name, as this guard's own limit, and one the JVM's stack
  // cannot take after all is reported the same way.
  private static final int MAX_BODY_DEPTH = 2_000;

  private static Node message(String commitText) throws ParseException {
    try {
      return parseMessage(commitText);
    } catch (StackOverflowError e) {
      throw new LimitException("too deep for this guard's parser (" + countLines(commitText)
          + " lines); whether release-please's parser reads it is not known");
    }
  }

  private static int countLines(String text) {
    int lines = 1;
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
        lines++;
      }
    }
    return lines;
  }

  private static Node parseMessage(String commitText) throws ParseException {
    final Scanner scanner = new Scanner(jsTrim(commitText));
    final Node node = scanner.enterChildren("message");

    // <summary> ...
    final Object s = summary(scanner);
    if (s instanceof Err err) {
      throw new ParseException(err.message());
    }
    node.children.add((Node) s);
    if (scanner.eof()) {
      return scanner.exit(node);
    }

    Object nl;
    Node b;
    // ... <newline>* <body> ...
    nl = newline(scanner);
    if (nl instanceof Err err) {
      throw new ParseException(err.message());
    }
    node.children.add((Node) nl);
    final Object bodyResult = body(scanner, 1);
    if (bodyResult instanceof Err) {
      b = null;
    } else {
      b = (Node) bodyResult;
      node.children.add(b);
    }
    if (scanner.eof()) {
      return scanner.exit(node);
    }

    //  ... <newline>* <footer>+
    if (b != null) {
      nl = newline(scanner);
      if (nl instanceof Err err) {
        throw new ParseException(err.message());
      }
      node.children.add((Node) nl);
    }
    while (!scanner.eof()) {
      final Object f = footer(scanner);
      if (f instanceof Err) {
        break;
      }
      node.children.add((Node) f);
      nl = newline(scanner);
      if (nl instanceof Err) {
        break;
      }
      node.children.add((Node) nl);
    }
    return scanner.exit(node);
  }

  /*
   * <summary>      ::= <type> "(" <scope> ")" ["!"] ":" <whitespace>* <text>
   *                 |  <type> ["!"] ":" <whitespace>* <text>
   */
  private static Object summary(Scanner scanner) throws ParseException {
    final Node node = scanner.enterChildren("summary");

    // <type> ...
    final Object t = type(scanner);
    if (t instanceof Err) {
      return t;
    }
    node.children.add((Node) t);

    // ... "(" <scope> ")" ...
    Object s = scope(scanner);
    if (s instanceof Err) {
      s = null;
    } else {
      node.children.add((Node) s);
    }

    // ... ["!"] ...
    Object b = breakingChange(scanner, true);
    if (b instanceof Err) {
      b = null;
    } else {
      node.children.add((Node) b);
    }

    // ... ":" ...
    final Object sep = separator(scanner);
    if (sep instanceof Err) {
      return scanner.abort(node, Arrays.asList(s == null ? "(" : null, b == null ? "!" : null, ":"));
    }
    node.children.add((Node) sep);

    // ... <whitespace>* ...
    final Object ws = whitespace(scanner);
    if (!(ws instanceof Err)) {
      node.children.add((Node) ws);
    }

    // ... <text>
    node.children.add(text(scanner));
    return scanner.exit(node);
  }

  /*
   * <type>         ::= 1*<any UTF8-octets except newline or parens or ["!"] ":" or whitespace>
   */
  private static Object type(Scanner scanner) {
    final Node node = scanner.enterValue("type");
    while (!scanner.eof()) {
      final String token = scanner.peek();
      if (isParens(token) || isWhitespace(token) || isNewline(token) || token.equals("!") || token.equals(":")) {
        break;
      }
      node.value += scanner.next();
    }
    if (node.value.isEmpty()) {
      return scanner.abort(node);
    }
    return scanner.exit(node);
  }

  /*
   * <text>         ::= 1*<any UTF8-octets except newline>
   */
  private static Node text(Scanner scanner) {
    final Node node = scanner.enterValue("text");
    while (!scanner.eof()) {
      final String token = scanner.peek();
      if (isNewline(token)) {
        break;
      }
      node.value += scanner.next();
    }
    return scanner.exit(node);
  }

  /*
   * "(" <scope> ")"        ::= 1*<any UTF8-octets except newline or parens>
   */
  private static Object scope(Scanner scanner) throws ParseException {
    if (!scanner.peek().equals("(")) {
      return scanner.abort(scanner.enterValue("scope"));
    }
    scanner.next();

    final Node node = scanner.enterValue("scope");

    while (!scanner.eof()) {
      final String token = scanner.peek();
      if (isParens(token) || isNewline(token)) {
        break;
      }
      node.value += scanner.next();
    }

    if (!scanner.peek().equals(")")) {
      throw new ParseException(scanner.abort(node, List.of(")")).message());
    }
    scanner.exit(node);
    scanner.next();

    if (node.value.isEmpty()) {
      return scanner.abort(node);
    }
    return node;
  }

  /*
   * <body>          ::= [<any body-text except pre-footer>], <newline>, <body>*
   *                  | [<any body-text except pre-footer>]
   */
  private static Object body(Scanner scanner, int depth) throws ParseException {
    final Node node = scanner.enterChildren("body");

    // check except <pre-footer> condition:
    final Object pf = preFooter(scanner);
    if (!(pf instanceof Err)) {
      return scanner.abort(node);
    }
    // Body text goes on past the guard's depth, in the message or in a footer re-read as a
    // commit; what release-please makes of it is not known here.
    if (depth > MAX_BODY_DEPTH) {
      throw new LimitException("more than " + MAX_BODY_DEPTH + " lines of body text (in the message, or in a footer "
          + "release-please re-reads as a commit), which this guard does not parse; release-please's parser fails on "
          + "a message a few thousand lines long and drops the commit");
    }

    // ["BREAKING CHANGE", ":", <whitespace>*]
    final Object b = breakingChange(scanner, false);
    if (!(b instanceof Err) && scanner.peek().equals(":")) {
      node.children.add((Node) b);
      node.children.add((Node) separator(scanner));
      final Object w = whitespace(scanner);
      if (!(w instanceof Err)) {
        node.children.add((Node) w);
      }
    }

    // [<text>]
    node.children.add(text(scanner));
    // <newline>, <body>*
    final Object nl = newline(scanner);
    if (!(nl instanceof Err)) {
      final Object rest = body(scanner, depth + 1);
      if (rest instanceof Err) {
        scanner.abort((Node) nl);
      } else {
        node.children.add((Node) nl);
        node.children.addAll(((Node) rest).children);
      }
    }
    return scanner.exit(node);
  }

  /*
   * <newline>*, <footer>+
   */
  private static Object preFooter(Scanner scanner) throws ParseException {
    final Node node = scanner.enterChildren("pre-footer");
    while (!scanner.eof()) {
      newline(scanner);
      final Object f = footer(scanner);
      if (f instanceof Err) {
        return scanner.abort(node);
      }
    }
    return scanner.exit(node);
  }

  /*
   * <footer>       ::= <token> <separator> <whitespace>* <value>
   */
  private static Object footer(Scanner scanner) throws ParseException {
    final Node node = scanner.enterChildren("footer");
    // <token>
    final Object t = token(scanner);
    if (t instanceof Err) {
      return t;
    }
    node.children.add((Node) t);

    // <separator>
    final Object s = separator(scanner);
    if (s instanceof Err) {
      scanner.abort(node);
      return s;
    }
    node.children.add((Node) s);

    // <whitespace>*
    final Object ws = whitespace(scanner);
    if (!(ws instanceof Err)) {
      node.children.add((Node) ws);
    }

    // <value>
    node.children.add(value(scanner));

    return scanner.exit(node);
  }

  /*
   * <token>        ::= <breaking-change>
   *                 |  <type>, "(" <scope> ")", ["!"]
   *                 |  <type>
   */
  private static Object token(Scanner scanner) throws ParseException {
    final Node node = scanner.enterChildren("token");
    // "BREAKING CHANGE"
    final Object b = breakingChange(scanner, true);
    if (b instanceof Err) {
      scanner.abort(node);
    } else {
      node.children.add((Node) b);
      return scanner.exit(node);
    }

    // <type>
    final Object t = type(scanner);
    if (t instanceof Err) {
      return t;
    }
    node.children.add((Node) t);
    // "(" <scope> ")"
    final Object s = scope(scanner);
    if (!(s instanceof Err)) {
      node.children.add((Node) s);
    }
    // ["!"]
    final Object bang = breakingChange(scanner, true);
    if (!(bang instanceof Err)) {
      node.children.add((Node) bang);
    }
    return scanner.exit(node);
  }

  /*
   * <breaking-change> ::= "!" | "BREAKING CHANGE" | "BREAKING-CHANGE"
   *
   * Note: "!" is only allowed in <footer> and <summary>, not <body>.
   */
  private static Object breakingChange(Scanner scanner, boolean allowBang) {
    final Node node = scanner.enterValue("breaking-change");
    if (scanner.peek().equals("!") && allowBang) {
      node.value = scanner.next();
    } else if (scanner.peekLiteral("BREAKING CHANGE") || scanner.peekLiteral("BREAKING-CHANGE")) {
      node.value = scanner.next("BREAKING CHANGE".length());
    }
    if (node.value.isEmpty()) {
      return scanner.abort(node, List.of("BREAKING CHANGE"));
    }
    return scanner.exit(node);
  }

  /*
   * <value>        ::= <text> <continuation>*
   *                 |  <text>
   */
  private static Node value(Scanner scanner) {
    final Node node = scanner.enterChildren("value");
    node.children.add(text(scanner));
    // <continuation>*
    Object c;
    while (!((c = continuation(scanner)) instanceof Err)) {
      node.children.add((Node) c);
    }
    return scanner.exit(node);
  }

  /*
   * <newline> <whitespace> <text>
   */
  private static Object continuation(Scanner scanner) {
    final Node node = scanner.enterChildren("continuation");
    // <newline>
    final Object nl = newline(scanner);
    if (nl instanceof Err) {
      return nl;
    }
    node.children.add((Node) nl);

    // <whitespace> <text>
    final Object ws = whitespace(scanner);
    if (ws instanceof Err) {
      scanner.abort(node);
      return ws;
    }
    node.children.add((Node) ws);
    node.children.add(text(scanner));

    return scanner.exit(node);
  }

  /*
   * <separator>    ::= ":" | " #"
   */
  private static Object separator(Scanner scanner) {
    final Node node = scanner.enterValue("separator");
    // ':'
    if (scanner.peek().equals(":")) {
      node.value = scanner.next();
      return scanner.exit(node);
    }

    // ' #'
    if (scanner.peek().equals(" ")) {
      scanner.next();
      if (scanner.peek().equals("#")) {
        scanner.next();
        node.value = " #";
        return scanner.exit(node);
      }
      return scanner.abort(node);
    }

    return scanner.abort(node);
  }

  /*
   * <whitespace>+   ::= <ZWNBSP> | <TAB> | <VT> | <FF> | <SP> | <NBSP> | <USP>
   */
  private static Object whitespace(Scanner scanner) {
    final Node node = scanner.enterValue("whitespace");
    while (isWhitespace(scanner.peek())) {
      node.value += scanner.next();
    }
    if (node.value.isEmpty()) {
      return scanner.abort(node, List.of(" "));
    }
    return scanner.exit(node);
  }

  /*
   * <newline>+       ::= [<CR>], <LF>
   */
  private static Object newline(Scanner scanner) {
    final Node node = scanner.enterValue("newline");
    while (isNewline(scanner.peek())) {
      node.value += scanner.next();
    }
    if (node.value.isEmpty()) {
      return scanner.abort(node, List.of("<CR><LF>", "<LF>"));
    }
    return scanner.exit(node);
  }

  // ---- src/commit.ts toConventionalChangelogFormat, the two fields the guard reads.

  /** The header release-please rebuilds from the summary: type, (scope), !, and ": subject". */
  private static String header(Node message) {
    final StringBuilder header = new StringBuilder();
    for (final Node node : message.children) {
      if (!node.type.equals("summary")) {
        continue;
      }
      for (final Node part : node.children) {
        switch (part.type) {
          case "type" -> header.append(part.value);
          case "scope" -> header.append('(').append(part.value).append(')');
          case "breaking-change" -> header.append('!');
          case "text" -> header.append(": ").append(part.value);
          default -> {
          }
        }
      }
    }
    return header.toString();
  }

  /**
   * The RELEASE AS note release-please finds first for a message, in the order its
   * toConventionalChangelogFormat lists commits: every footer with a type token is re-read
   * as a commit of its own, continuation lines included (so a Release-As on a continuation
   * line of another footer counts), and those commits come before the message's own, whose
   * note is the first footer whose token is release-as in any case, read as the last text
   * node under that footer (a continuation line replaces the first line).
   */
  private static String releaseAs(Node message) throws LimitException {
    for (final Node footer : footers(message)) {
      if (child(footer.children.get(0), "type") == null) {
        continue;
      }
      final StringBuilder footerText = new StringBuilder();
      appendFooterText(footer, footerText);
      try {
        final String nested = releaseAs(message(footerText.toString()));
        if (nested != null) {
          return nested;
        }
      } catch (LimitException e) {
        // this guard's limit, not the grammar: whether release-please reads a commit here, a
        // Release-As footer with it, is not known, so the message is refused as a whole
        throw e;
      } catch (ParseException e) {
        // the footer does not resemble a commit
      }
    }
    for (final Node footer : footers(message)) {
      final Node type = child(footer.children.get(0), "type");
      if (type == null || !type.value.toLowerCase(Locale.ROOT).equals("release-as")) {
        continue;
      }
      String releaseAs = "";
      for (final Node text : textNodes(footer)) {
        releaseAs = text.value;
      }
      return releaseAs;
    }
    return null;
  }

  private static List<Node> footers(Node message) {
    return message.children.stream().filter(node -> node.type.equals("footer")).toList();
  }

  private static Node child(Node node, String type) {
    for (final Node child : node.children) {
      if (child.type.equals(type)) {
        return child;
      }
    }
    return null;
  }

  // The footer as release-please re-reads it: its type, scope, bang, separator (left out
  // when it is " #"), text and line ends, in document order, and none of its whitespace.
  private static void appendFooterText(Node node, StringBuilder out) {
    switch (node.type) {
      case "scope" -> out.append('(').append(node.value).append(')');
      case "separator" -> {
        if (!node.value.contains("#")) {
          out.append(node.value).append(' ');
        }
      }
      case "type", "breaking-change", "text", "newline" -> out.append(node.value);
      default -> {
      }
    }
    if (node.children != null) {
      for (final Node child : node.children) {
        appendFooterText(child, out);
      }
    }
  }

  private static List<Node> textNodes(Node node) {
    final List<Node> texts = new ArrayList<>();
    collectTexts(node, texts);
    return texts;
  }

  private static void collectTexts(Node node, List<Node> texts) {
    if (node.type.equals("text")) {
      texts.add(node);
    }
    if (node.children != null) {
      for (final Node child : node.children) {
        collectTexts(child, texts);
      }
    }
  }
}
