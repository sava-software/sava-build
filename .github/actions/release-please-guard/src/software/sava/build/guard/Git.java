package software.sava.build.guard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The git reads the guard makes in the checkout it runs in. */
final class Git {

  /** A git command that failed, with what it wrote to stderr. */
  static final class GitException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GitException(String message) {
      super(message);
    }
  }

  /** A commit in the range: its full sha and its raw message. */
  record Commit(String sha, String message) {
  }

  private final Path directory;

  Git(Path directory) {
    this.directory = directory;
  }

  String run(String... arguments) {
    final List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(Arrays.asList(arguments));
    try {
      final Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
      final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
      final Thread drain = new Thread(() -> {
        try (InputStream errors = process.getErrorStream()) {
          errors.transferTo(stderr);
        } catch (IOException e) {
          // the exit code reports the failure
        }
      });
      drain.start();
      final byte[] stdout;
      try (InputStream output = process.getInputStream()) {
        stdout = output.readAllBytes();
      }
      final int exit = process.waitFor();
      drain.join();
      if (exit != 0) {
        throw new GitException("git " + String.join(" ", arguments) + " exited " + exit + ": "
            + stderr.toString(StandardCharsets.UTF_8).trim());
      }
      return new String(stdout, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new GitException("git " + String.join(" ", arguments) + " could not run: " + e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitException("git " + String.join(" ", arguments) + " was interrupted");
    }
  }

  boolean tagExists(String tag) {
    try {
      run("rev-parse", "-q", "--verify", "refs/tags/" + tag + "^{commit}");
      return true;
    } catch (GitException e) {
      return false;
    }
  }

  /** The nearest tag matching the glob that the commit descends from, or null. */
  String nearestTag(String glob, String commit) {
    try {
      final String tag = run("describe", "--tags", "--abbrev=0", "--match=" + glob, commit).trim();
      return tag.isEmpty() ? null : tag;
    } catch (GitException e) {
      return null;
    }
  }

  /** Whether the revision names a commit the checkout has. */
  boolean commitExists(String revision) {
    try {
      run("rev-parse", "-q", "--verify", revision + "^{commit}");
      return true;
    } catch (GitException e) {
      return false;
    }
  }

  /** A file's text at a revision, by its path from the repository's top level. */
  String show(String revision, String path) {
    return run("show", revision + ":" + path);
  }

  /** The commits of the range, newest first, as rev-list orders them, at most max of them. */
  List<Commit> log(int max, String range) {
    final String listing = run("-c", "log.showSignature=false", "log", "-z", "--format=%H%n%B",
        "--max-count=" + max, range);
    final List<Commit> commits = new ArrayList<>();
    for (final String record : listing.split("\0")) {
      if (record.isEmpty()) {
        continue;
      }
      final int newline = record.indexOf('\n');
      if (newline < 0) {
        commits.add(new Commit(record, ""));
      } else {
        commits.add(new Commit(record.substring(0, newline), record.substring(newline + 1)));
      }
    }
    return commits;
  }

  /**
   * The files GitHub lists for a commit: against the first parent, as release-please reads
   * them. NUL-separated, so no path is quoted whatever bytes it holds.
   */
  List<String> changedFiles(String sha) {
    final String[] parents = run("rev-list", "--parents", "-n", "1", sha).trim().split("\\s+");
    final String listing = parents.length <= 1
        ? run("diff-tree", "--root", "-r", "--no-commit-id", "--name-only", "-z", sha)
        : run("diff", "--name-only", "-z", parents[1], sha);
    final List<String> files = new ArrayList<>();
    for (final String file : listing.split("\0")) {
      if (!file.isEmpty()) {
        files.add(file);
      }
    }
    return files;
  }

  Path topLevel() {
    return Path.of(run("rev-parse", "--show-toplevel").trim());
  }
}
