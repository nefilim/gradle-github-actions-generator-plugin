import org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION as KOTLIN_VERSION

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    `java-gradle-plugin`
    signing
    `maven-publish`
    alias(libs.plugins.githubrelease)
    alias(libs.plugins.gradlePluginPublish)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.semver)
    alias(libs.plugins.versions)
    alias(libs.plugins.nexus.publish)
}

semver {
    tagPrefix("v")
    initialVersion("0.0.1")
    findProperty("semver.overrideVersion")?.toString()?.let { overrideVersion(it) }
    findProperty("semver.modifier")?.toString()?.let { versionModifier(buildVersionModifier(it)) } // this is only used for non user defined strategies, ie predefined Flow or Flat
}

val invalidQualifiers = setOf("alpha", "beta", "rc", "nightly")
fun hasInvalidQualifier(candidate: ModuleComponentIdentifier): Boolean {
    return invalidQualifiers.any { candidate.version.contains(it) }
}
configurations.all {
    resolutionStrategy {
        eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(libs.versions.kotlin.get())
            }
        }
        componentSelection {
            all {
                if (hasInvalidQualifier(candidate))
                    reject("invalid qualifier versions for $candidate")
            }
        }
    }
}

/*
 * Project information
 */
group = "io.github.nefilim.gradle"
description = "Github Actions Workflow Generator"
version = semver.version

inner class ProjectInfo {
    val longName = "Gradle Github Actions Workflow Generator"
    val pluginImplementationClass = "$group.ghagenerator.GithubActionsWorkflowGeneratorPlugin"
    val tags = listOf("github", "actions")
    val website = "https://github.com/nefilim/gradle-github-actions-generator-plugin"
    val vcsURL = "$website.git"
}
val info = ProjectInfo()

repositories {
    mavenCentral()
}

dependencies {
    api(gradleApi())
    api(gradleKotlinDsl())
    api(kotlin("stdlib-jdk8"))
    api(libs.githubActionsDSLCore)
    api(libs.githubActionsDSLActions)
    implementation(libs.kaml)
    testImplementation(gradleTestKit())
    testImplementation(libs.bundles.kotest)
}

// Enforce Kotlin version coherence
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin")) {
            useVersion(KOTLIN_VERSION)
            because("All Kotlin modules should use the same version, and compiler uses $KOTLIN_VERSION")
        }
    }
}

kotlin {
    target {
        compilations.all {
            kotlinOptions {
                freeCompilerArgs = freeCompilerArgs + listOf("-version", "-Xjsr305=strict", "-Xopt-in=kotlin.RequiresOptIn")
                jvmTarget = "11"
                languageVersion = "1.5"
                apiVersion = "1.5"
                verbose = true
            }
        }
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile> {
    sourceCompatibility = JavaVersion.VERSION_11.toString()
    targetCompatibility = sourceCompatibility
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        showCauses = true
        showStackTraces = true
        events(*org.gradle.api.tasks.testing.logging.TestLogEvent.values())
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// can only be applied to root project
nexusPublishing {
    repositories {
        sonatype {
            username.set(System.getenv("OSS_USER"))
            password.set(System.getenv("OSS_TOKEN"))
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
        }
    }
}

signing {
    val skipSigning = findProperty("skipSigning")?.let { (it as String).toBoolean() } ?: false
    if (!skipSigning) {
        val signingKey: String? by project
        val signingPassword: String? by project
        useInMemoryPgpKeys(signingKey, signingPassword)
    } else {
        logger.warn("skipping signing")
    }
}

pluginBundle {
    website = info.website
    vcsUrl = info.vcsURL
    tags = info.tags
}

gradlePlugin {
    plugins {
        create(project.name) {
            id = "$group.${project.name}"
            displayName = info.longName
            description = project.description
            implementationClass = info.pluginImplementationClass
        }
    }
}

fun MavenPublication.addSonaTypeRequirements() {
    this.pom {
        name.set(project.name)
        description.set("Github Actions Workflow Generator Plugin")
        url.set(info.website)
        licenses {
            license {
                name.set("GPL-3.0-only")
                url.set("https://opensource.org/licenses/GPL-3.0")
            }
        }
        developers {
            developer {
                id.set("nefilim")
                name.set("nefilim")
                email.set("nefilim@hotmail.com")
            }
        }
        scm {
            connection.set("scm:git:${info.vcsURL}")
            url.set(info.website)
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            names.filter { it.contains("plugin", ignoreCase = true) }.map {
                logger.lifecycle("decorating publication [$it]")
                findByName(it)?.let { it as MavenPublication }?.apply { addSonaTypeRequirements() } ?: logger.error("failed to find publication [$it]")
            }
        }
    }
    signing.sign(publishing.publications)
}

val githubTokenValue = findProperty("githubToken")?.toString() ?: System.getenv("GITHUB_TOKEN")

githubRelease {
    token(githubTokenValue)
    owner("nefilim")
    repo("gradle-github-actions-generator-plugin")
    tagName(semver.versionTagName)
    targetCommitish("main")
    body(changelog())
    draft(false)
    prerelease(false)

    overwrite(false)
    dryRun(false)
    apiEndpoint("https://api.github.com")
    client
}