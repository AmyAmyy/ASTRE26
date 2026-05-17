package nl.tudelft.instrumentation.patching;
import java.util.*;

public class PatchingLab {

        static Random r = new Random();
        static boolean isFinished = false;

        // Map from test index to set of operator IDs executed during that test
        static Map<Integer, Set<Integer>> executedOperators = new HashMap<>();

        static final int totalPopulationSize = 50;
        static List<Individual> population = new ArrayList<>();
        
        // Map<Integer, String> patch = new HashMap<>();

        static final int tournamentSize = 5;

        static final double mutationRate = 0.01;

        static class Individual {
                String[] operators;
                double fitness;

                public Individual(String[] operators, double fitness) {
                        this.operators = operators;
                        this.fitness = fitness;
                }
        }

        static void initialize(){
                // initialize the population based on OperatorTracker.operators
                population.clear();
                population.add(new Individual(OperatorTracker.operators.clone(), Double.MAX_VALUE)); // include original as first candidate

                for (int i = 1; i < totalPopulationSize; i++) {
                        String[] candidate = new String[OperatorTracker.operators.length];
                        for (int j = 0; j < candidate.length; j++) {
                                candidate[j] = randomReplacement(OperatorTracker.operators[j]); 
                        }
                        population.add(new Individual(candidate, Double.MAX_VALUE)); // fitness will be computed later
                }

        }

        // encounteredOperator gets called for each operator encountered while running tests
        static boolean encounteredOperator(String operator, int left, int right, int operator_nr){
                // Add encountered operator to executedOperators map for the current test
                int current_test = OperatorTracker.current_test;
                if (!executedOperators.containsKey(current_test)) {
                        executedOperators.put(current_test, new HashSet<>());
                }
                executedOperators.get(current_test).add(operator_nr);

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
                // Add encountered operator to executedOperators map for the current test
                int current_test = OperatorTracker.current_test;
                if (!executedOperators.containsKey(current_test)) {
                        executedOperators.put(current_test, new HashSet<>());
                }
                executedOperators.get(current_test).add(operator_nr);

                String replacement = OperatorTracker.operators[operator_nr];
                if(replacement.equals("!=")) return left != right;
                if(replacement.equals("==")) return left == right;
                return false;
        }

        static final String[] NUMERIC_OPERATORS = {"!=", "==", "<", ">", "<=", ">="};
        static final String[] BOOLEAN_OPERATORS = {"!=", "=="};

        static void run() {
                initialize();

                List<Boolean> results = OperatorTracker.runAllTests();
                // compute initial fitness for the current buggy operator set
                double initialFitness = computeFitness(results);
                System.out.println("Initial fitness = " + initialFitness);

                if (initialFitness == 0) {
                        System.out.println("All tests already pass. No faulty operator detected.");
                        return;
                }

                // Example search: try a few random one-operator mutations and keep improvements
                // String[] bestOperators = OperatorTracker.operators.clone();
                // int bestFitness = initialFitness;
                // int maxAttempts = 30;

                // for (int attempt = 1; attempt <= maxAttempts && bestFitness > 0; attempt++) {
                //         String[] candidate = bestOperators.clone();
                //         int operatorIndex = r.nextInt(candidate.length);
                //         candidate[operatorIndex] = randomReplacement(candidate[operatorIndex]);

                //         int fitness = computeFitness(candidate);
                //         System.out.println("Attempt " + attempt + ": operator[" + operatorIndex + "]=" + candidate[operatorIndex] + ", fitness=" + fitness);

                //         if (fitness < bestFitness) {
                //                 bestFitness = fitness;
                //                 bestOperators = candidate;
                //                 System.out.println("  New best fitness = " + bestFitness);
                //         }
                // }

                // OperatorTracker.operators = bestOperators;
                // System.out.println("Search finished. Best fitness = " + bestFitness);
                // if (bestFitness == 0) {
                //         System.out.println("Found a candidate that passes all tests.");
                // } else {
                //         System.out.println("No perfect repair found in the example search. Use this fitness function to guide a stronger search.");
                // }
        }

        public static void output(String out){
                // This will get called when the problem code tries to print things,
                // the prints in the original code have been removed for your convenience

                // System.out.println(out);
        }

        static double computeFitness(List<Boolean> results) {
                int failCount = 0;
                for (boolean passed : results) {
                        if (!passed) failCount++;
                }

                double fitness = (double) failCount / results.size();
                System.out.println("Fitness (normalized): " + fitness);
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

        static Map<Integer, Double> computeTarantulaFitness(List<Boolean> results) {
                int operatorCount = OperatorTracker.operators.length;
                
                int[] failCounts = new int[operatorCount];
                int[] passCounts = new int[operatorCount];
                int totalFailing = 0;
                int totalPassing = 0;
                
                for (int i = 0; i < results.size(); i++) {
                        boolean passed = results.get(i);
                        Set<Integer> executed = executedOperators.getOrDefault(i, Collections.emptySet());
                        for (int op : executed) {
                                if (passed) {
                                        passCounts[op]++;
                                } else {
                                        failCounts[op]++;
                                }
                        }
                        if (passed) totalPassing++;
                        else totalFailing++;
                }

                Map<Integer, Double> tarantulaScores = new HashMap<>();
                for (int op = 0; op < operatorCount; op++) {
                        double failRate = (double) failCounts[op] / totalFailing;
                        double passRate = (double) passCounts[op] / totalPassing;
                        tarantulaScores.put(op, failRate / (failRate + passRate));
                }

                return tarantulaScores;
        }

        static List<Integer> rankOperatorsByTarantula(Map<Integer, Double> tarantulaScores, int topN) {
                List<Integer> ranked = new ArrayList<>(tarantulaScores.keySet());
                ranked.sort((a, b) -> Double.compare(tarantulaScores.get(b), tarantulaScores.get(a)));
                return ranked.subList(0, Math.min(topN, ranked.size())); // return top N operators
        }

        static Individual tournamentSelection(List<Individual> population) {
                List<Individual> tournament = new ArrayList<>();
                for (int i = 0; i < tournamentSize; i++) {
                        tournament.add(population.get(r.nextInt(population.size())));
                }
                return Collections.min(tournament, Comparator.comparingDouble(ind -> ind.fitness));
        }

        static Individual mutate(Individual parent) {
                String[] childOperators = parent.operators.clone();
                for (int i = 0; i < childOperators.length; i++) {
                        if (r.nextDouble() < mutationRate) {
                                childOperators[i] = randomReplacement(childOperators[i]);
                        }
                }
                return new Individual(childOperators, Double.MAX_VALUE); // fitness will be computed later
        }
}

