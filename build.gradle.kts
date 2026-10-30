plugins {
    java
}

allprojects {
    group = "dev.aurora"
    version = "0.1.0"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java")
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Compile Java 21 bytecode while allowing newer installed JDKs (for example Java 25).
        options.release.set(21)
    }
}
