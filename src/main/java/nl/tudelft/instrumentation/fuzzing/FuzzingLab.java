package nl.tudelft.instrumentation.fuzzing;

import java.util.*;
import java.io.*;

/**
 * You should write your own solution using this class.
 */
public class FuzzingLab {
        // Seed from -Dseed=N system property (for reproducible, independent runs)
        static final long seed = Long.parseLong(System.getProperty("seed",
                String.valueOf(System.nanoTime() % 1_000_000L)));
        static Random r = new Random(seed);
        static List<String> currentTrace;
        static int traceLength = 15;
        static boolean isFinished = false;
        static final int K = 1;
        static final int MAX_TRACE_LENGTH = 20;
        static final int MIN_TRACE_LENGTH = 5;

        // Mode from -Dmode=random|hillclimber  (default: hillclimber, preserves old behaviour)
        static final boolean useHillClimber = !"random".equals(System.getProperty("mode", "hillclimber"));
        static final boolean runExperiments = false;

        // --- Hill Climbing ---
        static int nrMutations = 50;
        static List<String> bestTraceSoFar = null;
        static float bestDistanceSoFar = Float.MAX_VALUE;

        // --- Branch distance accumulator (reset per trace) ---
        static float currentTraceBranchDistance = 0;

        // --- Unique branch tracking ---
        static Set<String> allUniqueBranches = new HashSet<>();
        static Set<String> currentTraceUniqueBranches = new HashSet<>();

        // --- Error tracking ---
        static Set<String> triggeredErrors = new HashSet<>();
        static Map<String, List<String>> errorTraces = new LinkedHashMap<>();
        static List<long[]> errorDiscoveryTimeline = new ArrayList<>();

        // --- Branch discovery timeline (parallel to errorDiscoveryTimeline) ---
        static List<long[]> branchDiscoveryTimeline = new ArrayList<>();

        // --- Best-trace tracking ---
        static List<String> bestTrace = null;
        static int bestTraceUniqueBranchCount = 0;

        // --- Pool of traces that previously discovered new branches ---
        static List<List<String>> interestingTraces = new ArrayList<>();
        static final int MAX_POOL_SIZE = 20;

        // --- Timing ---
        // Allow -DtimeoutMs=N to override the budget (e.g. for quick smoke-tests)
        static final long TIMEOUT_MS = Long.parseLong(
                System.getProperty("timeoutMs", String.valueOf(5 * 60 * 1000L)));
        static long startTime;
        static int totalTraces = 0;

        // --- Shutdown-hook guard (prevents double-write) ---
        static volatile boolean csvWritten = false;

        static void initialize(String[] inputSymbols) {
                currentTrace = generateRandomTrace(inputSymbols);
                startTime = System.currentTimeMillis();
        }

        static void encounteredNewBranch(MyVar condition, boolean value, int line_nr) {
                String branchId = line_nr + "_" + value;
                String oppositeBranchId = line_nr + "_" + !value;
                boolean isNew = allUniqueBranches.add(branchId);
                currentTraceUniqueBranches.add(branchId);

                // Record timeline entry whenever a branch is visited for the first time
                if (isNew) {
                        branchDiscoveryTimeline.add(new long[]{
                                System.currentTimeMillis() - startTime,
                                allUniqueBranches.size()
                        });
                }

                // Only contribute distance if the opposite side is not yet covered
                if (!allUniqueBranches.contains(oppositeBranchId)) {
                        // Compute distance to reach the opposite (uncovered) outcome
                        float d = computeBranchDistance(condition, !value);
                        currentTraceBranchDistance += d;
                }
        }

        static float computeBranchDistance(MyVar condition) {
                return computeBranchDistance(condition, true);
        }

        static float computeBranchDistance(MyVar condition, boolean desiredOutcome) {
                switch (condition.type) {
                        case BOOL:
                                return normalize(condition.value == desiredOutcome ? 0 : 1);

                        case INT:
                                return normalize((condition.int_value != 0) == desiredOutcome ? 0 : 1);

                        case STRING:
                                return normalize((!condition.str_value.isEmpty()) == desiredOutcome ? 0 : 1);

                        case UNARY:
                                if (condition.operator.equals("!")) {
                                        return computeBranchDistance(condition.left, !desiredOutcome);
                                }
                                return 0.0f;

                        case BINARY: {
                                String op = condition.operator;

                                if (op.equals("&&")) {
                                        if (desiredOutcome) {
                                                return computeBranchDistance(condition.left, true)
                                                        + computeBranchDistance(condition.right, true);
                                        }
                                        return Math.min(
                                                computeBranchDistance(condition.left, false),
                                                computeBranchDistance(condition.right, false));
                                }
                                if (op.equals("||")) {
                                        if (desiredOutcome) {
                                                return Math.min(
                                                        computeBranchDistance(condition.left, true),
                                                        computeBranchDistance(condition.right, true));
                                        }
                                        return computeBranchDistance(condition.left, false)
                                                + computeBranchDistance(condition.right, false);
                                }
                                if (op.equals("^")) {
                                        float leftTrue  = computeBranchDistance(condition.left, true);
                                        float leftFalse = computeBranchDistance(condition.left, false);
                                        float rightTrue  = computeBranchDistance(condition.right, true);
                                        float rightFalse = computeBranchDistance(condition.right, false);
                                        if (desiredOutcome) {
                                                return Math.min(leftTrue + rightFalse, leftFalse + rightTrue);
                                        }
                                        return Math.min(leftTrue + rightTrue, leftFalse + rightFalse);
                                }

                                return normalize(comparisonDistance(condition, desiredOutcome));
                        }

                        default:
                                return 0.0f;
                }
        }

        static int comparisonDistance(MyVar condition, boolean desiredOutcome) {
                MyVar left  = condition.left;
                MyVar right = condition.right;
                String op   = desiredOutcome ? condition.operator : negateOperator(condition.operator);

                if (left.type == TypeEnum.STRING || right.type == TypeEnum.STRING) {
                        String ls = left.type  == TypeEnum.STRING ? left.str_value  : String.valueOf(left.int_value);
                        String rs = right.type == TypeEnum.STRING ? right.str_value : String.valueOf(right.int_value);

                        if (op.equals("==")) {
                                if (ls.equals(rs)) return 0;
                                int dist = Math.abs(ls.length() - rs.length());
                                int len  = Math.min(ls.length(), rs.length());
                                for (int i = 0; i < len; i++) {
                                        dist += Math.abs(ls.charAt(i) - rs.charAt(i));
                                }
                                return dist;
                        }
                        if (op.equals("!=")) return ls.equals(rs) ? 1 : 0;
                        return 0;
                }

                int lv = getIntValue(left);
                int rv = getIntValue(right);

                switch (op) {
                        case "==":  return Math.abs(lv - rv);
                        case "!=":  return lv != rv ? 0 : 1;
                        case "<":   return lv <  rv ? 0 : lv - rv + K;
                        case "<=":  return lv <= rv ? 0 : lv - rv;
                        case ">":   return lv >  rv ? 0 : rv - lv + K;
                        case ">=":  return lv >= rv ? 0 : rv - lv;
                        default:    return 0;
                }
        }

        static String negateOperator(String op) {
                switch (op) {
                        case "==": return "!=";
                        case "!=": return "==";
                        case "<":  return ">=";
                        case "<=": return ">";
                        case ">":  return "<=";
                        case ">=": return "<";
                        default:   return op;
                }
        }

        static int getIntValue(MyVar m) {
                if (m.type == TypeEnum.INT)  return m.int_value;
                if (m.type == TypeEnum.BOOL) return m.value ? 1 : 0;
                return 0;
        }

        static float normalize(int d) {
                return (float) d / (d + 1);
        }

        static List<String> mutate(String[] inputSymbols, List<String> trace) {
                List<String> mutated = new ArrayList<>(trace);

                if (mutated.isEmpty()) {
                        return generateRandomTrace(inputSymbols);
                }

                int mutationType = r.nextInt(3);

                if (mutated.size() <= MIN_TRACE_LENGTH) {
                        mutationType = 1; // add only
                }
                if (mutated.size() >= MAX_TRACE_LENGTH) {
                        mutationType = r.nextInt(2) == 0 ? 0 : 2; // change or delete only
                }

                if (inputSymbols.length <= 1 && mutationType == 0) {
                        mutationType = 1;
                }

                switch (mutationType) {
                        case 0: // Change
                                int changeIdx = r.nextInt(mutated.size());
                                String current = mutated.get(changeIdx);
                                String newSym = inputSymbols[r.nextInt(inputSymbols.length)];
                                while (newSym.equals(current) && inputSymbols.length > 1) {
                                        newSym = inputSymbols[r.nextInt(inputSymbols.length)];
                                }
                                mutated.set(changeIdx, newSym);
                                break;

                        case 1: // Add
                                int addIdx = r.nextInt(mutated.size() + 1);
                                mutated.add(addIdx, inputSymbols[r.nextInt(inputSymbols.length)]);
                                break;

                        case 2: // Delete
                                mutated.remove(r.nextInt(mutated.size()));
                                break;
                }

                return mutated;
        }

        static List<String> fuzz(String[] inputSymbols, List<String> inputTrace) {
                if (inputTrace == null) {
                        return generateRandomTrace(inputSymbols);
                }
                return mutate(inputSymbols, inputTrace);
        }

        static List<String> generateRandomTrace(String[] symbols) {
                ArrayList<String> trace = new ArrayList<>();
                for (int i = 0; i < traceLength; i++) {
                        trace.add(symbols[r.nextInt(symbols.length)]);
                }
                return trace;
        }

        static void executeCurrentTrace() {
                int prevTotal = allUniqueBranches.size();
                currentTraceBranchDistance = 0;
                currentTraceUniqueBranches = new HashSet<>();
                DistanceTracker.runNextFuzzedSequence(currentTrace.toArray(new String[0]));
                totalTraces++;

                // Save to pool if this trace found new branches
                if (allUniqueBranches.size() > prevTotal) {
                        if (interestingTraces.size() >= MAX_POOL_SIZE) {
                                interestingTraces.remove(r.nextInt(interestingTraces.size()));
                        }
                        interestingTraces.add(new ArrayList<>(currentTrace));
                }

                if (currentTraceUniqueBranches.size() > bestTraceUniqueBranchCount) {
                        bestTraceUniqueBranchCount = currentTraceUniqueBranches.size();
                        bestTrace = new ArrayList<>(currentTrace);
                        System.out.println("[New best] trace=" + bestTrace
                                + " unique_branches=" + bestTraceUniqueBranchCount
                                + " total_all=" + allUniqueBranches.size());
                }
        }

        static void logExperimentResults(String fuzzerName) {
                System.out.println("RESULTS " + fuzzerName);
                System.out.println("Total traces run:              " + totalTraces);
                System.out.println("Total unique branches visited: " + allUniqueBranches.size());
                System.out.println("Triggered error codes (" + triggeredErrors.size() + "): " + triggeredErrors);
                System.out.println("Best single-trace branch count: " + bestTraceUniqueBranchCount);
                System.out.println("Best trace: " + bestTrace);
                if (!errorTraces.isEmpty()) {
                        System.out.println("--- Error traces ---");
                        for (Map.Entry<String, List<String>> entry : errorTraces.entrySet()) {
                                System.out.println("  " + entry.getKey() + ": " + entry.getValue());
                        }
                }
                System.out.println("--- Error discovery timeline ---");
                System.out.println("time_ms,unique_errors");
                for (long[] point : errorDiscoveryTimeline) {
                        System.out.println(point[0] + "," + point[1]);
                }
                System.out.println("=================================================");
                System.out.flush();

                // --- Write CSV to logs/task1/<Problem>_<mode>_seed<N>.csv ---
                writeCsv(fuzzerName);
        }

        static void writeCsv(String fuzzerName) {
                if (csvWritten) return;          // prevent double-write from shutdown hook
                csvWritten = true;

                // Determine problem name from the tracker
                String problemName = "unknown";
                try {
                        if (DistanceTracker.problem != null) {
                                problemName = DistanceTracker.problem.getClass().getSimpleName();
                        }
                } catch (Exception ignored) { }

                // Map fuzzer name to a short mode string
                String mode = "HillClimber".equals(fuzzerName) ? "hillclimber" : "random";

                File dir = new File("logs/task1");
                dir.mkdirs();
                File f = new File(dir, problemName + "_" + mode + "_seed" + seed + ".csv");

                // Merge error and branch timelines into a single sorted sequence
                // Each event row: {time_ms, errors, branches}
                List<long[]> events = new ArrayList<>();
                for (long[] e : errorDiscoveryTimeline)    events.add(new long[]{e[0],  e[1], -1});
                for (long[] b : branchDiscoveryTimeline)   events.add(new long[]{b[0], -1, b[1]});
                events.sort(Comparator.comparingLong(x -> x[0]));

                long finalTime = System.currentTimeMillis() - startTime;

                try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
                        pw.println("time_ms,unique_errors,unique_branches");
                        pw.println("0,0,0");

                        long curErrors = 0, curBranches = 0;
                        for (long[] ev : events) {
                                if (ev[1] >= 0) curErrors   = ev[1];
                                if (ev[2] >= 0) curBranches = ev[2];
                                pw.println(ev[0] + "," + curErrors + "," + curBranches);
                        }
                        // Final state
                        pw.println(finalTime + "," + triggeredErrors.size() + "," + allUniqueBranches.size());
                } catch (IOException e) {
                        System.err.println("[writeCsv] failed to write " + f + ": " + e.getMessage());
                }
                System.out.println("Wrote " + f.getPath()
                        + " (errors=" + triggeredErrors.size()
                        + " branches=" + allUniqueBranches.size() + ")");
        }

        static void runHillClimber() {
                initialize(DistanceTracker.inputSymbols);
                int noImprovementCount = 0;

                while (!isFinished && System.currentTimeMillis() - startTime < TIMEOUT_MS) {
                        if (bestTraceSoFar == null) {
                                // Restart from pool if available, otherwise random
                                if (!interestingTraces.isEmpty()) {
                                        currentTrace = new ArrayList<>(
                                                interestingTraces.get(r.nextInt(interestingTraces.size())));
                                } else {
                                        currentTrace = generateRandomTrace(DistanceTracker.inputSymbols);
                                }
                                executeCurrentTrace();
                                bestTraceSoFar = new ArrayList<>(currentTrace);
                                bestDistanceSoFar = currentTraceBranchDistance;
                                noImprovementCount = 0;
                                continue;
                        }

                        List<String> bestMutation = null;
                        float bestMutationDistance = Float.MAX_VALUE;

                        for (int i = 0; i < nrMutations; i++) {
                                currentTrace = fuzz(DistanceTracker.inputSymbols, bestTraceSoFar);
                                executeCurrentTrace();

                                if (currentTraceBranchDistance < bestMutationDistance) {
                                        bestMutation = new ArrayList<>(currentTrace);
                                        bestMutationDistance = currentTraceBranchDistance;
                                }
                        }

                        if (bestMutationDistance < bestDistanceSoFar) {
                                bestTraceSoFar = new ArrayList<>(bestMutation);
                                bestDistanceSoFar = bestMutationDistance;
                                noImprovementCount = 0;
                        } else {
                                noImprovementCount++;
                                if (noImprovementCount >= 10) { // increased from 3
                                        bestTraceSoFar = null;
                                        bestDistanceSoFar = Float.MAX_VALUE;
                                        noImprovementCount = 0;
                                }
                        }
                }
                logExperimentResults("HillClimber");
        }

        static void runSimpleFuzzer() {
                initialize(DistanceTracker.inputSymbols);
                while (!isFinished && System.currentTimeMillis() - startTime < TIMEOUT_MS) {
                        currentTrace = fuzz(DistanceTracker.inputSymbols, null);
                        executeCurrentTrace();
                }
                logExperimentResults("SimpleFuzzer");
        }

        static void run() {
                // Register a JVM shutdown hook so the CSV is written even when the
                // process receives SIGTERM from an external timeout wrapper.
                String shutdownMode = useHillClimber ? "HillClimber" : "SimpleFuzzer";
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        if (!csvWritten && startTime != 0) {
                                writeCsv(shutdownMode);
                        }
                }));

                if (runExperiments) {
                        runSimpleFuzzer();

                        currentTrace = null;
                        allUniqueBranches.clear();
                        triggeredErrors.clear();
                        errorTraces.clear();
                        errorDiscoveryTimeline.clear();
                        branchDiscoveryTimeline.clear();
                        totalTraces = 0;
                        bestTrace = null;
                        bestTraceUniqueBranchCount = 0;
                        bestTraceSoFar = null;
                        bestDistanceSoFar = Float.MAX_VALUE;
                        interestingTraces.clear();

                        runHillClimber();
                } else {
                        if (useHillClimber) {
                                runHillClimber();
                        } else {
                                runSimpleFuzzer();
                        }
                }

                System.out.flush();
                System.exit(0);
        }

        public static void output(String out) {
                System.out.println(out);
                if (out.contains("error_")) {
                        String error = out.trim();
                        if (triggeredErrors.add(error)) {
                                errorTraces.put(error, new ArrayList<>(currentTrace));
                                errorDiscoveryTimeline.add(new long[]{
                                        System.currentTimeMillis() - startTime,
                                        triggeredErrors.size()
                                });
                        }
                }
        }
}

// The three changes from the previous message are all applied here. To summarise what changed and why:
// traceLength 10→15, MAX_TRACE_LENGTH 10→20 — RERS problems often require longer input sequences to reach deep states and trigger errors. Short traces can't even reach some parts of the state machine.
// Pool-based restarts — when bestTraceSoFar resets to null, it now picks a random trace from interestingTraces (traces that previously found new branches) instead of always going fully random. This means restarts still have some useful structure rather than starting from scratch every time.
// noImprovementCount threshold 3→10 — resetting after only 3 failed rounds was too aggressive. The hill climber was abandoning promising regions before they had a fair chance to improve.