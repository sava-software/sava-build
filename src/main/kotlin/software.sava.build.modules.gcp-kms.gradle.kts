// Opt-in module patches for projects that depend on the Google Cloud KMS client
// and its non-modular transitive dependencies, and the exclusion of the runtime
// dependencies that client never uses on the fleet's one path: TLS to the public
// endpoint with service-account or application-default credentials.
plugins {
  id("org.gradlex.extra-java-module-info")
}

// google-cloud-kms publishes a flattened POM (every transitive dependency listed as a
// direct one), so the edges below were rebuilt from each artifact's own POM and from the
// class references inside the jars (ravina, 2026-10-31). The exclusions sit on the
// dependencies of the client's family (kmsClientFamily below) rather than on a
// configuration: a dependency's excludes are an edge's, applied and published (POM and
// module metadata) only below that dependency, so a consumer of a library built with
// this convention inherits them on those edges and nothing else; a configuration's
// excludes would be published on every dependency of the variant. The list is a
// leaf-level denylist taken at google-cloud-kms 2.102.0: a transitive dependency a later
// release adds to the flat list arrives unexcluded, so re-read the list at a bump.
val kmsUnusedRuntimeDependencies = listOf(
  // The DirectPath bundle. In gax-grpc the one class that references io.grpc.alts is
  // InstantiatingGrpcChannelProvider, whose DirectPath branch runs only with Compute Engine
  // credentials on GCE, and grpc-googleapis is reached only by that branch's channel target
  // string; grpc-xds, the CEL jars, re2j and threeten-extra are declared only by that
  // bundle; conscrypt is declared by grpc-alts and, in compile scope, by gax-httpjson, which
  // loads it inside a LinkageError catch and falls back to the JDK's TLS without it.
  // gax-grpc also declares grpc-inprocess, the in-process channel, which no class in the
  // main jars references; gax-grpc's testlib jar (classifier testlib, the mock-server
  // helpers) does, so a dependency declared with a classifier keeps it (below).
  "io.grpc" to "grpc-googleapis",
  "io.grpc" to "grpc-xds",
  "io.grpc" to "grpc-alts",
  "io.grpc" to "grpc-services",
  "io.grpc" to "grpc-inprocess",
  "dev.cel" to "runtime",
  "dev.cel" to "common",
  "dev.cel" to "protobuf",
  "com.google.re2j" to "re2j",
  "org.conscrypt" to "conscrypt-openjdk-uber",
  "org.threeten" to "threeten-extra",
  // The legacy Apache transport, declared only by google-http-client, whose only classes
  // referencing org.apache.http are com.google.api.client.http.apache.*; every transport
  // in this graph, the auth library's token fetch included, is NetHttpTransport over
  // java.net. opencensus stays: HttpRequest calls OpenCensusUtils unconditionally.
  "org.apache.httpcomponents" to "httpclient",
  "org.apache.httpcomponents" to "httpcore",
  "commons-codec" to "commons-codec",
  // Two annotation-only jars whose signing keys the policy would otherwise have to carry
  // at exact versions: com.google.android:annotations (one lint annotation in grpc-core,
  // signed by a DSA-1024 key) and jsr305 (RUNTIME-retained javax.annotation.Nullable, which
  // the JVM skips when the type cannot be loaded; the last release of a finished project).
  // The other annotation jars in the graph sit under version-free publisher keys and stay.
  "com.google.android" to "annotations",
  "com.google.code.findbugs" to "jsr305",
  // api-common's javax.annotation-api, whose javax.annotation.Generated is SOURCE-retained
  // and so never in a class file. google-cloud-kms's flattened POM keeps it out (provided
  // scope, excluded on its api-common entry), but a direct edge to gax or api-common brings
  // it in compile scope, where it splits the javax.annotation package with jsr305 on a
  // module path and carries a signing key of its own.
  "javax.annotation" to "javax.annotation-api",
)

// grpc-xds bundles its own relocated copy of CEL and never loads the three dev.cel jars its
// POM declares, which are not modules and split a package between them. A module that wants
// DirectPath (attemptDirectPath on GCE, below) and so declares grpc-xds directly gets them off
// that edge here, so no
// module patch is needed for them; a graph that reaches dev.cel another way patches the
// three jars itself (they merge into dev.cel:common).
val xdsUnusedRuntimeDependencies = listOf(
  "dev.cel" to "runtime",
  "dev.cel" to "common",
  "dev.cel" to "protobuf",
  "org.threeten" to "threeten-extra",
)

// The dependencies that carry the exclusions: the client, and the libraries of its graph a
// module may require directly (a test's gax, a credential helper's auth or HTTP client); the
// java-module-dependencies plugin turns a `requires` into a dependency with no excludes of
// its own, which would bring the bundle back on that class path. A Gradle exclude is an
// edge's: a dependency declared outside this set keeps its own graph, so a module that
// requires io.grpc or io.opencensus directly gets those libraries' declared dependencies back
// on that class path, and adds the same excludes there if it wants them gone. The set names
// artifacts, not groups, so another Google Cloud client beside KMS keeps the DirectPath its
// channel provider attempts by default. A consumer that needs DirectPath for KMS itself
// (attemptDirectPath on GCE) declares io.grpc:grpc-alts, grpc-googleapis and grpc-xds
// directly, with versions (the catalog pins none), outside this set; they resolve with
// their own dependencies, grpc-xds without the dev.cel jars (above). The automatic-module
// patches kept below for excluded jars also map their module names for
// java-module-dependencies, so a module that requires one of them (jsr305, re2j, the
// android annotations) now needs a version for it, since no family edge supplies one.
val kmsClientFamily = setOf(
  "com.google.cloud:google-cloud-kms",
  "com.google.api:gax",
  "com.google.api:gax-grpc",
  "com.google.api:gax-httpjson",
  "com.google.api:api-common",
  "com.google.api.grpc:proto-google-cloud-kms-v1",
  "com.google.api.grpc:proto-google-common-protos",
  "com.google.api.grpc:proto-google-iam-v1",
  "com.google.auth:google-auth-library-oauth2-http",
  "com.google.auth:google-auth-library-credentials",
  "com.google.http-client:google-http-client",
  "com.google.http-client:google-http-client-gson",
)

// Applied from a plugins block, before anything resolves: a dependency in a configuration
// whose graph was already resolved cannot take an exclude, and Gradle says so.
// The two Google auth jars are automatic modules in their own right (Automatic-Module-Name in
// their manifests: com.google.auth.oauth2 and com.google.auth), so they need no patch below, but
// java-module-dependencies knows no coordinates for those names, its built-in list lacking them,
// and for a `requires` it cannot map it adds no dependency and only warns, so a test module that
// required them compiled against a transitive arrival and then failed its directives check
// (ravina, 2026-10-31). With the names mapped, the requires becomes the dependency it should be,
// which, being in the family, carries the excludes like the rest.
plugins.withId("org.gradlex.java-module-dependencies") {
  extensions.configure<org.gradlex.javamodule.dependencies.JavaModuleDependenciesExtension> {
    moduleNameToGA.put("com.google.auth.oauth2", "com.google.auth:google-auth-library-oauth2-http")
    moduleNameToGA.put("com.google.auth", "com.google.auth:google-auth-library-credentials")
  }
}

configurations.configureEach {
  dependencies.withType<ModuleDependency>().configureEach {
    if ("$group:$name" in kmsClientFamily) {
      val classified = artifacts.any { it.classifier != null }
      kmsUnusedRuntimeDependencies.forEach { (excludedGroup, excludedModule) ->
        if (!(classified && excludedModule == "grpc-inprocess")) {
          exclude(group = excludedGroup, module = excludedModule)
        }
      }
    } else if (group == "io.grpc" && name == "grpc-xds") {
      xdsUnusedRuntimeDependencies.forEach { (excludedGroup, excludedModule) ->
        exclude(group = excludedGroup, module = excludedModule)
      }
    }
  }
}

extraJavaModuleInfo {
  // https://mvnrepository.com/artifact/com.google.cloud/google-cloud-kms
  automaticModule("com.google.cloud:google-cloud-kms", "google.cloud.kms") {
    mergeJar("com.google.api.grpc:proto-google-cloud-kms-v1")
  }
  automaticModule("com.google.api:gax", "com.google.api.gax")
  automaticModule("com.google.api:gax-grpc", "com.google.api.gax_grpc") {
    mergeJar("com.google.api.grpc:proto-google-common-protos")
  }
  automaticModule("com.google.api:gax-httpjson", "com.google.api.gax_httpjson")
  automaticModule("com.google.api.grpc:proto-google-iam-v1", "com.google.api.grpc.proto_google_iam_v1")
  automaticModule("com.google.auto.value:auto-value-annotations", "com.google.auto_value_annotations")
  automaticModule("org.codehaus.mojo:animal-sniffer-annotations", "org.codehaus.mojo.animal_sniffer_annotations")
  automaticModule("io.grpc:grpc-context", "io.grpc.context")
  automaticModule("io.opencensus:opencensus-api", "io.opencensus.api")
  automaticModule("io.opencensus:opencensus-contrib-http-util", "io.opencensus.contrib_http_util")

  automaticModule("com.google.guava:listenablefuture", "com.google.guava.listenablefuture")

  // Patches for jars the exclusions above keep off every edge of the client's family. They
  // stay for a graph that reaches one of them another way, where it must still be a module.
  // The CEL jars have no patch any more: a mergeJar puts a version-less coordinate into the
  // plugin's merge configuration, which takes its version from the main graph and so cannot
  // resolve once the jar is excluded; `dependencies` prints it as FAILED and a resolver that
  // refuses an incomplete graph (sava-build's dependency-submission job) fails on it. A
  // consumer of a library built with this convention needs this convention, or no dev.cel
  // mergeJar of its own, before it takes that library's next release.
  automaticModule("com.google.code.findbugs:jsr305", "com.google.code.findbugs.jsr305")
  automaticModule("com.google.android:annotations", "com.google.android.annotations")
  automaticModule("com.google.re2j:re2j", "com.google.re2j")
}
