import com.google.devtools.ksp.gradle.KspTask
import com.google.devtools.ksp.gradle.KspTaskJvm
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Properties as Prop
import java.time.Duration
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.*
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.*
import org.junit.platform.engine.support.descriptor.*
import org.junit.platform.launcher.*
import org.junit.platform.launcher.core.*
import org.junit.platform.launcher.TagFilter.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import io.github.detekt.gradle.extensions.KotlinCompileTaskDetektExtension
import me.champeau.jmh.JMHTask

plugins {
	kotlin("jvm") version "${property("kotlin.version")}"
	kotlin("plugin.serialization") version "${property("kotlin.version")}"
	application
	id("com.github.johnrengelman.shadow") version "7.0.0"
	id("com.palantir.git-version") version "3.0.0"
	id("com.google.devtools.ksp") version "${property("kotlin.version")}-${property("ksp.version")}"
	id("me.champeau.jmh") version "0.7.2"
	id("io.github.detekt.gradle.compiler-plugin") version "${property("detekt.version")}"
	id("com.dorongold.task-tree") version "2.1.0"
	jacoco
}

jacoco {
	toolVersion = "0.8.9"
}

tasks.test {
	configure<JacocoTaskExtension> {
		isEnabled = System.getProperty("Jacoco") != null
	}
}

subprojects {
	apply(plugin = "kotlin")
	apply(plugin = "java")
	apply(plugin = "com.google.devtools.ksp")
	apply(plugin = "me.champeau.jmh")
	apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
	apply(plugin = "com.palantir.git-version")
	apply(plugin = "com.github.johnrengelman.shadow")
}

tasks.shadowJar {
	isZip64 = true
	archiveClassifier.set("jar-with-dependencies")
	archiveVersion.set("${project.property("version")}")
	archiveBaseName.set("${project.property("artifactId")}")
}

buildscript {
	dependencies {
		classpath("com.github.vbmacher:java-cup:11b-20160615")
		classpath("de.jflex:jflex:1.7.0")
		classpath("org.junit.platform:junit-platform-launcher:1.10.0-M1")
	}
}

allprojects {
	java {
		toolchain {
			languageVersion.set(JavaLanguageVersion.of(project.property("java.version")!!.toString()))
		}
	}

	tasks.withType<KotlinCompile> {
		kotlinOptions {
			jvmTarget = "${project.property("java.version")}"
			allWarningsAsErrors = project.property("kotlin.warningsAsErrors")!!.toString().toBoolean() && !project.hasProperty("detekt.baseline")
			freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
			freeCompilerArgs += "-Xcontext-receivers"
			freeCompilerArgs += "-Xrender-internal-diagnostic-names"
		}
	}

	tasks.withType<JMHTask> {
		outputs.upToDateWhen { false }
		project.findProperty("jmh.tests")?.let { includes.add(it.toString()) }
		fork.set(1)
		timeUnit.set("ms")
		timeOnIteration.set("1s")
		warmupIterations.set(1)
		warmup.set("1s")
		profilers.set(listOf("gc")) // include GC stats for all benchmarks
	}

	tasks.withType<Test> {
		timeout.set(Duration.ofHours(1))
        useJUnitPlatform {
			includeTags(project.findProperty("test.filter")?.toString() ?: "!expensive")
		}
		filter {
			isFailOnNoMatchingTests = false
		}

		// Log to the console
		testLogging.showStandardStreams = true

		/*
			Translate gradle properties into test properties to configure logging.
			For example, to enable verbose logging for the POINTS_TO logger:

				./gradlew test -Pverbose.points.to

			...or to enable trace logging:

				./gradlew test -Plevel.points.to=trace
		 */
		project.properties.filterKeys { it.startsWith("verbose.") }.forEach {
			systemProperty("level.${it.key.substringAfter("verbose.")}", "debug")
		}
		project.properties.filterKeys { it.startsWith("level.") }.forEach {
			systemProperty(it.key, it.value)
		}
		project.properties.filterKeys { it.startsWith("report.") }.forEach {
			systemProperty(it.key, it.value)
		}

		if(project.hasProperty("mathematicaHome")) {
			systemProperty("mathematicaHome", project.property("mathematicaHome") as String)
		}

		maxHeapSize = project.findProperty("test.maxheap")?.toString() ?: "5120m"

		// Experimental option to enable running tests in parallel processes.  Use at your own risk, and YMMV.
		// Note that the test themselves often assume they have the whole machine available to them, so this will result in
		// substantial oversubscription of resources.  And it's possible that two tests will accidentally modify the same
		// file.  If you see that, please fix it.
		if (project.property("test.parallel")!!.toString().toBoolean()) {
			maxParallelForks = Runtime.getRuntime().availableProcessors()
		}

		beforeTest(closureOf<TestDescriptor> { logger.lifecycle("[${this.className}] > ${this.name} > ${this.displayName} START") })
		afterTest(KotlinClosure2({ descriptor: TestDescriptor, result: TestResult ->
			logger.lifecycle(("[${descriptor.className}] > ${this.name} > ${descriptor.displayName}: ${result.resultType} " +
				"(Time: ${result.endTime - result.startTime}ms)"))
		}))
	}

	tasks.withType<org.gradle.jvm.tasks.Jar>() {
		duplicatesStrategy = DuplicatesStrategy.EXCLUDE
	}
}

/**
  Produces a file listing all test classes/methods that match a tag filter.
  This invokes JUnit to scan the actual test classes.
 */
abstract class TestListTask @Inject constructor(private val workerExecutor: WorkerExecutor) : DefaultTask() {

	init {
		dependsOn("testClasses")
	}

	@Input
	var includedTags: String = "any()|none()" // By default, list all tests (tests with *any* tags OR *none*)

	@Input
	var listMethods: Boolean = false

	@OutputFile
	var outputFile: File = project.buildDir.resolve("TestClasses.txt")

	@TaskAction
	fun execute() {
		// We need to run JUnit with the Jupiter engine, tests, and product code, in the classpath.  We use a Gradle
		// WorkerExecutor to do this.
		val workQueue = workerExecutor.classLoaderIsolation {
			classpath.from(project.sourceSets["test"].runtimeClasspath)
		}
		workQueue.submit<Worker.Params>(Worker::class.java) {
			testDirs = project.sourceSets["test"].output.classesDirs.toList()
			includedTags = this@TestListTask.includedTags
			outputFile = this@TestListTask.outputFile
			listMethods = this@TestListTask.listMethods
		}
		workQueue.await()
	}

	abstract class Worker @Inject constructor(val params: Params) : WorkAction<Worker.Params> {
		interface Params : WorkParameters {
			var testDirs: List<File>
			var includedTags: String
			var outputFile: File
			var listMethods: Boolean // methods or classes?
		}

		override fun execute() {
			// Invoke JUnit
			val request = LauncherDiscoveryRequestBuilder.request()
				.selectors(selectClasspathRoots(params.testDirs.map { it.toPath() }.toSet()))
				.filters(includeTags(params.includedTags))
				.build()
			val testPlan = LauncherFactory.create().discover(request)

			// Enumerate all methods/classes
			val sources = testPlan.roots.asSequence()
				.flatMap { testPlan.getDescendants(it) }
				.map { it.source.orElse(null) }
			val methodsOrClasses = if (params.listMethods) {
				sources.filterIsInstance<MethodSource>().map { "${it.className}.${it.methodName}" }
			} else {
				sources.filterIsInstance<ClassSource>().map { it.className }
			}

			// Write the file
			Files.write(params.outputFile.toPath(), methodsOrClasses.toSortedSet())
		}
	}
}

allprojects {
	var cheapTestListDir = file("$rootDir/build/testLists/cheap")
	var expensiveTestListDir = file("$rootDir/build/testLists/expensive")

	tasks.register<TestListTask>("cheapTestList") {
		includedTags = "!expensive"
		outputFile = cheapTestListDir.resolve("${project.name}.txt")
	}
	tasks.register<TestListTask>("expensiveTestList") {
		includedTags = "expensive"
		outputFile = expensiveTestListDir.resolve("${project.name}.txt")
		listMethods = true
	}
	tasks.register("testLists") {
		dependsOn("cheapTestList")
		dependsOn("expensiveTestList")
	}
}

open class CupGenerateExtension {
	var mapping : (String) -> String? = { _ -> null }
}

abstract class GeneratorTask : SourceTask() {
	@OutputDirectory
	var outputDirectory: File? = null

	@get:Incremental
	var source : FileCollection? = null

	@TaskAction
	fun execute(iChng: InputChanges) {
		val srcSet = this.source!!
		val rebuild = mutableListOf<File>()
		if(iChng.isIncremental) {
			for (fileChange in iChng.getFileChanges(srcSet)) {
				if(fileChange.fileType == FileType.FILE && fileChange.changeType == ChangeType.REMOVED) {
					project.delete(outputDirectory)
					rebuild.addAll(srcSet.files)
					break
				}
				rebuild.add(fileChange.file)
			}
		} else {
			rebuild.addAll(srcSet.files)
		}
		val outputDirectory = outputDirectory!!
		if(!outputDirectory.exists()) {
			project.mkdir(outputDirectory)
		}
		for (toBuild in rebuild) {
			// try to infer the output package name
			val pkg = toBuild.bufferedReader().readLine()?.let { it ->
				"package[ ]+([^;]+);".toRegex().matchEntire(it)?.groups?.get(1)?.value
			}
			val outputPkg = if(pkg != null) {
				outputDirectory.resolve(pkg.replace('.', File.separatorChar))
			} else {
				outputDirectory
			}
			if(!outputPkg.exists()) {
				project.mkdir(outputPkg)
			}
			this.doGeneration(outputPkg, toBuild)
		}
	}

	abstract fun doGeneration(outDir: File, inFile: File)
}

application {
	mainClass.set("EntryPointKt")
}

@CacheableTask
open class CupGenerateTask @Inject constructor() : GeneratorTask() {
	override fun doGeneration(outDir: File, inFile: File) {
		val procArgs = mutableListOf<String>("-locations", "-destdir", outDir.absolutePath, "-nosummary")
		project.the<CupGenerateExtension>().mapping.invoke(inFile.nameWithoutExtension)?.let {
			procArgs.add("-parser")
			procArgs.add(it)
		}
		procArgs.add(inFile.absolutePath)
		project.javaexec {
			classpath(project.rootProject.buildscript.configurations.getByName("classpath").asPath)
			mainClass.set("java_cup.Main")
			args = procArgs
		}
	}
}

interface SourceDelegate {
	val srcDir : SourceDirectorySet
}

class CupDirectoryDelegate(nm: String, objectFact: ObjectFactory) : SourceDelegate {
	val cup : SourceDirectorySet

	init {
		this.cup = objectFact.sourceDirectorySet(nm + "SourceSet", "${nm}'s cup source set")
		cup.filter.include("**/*.cup")
	}

	override val srcDir get() = cup
}

private object ConfigureGenerator {
	fun configure(project: Project, nm: String, toInstr: SourceSet, delegate: SourceDelegate): File {
		org.gradle.api.internal.plugins.DslObject(toInstr).convention.plugins.put(
				nm, delegate
		)
		delegate.srcDir.srcDir("src/${toInstr.name}/$nm")
		toInstr.allSource.source(delegate.srcDir)
		val outputDir = project.buildDir.resolve("generated-src/$nm/${toInstr.name}")
		toInstr.java.srcDir(outputDir)
		return outputDir
	}

	fun patchDependencies(project: Project, srcSet: SourceSet, genTask: Any, vararg deps: String) {
		project.tasks.named(srcSet.compileJavaTaskName) {
			dependsOn(genTask)
		}
		val compileTask = srcSet.getCompileTaskName("kotlin")
		project.tasks.withType(org.jetbrains.kotlin.gradle.dsl.KotlinCompile::class.java).all {
			if(this.name == compileTask) {
				this.dependsOn(genTask)
			}
		}
		project.tasks.withType(KspTask::class.java).all {
			if(this is KspTaskJvm) {
				this.dependsOn(genTask)
			}
		}
		project.configurations.named(srcSet.implementationConfigurationName).configure {
			deps.forEach {
				dependencies.add(project.dependencies.create(it))
			}
		}
	}
}

class CertoraCupPlugin : Plugin<Project> {
	override fun apply(project: Project) {
		project.plugins.apply(JavaPlugin::class)
		project.extensions.create<CupGenerateExtension>("cup")
		project.convention.getPlugin(JavaPluginConvention::class).sourceSets.forEach {
			val sourceSet = CupDirectoryDelegate(it.name, project.objects)
			val outputDir = ConfigureGenerator.configure(project, "cup", it, sourceSet)
			val cupGenName = it.getTaskName("generate", "CupGrammar")
			val genTask = project.tasks.register(cupGenName, CupGenerateTask::class.java) {
				source = sourceSet.cup
				this.outputDirectory = outputDir
			}
			ConfigureGenerator.patchDependencies(project, it,  genTask, "com.github.vbmacher:java-cup-runtime:11b-20160615")
		}
	}
}


class JFlexDirectoryDelegate : SourceDelegate {
	val jflex : SourceDirectorySet

	constructor(nm: String, objectFact: ObjectFactory) {
		this.jflex = objectFact.sourceDirectorySet(nm + "SourceSet", "${nm}'s jflex source set")
		jflex.filter.include("**/*.jflex").exclude("__base*")
	}

	override val srcDir get() = jflex
}

@CacheableTask
open class JFlexGenerateTask : GeneratorTask {
	@Inject
	constructor() : super()

	override fun doGeneration(outDir: File, inFile: File) {
		val jfArgs = listOf(
				"-q",
				inFile.absolutePath,
				"-d", outDir.absolutePath
		)
		project.javaexec {
			classpath(project.rootProject.buildscript.configurations.getByName("classpath").asPath)
			mainClass.set("jflex.Main")
			args = jfArgs
		}
	}

}

class CertoraJFlexPlugin : Plugin<Project> {
	override fun apply(project: Project) {
		val jflexDirName = "jflex" //case sensitive in circleCI
		project.plugins.apply(JavaPlugin::class)
		project.convention.getPlugin(JavaPluginConvention::class).sourceSets.forEach {
			val sourceSet = JFlexDirectoryDelegate(it.name, project.objects)
			val outputDir = ConfigureGenerator.configure(project, jflexDirName, it, sourceSet)
			val genName = it.getTaskName("generate", "JFLexer")
			val genTask = project.tasks.register(genName, JFlexGenerateTask::class.java) {
				source = sourceSet.jflex
				this.outputDirectory = outputDir
			}
			ConfigureGenerator.patchDependencies(project, it,  genTask)
		}
	}
}

allprojects {
	if (name != "DetektRules" && name != "KspGeneration") {
		apply(plugin = "io.github.detekt.gradle.compiler-plugin")

		var detektBaselineDir = file("$rootDir/detekt/${project.name}/baseline")
		var detektBaseline = detektBaselineDir.resolve("${project.name}-baseline.xml")

		dependencies {
			detektPlugins(project(":DetektRules"))
			detektPlugins("com.github.certora.collections:detekt-treapability:${property("certora.collect.version")}")
		}

		tasks.withType<KotlinCompile> {
			if (name != "compileKotlin") {
				// Disable Detekt for tests
				extensions.configure<KotlinCompileTaskDetektExtension> {
					isEnabled.set(false)
				}
			} else {
				// Set up the Detekt configuration.  We rewrite detekt.yml, substituting the correct value of $FULL$
				// for this build.
				val configFile = file("$rootDir/detekt/detekt.yml")
				val reportsDir = project.rootProject.buildDir.resolve("reports/detekt/")
				val detektFull = project.hasProperty("detekt.full") || project.hasProperty("detekt.baseline")
				val projectConfigFile = reportsDir.resolve("detekt_$detektFull.yml")

				// we put the detektFull value in the name of the task, so that Gradle will re-run the task if the value changes
				tasks.register<Copy>("detektConfig.$detektFull") {
					from(configFile)
					into(reportsDir)
					rename { projectConfigFile.name }
					filter { line ->
						when {
							!detektFull && line.contains("#ONLY_FULL") -> ""
							else -> line.replace("#IS_FULL", detektFull.toString())
						}
					}
				}

				dependsOn("detektConfig.$detektFull")

				// Enable Detekt with the configuration we just generated.
				extensions.configure<KotlinCompileTaskDetektExtension> {
					isEnabled.set(true)
					parallel.set(true)
					allRules.set(true)
					config.from(projectConfigFile)
					baseline.set(detektBaseline)
				}

				// Optional baseline update (`gradle assemble -Pdetekt.baseline`)
				if (project.hasProperty("detekt.baseline")) {
					// Delete the existing baseline so that our baseline reporter will see all of the issues.
					tasks.register<Delete>("cleanDetektBaseline") {
						delete(detektBaselineDir)
					}
					dependsOn("cleanDetektBaseline")
					outputs.dir(detektBaselineDir)
					extensions.configure<KotlinCompileTaskDetektExtension> {
						reports {
							// enable our custom baseline reporter
							create("baseline") {
								destination.set(detektBaseline)
							}
						}
					}
				}
			}
		}
	}
}

val optimizerName = if (Os.isFamily(Os.FAMILY_WINDOWS)) {
	"tac_optimizer.exe"
} else {
	"tac_optimizer"
}

// if submodule is not dirty maybe we can push the tac_optimizer from somewhere
val egg = project.task("fried-egg") {
	inputs.files(project.fileTree("fried-egg") {
		include("src/**/*.rs", "Cargo.*")
	})
	outputs.file(project.file("fried-egg/target/release/$optimizerName"))
	val stdOut = ByteArrayOutputStream()
	val stdErr = ByteArrayOutputStream()
	doLast {
		exec {
			workingDir(project.file("fried-egg"))
			commandLine("cargo", "build", "--release")
			standardOutput = stdOut
			errorOutput = stdErr
		}
		logger.lifecycle(stdOut.toString())
		val stde = stdErr.toString()
		if(stde.isNotEmpty()) {
			logger.lifecycle(stde)
		}
	}
}

val gimliDwarfJsonDumper = if (Os.isFamily(Os.FAMILY_WINDOWS)) {
	"Gimli-DWARF-JSONDump.exe"
} else {
	"Gimli-DWARF-JSONDump"
}

val dwarfJsonDump = project.task("gimli-dwarf-jsondump") {
	inputs.files(project.fileTree("scripts/Gimli-DWARF-JSONDump") {
		include("src/**/*.rs", "Cargo.*")
	})
	outputs.file(project.file("scripts/Gimli-DWARF-JSONDump/target/release/$gimliDwarfJsonDumper"))
	val stdOut = ByteArrayOutputStream()
	val stdErr = ByteArrayOutputStream()
	doLast {
		exec {
			workingDir(project.file("scripts/Gimli-DWARF-JSONDump"))
			environment("RUSTC_WRAPPER", "")
			commandLine("cargo", "build", "--release")
			standardOutput = stdOut
			errorOutput = stdErr
		}
		logger.lifecycle(stdOut.toString())
		val stde = stdErr.toString()
		if(stde.isNotEmpty()) {
			logger.lifecycle(stde)
		}
	}
}

apply<CertoraCupPlugin>()
apply<CertoraJFlexPlugin>()
configure<CupGenerateExtension> {
	mapping = {
		if(it == "TAC") {
			"ParserTAC"
		} else if(it == "TACInfixExpr") {
			"TACInfixExprParser"
		} else if(it == "minitac") {
			"MiniTACParser"
		} else {
			null
		}
	}
}
fun DependencyHandlerScope.applyCommon(projectName: String) {
	implementation("io.github.microutils:kotlin-logging:2.0.10")
	implementation("org.slf4j:slf4j-simple:2.0.0-alpha5")
	implementation("commons-cli:commons-cli:1.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
	implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.6")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
	implementation("com.github.komputing.khash:keccak:1.1.1")
	implementation("com.github.ajalt:clikt-multiplatform:2.6.0")
	implementation("org.apache.commons:commons-lang3:3.11")
	implementation("com.github.certora.collections:collect:${property("certora.collect.version")}")
	implementation("com.dylibso.chicory:runtime:${property("chicory.version")}")
	implementation("org.ow2.asm:asm:9.7.1")
	implementation("com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer:20240325.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:${property("junit.version")}")
    testImplementation("org.junit.jupiter:junit-jupiter-params:${property("junit.version")}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${property("junit.version")}")

	testImplementation("net.jqwik:jqwik:1.7.1")
	testImplementation("net.jqwik:jqwik-kotlin:1.7.1")
	testImplementation("io.mockk:mockk:1.14.2")

	testImplementation("org.jetbrains.kotlin:kotlin-reflect:${property("kotlin.version")}")

	jmh("org.openjdk.jmh:jmh-core:1.29")
	jmh("org.openjdk.jmh:jmh-generator-annprocess:1.29")

	if (projectName != "KspGeneration") {
		implementation(project(":KspGeneration"))
		ksp(project(":KspGeneration"))
	}

	if (projectName != "TestUtils") {
		testImplementation(project(":TestUtils"))
	}
}
dependencies {
	implementation("de.vandermeer:asciitable:0.3.2")
	implementation(project(":GeneralUtils"))
	implementation(project(":SMTLIBUtils"))
	implementation(project(":Shared"))

	implementation("net.fornwall:jelf:0.7.0")
	implementation("aws.sdk.kotlin:sqs:0.19.0-beta")

	testImplementation("com.dylibso.chicory:wasi:${property("chicory.version")}")
	testImplementation("com.github.certora:sui-move-build-kotlin:0.0.1")
}

allprojects {
	dependencies {
		applyCommon(name)
	}

	repositories {
		mavenLocal()
		mavenCentral()
		maven {
			url = uri("https://jitpack.io")
			name = "jitpack"
		}
	}
}

allprojects {
	ksp {
		arg("serializersModuleName", "serializersmodules.${project.name}")
	}
}

fun TaskContainer.copyJar(artifactId: String) {
	val artifactFilename = "${project.property(artifactId)}.jar"
	val copy = register<Copy>("copy-assets") {
		into(certoraJarsPath)

		from(shadowJar.get().outputs) {
			rename("-${project.property("version")}-all", "")
		}
	}

	assemble {
		dependsOn(copy)
	}

	jar {
		archiveFileName.set(artifactFilename)
	}
}

project(":KspGeneration") {
	dependencies {
		implementation("com.google.devtools.ksp:symbol-processing-api:${property("kotlin.version")}-${property("ksp.version")}")
		testImplementation("com.github.tschuchortdev:kotlin-compile-testing-ksp:1.5.0")
		kspTest(project(":KspGeneration"))
	}
}

project(":DetektRules") {
	// Update the version on every commit, to ensure rule changes get picked up by the gradle daemon.
	// Note that if you've made a change, but haven't committed it yet, you may still need to specify
	// --no-daemon on the Gradle command line.
	val gitVersion: groovy.lang.Closure<String> by extra // from https://github.com/palantir/gradle-git-version
	version = gitVersion()

	dependencies {
		compileOnly("io.gitlab.arturbosch.detekt:detekt-api:${property("detekt.version")}")
		compileOnly("io.gitlab.arturbosch.detekt:detekt-core:${property("detekt.version")}")
		testImplementation("io.gitlab.arturbosch.detekt:detekt-test:${property("detekt.version")}")
	}
}

project(":SMTLIBUtils") {
	apply<CertoraCupPlugin>()
	apply(plugin = "application")

	configure<CupGenerateExtension> {
		mapping = {
			if(it == "smtlib") {
				"ParserSMT"
			} else {
				null
			}
		}
	}

	application {
		mainClass.set("smtlibutils.SMTLIBUtilsEntryPointKt")
	}

	apply<CertoraJFlexPlugin>()

	tasks {
		copyJar("smtlibUtilsArtifactId")
	}

	dependencies {
		if(project.hasProperty("mathematicaHome")) {
			implementation(files("${project.property("mathematicaHome")}/SystemFiles/Links/JLink/JLink.jar"))
		}
		implementation(project(":GeneralUtils"))
	}

	sourceSets["main"].withConvention(org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet::class) {
		kotlin.srcDir("src/main/kotlin")
		if(!project.hasProperty("mathematicaHome")) {
			kotlin.exclude("smtlibutils/externalsolvers/mathematica/*.kt")
		}
	}
}

project(":Shared") {

	apply<CertoraCupPlugin>()

	configure<CupGenerateExtension> {
		mapping = {
			if(it == "cvl") {
				"ParserCVL"
			} else {
				null
			}
		}
	}

	apply<CertoraJFlexPlugin>()

	dependencies {
		implementation(project(":GeneralUtils"))
	}
}

project(":TestUtils") {
	dependencies {
	    implementation("org.junit.platform:junit-platform-launcher:${property("junit.platform.version")}")
		implementation(project(":GeneralUtils"))
	}
}


project(":Typechecker") {
	dependencies {
		implementation(project(":GeneralUtils"))
		implementation(project(":Shared"))
	}

	apply(plugin = "application")

	application {
		mainClass.set("EntryPointKt")
	}

	tasks {
		copyJar("typeCheckerArtifactId")
	}

}


project(":ASTExtraction") {
	dependencies {
		implementation(project(":GeneralUtils"))
		implementation(project(":Shared"))
	}

	apply(plugin = "application")

	application {
		mainClass.set("EntryPointKt")
	}

	tasks {
		copyJar("astExtractionArtifactId")
	}
}


val scripts = project.fileTree("scripts") {
	include(
		"certoraRun.py",
		"certoraSolanaProver.py",
		"certoraSorobanProver.py",
		"certoraSuiProver.py",
		"certoraEVMProver.py",
		"certoraRanger.py",
		"certoraConcord.py",
		"certoraMutate.py",
		"rustMutator.py",
		"certoraEqCheck.py",
		"CertoraProver/**/*.py",
		"Shared/*.py",
		"Mutate/*.py",
		"EquivalenceCheck/*.py",
		"EquivalenceCheck/*.conf",
		"EquivalenceCheck/*.spec",
		"certora-select",
		"certoraCVLFormatter.py"
	)
	exclude("**/__init__.py")
}


val installPath = provider {
	System.getenv().let {env ->
		if("CERTORA" in env) {
			project.file(env["CERTORA"]!!)
		} else {
			project.file("target/installed")
		}
	}
}

val certoraJarsPath = installPath.map {
	File(it.absolutePath+"/certora_jars")
}

val genResourceDir = project.file("${project.buildDir}/generated-resources")

sourceSets {
	main {
		resources {
			srcDir(genResourceDir)
		}
	}
}

kotlin {
	sourceSets.main {
		kotlin.srcDir("build/generated/ksp/main/kotlin")
	}
}

tasks {
	register<Zip>("package") {
		archiveClassifier.set("package")
		from(getByName("shadowJar").outputs)
		from(scripts)
		archiveBaseName.set("${project.property("artifactId")}")
	}

	val copy = register<Copy>("copy-assets") {
		into(installPath)
		from(shadowJar.get().outputs) {
			rename("-${project.property("version")}-jar-with-dependencies","")
		}
		from(scripts)
		from(egg)
		from(dwarfJsonDump)

		doFirst {
			delete(installPath.get().resolve(optimizerName))
		}

		doLast {
			val certoraRunPath = installPath.get().resolve("certoraRun.py")
			certoraRunPath.setExecutable(true)
			val certoraEqCheckPath = installPath.get().resolve("certoraEqCheck.py")
			certoraEqCheckPath.setExecutable(true)
		}
	}

	val gradleVersionTask = register<Task>("git-version-resource") {
		if(!project.hasProperty("testing")) {
			val versionDetails by lazy {
				(project.extensions.extraProperties.get("versionDetails") as Callable<com.palantir.gradle.gitversion.VersionDetails>)()
			}
			val versionString by lazy {
				"${versionDetails.gitHash}${
					versionDetails.branchName?.let {
						"($it)"
					} ?: ""
				}${".dirty".takeIf { !versionDetails.isCleanTag } ?: ""}"
			}
			val where = project.file("$genResourceDir/certora-version.properties")
			outputs.file(where)
			doLast {
				val p = Prop()
				p.setProperty("certora.version", versionString)
				val branchName = versionDetails.branchName ?: "(in-detached-head-state)"
				p.setProperty("git.branch.name", branchName)
				p.setProperty("git.hash", versionDetails.gitHash)
				p.setProperty("git.hash.full", versionDetails.gitHashFull)
				p.setProperty("git.last.tag", versionDetails.lastTag)
				p.setProperty(
					"git.is.clean", if (versionDetails.isCleanTag) {
						"true"
					} else {
						"false"
					}
				)
				p.store(project.file(where).outputStream(), "")
			}
			outputs.upToDateWhen {
				where.exists() && run {
					try {
						val p = Prop()
						p.load(where.bufferedReader())
						p.getProperty("certora.version") == versionString
					} catch (x: Throwable) {
						false
					}
				}
			}
		}
	}

	processResources {
		dependsOn.add(gradleVersionTask)
	}

	assemble {
		dependsOn(copy)
	}

	jar {
		archiveFileName.set("${project.property("artifactId")}.jar")
	}

	test {
		dependsOn(copy)
	}

	val res = register<Task>("internal-version-resource") {
		val where = project.file("${project.buildDir}/tmp/certora.properties")
		outputs.file(where)
		doLast {
			val p = Prop()
			p.setProperty("certora.cvt.deploy-seed", System.currentTimeMillis().toString())
			p.store(project.file(where).outputStream(), "")
		}
		outputs.upToDateWhen {
			false
		}
	}

	clean {
		doLast {
			exec {
				workingDir("fried-egg")
				commandLine("cargo", "clean")

				workingDir("scripts/Gimli-DWARF-JSONDump")
				commandLine("cargo", "clean")
			}
		}
	}


	register<Zip>("deploy-jar") {
		archiveExtension.set("jar")
		archiveBaseName.set("emv-prod")
		dependsOn(shadowJar)
		dependsOn(res)
		from(zipTree(shadowJar.get().outputs.files.first()))
		from(getByName("internal-version-resource").outputs)
	}
}
