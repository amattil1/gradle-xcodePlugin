package org.openbakery.carthage

import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.internal.impldep.org.apache.commons.io.FileUtils
import org.gradle.internal.logging.text.StyledTextOutput
import org.gradle.internal.logging.text.StyledTextOutputFactory
import org.openbakery.AbstractXcodeTask
import org.openbakery.output.ConsoleOutputAppender
import org.openbakery.xcode.Type

import java.nio.charset.Charset

abstract class AbstractCarthageTaskBase extends AbstractXcodeTask {

	static final String ACTION_BOOTSTRAP = "bootstrap"
	static final String ACTION_UPDATE = "update"
	static final String ARGUMENT_CACHE_BUILDS = "--cache-builds"
	static final String ARGUMENT_PLATFORM = "--platform"
	static final String ARGUMENT_DERIVED_DATA = "--derived-data"
	static final String CARTHAGE_FILE = "Cartfile"
	static final String CARTHAGE_FILE_RESOLVED = "Cartfile.resolved"
	static final String CARTHAGE_PLATFORM_IOS = "iOS"
	static final String CARTHAGE_PLATFORM_MACOS = "Mac"
	static final String CARTHAGE_PLATFORM_TVOS = "tvOS"
	static final String CARTHAGE_PLATFORM_WATCHOS = "watchOS"
	static final String CARTHAGE_USR_BIN_PATH = "/usr/local/bin/carthage"

	AbstractCarthageTaskBase() {
		super()
	}

	@Input
	@Optional
	String getRequiredXcodeVersion() {
		return getProjectXcodeVersion()
	}

	@InputFile
	@Optional
	@PathSensitive(PathSensitivity.RELATIVE)
	Provider<File> getCartFile() {
		// Cf https://github.com/gradle/gradle/issues/2016
		File file = project.rootProject.file(CARTHAGE_FILE)
		return project.provider {
			file.exists() ? file
					: File.createTempFile(CARTHAGE_FILE, "")
		}
	}

	@InputFile
	@Optional
	@PathSensitive(PathSensitivity.RELATIVE)
	Provider<File> getCartResolvedFile() {
		// Cf https://github.com/gradle/gradle/issues/2016
		File file = project.rootProject.file(CARTHAGE_FILE_RESOLVED)
		return project.provider {
			file.exists() ? file
					: File.createTempFile(CARTHAGE_FILE_RESOLVED, "resolved")
		}
	}

	@Input
	@Optional
	String getCarthagePlatformName() {
		switch (project.xcodebuild.type) {
			case Type.iOS: return CARTHAGE_PLATFORM_IOS
			case Type.tvOS: return CARTHAGE_PLATFORM_TVOS
			case Type.macOS: return CARTHAGE_PLATFORM_MACOS
			case Type.watchOS: return CARTHAGE_PLATFORM_WATCHOS
			default: return 'all'
		}
	}

	@OutputDirectory
	Provider<File> getOutputDirectory() {
		return project.provider {
			project.rootProject.file("Carthage/Build/" + getCarthagePlatformName())
		}
	}


	@Internal
	String getCarthageCommand() {
		try {
			return commandRunner.runWithResult("which", "carthage")
		} catch (CommandRunnerException) {
			// ignore, because try again with full path below
		}

		try {
			commandRunner.runWithResult("ls", CARTHAGE_USR_BIN_PATH)
			return CARTHAGE_USR_BIN_PATH
		} catch (CommandRunnerException) {
			// ignore, because blow an exception is thrown
		}
		throw new IllegalStateException("The carthage command was not found. Make sure that Carthage is installed")
	}

	boolean hasCartfile() {
		return project.rootProject
				.file(CARTHAGE_FILE)
				.exists()
	}

	void run(String command, StyledTextOutput output) {

		if (!hasCartfile()) {
			logger.debug("No Cartfile found, so we are done")
			return
		}

		logger.info('Update Carthage for platform ' + carthagePlatformName)
		def derivedDataPath = new File(project.xcodebuild.derivedDataPath, "carthage")

		List<String> args = [getCarthageCommand(),
												 command,
												 ARGUMENT_PLATFORM,
												 carthagePlatformName
		]
		if (project.carthage.cache) {
			args << ARGUMENT_CACHE_BUILDS
		}
		args << ARGUMENT_DERIVED_DATA
		args << derivedDataPath.absolutePath

		commandRunner.run(project.projectDir.absolutePath,
			args,
			getEnvironment(),
			new ConsoleOutputAppender(output))

	}

	@Internal
	Map<String, String> getEnvironment() {
		Map<String, String> environment = new HashMap<String, String>()
		File xconfigFile = createXCConfigIfNeeded()
		if (xconfigFile != null) {
			environment.put("XCODE_XCCONFIG_FILE", xconfigFile.absolutePath)
		}
		if (getRequiredXcodeVersion() != null) {
			environment.putAll(xcode.getXcodeSelectEnvironmentValue(getRequiredXcodeVersion()))
		}
		return environment
	}

	File createXCConfigIfNeeded() {
		if (this.xcode.version.major == 12) {
			File xconfigFile = new File(project.rootProject.file("Carthage"), "gradle-xc12-carthage.xcconfig")

			String xcodeBuildVersion = xcode.getBuildVersion()

			String contents = ""
			contents += 'EXCLUDED_ARCHS__EFFECTIVE_PLATFORM_SUFFIX_simulator__NATIVE_ARCH_64_BIT_x86_64__XCODE_1200__BUILD_' + xcodeBuildVersion + ' = arm64 arm64e armv7 armv7s armv6 armv8\n'
			contents += 'EXCLUDED_ARCHS__EFFECTIVE_PLATFORM_SUFFIX_simulator__NATIVE_ARCH_64_BIT_x86_64__XCODE_1200 = $(EXCLUDED_ARCHS__EFFECTIVE_PLATFORM_SUFFIX_simulator__NATIVE_ARCH_64_BIT_x86_64__XCODE_1200__BUILD_$(XCODE_PRODUCT_BUILD_VERSION))\n'
			contents += 'EXCLUDED_ARCHS = $(inherited) $(EXCLUDED_ARCHS__EFFECTIVE_PLATFORM_SUFFIX_$(EFFECTIVE_PLATFORM_SUFFIX)__NATIVE_ARCH_64_BIT_$(NATIVE_ARCH_64_BIT)__XCODE_$(XCODE_VERSION_MAJOR))\n'
			contents += 'ONLY_ACTIVE_ARCH=NO\n'
			contents += 'VALID_ARCHS = $(inherited) x86_64\n'

			FileUtils.writeStringToFile(xconfigFile, contents, Charset.forName("UTF-8"))

			return xconfigFile
		}
		return null

	}

}
