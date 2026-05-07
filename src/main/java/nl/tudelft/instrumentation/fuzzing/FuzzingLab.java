package nl.tudelft.instrumentation.fuzzing;

import java.util.*;

/**
 * You should write your own solution using this class.
 */
public class FuzzingLab {
        static Random r = new Random();
        static List<String> currentTrace;
        static int traceLength = 10;
        static boolean isFinished = false;
        static final int K = 1; // small positive constant for branch distance formulas
        static final int MAX_TRACE_LENGTH = 10;
        static final int MIN_TRACE_LENGTH = 5;

        static final boolean useHillClimber = true;
        static final boolean runExperiments = false;

        // --- Hill Climbing ---
        static int nrMutations = 50; 
        static List<String> bestTraceSoFar = null;
        static float bestDistanceSoFar = Float.MAX_VALUE;

        // --- Branch distance accumulator (reset per trace) ---
        static float currentTraceBranchDistance = 0;

        // --- Experiment unique branch tracking ---
        // A unique branch = (line_nr, value): both sides of every if are tracked separately.
        static Set<String> allUniqueBranches    = new HashSet<>(); // all branches seen across the whole session
        static Set<String> currentTraceUniqueBranches = new HashSet<>(); // branches seen in the current trace

        // -- Error code tracking --
        static Set<String> triggeredErrors = new HashSet<>();

        // --- Best-trace tracking ---
        static List<String> bestTrace = null;          // trace that saw the most unique branches in one run
        static int bestTraceUniqueBranchCount = 0;

        // --- Experiment timing ---
        static final long TIMEOUT_MS = 5 * 60 * 1000L; // 5 minutes
        static long startTime;
        static int totalTraces = 0;

        static void initialize(String[] inputSymbols){
                currentTrace = generateRandomTrace(inputSymbols);
                startTime    = System.currentTimeMillis();
        }

        /**
         * Called by DistanceTracker.myIf for every if-statement encountered during a run.
         * Records the branch as visited and accumulates its distance.
         */
        static void encounteredNewBranch(MyVar condition, boolean value, int line_nr) {
                // Track this (line, side) pair as a unique branch
                String branchId = line_nr + "_" + value;
                allUniqueBranches.add(branchId);
                currentTraceUniqueBranches.add(branchId);

                // Accumulate branch distance for this trace
                float d = computeBranchDistance(condition);
                currentTraceBranchDistance += d;
        }

        /**
         * Recursively compute the normalized branch distance in [0, 1) for making
         * the given condition evaluate to true, using the concrete values stored in
         * the MyVar tree.
         *
         * Formulas (from lecture slides):
         *   a           : 0 if true, 1 otherwise
         *   !p1         : 1 - D(p1)
         *   a == b      : |a - b|         (then normalized)
         *   a != b      : 0 if a != b, 1 otherwise
         *   a <  b      : 0 if a < b,  a - b + K otherwise  (then normalized)
         *   a <= b      : 0 if a <= b, a - b     otherwise
         *   a >  b      : 0 if a > b,  b - a + K otherwise
         *   a >= b      : 0 if a >= b, b - a     otherwise
         *   p1 && p2    : D(p1) + D(p2)
         *   p1 || p2    : min(D(p1), D(p2))
         *   p1 XOR p2   : min(D(p1) + (1 - D(p2)), (1 - D(p1)) + D(p2))
         */
        static float computeBranchDistance(MyVar condition) {
                switch (condition.type) {
                        case BOOL:
                                // a : d = 0 if a is true, 1 otherwise
                                return normalize(condition.value ? 0 : 1);

                        case INT:
                                // treat nonzero as true
                                return normalize(condition.int_value != 0 ? 0 : 1);

                        case STRING:
                                // treat non-empty string as true
                                return normalize(!condition.str_value.isEmpty() ? 0 : 1);

                        case UNARY:
                                if (condition.operator.equals("!")) {
                                        // !p1 : D = 1 - D(p1)
                                        return 1.0f - computeBranchDistance(condition.left);
                                }
                                return 0.0f;

                        case BINARY: {
                                String op = condition.operator;

                                // Logical operators: combine already-normalized sub-distances
                                if (op.equals("&&")) {
                                        // p1 && p2 : D = D(p1) + D(p2)
                                        return computeBranchDistance(condition.left)
                                                + computeBranchDistance(condition.right);
                                }
                                if (op.equals("||")) {
                                        // p1 || p2 : D = min(D(p1), D(p2))
                                        return Math.min(
                                                computeBranchDistance(condition.left),
                                                computeBranchDistance(condition.right));
                                }
                                if (op.equals("^")) {
                                        // XOR: D = min(D(p1) + (1-D(p2)), (1-D(p1)) + D(p2))
                                        float dp1 = computeBranchDistance(condition.left);
                                        float dp2 = computeBranchDistance(condition.right);
                                        return Math.min(dp1 + (1.0f - dp2), (1.0f - dp1) + dp2);
                                }

                                // Comparison operators: compute raw distance, then normalize
                                return normalize(comparisonDistance(condition));
                        }

                        default:
                                return 0.0f;
                }
        }

        /**
         * Compute the raw (unnormalized) distance for a binary comparison operator.
         * Handles both numeric and string operands.
         */
        static int comparisonDistance(MyVar condition) {
                MyVar left  = condition.left;
                MyVar right = condition.right;
                String op   = condition.operator;

                // --- String comparison ---
                if (left.type == TypeEnum.STRING || right.type == TypeEnum.STRING) {
                        String ls = left.type  == TypeEnum.STRING ? left.str_value  : String.valueOf(left.int_value);
                        String rs = right.type == TypeEnum.STRING ? right.str_value : String.valueOf(right.int_value);

                        if (op.equals("==")) {
                                if (ls.equals(rs)) return 0;
                                // Character-level distance (RERS inputs are typically single chars)
                                int dist = Math.abs(ls.length() - rs.length());
                                int len  = Math.min(ls.length(), rs.length());
                                for (int i = 0; i < len; i++) {
                                        dist += Math.abs(ls.charAt(i) - rs.charAt(i));
                                }
                                return dist;
                        }
                        if (op.equals("!=")) return ls.equals(rs) ? 1 : 0;
                        return 0; // <, <=, >, >= not meaningful for arbitrary strings
                }

                // --- Numeric comparison ---
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

        /** Extract an integer value from a leaf MyVar node. */
        static int getIntValue(MyVar m) {
                if (m.type == TypeEnum.INT)  return m.int_value;
                if (m.type == TypeEnum.BOOL) return m.value ? 1 : 0;
                return 0;
        }

        /**
         * Normalize a raw distance d to the range [0, 1).
         * D = d / (d + 1)
         */
        static float normalize(int d) {
                return (float) d / (d + 1);
        }

        static List<String> mutate(String[] inputSymbols, List<String> trace) {
                List<String> mutated = new ArrayList<>(trace);

                if (mutated.isEmpty()) {
                        // If empty somehow, recover with random trace
                        return generateRandomTrace(inputSymbols);
                }

                int mutationType = r.nextInt(3);

                if (mutated.size() <= MIN_TRACE_LENGTH) {
                        mutationType = 1; // add only
                }
                if (mutated.size() >= MAX_TRACE_LENGTH) {
                        mutationType = r.nextInt(2) == 0 ? 0 : 2; // change or delete only
                }

                // Single-symbol alphabet: can't do a meaningful change
                if (inputSymbols.length <= 1 && mutationType == 0) {
                        mutationType = 1;
                }

                switch (mutationType) {
                        case 0: // Change: replace symbol at a random index with a DIFFERENT symbol
                                int changeIdx = r.nextInt(mutated.size());
                                String current = mutated.get(changeIdx);
                                String newSym = inputSymbols[r.nextInt(inputSymbols.length)];
                                while (newSym.equals(current)) {
                                        newSym = inputSymbols[r.nextInt(inputSymbols.length)];
                                }
                                mutated.set(changeIdx, newSym);
                                break;

                        case 1: // Add: insert a random symbol at a random position
                                int addIdx = r.nextInt(mutated.size() + 1);
                                mutated.add(addIdx, inputSymbols[r.nextInt(inputSymbols.length)]);
                                break;

                        case 2: // Delete: remove symbol at a random index
                                mutated.remove(r.nextInt(mutated.size()));
                                break;
                }

                return mutated;
        }

        /**
         * Method for fuzzing new inputs for a program.
         * @param inputSymbols the inputSymbols to fuzz from.
         * @return a fuzzed sequence
         */
        static List<String> fuzz(String[] inputSymbols, List<String> inputTrace){
                /*
                 * Add here your code for fuzzing a new sequence for the RERS problem.
                 * You can guide your fuzzer to fuzz "smart" input sequences to cover
                 * more branches. Right now we just generate a complete random sequence
                 * using the given input symbols. Please change it to your own code.
                 */

                if (inputTrace == null) {
                        return generateRandomTrace(inputSymbols);
                }
                
                return mutate(inputSymbols, inputTrace);
        }

        /**
         * Generate a random trace from an array of symbols.
         * @param symbols the symbols from which a trace should be generated from.
         * @return a random trace that is generated from the given symbols.
         */
        static List<String> generateRandomTrace(String[] symbols) {
                ArrayList<String> trace = new ArrayList<>();
                for (int i = 0; i < traceLength; i++) {
                        trace.add(symbols[r.nextInt(symbols.length)]);
                }
                return trace;
        }

        /**
         * Execute the current trace and updates the total traces executed and
         * the best trace of unique branch counts
         */
        static void executeCurrentTrace() {
                currentTraceBranchDistance = 0;
                currentTraceUniqueBranches = new HashSet<>();
                DistanceTracker.runNextFuzzedSequence(currentTrace.toArray(new String[0]));
                totalTraces++;

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
                System.out.println("=================================================");
        }

        static void runHillClimber() {
                initialize(DistanceTracker.inputSymbols);
                int noImprovementCount = 0;

                while (!isFinished && System.currentTimeMillis() - startTime < TIMEOUT_MS) {
                        // Reset or start fresh
                        if (bestTraceSoFar == null) {
                        currentTrace = generateRandomTrace(DistanceTracker.inputSymbols);
                        executeCurrentTrace();
                        bestTraceSoFar = new ArrayList<>(currentTrace);
                        bestDistanceSoFar = currentTraceBranchDistance;
                        noImprovementCount = 0;
                        continue; // start climbing from this base
                        }

                        List<String> bestMutation = null;
                        float bestMutationDistance = Float.MAX_VALUE; // <-- key fix: start fresh

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
                        // Only reset after N consecutive failures, not immediately
                        if (noImprovementCount >= 3) {
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
                if (runExperiments) {
                        runSimpleFuzzer();
                        
                        // Reset for next experiment
                        currentTrace = null;
                        allUniqueBranches.clear();
                        triggeredErrors.clear();
                        totalTraces = 0;
                        bestTrace = null;
                        bestTraceUniqueBranchCount = 0;
                        bestTraceSoFar = null;
                        bestDistanceSoFar = Float.MAX_VALUE;

                        runHillClimber();
                }
                else {
                        if (useHillClimber) {
                                runHillClimber();
                        }
                        else {
                                runSimpleFuzzer();
                        }
                }

                System.exit(0);
        }

        /**
         * Method that is used for catching the output from standard out.
         * You should write your own logic here.
         * @param out the string that has been outputted in the standard out.
         */
        public static void output(String out){
                System.out.println(out);
                if (out.contains("error_")) {
                        triggeredErrors.add(out.trim());
                }
        }
}
