plugins {
    java
    id("org.springframework.boot") version "4.1.0"
}

group = "org.haoemr"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("generated/contracts/java"))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(platform("org.springframework.modulith:spring-modulith-bom:2.1.0"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val generateContracts = tasks.register<Exec>("generateContracts") {
    inputs.file("contracts/openapi.json")
    inputs.file("contracts/governance.source.json")
    inputs.file("contracts/generate.mjs")
    inputs.files(fileTree("src/main/resources/db/migration") { include("V*__*.sql") })
    inputs.file("prototype/traceability.csv")
    inputs.file("docs/design/ui-delivery/route-design-map.csv")
    outputs.dir(layout.buildDirectory.dir("generated/contracts/java"))
    outputs.dir("contracts/generated")
    outputs.file("web/src/generated/contracts.ts")
    commandLine("node", "contracts/generate.mjs")
}

tasks.named("compileJava") {
    dependsOn(generateContracts)
}
