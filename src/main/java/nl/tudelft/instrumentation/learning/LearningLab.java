package nl.tudelft.instrumentation.learning;

import java.util.*;

/**
 * You should write your own solution using this class.
 */
public class LearningLab {
    static Random r = new Random();
    static int traceLength = 10;
    static boolean isFinished = false;

    static ObservationTable observationTable;
    static EquivalenceChecker equivalenceChecker;

    static void run() {

        SystemUnderLearn sul = new RersSUL();
        observationTable = new ObservationTable(LearningTracker.inputSymbols, sul);
        equivalenceChecker = new WMethodEquivalenceChecker(sul, LearningTracker.inputSymbols, 3, observationTable, observationTable);
        // equivalenceChecker = new RandomWalkEquivalenceChecker(sul, LearningTracker.inputSymbols, 100, 1000);

        int iteration = 1;

        // L* main learning loop
        while (!isFinished) {

            // Step 1: Make the observation table closed and consistent
            Optional<Word<String>> unclosed = observationTable.checkForClosed();
            while (unclosed.isPresent()) {
                observationTable.addToS(unclosed.get());
                unclosed = observationTable.checkForClosed();
            }

            Optional<Word<String>> inconsistency = observationTable.checkForConsistent();
            while (inconsistency.isPresent()) {
                observationTable.addToE(inconsistency.get());
                // After adding to E, table might become not closed again
                unclosed = observationTable.checkForClosed();
                while (unclosed.isPresent()) {
                    observationTable.addToS(unclosed.get());
                    unclosed = observationTable.checkForClosed();
                }
                inconsistency = observationTable.checkForConsistent();
            }

            // Step 2: Generate hypothesis and write DOT (only a single output line per iteration)
            MealyMachine hypothesis = observationTable.generateHypothesis();
            int stateCount = hypothesis.getStates().length;
            String dotFile = String.format("hypothesis-iter-%02d.dot", iteration);
            hypothesis.writeToDot(dotFile);
            System.out.printf("%% Generated hypothesis iteration %d with %d states (%s)\n", iteration, stateCount, dotFile);

            // Step 3: Check equivalence
            Optional<Word<String>> counterexample = equivalenceChecker.verify(hypothesis);

            if (counterexample.isPresent()) {
                // Step 4: Process counterexample - add all prefixes to S (no extra printing)
                Word<String> ce = counterexample.get();
                List<String> symbols = ce.asList();
                Word<String> prefix = new Word<>();
                for (String symbol : symbols) {
                    prefix = prefix.append(symbol);
                    observationTable.addToS(prefix);
                }
                iteration++;
            } else {
                // No counterexample found - learning is done
                System.out.printf("%% No counterexample found. Learning complete.\n");
                isFinished = true;
            }
        }
    }


    /**
     * Method that is used for catching the output from standard out.
     * 
     * @param out the string that has been outputted in the standard out.
     */
    public static void output(String out) {
        // System.out.println(out);
    }
}
