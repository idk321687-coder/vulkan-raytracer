dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.kyori:adventure-text-minimessage:4.24.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

tasks.test { useJUnitPlatform() }
