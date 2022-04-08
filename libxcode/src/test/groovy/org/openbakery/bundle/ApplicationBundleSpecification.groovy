package org.openbakery.bundle

import org.openbakery.test.ApplicationDummy
import org.openbakery.testdouble.PlistHelperStub
import org.openbakery.xcode.Type
import spock.lang.Specification

class ApplicationBundleSpecification  extends Specification {

	ApplicationDummy applicationDummy

	def setup() {
		def tmpDirectory = new File(System.getProperty("java.io.tmpdir"), "gxp-test")
		applicationDummy = new ApplicationDummy(tmpDirectory)
	}

	def cleanup() {
		applicationDummy.cleanup()
		applicationDummy = null
	}

	ApplicationBundle createApplicationBundle(String filePath, Type type = Type.iOS) {
		def applicationPath = new File(filePath)
		def plistHelper = new PlistHelperStub()
		return new ApplicationBundle(applicationPath, type, false, plistHelper)
	}


	def "test bundleName from path"(def bundleName, def filePath) {
		expect:
		bundleName == createApplicationBundle(filePath).getBundleName()

		where:
		bundleName   | filePath
		"bundle.app" | "my/bundle.app"
		"1.app"      | "my/1.app"
		"my.app"     | "a/long/path/my/my.app"
		"bar"        | "foo/bar"
	}

	def "test application base path"() {
		when:
		def bundle  = createApplicationBundle("Example.app")

		then:
		bundle.mainBundle.path == new File("Example.app")
	}

	def "application has plugin bundle"() {
		when:
		File path = applicationDummy.create()
		applicationDummy.createPluginBundle()
		def bundle  = createApplicationBundle(path.absolutePath)

		then:
		bundle.getBundles().size() == 2
	}


	def "application bundle first is plugin bundle"() {
		when:
		File path = applicationDummy.create()
		applicationDummy.createPluginBundle()
		def bundle  = createApplicationBundle(path.absolutePath)

		then:
		!bundle.getBundles().first().isMain
	}


	def "application bundle last is main bundle"() {
		when:
		applicationDummy.create()
		applicationDummy.createPluginBundle()
		def bundle  = createApplicationBundle("Example.app")

		then:
		bundle.getBundles().last().isMain
	}



}
