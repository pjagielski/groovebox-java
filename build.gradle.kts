plugins {
    application
}

group = "io.groovebox"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// JavaFX is not part of the JDK. Its artifacts are per platform, and Gradle does not resolve the
// Maven profiles openjfx picks its classifier with, hence the modules listed with a classifier.
val javafxVersion = "25.0.4"
val javafxPlatform = System.getProperty("os.name").lowercase().let { os ->
    val arm = System.getProperty("os.arch").contains("aarch64")
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    listOf("base", "graphics", "controls").forEach {
        implementation("org.openjfx:javafx-$it:$javafxVersion:$javafxPlatform")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.groovebox.app.GrooveboxLauncher"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Real skin gestures need a display, but no audio device. Keep them explicitly runnable so
// the offline suite remains usable on hosts without a window system.
val uiTest = sourceSets.create("uiTest") {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

tasks.register<JavaExec>("uiGestureTest") {
    group = "verification"
    description = "Checks effect-slider mouse gestures against the real JavaFX skin (requires a display)."
    dependsOn(uiTest.classesTaskName)
    classpath = uiTest.runtimeClasspath
    mainClass.set("io.groovebox.app.StripGestureLauncher")
}

// JavaFX loads its native libraries (windows, graphics) through System.load. Since JDK 24 that
// warns on every start unless native access is granted, and a later release will refuse it
// outright, so every task that may bring JavaFX up grants it.
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
