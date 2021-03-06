package com.javaclimber.jenkins.testswarmplugin;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.VariableResolver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Serializable;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.codehaus.jackson.map.ObjectMapper;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.tap4j.model.Comment;
import org.tap4j.model.Directive;
import org.tap4j.model.Plan;
import org.tap4j.model.TestResult;
import org.tap4j.model.TestSet;
import org.tap4j.producer.TapProducer;
import org.tap4j.util.DirectiveValues;
import org.tap4j.util.StatusValues;

/**
 * This is plugin is responsible for integrating TestSwarm into jenkins. It will
 * take all test case urls and post it to TestSwarm server
 * 
 * @author kevinnilson
 * 
 */
public class TestSwarmBuilder extends Builder {
	private ObjectMapper mapper = new ObjectMapper();

	protected final String CHAR_ENCODING = "iso-8859-1";

	// client id
	private final String CLIENT_ID = "fromJenkins";

	// state
	private final String STATE = "addjob";

	// browsers type
	private String chooseBrowsers;

	// job name
	private String jobName;
	private String jobNameCopy;

	// user name
	private String projectId;

	// password
	private String authToken;

	// max run
	private String maxRuns;

	// minimum passing
	private String minimumPassing;

	// test swarm server url
	private String testswarmServerUrl;

	/*
	 * How frequent this plugin will hit the testswarm job url to know about
	 * test suite results
	 */
	private String pollingIntervalInSecs;

	/*
	 * How long this plugin will wait to know about test suite results
	 */
	private String timeOutPeriodInMins;

	private TestSuiteData[] testSuiteList = new TestSuiteData[0];

	private TestSuiteData[] testSuiteListCopy;

	private String testswarmServerUrlCopy;

	// private TestTypeConfig testTypeConfig;

	private TestSwarmDecisionMaker resultsAnalyzer;

	public static final int UNKNOWN = 0;
	public static final int ALL_PASSING = 1;
	public static final int IN_PROGRESS_ENOUGH_PASSING_NO_ERRORS = 2;
	public static final int IN_PROGRESS_NOT_ENOUGH_PASSING_NO_ERRORS = 3;
	public static final int TIMEOUT_NOT_ENOUGH_PASSING_NO_ERRORS = 4;
	public static final int FAILURE_IN_PROGRESS = 5;
	public static final int FAILURE_DONE = 6;

	// Fields in config.jelly must match the parameter names in the
	// "DataBoundConstructor"
	@DataBoundConstructor
	public TestSwarmBuilder(String testswarmServerUrl, String jobName,
			String projectId, String authToken, String maxRuns,
			String chooseBrowsers, String pollingIntervalInSecs,
			String timeOutPeriodInMins, String minimumPassing,
			List<TestSuiteData> testSuiteList) {

		this.testswarmServerUrl = testswarmServerUrl;
		this.jobName = jobName;
		this.projectId = projectId;
		this.authToken = authToken;
		this.maxRuns = maxRuns;
		this.chooseBrowsers = chooseBrowsers;
		this.pollingIntervalInSecs = pollingIntervalInSecs;
		this.timeOutPeriodInMins = timeOutPeriodInMins;
		this.minimumPassing = minimumPassing;
		this.testSuiteList = testSuiteList
				.toArray(new TestSuiteData[testSuiteList.size()]);
		// this.testTypeConfig = testTypeConfig;
		this.resultsAnalyzer = new TestSwarmDecisionMaker();

	}

	@Exported
	public TestSuiteData[] getTestSuiteList() {
		return testSuiteList;
	}

	/**
	 * We'll use this from the <tt>config.jelly</tt>.
	 */
	public String getTestswarmServerUrl() {
		return testswarmServerUrl;
	}

	public String getChooseBrowsers() {
		return chooseBrowsers;
	}

	public String getJobName() {
		return jobName;
	}

	public String getProjectId() {
		return projectId;
	}

	public String getAuthToken() {
		return authToken;
	}

	public String getMaxRuns() {
		return maxRuns;
	}

	public String getPollingIntervalInSecs() {
		return pollingIntervalInSecs;
	}

	public String getTimeOutPeriodInMins() {
		return timeOutPeriodInMins;
	}

	/**
	 * Check if config file loc is a url
	 * 
	 * @return true if the configFileLoc is a valid url else return false
	 */
	public boolean isValidUrl(String urlStr) {

		try {
			URL url = new URL(urlStr);
			return url != null;
		} catch (Exception ex) {
			return false;
		}
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}

	@Override
	public boolean perform(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {

		listener.getLogger().println("");
		listener.getLogger()
				.println("Launching TestSwarm Integration Suite...");

		testswarmServerUrlCopy = new String(testswarmServerUrl);

		testSuiteListCopy = new TestSuiteData[testSuiteList.length];
		TestSuiteData copyData;
		TestSuiteData origData;
		for (int i = 0; i < testSuiteList.length; i++) {
			origData = (TestSuiteData) testSuiteList[i];
			copyData = new TestSuiteData(origData.testName, origData.testUrl,
					origData.testCacheCracker, origData.disableTest);
			// listener.getLogger().println(copyData.testName+"  --->  "+copyData.testUrl);

			if (origData.disableTest)
				listener.getLogger().println(
						"Test is disabled for : " + origData.testName);

			testSuiteListCopy[i] = copyData;

		}

		// resolve environmental variables
		expandRuntimeVariables(listener, build);

		// check all required parameters are entered
		if (this.getTestswarmServerUrl() == null
				|| this.getTestswarmServerUrl().length() == 0) {
			listener.error("TestSwarm Server Url is mandatory");
			build.setResult(Result.FAILURE);
			return false;
		}

		if (this.getJobName() == null || this.getJobName().length() == 0) {
			listener.error("Jobname is mandatory");
			build.setResult(Result.FAILURE);
			return false;
		}

		if (this.getProjectId() == null || this.getProjectId().length() == 0) {
			listener.error("ProjectId is mandatory");
			build.setResult(Result.FAILURE);
			return false;
		}

		if (this.getAuthToken() == null || this.getAuthToken().length() == 0) {
			listener.error("Auth Token is mandatory");
			build.setResult(Result.FAILURE);
			return false;
		}

		if (this.getMaxRuns() == null || this.getMaxRuns().length() == 0) {
			listener.error("Maximum number of runs is mandatory");
			build.setResult(Result.FAILURE);
			return false;
		} else {
			// Check for integer value
			try {
				Integer.parseInt(getMaxRuns());
			} catch (Exception parseEx) {
				listener.error("Maximum number of runs is not an integer");
				build.setResult(Result.FAILURE);
				return false;
			}
		}

		if (this.getPollingIntervalInSecs() == null
				|| this.getPollingIntervalInSecs().length() == 0) {
			listener.error("Polling interval is mandatory");
			build.setResult(Result.FAILURE);
			return false;
		} else {
			// Check for integer value
			try {
				Integer.parseInt(getPollingIntervalInSecs());
			} catch (Exception parseEx) {
				listener.error("Polling interval is not an integer");
				build.setResult(Result.FAILURE);
				return false;
			}
		}

		if (this.getTimeOutPeriodInMins() == null
				|| this.getTimeOutPeriodInMins().length() == 0) {
			listener.error("Timeout Period is mandatory");
			build.setResult(Result.FAILURE);
			return false;
		} else {
			// Check for integer value
			try {
				Integer.parseInt(getTimeOutPeriodInMins());
			} catch (Exception parseEx) {
				listener.error("Timeout period is not an integer");
				build.setResult(Result.FAILURE);
				return false;
			}
		}

		if (!isValidUrl(getTestswarmServerUrl())) {
			listener.error("Testswarm Server Url is not a valid url ! check your TestSwarm Integration Plugin configuration");
			build.setResult(Result.FAILURE);
			return false;
		}

		try {

			String data = "authID="
					+ URLEncoder.encode(projectId, "UTF-8") + "&authToken="
					+ URLEncoder.encode(authToken, "UTF-8") + "&jobName="
					+ jobName + "&runMax=" + maxRuns + "&browserSets[]="
					+ chooseBrowsers + buildTestSuitesQueryString();

			System.out.println(data);

			URL url = new URL(testswarmServerUrl + "/api.php?action=addjob");
			URLConnection conn = url.openConnection();
			conn.setDoOutput(true);
			OutputStreamWriter wr = new OutputStreamWriter(
					conn.getOutputStream());
			wr.write(data);
			wr.flush();

			// Get the response
			BufferedReader rd = new BufferedReader(new InputStreamReader(
					conn.getInputStream()));
			String line;
			String result = "";
			while ((line = rd.readLine()) != null) {
				listener.getLogger().println(line);
				result += line;
			}
			wr.close();
			rd.close();

			int jobId = -1;
			if (result == null || "".equals(result)) {
				listener.error("no result from job submission");
				build.setResult(Result.FAILURE);
				return false;
			}

			ObjectMapper mapper = new ObjectMapper(); // can reuse, share
														// globally
			Map<String, Object> resultMap = mapper.readValue(result, Map.class);

			Map<String, Object> addJobResult = (Map) resultMap.get("addjob");
			jobId = (Integer) addJobResult.get("id");

			String jobUrl = this.testswarmServerUrlCopy
					+ "/api.php?format=json&action=job&item=" + jobId;
			listener.getLogger()
					.println(
							"**************************************************************");
			listener.getLogger().println(
					"Your request is successfully posted to TestSwarm Server and "
							+ " you can view the result in the following URL");
			listener.getLogger().println(
					testswarmServerUrlCopy + "/job/" + jobId);

			listener.getLogger().println();

			listener.getLogger().println(jobUrl);
			listener.getLogger()
					.println(
							"**************************************************************");
			listener.getLogger().println("");
			listener.getLogger().println("Analyzing Test Suite Result....");
			int jobStatus = analyzeTestSuiteResults(jobUrl, build, listener);

			boolean jobResult = (jobStatus == ALL_PASSING || jobStatus == IN_PROGRESS_ENOUGH_PASSING_NO_ERRORS);

			if (jobResult) {
				build.setResult(Result.SUCCESS);
			} else {
				build.setResult(Result.FAILURE);
			}

			listener.getLogger().println(
					"Analyzing Test Suite Result COMPLETED...");

			if (jobStatus == ALL_PASSING)
				listener.getLogger().println("ALL PASSING");
			else if (jobStatus == IN_PROGRESS_ENOUGH_PASSING_NO_ERRORS)
				listener.getLogger()
						.println("ALL PASSING - SOME STILL RUNNING");
			else if (jobStatus == IN_PROGRESS_NOT_ENOUGH_PASSING_NO_ERRORS)
				listener.getLogger().println("FAILURE - NOT ENOUGH FINISHED");
			else if (jobStatus == FAILURE_DONE
					|| jobStatus == FAILURE_IN_PROGRESS)
				listener.getLogger().println("FAILURE");

			produceTAPReport(jobUrl, build, Integer.parseInt(minimumPassing),
					testswarmServerUrlCopy + "/job/" + jobId);
			return jobResult;

		} catch (Exception ex) {
			ex.printStackTrace();
			listener.error(ex.toString());
			build.setResult(Result.FAILURE);
			return false;

		}

	}

	// TODO add skipped
	@SuppressWarnings("unchecked")
	private void produceTAPReport(String jobUrl, AbstractBuild build,
			int minimumPassing, String jobFriendlyUrl) {
		try {

			List<TestSuiteData> disabledTests = new ArrayList<TestSwarmBuilder.TestSuiteData>();
			for (int i = 0; i < testSuiteListCopy.length; i++) {
				if (testSuiteListCopy[i].isDisableTest()) {
					disabledTests.add(testSuiteListCopy[i]);
				}
			}

			TapProducer tapProducer = new TapProducer();// TapProducerFactory.makeTap13YamlProducer();

			TestSet testSet = new TestSet();
			testSet.addComment(new Comment(jobFriendlyUrl));

			String json;

			json = this.resultsAnalyzer.grabPage(jobUrl);

			ObjectMapper mapper = new ObjectMapper(); // can reuse, share
			// globally

			Map<String, Object> resultMap = mapper.readValue(json, Map.class);

			Map<String, Object> job = (Map<String, Object>) resultMap
					.get("job");
			List<Map<String, Object>> runs = (ArrayList<Map<String, Object>>) job
					.get("runs");
			testSet.setPlan(new Plan(runs.size() + disabledTests.size()));
			int i = 1;
			for (Map<String, Object> run : runs) {
				Map<String, Integer> resultCount = new HashMap<String, Integer>();
				Map<String, List<String>> resultBrowsers = new HashMap<String, List<String>>();
				Map<String, Object> uaRuns = (Map<String, Object>) run
						.get("uaRuns");
				for (String ua : uaRuns.keySet()) {
					Map<String, Object> uaRun = (Map<String, Object>) uaRuns
							.get(ua);
					String runStatus = (String) uaRun.get("runStatus");
					Integer resultTypeCount = resultCount.get(runStatus);
					if (resultTypeCount == null) {
						resultTypeCount = new Integer(0);
					}
					resultTypeCount++;
					resultCount.put(runStatus, resultTypeCount);

					List<String> browserList = resultBrowsers.get(runStatus);
					if (browserList == null)
						browserList = new ArrayList<String>();
					browserList.add(ua);
					resultBrowsers.put(runStatus, browserList);
				}
				resultCount.remove("new");
				resultBrowsers.remove("new");

				TestResult testResult = null;
				if (resultCount.get("failed") == null)
					if (resultCount.get("passed") != null
							&& resultCount.get("passed") >= minimumPassing)
						testResult = new TestResult(StatusValues.OK, i);
					else {
						testResult = new TestResult(StatusValues.NOT_OK, i);
						testResult.addComment(new Comment("passing: "
								+ resultCount.get("passed") + " < "
								+ minimumPassing));
					}
				else {
					// failure
					testResult = new TestResult(StatusValues.NOT_OK, i);
					testResult.addComment(new Comment("failing - "
							+ resultBrowsers.get("failed")));
					testResult.addComment(new Comment("passed - "
							+ resultBrowsers.get("passed")));
					// TestSet testSubset = new TestSet();
					//
					// int subtestIndex = 0;
					//
					// for (String result : resultBrowsers.keySet()) {
					// if ("passed".equals(result)) {
					// List<String> uas = resultBrowsers.get(result);
					// for (String ua : uas) {
					// TestResult subtestResult = new TestResult(
					// StatusValues.OK, subtestIndex);
					// subtestResult
					// .setDescription((String) ((Map) run
					// .get("info")).get("name")
					// + " - " + ua);
					// testSubset.addTestResult(subtestResult);
					// subtestIndex++;
					// }
					// } else if ("failed".equals(result)) {
					// List<String> uas = resultBrowsers.get(result);
					// for (String ua : uas) {
					// TestResult subtestResult = new TestResult(
					// StatusValues.NOT_OK, subtestIndex);
					// subtestResult
					// .setDescription((String) ((Map) run
					// .get("info")).get("name")
					// + " - " + ua);
					// testSubset.addTestResult(subtestResult);
					// subtestIndex++;
					// }
					//
					// }
					// }
					//
					// if (subtestIndex > 0) {
					// testSubset.setPlan(new Plan(subtestIndex));
					// testResult.setSubtest(testSubset);
					// }
				}

				testResult.setDescription((String) ((Map) run.get("info"))
						.get("name"));
				testResult.addComment(new Comment((String) ((Map) run
						.get("info")).get("url")));
				testSet.addTestResult(testResult);
				i++;
			}

			for (TestSuiteData diabledTest : disabledTests) {
				TestResult testResult = new TestResult(StatusValues.OK, i);

				testResult.setDescription(diabledTest.testName);

				testResult.setDirective(new Directive(DirectiveValues.SKIP,
						"test disabled"));
				testSet.addTestResult(testResult);
				i++;
			}

			String tapStream = tapProducer.dump(testSet);
			System.out.println(tapStream);

			// File f = new
			// File(build.getProject().getRootDir().getAbsolutePath()
			// + File.separatorChar + "builds" + File.separatorChar
			// + String.valueOf(build.getNumber()) + File.separatorChar
			// + "tap", "tap-result.txt");

			File f = new File(build.getProject().getRootDir().getAbsolutePath()
					+ File.separatorChar + "workspace", "testswarm.tap");

			System.out.println("Writing TAP results to " + f.getAbsolutePath());
			PrintWriter out = new PrintWriter(f);
			out.write(tapStream);
			out.close();

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private void expandRuntimeVariables(BuildListener listener,
			AbstractBuild build) throws IOException, InterruptedException {
		VariableResolver<String> varResolver = build.getBuildVariableResolver();
		EnvVars env = build.getEnvironment(listener);
		this.jobNameCopy = Util.replaceMacro(this.getJobName(), varResolver);
		this.jobNameCopy = Util.replaceMacro(this.jobNameCopy, env);
		this.testswarmServerUrlCopy = Util.replaceMacro(
				this.getTestswarmServerUrl(), varResolver);
		this.testswarmServerUrlCopy = Util.replaceMacro(
				this.testswarmServerUrlCopy, env);

		for (int i = testSuiteListCopy.length - 1; i >= 0; i--) {
			// Ignore testcase if disbled
			if (!testSuiteListCopy[i].isDisableTest()) {
				testSuiteListCopy[i].setTestName(Util.replaceMacro(
						testSuiteListCopy[i].getTestName(), varResolver));
				testSuiteListCopy[i].setTestName(Util.replaceMacro(
						testSuiteListCopy[i].getTestName(), env));
				testSuiteListCopy[i].setTestUrl(Util.replaceMacro(
						testSuiteListCopy[i].getTestUrl(), varResolver));
				testSuiteListCopy[i].setTestUrl(Util.replaceMacro(
						testSuiteListCopy[i].getTestUrl(), env));
			}
		}
	}

	private void populateStaticDataInRequestString(StringBuffer requestStr)
			throws Exception {

		// Populate static data like user credentials and other properties
		requestStr.append("client_id=").append(CLIENT_ID).append("&state=")
				.append(STATE).append("&job_name=")
				.append(URLEncoder.encode(this.jobNameCopy, CHAR_ENCODING))
				.append("&user=").append(getProjectId()).append("&auth=")
				.append(getAuthToken()).append("&max=").append(getMaxRuns())
				.append("&browsers=").append(getChooseBrowsers());
	}

	private String buildTestSuitesQueryString() throws Exception {
		StringBuffer requestStr = new StringBuffer();

		for (int i = 0; i < testSuiteListCopy.length; i++) {
			// Ignore testcase if disbled
			if (!testSuiteListCopy[i].isDisableTest()) {
				encodeAndAppendTestSuiteUrl(requestStr,
						testSuiteListCopy[i].getTestName(),
						testSuiteListCopy[i].getTestUrl(),
						testSuiteListCopy[i].isTestCacheCracker());
			}
		}

		return requestStr.toString();
	}

	private void encodeAndAppendTestSuiteUrl(StringBuffer requestStr,
			String testName, String testSuiteUrl, boolean cacheCrackerEnabled)
			throws Exception {

		requestStr.append("&")
				.append(URLEncoder.encode("runNames[]", CHAR_ENCODING))
				.append("=").append(URLEncoder.encode(testName, CHAR_ENCODING))
				.append("&")
				.append(URLEncoder.encode("runUrls[]", CHAR_ENCODING))
				.append("=");
		requestStr.append(URLEncoder.encode(testSuiteUrl, CHAR_ENCODING));
		if (cacheCrackerEnabled) {
			if (testSuiteUrl.contains("?"))
				requestStr.append(URLEncoder.encode("&", CHAR_ENCODING));
			else
				requestStr.append(URLEncoder.encode("?", CHAR_ENCODING));

			requestStr
					.append(URLEncoder.encode(
							"cache_killer=" + System.currentTimeMillis(),
							CHAR_ENCODING));
		}
	}

	@SuppressWarnings("unchecked")
	private int analyzeTestSuiteResults(String jobUrl, AbstractBuild build,
			BuildListener listener) throws Exception {

		long secondsBetweenResultPolls = Long
				.parseLong(getPollingIntervalInSecs());
		long minutesTimeOut = Long.parseLong(getTimeOutPeriodInMins());

		long start = System.currentTimeMillis();
		// give testswarm 15 seconds to finish earlier activities
		try {
			Thread.sleep(15 * 1000);
		} catch (InterruptedException ex) {
			// ignore
		}
		String json;
		int jobStatus = UNKNOWN;
		while (start + (minutesTimeOut * 60000) > System.currentTimeMillis()
				&& jobStatus != ALL_PASSING && jobStatus != FAILURE_DONE) {
			json = this.resultsAnalyzer.grabPage(jobUrl);

			System.out.println(json);

			ObjectMapper mapper = new ObjectMapper(); // can reuse, share
			// globally

			Map<String, Object> resultMap = mapper.readValue(json, Map.class);

			jobStatus = resultsAnalyzer.jobStatus(resultMap,
					Integer.parseInt(minimumPassing), listener);
			if (jobStatus != ALL_PASSING && jobStatus != FAILURE_DONE) {

				listener.getLogger().println(
						"Sleeping for " + secondsBetweenResultPolls
								+ " seconds...");
				listener.getLogger().println();
				Thread.sleep(secondsBetweenResultPolls * 1000);
				listener.getLogger().println();
			}
		}

		return jobStatus;

	}

	/**
	 * Descriptor for {@link HelloWorldBuilder}. Used as a singleton. The class
	 * is marked as public so that it can be accessed from views.
	 * 
	 * <p>
	 * See <tt>views/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
	 * for the actual HTML fragment for the configuration screen.
	 */
	@Extension
	// this marker indicates Hudson that this is an implementation of an
	// extension point.
	public static final class DescriptorImpl extends
			BuildStepDescriptor<Builder> {
	

		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			// indicates that this builder can be used with all kinds of project
			// types
			return true;
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		public String getDisplayName() {
			return "TestSwarm Integration Test";
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData)
				throws FormException {
		
			return super.configure(req, formData);
		}

		@Override
		public Builder newInstance(StaplerRequest staplerRequest,
				JSONObject jsonObject) throws FormException {
			return super.newInstance(staplerRequest, jsonObject);
		}

		public DescriptorImpl() {
			super(TestSwarmBuilder.class);
			load();
		}


	}

	@ExportedBean
	public static final class TestSuiteData implements Serializable {

		@Exported
		public String testName;

		@Exported
		public String testUrl;

		@Exported
		public boolean testCacheCracker;

		@Exported
		public boolean disableTest;

		@DataBoundConstructor
		public TestSuiteData(String testName, String testUrl,
				boolean testCacheCracker, boolean disableTest) {
			this.testName = testName;
			this.testUrl = testUrl;
			this.testCacheCracker = testCacheCracker;
			this.disableTest = disableTest;
		}

		public void setTestName(String testName) {
			this.testName = testName;
		}

		public void setTestUrl(String testUrl) {
			this.testUrl = testUrl;
		}

		public String getTestName() {
			return testName;
		}

		public String getTestUrl() {
			return testUrl;
		}

		public String toString() {
			return "==> " + testName + ", " + testUrl + ", " + testCacheCracker
					+ "sss<==";
		}

		public boolean isTestCacheCracker() {
			return testCacheCracker;
		}

		public boolean isDisableTest() {
			return disableTest;
		}

	}

}
