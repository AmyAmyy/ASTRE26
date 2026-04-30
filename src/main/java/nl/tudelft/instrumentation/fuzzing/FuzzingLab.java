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
        static float totalBranchDistance = 0; // accumulated distance over one trace run

        static void initialize(String[] inputSymbols){
                // Initialise a random trace from the input symbols of the problem.
                currentTrace = generateRandomTrace(inputSymbols);
        }

        /**
         * Called by DistanceTracker.myIf for every if-statement encountered during a run.
         * Computes the branch distance for this condition and accumulates it.
         */
        static void encounteredNewBranch(MyVar condition, boolean value, int line_nr) {
                float d = computeBranchDistance(condition);
                totalBranchDistance += d;

                System.out.println("Condition: " + condition.toString());
                System.out.println("Value: " + value + " | Line: " + line_nr + " | Distance: " + d);
        }

        /**
         * Recursively compute the normalized branch distance in [0, 1) for making
         * the given condition evaluate to true, using the concrete values stored in
         * the MyVar tree.
         *
         * Formulas (from lecture slides):
         *   a           : 0 if true, 1 otherwise
         *   !p1         : 1 - D(p1)
         *   a == b      : |a - b|
         *   a != b      : 0 if a != b, 1 otherwise
         *   a <  b      : 0 if a < b,  a - b + K otherwise
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

                                // --- Logical operators: combine normalized sub-distances ---
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

                                // --- Comparison operators: compute raw distance, then normalize ---
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
                        return 0; // <, <=, >, >= not meaningful for strings here
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

        /**
         * Method for fuzzing new inputs for a program.
         * @param inputSymbols the inputSymbols to fuzz from.
         * @return a fuzzed sequence
         */
        static List<String> fuzz(String[] inputSymbols){
                /*
                 * Add here your code for fuzzing a new sequence for the RERS problem.
                 * You can guide your fuzzer to fuzz "smart" input sequences to cover
                 * more branches. Right now we just generate a complete random sequence
                 * using the given input symbols. Please change it to your own code.
                 */
                return generateRandomTrace(inputSymbols);
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

        static void run() {
                initialize(DistanceTracker.inputSymbols);

                // Place here your code to guide your fuzzer with its search.
                while(!isFinished) {
                        // Reset per-trace accumulator, generate and run a new trace
                        totalBranchDistance = 0;
                        currentTrace = fuzz(DistanceTracker.inputSymbols);
                        DistanceTracker.runNextFuzzedSequence(currentTrace.toArray(new String[0]));

                        System.out.println("Trace: " + currentTrace);
                        System.out.println("Total branch distance: " + totalBranchDistance);
                }
        }

        /**
         * Method that is used for catching the output from standard out.
         * You should write your own logic here.
         * @param out the string that has been outputted in the standard out.
         */
        public static void output(String out){
                System.out.println(out);
        }
}
