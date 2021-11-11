package org.openbakery.cocoapods

import org.apache.commons.io.FileUtils
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.openbakery.CommandRunner
import org.openbakery.CommandRunnerException
import org.openbakery.XcodePlugin
import spock.lang.Specification

class CocoapodsUpdateTaskSpecification extends Specification {

	Project project
	CocoapodsUpdateTask cocoapodsTask;

	CommandRunner commandRunner = Mock(CommandRunner)

	def setup() {

		File projectDir =  new File(System.getProperty("java.io.tmpdir"), "gradle-xcodebuild")

		project = ProjectBuilder.builder().withProjectDir(projectDir).build()
		project.buildDir = new File('build').absoluteFile
		project.apply plugin:org.openbakery.XcodePlugin

		cocoapodsTask = project.getTasks().getByPath('cocoapodsUpdate')

		cocoapodsTask.commandRunner = commandRunner

	}

	def cleanup() {
		FileUtils.deleteDirectory(project.projectDir)
	}

	def "run pod setup"() {
		given:
		commandRunner.runWithResult("ruby", "-rubygems", "-e", "puts Gem.user_dir") >> "/usr/local"
		commandRunner.runWithResult("which", "pod") >> "/usr/local/bin/pod"

		when:
		cocoapodsTask.update()

		then:
		1 * commandRunner.run(project.projectDir.absolutePath, ["/usr/local/bin/pod", "setup"], _)
	}

	def "update pods"() {
		given:
		commandRunner.runWithResult("ruby", "-rubygems", "-e", "puts Gem.user_dir") >> "/usr/local"
		commandRunner.runWithResult("which", "pod") >> "/usr/local/bin/pod"

		when:
		cocoapodsTask.update()

		then:
		1 * commandRunner.run(project.projectDir.absolutePath, ["/usr/local/bin/pod", "update"], _)
	}

	def "update pods with user cocoapods"() {
		given:
		cocoapodsTask.dependsOn(XcodePlugin.COCOAPODS_BOOTSTRAP_TASK_NAME)
		commandRunner.runWithResult("ruby", "-rubygems", "-e", "puts Gem.user_dir") >> "/tmp/gems"

		when:
		cocoapodsTask.update()

		then:
		1 * commandRunner.run(project.projectDir.absolutePath, ["/tmp/gems/bin/pod", "update"], _)
	}

	def "depends on"() {
		when:
		cocoapodsTask.commandRunner = commandRunner
		commandRunner.runWithResult("which", "pod") >> { throw new CommandRunnerException() }
		cocoapodsTask.addBootstrapDependency()

		then:
		cocoapodsTask.getDependsOn().contains(XcodePlugin.COCOAPODS_BOOTSTRAP_TASK_NAME)
	}

/* @rpirringer: This tests does not work properly because the constructor is executed when the task is created.
It is not possible to create an instance of the CocoapodsUpdateTask directory with new, therefor this is hard
to test. I have disabled these tests, because I don't know if it makes sense to bootstrap cocoapods. Maybe I will
remove the bootstrap in the future.
	def "not depends on bootstrap"() {
		when:

		cocoapodsTask.commandRunner = commandRunner
		commandRunner.runWithResult("which", "pod") >> "/usr/local/bin/pod"

		cocoapodsTask.addBootstrapDependency()

		then:
		!cocoapodsTask.getDependsOn().contains(XcodePlugin.COCOAPODS_BOOTSTRAP_TASK_NAME)
	}


	def "not depends on bootstrap, pod install locally"() {
		when:

		cocoapodsTask.commandRunner = commandRunner
		commandRunner.runWithResult("which", "pod") >> "/Users/build/.rvm/gems/ruby-2.3.0/bin/pod"

		cocoapodsTask.addBootstrapDependency()

		then:
		!cocoapodsTask.getDependsOn().contains(XcodePlugin.COCOAPODS_BOOTSTRAP_TASK_NAME)
	}


	def "runPod install locally"() {
		when:
		commandRunner.runWithResult("which", "pod") >> "/Users/build/.rvm/gems/ruby-2.3.0/bin/pod"
		cocoapodsTask.runPod("setup")

		then:
		1 * commandRunner.run(project.projectDir.absolutePath, ["/Users/build/.rvm/gems/ruby-2.3.0/bin/pod", "setup"], _)

	}
*/
}
