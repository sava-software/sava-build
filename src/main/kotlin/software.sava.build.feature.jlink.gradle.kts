import com.github.iherasymenko.jlink.JlinkImageTask

plugins {
  id("com.github.iherasymenko.jlink")
}

tasks.withType<JlinkImageTask>().configureEach {
  bindServices = true
  ignoreSigningInformation = true
}
