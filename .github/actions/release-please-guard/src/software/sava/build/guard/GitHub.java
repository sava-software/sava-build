package software.sava.build.guard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The GitHub API reads the guard makes, and nothing else: REST GETs, and one GraphQL query,
 * which is sent as a POST but only queries. A read that fails for any reason but a missing
 * resource is a {@link ReadException}, which a re-run of the job may cure; a missing resource
 * is a {@link NotFoundException}, which names something the repository does not have.
 */
final class GitHub {

  /** A pull request a commit belongs to, as release-please reads it with the commit. */
  record AssociatedPullRequest(long number, String body, String mergeCommitOid) {
  }

  static class ReadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ReadException(String message) {
      super(message);
    }
  }

  static final class NotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotFoundException(String message) {
      super(message);
    }
  }

  private final HttpClient client;
  private final String apiUrl;
  private final String token;
  private final String repository;

  GitHub(String apiUrl, String token, String repository) {
    // No redirect is followed: the token would travel with it, and no read here redirects.
    this.client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(30))
        .build();
    this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
    this.token = token;
    this.repository = repository;
  }

  String repository() {
    return repository;
  }

  /** GET, with the given Accept header; the body on 200. */
  String get(String query, String accept) {
    return send(query, accept, null);
  }

  private String send(String query, String accept, String postBody) {
    final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiUrl + "/" + query))
        .timeout(Duration.ofSeconds(60))
        .header("Accept", accept)
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "sava-build release-please guard");
    if (!token.isEmpty()) {
      request.header("Authorization", "Bearer " + token);
    }
    if (postBody != null) {
      request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(postBody));
    } else {
      request.GET();
    }
    final String call = (postBody != null ? "POST " : "GET ") + query;
    final HttpResponse<String> response;
    try {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new ReadException(call + " failed: " + e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ReadException(call + " was interrupted");
    }
    final int status = response.statusCode();
    if (status == 200) {
      return response.body();
    }
    final String detail = "HTTP " + status + ": " + firstLine(response.body());
    if (status == 404) {
      throw new NotFoundException(detail);
    }
    throw new ReadException(call + " failed with " + detail);
  }

  private static String firstLine(String body) {
    final String line = body.lines().findFirst().orElse("").trim();
    return line.length() > 200 ? line.substring(0, 200) : line;
  }

  Object json(String query) {
    return Json.parse(get(query, "application/vnd.github+json"));
  }

  /**
   * One page of the repository's pull requests, as release-please lists them without files
   * (its pullRequestIteratorWithoutFiles): the REST API, by update, newest first.
   */
  List<Map<String, Object>> pulls(String state, String base, int page) {
    final List<Map<String, Object>> pulls = new ArrayList<>();
    final String query = "repos/" + repository + "/pulls?state=" + state + "&base=" + encode(base)
        + "&sort=updated&direction=desc&per_page=100&page=" + page;
    for (final Object pull : Json.asArray(json(query), "the pull request listing")) {
      pulls.add(Json.asObject(pull, "a listed pull request"));
    }
    return pulls;
  }

  /**
   * The pull requests each commit belongs to, the first ten as release-please reads them beside
   * the commit (its history query's associatedPullRequests), in one GraphQL query per hundred
   * commits. A commit pushed directly belongs to none.
   */
  Map<String, List<AssociatedPullRequest>> associatedPullRequests(List<String> shas) {
    final Map<String, List<AssociatedPullRequest>> result = new HashMap<>();
    final String[] ownerAndRepo = repository.split("/", 2);
    if (ownerAndRepo.length != 2) {
      throw new ReadException("GITHUB_REPOSITORY is not owner/repo: " + repository);
    }
    for (int from = 0; from < shas.size(); from += 100) {
      final List<String> batch = shas.subList(from, Math.min(shas.size(), from + 100));
      final StringBuilder query = new StringBuilder("query { repository(owner: ").append(Json.string(ownerAndRepo[0]))
          .append(", name: ").append(Json.string(ownerAndRepo[1])).append(") {");
      for (int i = 0; i < batch.size(); i++) {
        query.append(" c").append(i).append(": object(oid: ").append(Json.string(batch.get(i)))
            .append(") { ... on Commit { associatedPullRequests(first: 10) { nodes { number body mergeCommit { oid } } } } }");
      }
      query.append(" } }");
      final Map<String, Object> response = Json.asObject(
          Json.parse(send("graphql", "application/json", "{\"query\":" + Json.string(query.toString()) + "}")),
          "the GraphQL response");
      if (response.get("errors") instanceof List<?> errors && !errors.isEmpty()) {
        throw new ReadException("the GraphQL query for the commits' pull requests failed: " + firstLine(errors.toString()));
      }
      final Map<String, Object> repo = Json.asObject(Json.asObject(response.get("data"), "the GraphQL data")
          .get("repository"), "the repository");
      for (int i = 0; i < batch.size(); i++) {
        // Every commit asked about is on the pushed branch, so one GitHub does not know means
        // the checkout and GITHUB_REPOSITORY disagree: nothing is read as pushed directly.
        if (!(repo.get("c" + i) instanceof Map<?, ?> commit)) {
          throw new IllegalStateException("GitHub does not know commit " + batch.get(i) + " in " + repository
              + ": the checkout and GITHUB_REPOSITORY disagree, or the commit is not pushed");
        }
        final List<AssociatedPullRequest> pulls = new ArrayList<>();
        final Object associated = Json.asObject(commit, "a commit").get("associatedPullRequests");
        for (final Object node : Json.asArray(Json.asObject(associated, "associatedPullRequests").get("nodes"), "nodes")) {
          final Map<String, Object> pull = Json.asObject(node, "a pull request");
          final Object merge = pull.get("mergeCommit");
          pulls.add(new AssociatedPullRequest(
              ((Number) pull.get("number")).longValue(),
              pull.get("body") instanceof String body ? body : "",
              merge instanceof Map<?, ?> m && Json.asObject(m, "mergeCommit").get("oid") instanceof String oid ? oid : null));
        }
        result.put(batch.get(i), pulls);
      }
    }
    return result;
  }

  /** A file's text at a ref; NotFoundException when the ref or the file does not exist there. */
  String fileAt(String path, String ref) {
    final StringBuilder encodedPath = new StringBuilder();
    for (final String segment : path.split("/", -1)) {
      if (encodedPath.length() > 0) {
        encodedPath.append('/');
      }
      encodedPath.append(encode(segment));
    }
    return get("repos/" + repository + "/contents/" + encodedPath + "?ref=" + encode(ref),
        "application/vnd.github.raw+json");
  }

  /** The tag of the latest GitHub release, or null when there is none. */
  String latestReleaseTag() {
    try {
      final Object tag = Json.asObject(json("repos/" + repository + "/releases/latest"), "the latest release")
          .get("tag_name");
      return tag instanceof String name && !name.isEmpty() ? name : null;
    } catch (NotFoundException e) {
      return null;
    }
  }

  /** JavaScript's encodeURIComponent. */
  static String encode(String value) {
    final StringBuilder out = new StringBuilder();
    for (final byte b : value.getBytes(StandardCharsets.UTF_8)) {
      final char c = (char) (b & 0xff);
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || "-_.!~*'()".indexOf(c) >= 0) {
        out.append(c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xf, 16)))
            .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
      }
    }
    return out.toString();
  }
}
