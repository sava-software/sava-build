import software.sava.build.jmh.JmhPluginRequirement

plugins {
  id("java")
}

// The JMH Gradle plugin (me.champeau.jmh) is the benchmark build's to declare, beside this
// feature: JmhPluginRequirement says why, applies it, and lays the conventions over it.
JmhPluginRequirement.apply(project)
