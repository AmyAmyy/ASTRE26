import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nl.tudelft.instrumentation.fuzzing.MyVar;
import nl.tudelft.instrumentation.runner.CallableTraceRunner;

/**
 * AflReplay  –  Replay AFL-generated inputs through the Task-1 fuzzing
 * instrumentation so AFL coverage is measured in the *same RERS-branch unit*
 * as the random fuzzer / hill climber / concolic tools.
 *
 * A "branch" here is exactly the Task-1 definition: a (line_nr, value) pair
 * fired by DistanceTracker.myIf(...) -> FuzzingLab.encounteredNewBranch(...).
 * We do NOT use FuzzingLab itself (it is a self-driving random fuzzer); instead
 * we reproduce its branch bookkeeping verbatim and drive the *instrumented*
 * Problem class with externally supplied traces.
 *
 * The instrumented ProblemN.calculateOutput(String) calls
 * DistanceTracker.myIf(cond, value, line) for every guard.  DistanceTracker
 * forwards to FuzzingLab.encounteredNewBranch, which records branchId =
 * line + "_" + value into a static Set.  We cannot easily intercept that call
 * without modifying compiled classes, so instead we re-route myIf accounting
 * here by re-reading FuzzingLab's public static set after each trace.
 *
 * Input  (stdin): one trace per line, formatted   <time_ms>\t<sym> <sym> ...
 *                 An empty symbol list (just "<time_ms>\t") is allowed.
 * Output (file): CSV   time_ms,unique_branches   one row per input replayed,
 *                 reporting the cumulative unique-branch count *after* that
 *                 input, in AFL discovery order.  Written to the file given as
 *                 the 2nd arg (NOT stdout — the instrumented Problem classes
 *                 print diagnostics to stdout, which would corrupt the CSV).
 *
 * Usage:
 *   java -cp target/aistr.jar:instrumented/fuzzing:scripts/final:. \
 *        AflReplay ProblemN out.csv
 */
public class AflReplay {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: AflReplay ProblemN out.csv  (traces on stdin)");
            System.exit(2);
        }
        String problemClass = args[0];
        String outPath = args[1];

        @SuppressWarnings("unchecked")
        Class<? extends CallableTraceRunner<?>> cls =
                (Class<? extends CallableTraceRunner<?>>) Class.forName(problemClass);

        // FuzzingLab keeps the unique-branch set as a static field.  We reset it
        // before replay and read it after each trace.  Accessed reflectively so
        // this file compiles against the published jar without source coupling.
        Class<?> fuzzingLab = Class.forName("nl.tudelft.instrumentation.fuzzing.FuzzingLab");
        java.lang.reflect.Field branchesField = fuzzingLab.getDeclaredField("allUniqueBranches");
        branchesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> allUniqueBranches = (Set<String>) branchesField.get(null);
        allUniqueBranches.clear();

        // startTime must be set so encounteredNewBranch's timeline append does not
        // divide by a zero clock; we never use FuzzingLab's own timeline though.
        java.lang.reflect.Field startField = fuzzingLab.getDeclaredField("startTime");
        startField.setAccessible(true);
        startField.setLong(null, System.currentTimeMillis());

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder out = new StringBuilder();
        out.append("time_ms,unique_branches\n");

        String line;
        while ((line = stdin.readLine()) != null) {
            int tab = line.indexOf('\t');
            long timeMs;
            String symPart;
            if (tab < 0) {
                timeMs = Long.parseLong(line.trim());
                symPart = "";
            } else {
                timeMs = Long.parseLong(line.substring(0, tab).trim());
                symPart = line.substring(tab + 1).trim();
            }

            String[] sequence;
            if (symPart.isEmpty()) {
                sequence = new String[0];
            } else {
                sequence = symPart.split("\\s+");
            }

            CallableTraceRunner<?> runner = cls.getDeclaredConstructor().newInstance();
            runner.setSequence(sequence);
            try {
                runner.call();
            } catch (Throwable t) {
                // Mirrors FuzzingLab: a thrown exception (e.g. dead-end state,
                // or Errors.__VERIFIER_error) just ends this trace.  Branches
                // visited up to that point are already recorded.
            }

            out.append(timeMs).append(',').append(allUniqueBranches.size()).append('\n');
        }

        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(outPath))) {
            pw.print(out);
        }
        System.exit(0);
    }
}
