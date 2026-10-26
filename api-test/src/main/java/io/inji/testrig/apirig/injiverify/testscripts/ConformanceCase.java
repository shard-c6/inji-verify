package io.inji.testrig.apirig.injiverify.testscripts;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One row of the OpenID conformance report: a conformance module (or the run's gate)
 * translated from the runner-to-testrig contract into a TestNG outcome.
 *
 * <p>Kept free of TestNG and network code so the mapping can be unit tested on its own.
 * The mapping follows the contract: the gate's {@code verdict} decides the TestNG outcome,
 * while the suite's own {@code result} is always shown and never rewritten - a known,
 * documented failure can be reported as PASS by the gate without the report claiming the
 * conformance suite passed it.
 */
public final class ConformanceCase {

	public enum Outcome {
		PASS, FAIL, SKIP
	}

	final String name;
	final Outcome outcome;
	final String reason;
	private final JsonNode detail;

	private ConformanceCase(String name, Outcome outcome, String reason, JsonNode detail) {
		this.name = name;
		this.outcome = outcome;
		this.reason = reason;
		this.detail = detail;
	}

	static ConformanceCase module(String component, JsonNode module) {
		String moduleName = module.path("moduleName").asText("unknown-module");
		String name = "conformance." + component + "." + moduleName;
		String suiteResult = module.path("result").asText("none");
		String verdict = module.path("verdict").asText("");

		switch (verdict) {
		case "PASS":
			return new ConformanceCase(name, Outcome.PASS, "Conformance suite: " + suiteResult, module);
		case "SKIP":
			return new ConformanceCase(name, Outcome.SKIP, skipReason(module), module);
		case "FAIL":
			return new ConformanceCase(name, Outcome.FAIL, failReason(module, suiteResult), module);
		default:
			// Not gated (e.g. the runner was run without a baseline): fall back to the
			// suite's own result rather than guessing.
			boolean ok = "PASSED".equals(suiteResult);
			return new ConformanceCase(name, ok ? Outcome.PASS : Outcome.FAIL,
					"Ungated result; conformance suite reported " + suiteResult, module);
		}
	}

	static ConformanceCase gate(String component, JsonNode gate) {
		String name = "conformance." + component + ".gate";
		if (gate.path("passed").asBoolean(false)) {
			return new ConformanceCase(name, Outcome.PASS,
					"No regressions against the recorded baseline (" + gate.path("policy").asText() + ")", gate);
		}
		StringBuilder why = new StringBuilder("Benchmark gate failed:");
		appendList(why, gate.path("regressions"), "regression");
		appendList(why, gate.path("harnessErrors"), "harness could not execute");
		appendList(why, gate.path("reviewMismatches"), "verifier verdict contradicts the test");
		return new ConformanceCase(name, Outcome.FAIL, why.toString(), gate);
	}

	static ConformanceCase setupProblem(String component, String message, boolean skip) {
		return new ConformanceCase("conformance." + component + ".setup", skip ? Outcome.SKIP : Outcome.FAIL,
				message, null);
	}

	private static String skipReason(JsonNode module) {
		if ("HARNESS_ERROR".equals(module.path("status").asText())) {
			JsonNode first = module.path("checks").path(0);
			return "Harness could not execute this module (not a conformance result): " + first.path("msg").asText();
		}
		return "No recorded baseline for this module; reported as SKIP until one is recorded";
	}

	private static String failReason(JsonNode module, String suiteResult) {
		StringBuilder why = new StringBuilder();
		JsonNode resolution = module.path("reviewResolution");
		if (resolution.path("consistent").isBoolean() && !resolution.path("consistent").asBoolean()) {
			why.append("The test required the verifier to ").append(resolution.path("expected").asText())
					.append(" the presentation, but the verifier ").append(resolution.path("verifier").asText())
					.append(" it. ");
		}
		if (module.path("regression").asBoolean(false)) {
			why.append("Regression: expected ").append(module.path("expected").asText()).append(", got ")
					.append(suiteResult).append(". ");
		}
		for (JsonNode check : module.path("checks")) {
			if ("FAILURE".equals(check.path("result").asText())) {
				why.append("[").append(check.path("src").asText()).append("] ").append(check.path("msg").asText());
				if (!check.path("findingRef").isNull() && !check.path("findingRef").asText().isEmpty()) {
					why.append(" (").append(check.path("findingRef").asText()).append(")");
				}
				why.append(". ");
			}
		}
		return why.length() == 0 ? "Conformance suite reported " + suiteResult : why.toString().trim();
	}

	private static void appendList(StringBuilder sb, JsonNode list, String label) {
		for (JsonNode e : list) {
			sb.append(" [").append(label).append(": ").append(e.path("component").asText()).append(" / ")
					.append(e.path("moduleName").asText()).append("]");
		}
	}

	/** HTML for the Extent/TestNG report: what the suite said, what the gate decided, why. */
	String report() {
		StringBuilder html = new StringBuilder("<b>").append(escape(name)).append("</b><br/>");
		html.append("Outcome: ").append(outcome).append(" &mdash; ").append(escape(reason)).append("<br/>");
		if (detail != null && detail.has("moduleName")) {
			html.append("Suite status/result: ").append(escape(detail.path("status").asText())).append(" / ")
					.append(escape(detail.path("result").asText())).append("; baseline expected: ")
					.append(escape(detail.path("expected").asText("none"))).append("<br/>");
			JsonNode res = detail.path("reviewResolution");
			if (res.isObject()) {
				html.append("REVIEW resolved by verifier verdict: required ").append(escape(res.path("expected").asText()))
						.append(", verifier ").append(escape(res.path("verifier").asText())).append("<br/>");
			}
			if (detail.hasNonNull("logUrl")) {
				html.append("Suite log: ").append(escape(detail.path("logUrl").asText())).append("<br/>");
			}
		}
		return html.toString();
	}

	private static String escape(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	@Override
	public String toString() {
		return name;
	}
}
