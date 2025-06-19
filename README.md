# Sava Shard GitHub Actions and Gradle Conventions

## Gradle Conventions

Gradle [convention plugins](https://docs.gradle.org/current/samples/sample_convention_plugins.html) used by Sava projects.

To modify and test the convention plugins on a project using them, insert the line
`pluginManagement { includeBuild("<path-to-hiero-gradle-conventions>") }`
in the top of the `settings.gradle.kts` of the project.
For example, if this repository is cloned next to the project repository in
your local file system, the first line in your `settings.gradle.kts` would be:

```kotlin
pluginManagement { includeBuild("../sava-build") }
```

After inserting that line, reload the project in IntelliJ. `sava-build` will show up next to your project in the workspace.
You can now make changes to the files in [src/main/kotlin](src/main/kotlin).
