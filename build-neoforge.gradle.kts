import org.apache.tools.ant.filters.ReplaceTokens
import org.mtr.BuildTools
import org.mtr.core.Generator
import org.mtr.core.WebserverSetup
import java.io.File

plugins {
	id("net.neoforged.moddev")
	id("dev.kikugie.fletching-table.neoforge") version "+"
	id("io.freefair.lombok") version "+"
	id("com.gradleup.shadow") version "+"
}

base.archivesName = property("mod.id") as String
version = "${property("mod.version")}+${sc.current.version}-neoforge"

repositories {
	mavenCentral()
	maven { url = uri("https://repo.codemc.org/repository/maven-public") } // Occlusion Culling
	maven { url = uri("https://repo.essential.gg/repository/maven-public") } // Elementa and UniversalCraft
	maven { url = uri("https://maven.fabricmc.net/") } // Fabric Language Kotlin
	maven {
		url = uri("https://maven.pkg.github.com/Minecraft-Transit-Railway/Transport-Simulation-Core")
		credentials {
			username = providers.gradleProperty("gpr.user").getOrNull() ?: "github-actions"
			password = providers.gradleProperty("gpr.key").getOrNull() ?: System.getenv("GITHUB_TOKEN")
		}
	}
}

val buildTools = BuildTools(sc.current.version, "neoforge", project.property("mod.version").toString(), project.rootDir)
val requiredJava = when {
	sc.current.parsed < "26.0" -> JavaVersion.VERSION_21
	else -> JavaVersion.VERSION_26
}

configurations {
	create("shadowBundle") {
		isCanBeResolved = true
		isCanBeConsumed = false
	}
}

java {
	withSourcesJar()
	targetCompatibility = requiredJava
	sourceCompatibility = requiredJava
}

fun DependencyHandlerScope.implementationAndShadow(notation: Any) {
	implementation(notation)
	add("shadowBundle", notation)
}

dependencies {
	implementationAndShadow("org.mtr:transport-simulation-core:+")
	implementationAndShadow("com.logisticscraft:occlusionculling:+")
	implementationAndShadow("gg.essential:elementa:${property("dependency.elementa")}")
	implementationAndShadow("gg.essential:universalcraft-${property("dependency.universal_craft_minecraft")}-neoforge:${property("dependency.universal_craft")}")
	implementationAndShadow("org.jetbrains.kotlin:kotlin-stdlib:+")
	implementation("org.jspecify:jspecify:+")

	testImplementation("org.junit.jupiter:junit-jupiter-api:5.+")
	testImplementation("org.junit.platform:junit-platform-launcher:1.+")
	testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.+")
}

neoForge {
	version = property("dependency.neoforge") as String

	mods {
		register(property("mod.id") as String) {
			sourceSet(sourceSets["main"])
		}
	}

	sourceSets["main"].resources.srcDir("src/main/generated")
}

tasks {
	processResources {
		val properties = mapOf(
			"mod_id" to project.property("mod.id"),
			"mod_name" to project.property("mod.name"),
			"mod_description" to project.property("mod.description"),
			"mod_license" to project.property("mod.license"),
			"mod_author" to project.property("mod.author"),
			"mod_version" to project.property("mod.version"),
			"mod_homepage" to project.property("mod.homepage"),
			"mod_sources" to project.property("mod.sources"),
			"mod_issues" to project.property("mod.issues"),
			"minecraft_version" to sc.current.version,
		)

		filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
			expand(properties)
		}

		exclude("**/fabric.mod.json")
	}

	test {
		useJUnitPlatform()
		testLogging { showStandardStreams = true }
	}

	javadoc {
		// Suppress "missing" doclint only (generated classes don't need javadoc)
		(options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
	}

	// Verifies that the Transport-Simulation-Core artifact being shaded into the mod actually embeds the
	// Transport System Map website. A TSC jar built without building its Angular website first contains an
	// (almost) empty org.mtr.core.generated.WebserverResources class, and every mod shaded with it serves a
	// broken Transport System Map page at runtime - exactly how the 1.21.x release jars ended up broken.
	val verifyTransportSimulationCoreTask = register("verifyTransportSimulationCore") {
		group = "verification"
		description = "Verifies that the Transport-Simulation-Core dependency embeds the Transport System Map website."

		doLast {
			val tscJars = configurations.getByName("shadowBundle").resolve()
				.filter { it.isFile && it.extension == "jar" }
				.filter { val n = it.nameWithoutExtension.lowercase(); n.startsWith("transport-simulation-core") && !n.contains("build-tools") && !n.contains("sources") && !n.contains("javadoc") }
			if (tscJars.isEmpty()) {
				println("No Transport-Simulation-Core jar found on the shadowBundle classpath, skipping verification")
				return@doLast
			}
			for (jarFile in tscJars) {
				java.util.zip.ZipFile(jarFile).use { zip ->
					val entry = zip.getEntry("org/mtr/core/generated/WebserverResources.class")
					val size = entry?.size ?: -1L
					if (entry == null || size < 100_000L) {
						throw GradleException(
							"Transport-Simulation-Core artifact ${jarFile.name} does not embed the Transport System Map website " +
									"(org/mtr/core/generated/WebserverResources.class is ${if (entry == null) "missing" else "$size bytes"}). " +
									"The jar was built without building the Angular website in website/dist/website/browser first. " +
									"Rebuild Transport-Simulation-Core from a checkout that embeds the website (a fixed build runs the website build automatically), " +
									"then refresh the dependency and build again. Without this check the mod would silently ship a broken Transport System Map page.")
					}
					println("Transport System Map website verified in ${jarFile.name} (WebserverResources.class: $size bytes)")
				}
			}
		}
	}

	shadowJar {
		configurations = listOf(project.configurations["shadowBundle"])
		minimize()
		relocate("com.logisticscraft", "org.mtr.libraries.com.logisticscraft")
		relocate("de.javagl", "org.mtr.libraries.de.javagl")
		relocate("gg.essential", "org.mtr.libraries.gg.essential")
		relocate("kotlin.", "org.mtr.libraries.kotlin")
		relocate("org.jetbrains", "org.mtr.libraries.org.jetbrains")

		dependsOn(verifyTransportSimulationCoreTask)
	}

	withType<JavaCompile>().configureEach {
		options.compilerArgs.addAll(
			listOf(
				"-Xlint:all",
				"-Xlint:-serial",     // No Java serialization
				"-Xlint:-processing", // Lombok annotation processor noise
				"-Xlint:-this-escape" // Safe: schema constructor pattern
			)
		)
	}

	register<Copy>("buildAndCollect") {
		description = "Builds the mod and collects the JAR and sources JAR into the build/libs directory with versioned naming."
		group = "build"
		outputs.upToDateWhen { false }
		from(shadowJar.map { it.archiveFile })
		into(rootProject.layout.buildDirectory.file("release"))
		rename("${project.property("mod.id")}-([^-]+)-([^-]+)-([a-z]+)-all\\.jar", "${project.property("mod.id").toString().uppercase()}-$3-$1-$2.jar")
		dependsOn("build")
	}

	named("createMinecraftArtifacts") {
		dependsOn("stonecutterGenerate")
	}

	register("setupWebsiteFiles") {
		description = "Generates TypeScript files for the website based on the resource schema."
		Generator.generateTypeScript(project, "schema/resource", "../../website/src/app/entity/generated")
	}

	// Ensures the Resource Pack Creator website has been built into website/dist/website/browser.
	// WebserverSetup.setup (called from setupFiles) embeds every file in that directory into the generated
	// WebserverResources class. If the directory is missing, an empty WebserverResources is silently generated and the
	// in-game Resource Pack Creator button redirects to the Transport System Map instead of opening the creator.
	fun ensureWebsiteBuilt() {
		val websiteDir = File(project.rootDir, "website")
		val distBrowserDir = File(websiteDir, "dist/website/browser")
		if (File(distBrowserDir, "index.html").exists()) {
			return
		}

		println("Resource Pack Creator website build output not found at $distBrowserDir, building it now...")
		// The website imports generated TypeScript entities, regenerate them before compiling the Angular app
		Generator.generateTypeScript(project, "schema/resource", "../../website/src/app/entity/generated")

		val npmCommand = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "npm.cmd" else "npm"
		try {
			project.exec {
				workingDir(websiteDir)
				commandLine(npmCommand, "install", "--no-audit", "--no-fund")
			}
			project.exec {
				workingDir(websiteDir)
				commandLine(npmCommand, "run", "build")
			}
		} catch (e: Exception) {
			throw GradleException(
				"Failed to build the Resource Pack Creator website. " +
						"Please install Node.js, then run `npm install` and `npm run build` inside the `website` directory before building the mod. " +
						"Without the built website, the generated WebserverResources is empty and the Resource Pack Creator would redirect to the Transport System Map.",
				e)
		}
		if (!File(distBrowserDir, "index.html").exists()) {
			throw GradleException(
				"The Resource Pack Creator website build output is still missing ($distBrowserDir/index.html). " +
						"Run `npm install` and `npm run build` inside the `website` directory, then build the mod again. " +
						"Without the built website, the generated WebserverResources is empty and the Resource Pack Creator would redirect to the Transport System Map.")
		}
	}

	register("buildWebsite") {
		group = "build"
		description = "Builds the Resource Pack Creator website (Angular) so it can be embedded into WebserverResources. Requires Node.js."
		doLast {
			ensureWebsiteBuilt()
		}
	}

	register("setupFiles") {
		description = "Sets up necessary files for the mod, including generating Java classes from templates and processing translations."

		// Build the website first (if not already built) so WebserverSetup embeds real resources instead of generating an empty class
		ensureWebsiteBuilt()

		copy {
			outputs.upToDateWhen { false }
			from("../../src/main/KeysTemplate.java")
			into("../../src/main/java/org/mtr")
			filter<ReplaceTokens>(mapOf("tokens" to mapOf("version" to "${project.property("mod.version")}+${sc.current.version}", "debug" to "${project.property("debug")}")))
			rename("(.+)Template.java", "$1.java")
		}

		buildTools.downloadTranslations(project.property("key.crowdin").toString())
		buildTools.generateTranslations()
		buildTools.copyVehicleTemplates()
		buildTools.getPatreonList(project.property("key.patreon").toString())
		buildTools.setupObjLibrary()
		Generator.generateJava(project, "schema/config", "generated/config", "config")
		Generator.generateJava(project, "schema/resource", "generated/resource", "core.data", "resource")
		Generator.generateJava(project, "schema/legacy", "legacy/generated/resource")
		WebserverSetup.setup(project.rootDir, "", "")
		buildTools.fixImports(project, "generated/config")
		buildTools.fixImports(project, "generated/resource")
		buildTools.fixImports(project, "legacy/generated/resource")
	}
}
