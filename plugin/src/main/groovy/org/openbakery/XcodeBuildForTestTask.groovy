package org.openbakery

import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.openbakery.xcode.Destination
import org.openbakery.xcode.Type
import org.openbakery.xcode.Xcodebuild

class XcodeBuildForTestTask extends AbstractXcodeBuildTask {

	@Internal
	File outputDirectory

	XcodeBuildForTestTask() {
		super()
		dependsOn(
			XcodePlugin.XCODE_CONFIG_TASK_NAME,
			XcodePlugin.SIMULATORS_KILL_TASK_NAME,
			XcodePlugin.COCOAPODS_INSTALL_TASK_NAME,
			XcodePlugin.CARTHAGE_BOOTSTRAP_TASK_NAME
		)
		this.description = "Builds the xcode project and test target. Creates a testbundle that contains the result."
	}

	@Internal
	Xcodebuild getXcodebuild() {
		// Start with the destinations requested by the project
		List<Destination> destinations = getDestinations()

		if (parameters.type == Type.tvOS) {
			// Get all destinations available for tvOS projects
			Boolean isSimulator = parameters.simulator
			if (isSimulator == null) {
				isSimulator = Boolean.FALSE
			}
			destinations = getDestinationResolver().getAllDestinations(parameters.type, isSimulator)
		}

		return new Xcodebuild(project.projectDir, commandRunner, xcode, parameters, destinations)
	}

	@TaskAction
	def buildForTest() {
		logger.debug("buildForTest")
		parameters = project.xcodebuild.xcodebuildParameters.merge(parameters)
		logger.debug("buildForTest parameters {}", parameters)
		if (parameters.scheme == null && parameters.target == null) {
			throw new IllegalArgumentException("No 'scheme' or 'target' specified, so do not know what to build")
		}

		outputDirectory = new File(project.getBuildDir(), "for-testing")
		if (!outputDirectory.exists()) {
			outputDirectory.mkdirs()
		}

		File outputFile = new File(outputDirectory, "xcodebuild-output.txt")
		commandRunner.setOutputFile(outputFile)

		xcodebuild.executeBuildForTesting(createXcodeBuildOutputAppender("XcodeBuildForTestTask"), project.xcodebuild.environment)

		createTestBundle()
	}

	def createTestBundle() {
		String bundleDirectory = project.xcodebuild.bundleName

		bundleDirectory += "-" + parameters.type

		if (parameters.simulator) {
			bundleDirectory += "-Simulator"
		}
		bundleDirectory += ".testbundle"

		File testBundleFile = new File(outputDirectory, bundleDirectory)
		testBundleFile.mkdirs()


		File xcrunfile = getXcruntestFile()
		if (xcrunfile != null) {
			copy(xcrunfile, testBundleFile)
			getAppBundles(xcrunfile).each {

				File source = new File(xcrunfile.parentFile, it)
				String path = new File(it).parent
				copy(source, new File(testBundleFile, path))
			}
		}
	}


	List<String> getAppBundles(File xcrunfile) {
		logger.debug("getAppBundles for {}", xcrunfile)

		List<String> result = []
		XMLPropertyListConfiguration config = new XMLPropertyListConfiguration()
		config.read(new FileReader(xcrunfile))

		for (key in config.getKeys()) {
			if (key.endsWith(".DependentProductPaths")) {
				String[] items = config.getStringArray(key)
				for (def item in items) {
					result << item - "__TESTROOT__/"
				}
				logger.debug("result appBundles for {}", result)
				return result
			}
		}
		logger.debug("result appBundles for {} is empty")
		return []
	}


	@Internal
	File getXcruntestFile() {
		def fileList = parameters.symRoot.list(
						[accept: { d, f -> f ==~ /.*xctestrun/ }] as FilenameFilter
		)

		if (fileList == null || fileList.toList().isEmpty()) {
			return null
		}
		return new File(parameters.symRoot, fileList.toList().get(0))
	}


}
