package software.sava.build.jlink

internal fun executableName(name: String): String =
  if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) "$name.exe" else name
