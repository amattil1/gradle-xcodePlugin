package org.openbakery.test

import groovy.json.JsonSlurper
import groovy.xml.MarkupBuilder
import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration
import org.openbakery.CommandRunner
import org.openbakery.xcode.Destination
import org.slf4j.Logger
import org.slf4j.LoggerFactory

public class TestResultParser {

	private static Logger logger = LoggerFactory.getLogger(TestResultParser.class)


	def TEST_CASE_PATTERN = ~/^Test Case '(.*)'(.*)/

	def TEST_CLASS_PATTERN = ~/-\[([\w\.]*)\s(\w*)\]/

	def TEST_FAILED_PATTERN = ~/.*\*\* TEST FAILED \*\*/
	def TEST_SUCCEEDED_PATTERN = ~/.*\*\* TEST SUCCEEDED \*\*/

	def DURATION_PATTERN = ~/^\w+\s\((\d+\.\d+).*/


	File testSummariesDirectory
	List<Destination> destinations
	String xcresulttoolPath

	def testResults = new HashMap<Destination, ArrayList<TestClass>>()

	public TestResultParser(File testSummariesDirectory, String xcresulttoolPath, List<Destination> destinations) {
		this.testSummariesDirectory = testSummariesDirectory
		this.destinations = destinations
		this.xcresulttoolPath = xcresulttoolPath
	}

	public void parseAndStore(File outputDirectory) {
		parse()
		store(outputDirectory)
	}

	private void parse() {
		if (!testSummariesDirectory.exists()) {
			return
		}

		if (isTestSummaryPlistAvailable()) {
			parseTestSummaryFile()
		} else {
			parseXcResult()
		}
	}

	private void parseXcResult() {
		logger.debug("Using new xcresult scheme version")
		def files = testSummariesDirectory.listFiles({ d, f -> f.endsWith(".xcresult") } as FilenameFilter)
		files.each {
			if (xcresulttoolPath == null) {
				logger.debug("No xcresulttool found.")
				return
			}
			def xcResult = loadXCResultFile(it)
			def identifier = xcResult.actions._values.first().runDestination.targetDeviceRecord.identifier._value
			logger.debug("identifier {}", identifier)
			Destination destination = findDestinationForIdentifier(destinations, identifier)
			if (destination) {
				def resultList = processTestSummary(xcResult, it)
				testResults.put(destination, resultList)
			} else {
				logger.debug("destination not found for identifier {}", identifier)
			}

		}
	}

	private void parseTestSummaryFile() {

		List<String> testSummariesArray = new FileNameFinder()
			.getFileNames(testSummariesDirectory.path, '*TestSummaries.plist *.xcresult/*_Test/action_TestSummaries.plist')

		if (testSummariesArray == null) {
			return
		}

		logger.debug("parsing xcresult scheme version < 3")
		testSummariesArray.toList().each {
			def testResult = new XMLPropertyListConfiguration()
			testResult.read(new FileReader(it))

			def identifier = testResult.getString("RunDestination.TargetDevice.Identifier")

			Destination destination = findDestinationForIdentifier(destinations, identifier)
			if (destination != null) {
				def resultList = processLegacyTestSummary(testResult.getList("TestableSummaries"))
				testResults.put(destination, resultList)
			}
		}
	}

	private Boolean isTestSummaryPlistAvailable() {
		def testSummaries = new FileNameFinder()
			.getFileNames(testSummariesDirectory.path, '*TestSummaries.plist *.xcresult/*_Test/action_TestSummaries.plist')

		return !testSummaries.isEmpty()
	}

	Map<String, Object> loadXCResultFile(File file) {
		logger.info("load result file {}", file)
		def runner = new CommandRunner()
		def result = runner.runWithResult(xcresulttoolPath, "get", "--format", "json", "--path", file.absolutePath)
		def json = new JsonSlurper()
		def object = json.parseText(result)
		return object
	}

	ArrayList<TestClass> processTestSummary(Map<String, Object> xcResult, File file) {
		logger.debug("processTestSummary")
		def runner = new CommandRunner()
		def json = new JsonSlurper()

		def testsRef = xcResult.actions._values.actionResult.testsRef.id._value[0]
		if (testsRef == null) {
			logger.debug("No tests reference found, skipping test result parsing")
			return []
		}

		def testsResults = runner.runWithResult(xcresulttoolPath, "get", "--format", "json", "--path", file.absolutePath, "--id", testsRef)
		def results = json.parseText(testsResults)
		def testStatus = new ArrayList<TestClass>()

		if (results.summaries != null && results.summaries._values == null) {
			logger.debug("No test result summaries present")
			return []
		}

		results.summaries._values.each { summaryItem ->
			if (summaryItem.testableSummaries != null && summaryItem.testableSummaries._values != null) {
				summaryItem.testableSummaries._values.each { testableSummaryItem ->
					if (testableSummaryItem.tests != null && testableSummaryItem.tests._values != null) {
						testableSummaryItem.tests._values.each { testItem ->
							addTestResultWithStatusToTestClass(testItem, testStatus, null)
						}
					} else {
						logger.debug("tests are empty")
					}
				}
			} else {
				logger.debug.debug("testable summaries are empty")
			}
		}

		return testStatus
	}

	private void addTestResultWithStatusToTestClass(Map<String, Object> testData, ArrayList<TestClass> testsStatus, TestClass testClass) {
		if (testData.testStatus) {
			TestResult.State state
			def value = testData.testStatus._value
			if (value == "Success") {
				state = TestResult.State.Passed
			} else if (value == "Skipped") {
				state = TestResult.State.Skipped
			} else {
				state = TestResult.State.Failed
			}
			def testResult = new TestResult(method: testData.identifier._value, state: state)
			testClass.results.add(testResult)
		}

		if (testData.subtests && testData.subtests._values && testData._type._name == "ActionTestSummaryGroup") {
			testClass = new TestClass(name: testData.name._value)
			testData.subtests._values.each {
				addTestResultWithStatusToTestClass(it, testsStatus, testClass)
			}
			if (!testClass.results.empty) {
				testsStatus << testClass
			}
		}
	}

	private void store(File outputDirectory) {
		logger.debug("store to test-result.xml")

		FileWriter writer = new FileWriter(new File(outputDirectory, "test-results.xml"))

		def xmlBuilder = new MarkupBuilder(writer)

		xmlBuilder.testsuites() {
			for (result in testResults) {
				String name = result.key.toPrettyString()

				def resultList = result.value
				int success = 0;
				int errors = 0;
				if (resultList != null) {
					success = number(resultList, TestResult.State.Passed);
					errors = number(resultList, TestResult.State.Failed);
				}

				testsuite(name: name, tests: success, errors: errors, failures: "0", skipped: "0") {

					for (TestClass testClass in resultList) {

						for (TestResult testResult in testClass.results) {
							logger.debug("testResult: {}", testResult)
							testcase(classname: testClass.name, name: testResult.method, time: testResult.duration) {
								if (testResult.state == TestResult.State.Failed) {
									error(type: "failure", message: "", testResult.output)
								}
								'system-out'(testResult.output)
							}
						}

					}

				}
			}
		}


	}

	Destination findDestinationForIdentifier(List<Destination> destinations, String identifier) {
		for (destination in destinations) {
			if (destination.id == identifier) {
				return destination
			}
		}
		return null
	}


	HashMap<Destination, ArrayList<TestClass>> parseResult(File outputFile) {
		def testResults = new HashMap<Destination, ArrayList<TestClass>>()
		logger.debug("parse result from: {}", outputFile)
		if (!outputFile.exists()) {
			logger.info("No xcodebuild output file found!");
			return testResults;
		}
		boolean overallTestSuccess = true;

		def resultList = []

		int testRun = 0;
		boolean endOfDestination = false;

		StringBuilder output = new StringBuilder()

		outputFile.eachLine { line ->


			def matcher = TEST_CASE_PATTERN.matcher(line)
			if (matcher.matches()) {

				String message = matcher[0][2].trim()

				def nameMatcher = TEST_CLASS_PATTERN.matcher(matcher[0][1])
				if (!nameMatcher.matches()) {
					return
				}

				String testClassName = nameMatcher[0][1]
				String method = nameMatcher[0][2]


				if (message.startsWith("started")) {
					output = new StringBuilder()



					TestClass testClass = resultList.find { testClass -> testClass.name.equals(testClassName) }
					if (testClass == null) {
						testClass = new TestClass(name: testClassName);
						resultList << testClass
					}
					testClass.results << new TestResult(method: method)

				} else {
					//TestCase testCase = resultMap.get(testClass).find{ testCase -> testCase.method.equals(method) }
					TestClass testClass = resultList.find { testClass -> testClass.name.equals(testClassName) }
					if (testClass != null) {
						TestResult testResult = testClass.results.find { testResult -> testResult.method.equals(method) }

						if (testResult != null) {
							testResult.output = output.toString()

							if (message.toLowerCase().startsWith("failed")) {
								testResult.state = TestResult.State.Failed
								logger.info("test + " + testResult + "failed!")
								overallTestSuccess = false;
							} else if (message.toLowerCase().startsWith("skipped")) {
								testResult.state = TestResult.State.Skipped
							} else {
								testResult.state = TestResult.State.Passed
							}


							def durationMatcher = DURATION_PATTERN.matcher(message)
							if (durationMatcher.matches()) {
								testResult.duration = Float.parseFloat(durationMatcher[0][1])
							}
						}
					} else {
						logger.info("No TestClass found for name: " + testClassName + " => " + line)
					}
				}
			}

			def testSuccessMatcher = TEST_SUCCEEDED_PATTERN.matcher(line)
			def testFailedMatcher = TEST_FAILED_PATTERN.matcher(line)

			if (testSuccessMatcher.matches() || testFailedMatcher.matches()) {
				testRun++;
				endOfDestination = true
			}

			if (testFailedMatcher.matches()) {
				overallTestSuccess = false;
			}


			if (endOfDestination) {
				Destination destination = destinations[(testRun - 1)]

				if (testResults.containsKey(destination)) {
					def destinationResultList = testResults.get(destination)
					destinationResultList.addAll(resultList);
				} else {
					testResults.put(destination, resultList)
				}

				resultList = []
				endOfDestination = false
			} else {
				if (output != null) {
					if (output.length() > 0) {
						output.append("\n")
					}
					output.append(line)
				}
			}
		}
		return testResults;
	}


	List<TestClass> processLegacyTestSummary(List<XMLPropertyListConfiguration> list) {

		List<TestClass> resultList = []

		for (entry in list) {
			List<XMLPropertyListConfiguration> testList = entry.getList("Tests")
			processTests(testList, resultList)
		}

		return resultList

	}

	List<TestClass> processTests(List<XMLPropertyListConfiguration> list, List<TestClass> resultList) {
		for (entry in list) {
			List<XMLPropertyListConfiguration> testList = entry.getList("Subtests")
			processSubtests(testList, resultList, entry.getString("TestName"))
		}


	}


	List<TestClass> processSubtests(List<XMLPropertyListConfiguration> list, List<TestClass> resultList, String name) {

		TestClass testClass = null

		for (entry in list) {
			if (entry.getString("TestObjectClass") == "IDESchemeActionTestSummary") {
				if (testClass == null) {
					testClass = new TestClass(name: name);
					resultList << testClass
				}

				String method = entry.getString("TestName")
				TestResult testResult = new TestResult(method: method)

				if (entry.getString("TestStatus") == "Success") {
					testResult.state = TestResult.State.Passed
				} else {
					testResult.state = TestResult.State.Failed
				}
				testClass.results << testResult

			} else if (entry.getString("TestObjectClass") == "IDESchemeActionTestSummaryGroup") {
				List<XMLPropertyListConfiguration> testList = entry.getList("Subtests")
				processSubtests(testList, resultList, entry.getString("TestName"))
			}


		}

	}


	public int number(TestResult.State state) {
		return number(testResults, state)
	}


	public int number(HashMap<Destination, ArrayList<TestClass>> results, TestResult.State state) {
		int success = 0;
		for (List list in results.values()) {
			success += number(list, state)
		}
		return success;
	}



	int number(java.util.List results, TestResult.State state) {
		int number = 0;
		for (TestClass testClass in results) {
			number += testClass.number(state)
		}
		return number
	}


	def escapeString(String string) {
		if (string == null) {
			return null;
		}
		StringBuffer buffer = new StringBuffer();

		for (int i = 0; i < string.length(); i++) {
			char ch = string.charAt(i);
			switch (ch) {
				case '"':
					buffer.append("\\\"");
					break;
				case '\\':
					buffer.append("\\\\");
					break;
				case '\b':
					buffer.append("\\b");
					break;
				case '\f':
					buffer.append("\\f");
					break;
				case '\n':
					buffer.append("\\n");
					break;
				case '\r':
					buffer.append("\\r");
					break;
				case '\t':
					buffer.append("\\t");
					break;
				case '/':
					buffer.append("\\/");
					break;
				default:
					buffer.append(ch);
					break;
			}
		}
		return buffer.toString();
	}

	void mergeResult(HashMap<Destination, ArrayList<TestClass>> fromPlist, HashMap<Destination, ArrayList<TestClass>> fromOutput) {

		fromPlist.each { destination, testClasses ->
			def secondDestinationClasses = fromOutput.get(destination)
			if (secondDestinationClasses != null) {
				mergeTestClasses(testClasses, secondDestinationClasses)
			}
		}
	}


	def mergeTestClasses(ArrayList<TestClass> fromPlist, ArrayList<TestClass> fromOutput) {
		fromPlist.each { testClass ->
			def testClassFromOutput = fromOutput.find { it.name == testClass.name }
			if (testClassFromOutput != null) {
				mergeTestResults(testClass.results, testClassFromOutput.results)
			}

		}

	}

	def mergeTestResults(List<TestResult> fromPlist, List<TestResult> fromOutput) {
		fromPlist.each { testResult ->
			def testResultFromOutput = fromOutput.find { it.method == testResult.method }
			if (testResultFromOutput != null) {
				testResult.duration = testResultFromOutput.duration
				testResult.output = testResultFromOutput.output
			}
		}
	}
}
