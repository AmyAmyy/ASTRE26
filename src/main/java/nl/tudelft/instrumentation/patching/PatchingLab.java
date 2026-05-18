package nl.tudelft.instrumentation.patching;
import java.util.*;

public class PatchingLab {

        static Random r = new Random();
        static boolean isFinished = false;

        // Map from test index to set of operator IDs executed during that test
        static Map<Integer, Set<Integer>> executedOperators = new HashMap<>();

        static final int totalPopulationSize = 50;
        static List<Individual> population = new ArrayList<>();
        
        static final int tournamentSize = 5;

        static final double mutationRate = 0.2;

        static final int maxGenerations = 200;

        static final int mutateOperatorCount = 15;

        // Number of best individuals copied unchanged into the next generation.
        static final int eliteCount = 5;

        // Probability that a child receives a flip outside the tarantula topN
        static final double offListMutationRate = 0.10;

        static final String[] NUMERIC_OPERATORS = new String[] {"==", "!=", "<", ">", "<=", ">="};
        static final String[] BOOLEAN_OPERATORS = new String[] {"==", "!="};

        // Array to track whether each operator slot is a boolean operator (true) or numeric operator (false)
        static boolean[] isBooleanOp;

        static class Individual {
                String[] operators;
                double fitness;

                public Individual(String[] operators, double fitness) {
                        this.operators = operators;
                        this.fitness = fitness;
                }
        }

        static void initialize(Individual initial, List<Integer> mutableOperators) {
                // initialize the population based on OperatorTracker.operators
                population.clear();

                population.add(initial);
                for (int i = 1; i < totalPopulationSize; i++) {
                        population.add(mutate(initial, mutableOperators));
                }
        }

        // encounteredOperator gets called for each operator encountered while running tests
        static boolean encounteredOperator(String operator, int left, int right, int operator_nr){
                if (isBooleanOp == null) {
                        isBooleanOp = new boolean[OperatorTracker.operators.length];
                }
                isBooleanOp[operator_nr] = false;

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
                if (isBooleanOp == null) {
                        isBooleanOp = new boolean[OperatorTracker.operators.length];
                }
                isBooleanOp[operator_nr] = true;

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

        static void run() {
                String[] initialBuggyProgram = OperatorTracker.operators.clone();
                List<Boolean> results = OperatorTracker.runAllTests();
                Map<Integer, Double> initialTarantulaScores = computeTarantulaFitness(results);
                List<Integer> initialMutableOperators = localizeTarantula(initialTarantulaScores, mutateOperatorCount);
                initialize(new Individual(initialBuggyProgram, Double.MAX_VALUE), initialMutableOperators);

                // compute initial fitness for the current buggy operator set
                double initialFitness = computeFitness(initialBuggyProgram);
                System.out.println("Initial fitness = " + initialFitness);

                if (initialFitness == 0) {
                        System.out.println("All tests already pass. No faulty operator detected.");
                        return;
                }
                
                Individual bestIndividual = new Individual(initialBuggyProgram, initialFitness);

                for (int generation = 1; generation <= maxGenerations && bestIndividual.fitness > 0.0; generation++) {
                        // Re-run tests with best known operators to get fresh coverage data
                        executedOperators.clear();
                        OperatorTracker.operators = bestIndividual.operators;
                        List<Boolean> currentResults = OperatorTracker.runAllTests();

                        // Recompute Tarantula based on current best
                        Map<Integer, Double> tarantulaScores = computeTarantulaFitness(currentResults);
                        List<Integer> mutableOperators = localizeTarantula(tarantulaScores, mutateOperatorCount);

                        List<Individual> newPopulation = new ArrayList<>();

                        // Carry the top eliteCount individuals unchanged
                        List<Individual> sorted = new ArrayList<>(population);
                        sorted.sort(Comparator.comparingDouble(ind -> ind.fitness));
                        int elites = Math.min(eliteCount, sorted.size());
                        for (int i = 0; i < elites; i++) {
                                newPopulation.add(sorted.get(i));
                        }

                        // Fill the rest with offspring.
                        while (newPopulation.size() < totalPopulationSize) {
                                Individual parent = tournamentSelection(population);
                                Individual offspring = mutate(parent, mutableOperators);
                                if (offspring.fitness < bestIndividual.fitness) {
                                        bestIndividual = offspring;
                                }
                                newPopulation.add(offspring);
                        }
                        population = newPopulation;
                        System.out.println("Generation " + generation + ": best fitness = " + bestIndividual.fitness);
                }
                
        }

        public static void output(String out){
                // This will get called when the problem code tries to print things,
                // the prints in the original code have been removed for your convenience

                // System.out.println(out);
        }

        static double computeFitness(String[] candidateOperators) {
                OperatorTracker.operators = candidateOperators;
                List<Boolean> results = OperatorTracker.runAllTests();

                int failCount = 0;
                for (boolean passed : results) {
                        if (!passed) failCount++;
                }

                double fitness = (double) failCount / results.size();
                // System.out.println("Fitness (normalized): " + fitness);
                return fitness;                
        }

        static String randomReplacement(boolean isBooleanOp, String current) {
                String[] candidates =
                        isBooleanOp
                                ? BOOLEAN_OPERATORS
                                : NUMERIC_OPERATORS;

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
                        if (failCounts[op] == 0 && passCounts[op] == 0) {
                                tarantulaScores.put(op, 0.0); // not executed in any test
                                continue;
                        }
                        if (totalFailing == 0) {
                                tarantulaScores.put(op, 0.0); // no failing tests, so no suspicion
                                continue;
                        }
                        if (totalPassing == 0) {
                                tarantulaScores.put(op, 1.0); // no passing tests, so all executed operators are suspicious
                                continue;
                        }
                        double failRate = (double) failCounts[op] / totalFailing;
                        double passRate = (double) passCounts[op] / totalPassing;
                        tarantulaScores.put(op, failRate / (failRate + passRate));
                }

                return tarantulaScores;
        }

        static List<Integer> localizeTarantula(Map<Integer, Double> tarantulaScores, int topN) {
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

        static Individual mutate(Individual parent, List<Integer> mutableOperators) {
                String[] childOperators = parent.operators.clone();

                int flips = 0;
                for (int i = 0; i < mutableOperators.size(); i++) {
                        if (r.nextDouble() < mutationRate) {
                                int idx = mutableOperators.get(i);
                                childOperators[idx] = randomReplacement(isBooleanOp[idx], childOperators[idx]);
                                flips++;
                        }
                }

                // Exploratory mutation
                if (r.nextDouble() < offListMutationRate) {
                        // Randomly flip an operator
                        int idx = r.nextInt(childOperators.length);
                        childOperators[idx] = randomReplacement(isBooleanOp[idx], childOperators[idx]);
                }

                // Force at least one flip so we make progress
                if (flips == 0 && !mutableOperators.isEmpty()) {
                        int idx = mutableOperators.get(r.nextInt(mutableOperators.size()));
                        childOperators[idx] = randomReplacement(isBooleanOp[idx], childOperators[idx]);
                }

                return new Individual(childOperators, computeFitness(childOperators));
        }
}

