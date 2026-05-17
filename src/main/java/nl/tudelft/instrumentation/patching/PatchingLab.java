package nl.tudelft.instrumentation.patching;
import java.util.*;

public class PatchingLab {

        static Random r = new Random();
        static boolean isFinished = false;

        Map<Integer, String> patch = new HashMap<>();

        static void initialize(){
                // initialize the population based on OperatorTracker.operators
        }

        // encounteredOperator gets called for each operator encountered while running tests
        static boolean encounteredOperator(String operator, int left, int right, int operator_nr){
                // Do something useful

                String replacement = OperatorTracker.operators[operator_nr];
                if(replacement.equals("!=")) return left != right;
                if(replacement.equals("==")) return left == right;
                if(replacement.equals("<")) return left < right;
                if(replacement.equals(">")) return left > right;
                if(replacement.equals("<=")) return left <= right;
                if(replacement.equals(">=")) return left >= right;
                return false;
        }

        static boolean encounteredOperator(String operator, boolean left, boolean right, int operator_nr){
                // Do something useful

                String replacement = OperatorTracker.operators[operator_nr];
                if(replacement.equals("!=")) return left != right;
                if(replacement.equals("==")) return left == right;
                return false;
        }

        static final String[] NUMERIC_OPERATORS = {"!=", "==", "<", ">", "<=", ">="};
        static final String[] BOOLEAN_OPERATORS = {"!=", "=="};

        static void run() {
                initialize();

                // compute initial fitness for the current buggy operator set
                int initialFitness = computeFitness(OperatorTracker.operators);
                System.out.println("Initial fitness = " + initialFitness);

                if (initialFitness == 0) {
                        System.out.println("All tests already pass. No faulty operator detected.");
                        return;
                }

                // Example search: try a few random one-operator mutations and keep improvements
                String[] bestOperators = OperatorTracker.operators.clone();
                int bestFitness = initialFitness;
                int maxAttempts = 30;

                for (int attempt = 1; attempt <= maxAttempts && bestFitness > 0; attempt++) {
                        String[] candidate = bestOperators.clone();
                        int operatorIndex = r.nextInt(candidate.length);
                        candidate[operatorIndex] = randomReplacement(candidate[operatorIndex]);

                        int fitness = computeFitness(candidate);
                        System.out.println("Attempt " + attempt + ": operator[" + operatorIndex + "]=" + candidate[operatorIndex] + ", fitness=" + fitness);

                        if (fitness < bestFitness) {
                                bestFitness = fitness;
                                bestOperators = candidate;
                                System.out.println("  New best fitness = " + bestFitness);
                        }
                }

                OperatorTracker.operators = bestOperators;
                System.out.println("Search finished. Best fitness = " + bestFitness);
                if (bestFitness == 0) {
                        System.out.println("Found a candidate that passes all tests.");
                } else {
                        System.out.println("No perfect repair found in the example search. Use this fitness function to guide a stronger search.");
                }
        }

        public static void output(String out){
                // This will get called when the problem code tries to print things,
                // the prints in the original code have been removed for your convenience

                System.out.println(out);
        }

        static double computeFitness() {
                List<Boolean> results = OperatorTracker.runAllTests();
                int failCount = 0;
                for (boolean passed : results) {
                        if (!passed) failCount++;
                }
                
                double fitness = (double) failCount / (double) results.size();
                System.out.println("Fitness (failing tests): " + fitness);
                return fitness;
        }

        static double computeFitness(String[] candidateOperators) {
                String[] previousOperators = OperatorTracker.operators;
                OperatorTracker.operators = candidateOperators;
                double fitness = computeFitness();
                OperatorTracker.operators = previousOperators;
                return fitness;
        }

        static String randomReplacement(String current) {
                String[] candidates = current.equals("==") || current.equals("!=") ? NUMERIC_OPERATORS : NUMERIC_OPERATORS;
                String replacement = current;
                while (replacement.equals(current)) {
                        replacement = candidates[r.nextInt(candidates.length)];
                }
                return replacement;
        }
}