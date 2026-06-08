package nl.tudelft.instrumentation.learning;

import java.util.*;
import java.io.File;

/**
 * You should write your own solution using this class.
 */
public class LearningLab {
    static Random r = new Random();
    static int traceLength = 10;
    static boolean isFinished = false;
    // W-method depth: pass via -Dw=<value>, default 3
    static int wMethodDepth = Integer.parseInt(System.getProperty("w", "3"));
    // Output directory for dot files: pass via -DoutputDir=<path>, default "."
    static String outputDir = System.getProperty("outputDir", ".");

    static ObservationTable observationTable;
    static EquivalenceChecker equivalenceChecker;

    static void run() {

        // If "learnlib" system property is set, run LearnLib instead
        if (System.getProperty("learnlib") != null) {
            LearnLibRunner llr = new LearnLibRunner();
            llr.start(wMethodDepth);
            System.exit(0);
        }

        // Ensure output directory exists
        new File(outputDir).mkdirs();

        SystemUnderLearn sul = new RersSUL();
        observationTable = new ObservationTable(LearningTracker.inputSymbols, sul);
        equivalenceChecker = new WMethodEquivalenceChecker(sul, LearningTracker.inputSymbols, wMethodDepth, observationTable, observationTable);

        int iteration = 1;
        long startTime = System.currentTimeMillis();

        // L* main learning loop
        while (!isFinished) {

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

            // Generate hypothesis and write DOT
            MealyMachine hypothesis = observationTable.generateHypothesis();
            int stateCount = hypothesis.getStates().length;
            String dotFile = outputDir + "/hypothesis-iter-" + String.format("%02d", iteration) + ".dot";
            hypothesis.writeToDot(dotFile);
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.printf("ITERATION %d | states=%d | queries=%d | time_ms=%d\n",
                    iteration, stateCount, LearningTracker.membershipQueries, elapsed);

            Optional<Word<String>> counterexample = equivalenceChecker.verify(hypothesis);

            if (counterexample.isPresent()) {
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
                long totalTime = System.currentTimeMillis() - startTime;
                System.out.printf("DONE | final_states=%d | total_queries=%d | total_time_ms=%d\n",
                        stateCount, LearningTracker.membershipQueries, totalTime);
                // Write final hypothesis
                hypothesis.writeToDot(outputDir + "/hypothesis-final.dot");
                isFinished = true;
                System.exit(0);
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
