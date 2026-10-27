package software.sava.build.jlink;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.Configuration;
import java.lang.module.FindException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.module.ResolutionException;
import java.lang.module.ResolvedModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reports what a linked image does not hold: the module-path modules jlink left out, and the
 * modules {@code --bind-services} would have added. JlinkImageTask runs it on the JDK that ran
 * jlink, not in the Gradle daemon, because a module descriptor compiled for a newer release than
 * the daemon's JVM cannot be read there, and the module path is read the way the toolchain reads
 * it (automatic module names, multi-release jars).
 *
 * <p>Arguments: the image directory; {@code --limit-modules name,...} when jlink was limited, so
 * the analysis sees what jlink saw; {@code --bind-services} when jlink bound services, which
 * skips the binding analysis (a bound image already holds everything it would predict, and the
 * analysis can only report an error about a question the build has answered); then the
 * module-path entries jlink was given. Output, one tab-separated record per line:
 * <pre>
 *   left-out  name  explicit|automatic|unlinkable  provider|other     a module-path module not in the image
 *   bindable  name  system|module-path             provider|required  what service binding would have added
 *   refused   name  automatic|unlinkable|no-jmods                     a module binding reaches that jlink cannot link
 *   error     message                                                 the binding analysis could not run
 * </pre>
 * An unlinkable module is an explicit one whose mandatory requires reach an automatic module,
 * which jlink refuses to link. A refused record says that {@code --bind-services} itself would
 * fail: jlink binds over everything it observes and then refuses the automatic modules among the
 * result, so a provider of a used service that is automatic, or requires an automatic module,
 * fails the link once it is reached; and binding reaches jdk.jlink through java.base's
 * ToolProvider service, which a JDK without jmods (Temurin 24 and later) cannot link.
 * No output when the image has no release file (the release-info plugin was disabled). Exit 2 on
 * misuse and 1, with the cause on stderr, when the report itself fails; everything else is
 * reported on stdout and exits 0.
 */
public final class LinkReport {

  private LinkReport() {}

  /**
   * Prints the report for an image.
   *
   * @param args the image directory, the {@code --bind-services} and {@code --limit-modules}
   *             options jlink was given, then the module-path entries
   */
  public static void main(String... args) {
    if (args.length < 1) {
      usage();
    }
    try {
      Set<String> linked = linkedModules(Path.of(args[0]));
      if (linked == null) {
        return;
      }
      boolean bindServices = false;
      Set<String> limit = new TreeSet<>();
      List<Path> entries = new ArrayList<>();
      for (int i = 1; i < args.length; i++) {
        switch (args[i]) {
          case "--bind-services" -> bindServices = true;
          case "--limit-modules" -> {
            if (++i == args.length) {
              usage();
            }
            limit.addAll(Arrays.asList(args[i].split(",")));
          }
          default -> entries.add(Path.of(args[i]));
        }
      }
      for (String line : report(linked, entries, limit, bindServices)) {
        System.out.println(line);
      }
    } catch (IOException | RuntimeException e) {
      System.err.println(e);
      System.exit(1);
    }
  }

  private static void usage() {
    System.err.println("Usage: LinkReport <image directory> [--bind-services] [--limit-modules name,...] [module path entry...]");
    System.exit(2);
  }

  /** The MODULES of the image's release file, or null without a release file or MODULES line. */
  static Set<String> linkedModules(Path image) throws IOException {
    Path release = image.resolve("release");
    if (!Files.isRegularFile(release)) {
      return null;
    }
    try (Stream<String> lines = Files.lines(release)) {
      Optional<String> modules = lines.filter(line -> line.startsWith("MODULES=")).findFirst();
      if (modules.isEmpty()) {
        return null;
      }
      String value = modules.get().substring("MODULES=".length());
      if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
        value = value.substring(1, value.length() - 1);
      }
      return Arrays.stream(value.trim().split("\\s+")).filter(name -> !name.isEmpty())
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  /**
   * The report lines for an image holding {@code linked}, linked from {@code entries} with
   * {@code limit} as jlink's --limit-modules set (empty for none), with or without service binding.
   */
  static List<String> report(Set<String> linked, List<Path> entries, Set<String> limit, boolean bindServices) {
    List<String> lines = new ArrayList<>();
    ModuleFinder system = ModuleFinder.ofSystem();
    Map<String, ModuleDescriptor> onModulePath = new TreeMap<>();
    ModulePath modulePath;
    try {
      modulePath = modulePathFinder(entries);
      for (ModuleReference reference : modulePath.modules().findAll()) {
        onModulePath.put(reference.descriptor().name(), reference.descriptor());
      }
      // A module path may carry JDK jmods (a JavaFX jmods directory; a target JDK's when
      // cross-linking, which this report does not follow: it classifies and resolves against the
      // JDK it runs on). The ones that JDK has are the system's modules, not the application's.
      for (ModuleReference reference : modulePath.jmods().findAll()) {
        String name = reference.descriptor().name();
        if (system.find(name).isEmpty()) {
          onModulePath.putIfAbsent(name, reference.descriptor());
        }
      }
    } catch (FindException | UncheckedIOException e) {
      lines.add("error\t" + e.getMessage());
      return lines;
    }
    // jlink with the JDK's jmods searches the application module path before them, so a jar named
    // like a system module shadows it; a link from a JDK without jmods puts the system's modules
    // first, which this report does not follow. Jmods on the module path come after the system's
    // modules: the readers here cannot be opened, so a JDK jmod among them would fail java.base's
    // hash check.
    ModuleFinder finder = ModuleFinder.compose(modulePath.modules(), system, modulePath.jmods());
    // A property of the module path, so it labels the left-out modules whether or not the binding
    // analysis runs or gets through.
    Set<String> unlinkable = unlinkable(finder);
    Binding binding = Binding.NONE;
    if (!bindServices) {
      try {
        binding = binding(finder, limit, linked, unlinkable);
      } catch (FindException | ResolutionException e) {
        lines.add("error\t" + e.getMessage());
      }
    }
    for (Map.Entry<String, ModuleDescriptor> entry : onModulePath.entrySet()) {
      String name = entry.getKey();
      if (linked.contains(name)) {
        continue;
      }
      String kind = entry.getValue().isAutomatic() ? "automatic"
          : unlinkable.contains(name) ? "unlinkable" : "explicit";
      lines.add("left-out\t" + name + '\t' + kind + '\t' + (binding.providers().contains(name) ? "provider" : "other"));
    }
    for (String name : binding.added()) {
      lines.add("bindable\t" + name + '\t' + (onModulePath.containsKey(name) ? "module-path" : "system")
          + '\t' + (binding.providers().contains(name) ? "provider" : "required"));
    }
    for (Map.Entry<String, String> refused : binding.refused().entrySet()) {
      lines.add("refused\t" + refused.getKey() + '\t' + refused.getValue());
    }
    return lines;
  }

  /**
   * What service binding would add to the linked modules, which of those are the providers, and
   * the modules it would reach that jlink cannot link, by name and reason (the report's refused
   * kinds), which make {@code --bind-services} fail.
   */
  record Binding(Set<String> added, Set<String> providers, Map<String, String> refused) {
    static final Binding NONE = new Binding(Set.of(), Set.of(), Map.of());
  }

  /**
   * Resolves the linked modules with and without service binding over the modules jlink could
   * observe: those within --limit-modules when it was set, worked out over the whole module
   * universe as jlink does, less the {@code unlinkable} ones, which jlink refuses to link:
   * automatic modules (whose resolution would also drag every other automatic module along) and
   * the explicit modules whose mandatory requires reach one. A requires on a module that is nowhere
   * stays an error, as it is for jlink.
   */
  static Binding binding(ModuleFinder finder, Set<String> limit, Set<String> linked, Set<String> unlinkable) {
    ModuleFinder observable = limit.isEmpty() ? finder : limitFinder(finder, limit, linked);
    ModuleFinder linkable = filter(observable, reference -> !unlinkable.contains(reference.descriptor().name()));
    Configuration resolved = Configuration.empty().resolve(linkable, ModuleFinder.of(), linked);
    Configuration bound = Configuration.empty().resolveAndBind(linkable, ModuleFinder.of(), linked);
    Set<String> added = new TreeSet<>(names(bound));
    added.removeAll(names(resolved));
    // The services in use are those of the bound graph: jdk.naming.dns provides what java.naming
    // uses, and java.naming is itself only bound, so it is a provider too, not a plain dependency.
    Set<String> used = new HashSet<>();
    for (ResolvedModule module : bound.modules()) {
      used.addAll(module.reference().descriptor().uses());
    }
    Set<String> providers = new TreeSet<>();
    for (ResolvedModule module : bound.modules()) {
      String name = module.name();
      if (added.contains(name)
          && module.reference().descriptor().provides().stream().anyMatch(provides -> used.contains(provides.service()))) {
        providers.add(name);
      }
    }
    // jlink's own --bind-services binds over everything it observes, the unlinkable modules too, and
    // refuses the automatic modules among the result (binding one resolves every automatic module
    // with it, and an explicit module whose requires reach one brings it along). So an unlinkable
    // provider of a service the bound graph uses fails the link as soon as binding reaches it; the
    // ones reached first, through the services this graph uses, are enough to say so.
    Map<String, String> refused = new TreeMap<>();
    for (String name : unlinkable) {
      observable.find(name).ifPresent(reference -> {
        ModuleDescriptor descriptor = reference.descriptor();
        if (descriptor.provides().stream().anyMatch(provides -> used.contains(provides.service()))) {
          refused.put(name, descriptor.isAutomatic() ? "automatic" : "unlinkable");
        }
      });
    }
    // Binding links jdk.jlink through java.base's ToolProvider service, which a JDK without jmods
    // cannot link: this JVM's, as the system modules read above are this JVM's, which the task
    // makes the JDK that ran jlink.
    if (added.contains("jdk.jlink") && !Files.isDirectory(Path.of(System.getProperty("java.home"), "jmods"))) {
      refused.put("jdk.jlink", "no-jmods");
    }
    return new Binding(added, providers, refused);
  }

  /**
   * The modules jlink refuses to link: automatic modules, and the explicit modules whose mandatory
   * requires (a 'requires static' does not count) reach one, directly or through other modules.
   */
  static Set<String> unlinkable(ModuleFinder finder) {
    List<ModuleDescriptor> descriptors = finder.findAll().stream().map(ModuleReference::descriptor).toList();
    Set<String> unlinkable = new TreeSet<>();
    for (ModuleDescriptor descriptor : descriptors) {
      if (descriptor.isAutomatic()) {
        unlinkable.add(descriptor.name());
      }
    }
    boolean grown = true;
    while (grown) {
      grown = false;
      for (ModuleDescriptor descriptor : descriptors) {
        if (!unlinkable.contains(descriptor.name()) && descriptor.requires().stream().anyMatch(requires ->
            !requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC) && unlinkable.contains(requires.name()))) {
          unlinkable.add(descriptor.name());
          grown = true;
        }
      }
    }
    return unlinkable;
  }

  /**
   * jlink's --limit-modules: the observable modules are the limit set with what it requires, plus
   * the roots jlink was asked to link (here, what it linked, which those roots resolved to).
   */
  static ModuleFinder limitFinder(ModuleFinder finder, Set<String> limit, Set<String> roots) {
    Map<String, ModuleReference> observable = new HashMap<>();
    for (ResolvedModule module : Configuration.empty().resolve(finder, ModuleFinder.of(), limit).modules()) {
      observable.put(module.name(), module.reference());
    }
    for (String root : roots) {
      finder.find(root).ifPresent(reference -> observable.putIfAbsent(root, reference));
    }
    return finderOf(observable);
  }

  private static ModuleFinder filter(ModuleFinder finder, Predicate<ModuleReference> keep) {
    Map<String, ModuleReference> kept = new HashMap<>();
    for (ModuleReference reference : finder.findAll()) {
      if (keep.test(reference)) {
        kept.put(reference.descriptor().name(), reference);
      }
    }
    return finderOf(kept);
  }

  private static ModuleFinder finderOf(Map<String, ModuleReference> modules) {
    return new ModuleFinder() {
      @Override
      public Optional<ModuleReference> find(String name) {
        return Optional.ofNullable(modules.get(name));
      }

      @Override
      public Set<ModuleReference> findAll() {
        return new HashSet<>(modules.values());
      }
    };
  }

  private static Set<String> names(Configuration configuration) {
    return configuration.modules().stream().map(ResolvedModule::name).collect(Collectors.toSet());
  }

  /** The module path as jlink reads it: jars and exploded modules, and the jmod files among them. */
  record ModulePath(ModuleFinder modules, ModuleFinder jmods) {}

  /**
   * ModuleFinder.of handles jars and exploded modules, and a directory of them one level deep as
   * jlink does, but outside the link phase not jmod files, so those are read here; a jlink module
   * path may hold them (a JavaFX jmods directory).
   */
  static ModulePath modulePathFinder(List<Path> entries) {
    List<Path> plain = new ArrayList<>();
    Map<String, ModuleReference> jmods = new TreeMap<>();
    for (Path entry : entries) {
      if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jmod")) {
        ModuleReference reference = readJmod(entry);
        jmods.putIfAbsent(reference.descriptor().name(), reference);
        continue;
      }
      plain.add(entry);
      if (Files.isDirectory(entry) && !Files.isRegularFile(entry.resolve("module-info.class"))) {
        try (Stream<Path> listing = Files.list(entry)) {
          for (Path file : listing.sorted().toList()) {
            if (Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jmod")) {
              ModuleReference reference = readJmod(file);
              jmods.putIfAbsent(reference.descriptor().name(), reference);
            }
          }
        } catch (IOException e) {
          throw new UncheckedIOException("Error reading module path entry " + entry, e);
        }
      }
    }
    return new ModulePath(ModuleFinder.of(plain.toArray(Path[]::new)), finderOf(jmods));
  }

  /** The descriptor of a jmod file, wrapped in a reference that resolution can use but not open. */
  private static ModuleReference readJmod(Path jmod) {
    try (ZipFile zip = new ZipFile(jmod.toFile())) {
      ZipEntry entry = zip.getEntry("classes/module-info.class");
      if (entry == null) {
        throw new FindException("No module-info.class in " + jmod);
      }
      ModuleDescriptor descriptor;
      try (InputStream in = zip.getInputStream(entry)) {
        descriptor = ModuleDescriptor.read(in);
      }
      return new ModuleReference(descriptor, jmod.toUri()) {
        @Override
        public ModuleReader open() {
          throw new UnsupportedOperationException("LinkReport does not open modules");
        }
      };
    } catch (IOException e) {
      throw new UncheckedIOException("Error reading module path entry " + jmod, e);
    }
  }
}
