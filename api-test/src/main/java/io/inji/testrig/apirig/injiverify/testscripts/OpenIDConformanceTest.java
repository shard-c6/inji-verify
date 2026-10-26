package io.inji.testrig.apirig.injiverify.testscripts;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.testng.ITest;
import org.testng.ITestResult;
import org.testng.Reporter;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.inji.testrig.apirig.injiverify.utils.InjiVerifyConfigManager;
import io.inji.testrig.apirig.injiverify.utils.InjiVerifyConstants;
import io.inji.testrig.apirig.injiverify.utils.InjiVerifyUtil;
import io.mosip.testrig.apirig.utils.AdminTestException;

/**
 * Runs the OpenID Foundation conformance suite against Inji Verify and reports each
 * conformance module as a TestNG result, so conformance lands in the same Extent/TestNG
 * report as the rest of this api-testrig.
 *
 * <p>The conformance runner (an external Python harness) drives the suite's REST API,
 * normalises the results and applies a benchmark gate. It writes one JSON document whose
 * shape is the runner-to-testrig contract. This class reads that document; it contains no
 * protocol logic of its own.
 *
 * <p>Where the result comes from, in order:
 * <ol>
 * <li>{@code conformanceResultFile} - an existing result file, e.g. produced by CI;</li>
 * <li>{@code conformanceRunnerDir} - the runner's directory; it is invoked with
 * {@code --component verify} against this testrig's own {@code injiVerifyBaseUrl}, so
 * conformance is measured on the same environment the other tests target.</li>
 * </ol>
 * With neither configured the conformance tests are skipped with an explanation, so
 * ordinary testrig runs are unaffected.
 *
 * <p>Each module becomes one test named {@code conformance.<component>.<module>}:
 * the gate's verdict PASS passes, FAIL fails, SKIP skips. One further test,
 * {@code conformance.<component>.gate}, fails if the run as a whole failed its benchmark
 * gate - a regression, a harness error, or a verifier verdict that contradicts what a
 * conformance test required.
 */
public class OpenIDConformanceTest extends InjiVerifyUtil implements ITest {

	private static final Logger logger = Logger.getLogger(OpenIDConformanceTest.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static final String RESULT_FILE_KEY = "conformanceResultFile";
	static final String RUNNER_DIR_KEY = "conformanceRunnerDir";
	static final String COMPONENT_KEY = "conformanceComponent";
	static final String TIMEOUT_KEY = "conformanceRunnerTimeoutMinutes";
	static final String VERIFY_API_PATH = "/v1/verify";
	static final String BASE_URL_KEY = InjiVerifyConstants.INJI_VERIFY_BASE_URL;

	protected String testCaseName = "";

	@BeforeClass
	public static void setLogLevel() {
		if (InjiVerifyConfigManager.IsDebugEnabled())
			logger.setLevel(Level.ALL);
		else
			logger.setLevel(Level.ERROR);
	}

	@Override
	public String getTestName() {
		return testCaseName;
	}

	/** One entry per conformance module, plus a final entry for the run's gate. */
	@DataProvider(name = "conformanceModules")
	public Object[] getConformanceModules() {
		String component = setting(COMPONENT_KEY, "inji-verify");
		JsonNode document;
		try {
			document = loadResult();
		} catch (ConformanceSetupException e) {
			return new Object[] { ConformanceCase.setupProblem(component, e.getMessage(), e.skip) };
		}

		List<Object> cases = new ArrayList<>();
		for (JsonNode c : document.path("components")) {
			if (!component.equals(c.path("component").asText())) {
				continue;
			}
			for (JsonNode module : c.path("modules")) {
				cases.add(ConformanceCase.module(component, module));
			}
		}
		if (cases.isEmpty()) {
			return new Object[] { ConformanceCase.setupProblem(component,
					"The conformance result contains no modules for component '" + component + "'.", false) };
		}
		cases.add(ConformanceCase.gate(component, document.path("gate")));
		return cases.toArray();
	}

	@Test(dataProvider = "conformanceModules")
	public void test(ConformanceCase conformanceCase) throws AdminTestException {
		testCaseName = conformanceCase.name;
		Reporter.log(conformanceCase.report());

		switch (conformanceCase.outcome) {
		case PASS:
			return;
		case SKIP:
			throw new SkipException(conformanceCase.reason);
		default:
			throw new AdminTestException(conformanceCase.reason);
		}
	}

	@AfterMethod(alwaysRun = true)
	public void setResultTestName(ITestResult result) {
		result.setAttribute("TestCaseName", testCaseName);
	}

	// ---------------------------------------------------------------------------------
	// Obtaining the result
	// ---------------------------------------------------------------------------------

	private JsonNode loadResult() throws ConformanceSetupException {
		String resultFile = setting(RESULT_FILE_KEY, "");
		if (!resultFile.isBlank()) {
			return readJson(new File(resultFile));
		}
		String runnerDir = setting(RUNNER_DIR_KEY, "");
		if (!runnerDir.isBlank()) {
			return readJson(invokeRunner(new File(runnerDir)));
		}
		throw new ConformanceSetupException("OpenID conformance not configured: set '" + RESULT_FILE_KEY
				+ "' to a runner result file, or '" + RUNNER_DIR_KEY
				+ "' to the conformance runner's directory. Skipping.", true);
	}

	/**
	 * Run the conformance runner against this testrig's own target environment. The gate's
	 * exit status is deliberately ignored here ({@code --no-gate-exit}): the verdict is
	 * reported per module and by the gate test, not by the process exit code.
	 */
	private File invokeRunner(File runnerDir) throws ConformanceSetupException {
		File script = new File(runnerDir, "run-conformance.sh");
		if (!script.isFile()) {
			throw new ConformanceSetupException("Conformance runner not found at " + script.getAbsolutePath(), false);
		}
		try {
			// The testrig's own base URL; empty when its configuration was never loaded (TestNG
			// started without MosipTestRunner), and the runner must not get a relative URL then.
			String base = setting(BASE_URL_KEY, injiVerifyBaseUrl == null ? "" : injiVerifyBaseUrl.trim());
			if (!base.matches("(?i)https?://.+")) {
				throw new ConformanceSetupException("No absolute " + BASE_URL_KEY + " to run conformance against (got '"
						+ base + "'); set it in the testrig configuration or as a JVM system property", false);
			}
			File output = File.createTempFile("conformance-result-", ".json");
			output.deleteOnExit();
			String endpoint = base.replaceAll("/+$", "") + VERIFY_API_PATH;
			ProcessBuilder pb = new ProcessBuilder(script.getAbsolutePath(), "--component", "verify", "--endpoint",
					endpoint, "--no-gate-exit", "--output", output.getAbsolutePath());
			pb.directory(runnerDir);
			pb.redirectErrorStream(true);
			pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
			logger.info("Running OpenID conformance against " + endpoint);

			Process process = pb.start();
			long timeout = Long.parseLong(setting(TIMEOUT_KEY, "60"));
			if (!process.waitFor(timeout, TimeUnit.MINUTES)) {
				process.destroyForcibly();
				throw new ConformanceSetupException("Conformance runner did not finish within " + timeout + " minutes", false);
			}
			if (process.exitValue() != 0) {
				throw new ConformanceSetupException(
						"Conformance runner failed with exit status " + process.exitValue() + "; see its output above", false);
			}
			return output;
		} catch (IOException e) {
			throw new ConformanceSetupException("Could not run the conformance runner: " + e.getMessage(), false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ConformanceSetupException("Interrupted while running the conformance runner", false);
		}
	}

	private static JsonNode readJson(File file) throws ConformanceSetupException {
		if (!file.isFile()) {
			throw new ConformanceSetupException("Conformance result file not found: " + file.getAbsolutePath(), false);
		}
		try {
			return MAPPER.readTree(Files.readString(file.toPath()));
		} catch (IOException e) {
			throw new ConformanceSetupException("Conformance result file is not valid JSON: " + e.getMessage(), false);
		}
	}

	/** A testrig property, overridable by a JVM system property of the same name (for CI). */
	private static String setting(String key, String fallback) {
		String value = System.getProperty(key);
		if (value == null || value.isBlank()) {
			value = InjiVerifyConfigManager.getproperty(key);
		}
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	static final class ConformanceSetupException extends Exception {
		private static final long serialVersionUID = 1L;
		final boolean skip;

		ConformanceSetupException(String message, boolean skip) {
			super(message);
			this.skip = skip;
		}
	}
}
