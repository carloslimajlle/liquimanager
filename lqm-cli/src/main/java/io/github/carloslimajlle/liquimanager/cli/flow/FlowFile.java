package io.github.carloslimajlle.liquimanager.cli.flow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * {@code lqm.flow.yaml} (docs/07 § 2.2): the stages of a deploy, in order, with when each applies and what a failure
 * means.
 *
 * <p>Everything that can be wrong with the FILE is found while reading it, before a single stage runs: a flow that
 * discovers on stage four that stage five is a typo has already changed a database. That is why the {@code when}
 * clauses are parsed here, not at the moment they are evaluated.
 */
public final class FlowFile {

    /** What a failing stage does to the flow. */
    public enum OnFailure { STOP, CONTINUE }

    public record Stage(String name, String run, String when, OnFailure onFailure, String artifact) {
    }

    /** The file is wrong; the message names the stage and the problem. */
    public static final class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message);
        }
    }

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");
    private static final Pattern WHEN = Pattern.compile(
            "^env\\s+(in|not in)\\s*\\[([^\\]]*)]$|^env\\s*(==|!=)\\s*([A-Za-z0-9_-]+)$", Pattern.CASE_INSENSITIVE);

    private final Map<String, String> variables;
    private final List<Stage> stages;

    private FlowFile(Map<String, String> variables, List<Stage> stages) {
        this.variables = variables;
        this.stages = stages;
    }

    public List<Stage> stages() {
        return stages;
    }

    public Map<String, String> variables() {
        return variables;
    }

    // ------------------------------------------------------------------ reading

    /** @param environment where ${NAME} falls back to, after the flow's own variables (normally the process environment) */
    @SuppressWarnings("unchecked")
    public static FlowFile parse(String yaml, Map<String, String> environment) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (RuntimeException e) {
            throw new Invalid("the flow file is not valid YAML: " + firstLine(e.getMessage()));
        }
        if (!(root instanceof Map<?, ?> top)) {
            throw new Invalid("the flow file must be a YAML mapping");
        }
        Object version = top.get("version");
        if (version == null || !String.valueOf(version).equals("1")) {
            throw new Invalid("version must be 1");
        }

        Map<String, String> variables = new LinkedHashMap<>();
        Object declared = top.get("variables");
        if (declared instanceof Map<?, ?> map) {
            // In order, so a variable can use the ones above it.
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                variables.put(String.valueOf(entry.getKey()),
                        interpolate(String.valueOf(entry.getValue()), variables, environment));
            }
        } else if (declared != null) {
            throw new Invalid("variables must be a mapping");
        }

        Object listed = top.get("stages");
        if (!(listed instanceof List<?> list) || list.isEmpty()) {
            throw new Invalid("the flow needs at least one stage");
        }
        List<Stage> stages = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new Invalid("every stage must be a mapping");
            }
            String name = text(m.get("name"));
            if (name == null) {
                throw new Invalid("a stage has no name");
            }
            if (names.contains(name)) {
                throw new Invalid("two stages are named " + name);
            }
            names.add(name);
            String run = text(m.get("run"));
            if (run == null) {
                throw new Invalid("stage " + name + " has no run");
            }
            String when = text(m.get("when"));
            if (when != null && !WHEN.matcher(when.trim()).matches()) {
                throw new Invalid("stage " + name + ": cannot read the condition '" + when
                        + "'; use env in [A, B], env not in [A], env == A or env != A");
            }
            stages.add(new Stage(name, interpolate(run, variables, environment), when, onFailure(name, m.get("onFailure")),
                    text(m.get("artifact")) == null ? null : interpolate(text(m.get("artifact")), variables, environment)));
        }
        return new FlowFile(variables, List.copyOf(stages));
    }

    /** ${NAME} and ${NAME:-default}: the flow's variables first, then the environment, then the default. */
    static String interpolate(String text, Map<String, String> variables, Map<String, String> environment) {
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variables.get(name);
            if (value == null) {
                value = environment.get(name);
            }
            if ((value == null || value.isEmpty()) && matcher.group(2) != null) {
                value = matcher.group(2);
            }
            if (value == null) {
                throw new Invalid("the variable " + name + " is not defined and has no default");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static OnFailure onFailure(String stage, Object value) {
        if (value == null) {
            return OnFailure.STOP;
        }
        return switch (String.valueOf(value).toLowerCase(Locale.ROOT)) {
            case "stop" -> OnFailure.STOP;
            case "continue" -> OnFailure.CONTINUE;
            default -> throw new Invalid("stage " + stage + ": onFailure must be stop or continue");
        };
    }

    // --------------------------------------------------------------- conditions

    /** Does the stage apply in this environment? A stage without a condition always does. */
    public static boolean applies(Stage stage, String environment) {
        if (stage.when() == null) {
            return true;
        }
        Matcher m = WHEN.matcher(stage.when().trim());
        if (!m.matches()) {
            return false;
        }
        String env = environment == null ? "" : environment.trim().toUpperCase(Locale.ROOT);
        if (m.group(1) != null) {
            boolean listed = Arrays.stream(m.group(2).split(",")).map(s -> s.trim().toUpperCase(Locale.ROOT)).anyMatch(env::equals);
            return m.group(1).equalsIgnoreCase("in") == listed;
        }
        boolean same = m.group(4).trim().toUpperCase(Locale.ROOT).equals(env);
        return m.group(3).equals("==") == same;
    }

    // ------------------------------------------------------------------ helpers

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).trim();
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }
}
