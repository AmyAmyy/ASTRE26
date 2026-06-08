package nl.tudelft.instrumentation.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Arrays;

public class WMethodEquivalenceChecker extends EquivalenceChecker{

    private int w;
    private AccessSequenceGenerator accessSequenceGenerator;
    private DistinguishingSequenceGenerator distinguishingSequenceGenerator;

    public WMethodEquivalenceChecker(SystemUnderLearn sul, String[] inputSymbols, int w, DistinguishingSequenceGenerator dg, AccessSequenceGenerator ag) {
        super(sul, inputSymbols);
        this.w = w;
        this.distinguishingSequenceGenerator= dg;
        this.accessSequenceGenerator= ag;
    }

    @Override
    public Optional<Word<String>> verify(MealyMachine hypothesis) {
        List<Word<String>> accessSequences = accessSequenceGenerator.getAccessSequences();
        List<Word<String>> distinguishingSequences = distinguishingSequenceGenerator.getDistinguishingSequences();

        // Generate all middle parts X of length 0 to w
        List<Word<String>> middleParts = generateMiddleParts();

        // Test all combinations: A X W
        for (Word<String> a : accessSequences) {
            for (Word<String> x : middleParts) {
                for (Word<String> d : distinguishingSequences) {
                    // Build the full test word: a x d
                    Word<String> testWord = a.append(x).append(d);

                    String[] sulOutputs = sul.getOutput(testWord);
                    String[] hypothesisOutputs = hypothesis.getOutput(testWord);
                    if (!Arrays.equals(sulOutputs, hypothesisOutputs)) {
                        return Optional.of(findCounterexample(testWord, hypothesis));
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Generate all words over the input alphabet of length 0 up to w.
     */
    private List<Word<String>> generateMiddleParts() {
        List<Word<String>> result = new ArrayList<>();
        // Length 0: empty word
        result.add(new Word<>());

        // For lengths 1 to w, iteratively extend
        List<Word<String>> currentLevel = new ArrayList<>();
        currentLevel.add(new Word<>());

        for (int depth = 1; depth <= w; depth++) {
            List<Word<String>> nextLevel = new ArrayList<>();
            for (Word<String> word : currentLevel) {
                for (String symbol : inputSymbols) {
                    Word<String> extended = word.append(symbol);
                    nextLevel.add(extended);
                    result.add(extended);
                }
            }
            currentLevel = nextLevel;
        }

        return result;
    }

    /**
     * Find the shortest prefix of testWord where the SUL and hypothesis diverge.
     */
    private Word<String> findCounterexample(Word<String> testWord, MealyMachine hypothesis) {
        List<String> symbols = testWord.asList();
        String[] sulOutputs = sul.getOutput(testWord);
        String[] hypOutputs = hypothesis.getOutput(testWord);

        // Find the first point of divergence and return the prefix up to that point
        for (int i = 0; i < sulOutputs.length; i++) {
            if (!sulOutputs[i].equals(hypOutputs[i])) {
                // Return prefix of length i+1
                List<String> prefix = symbols.subList(0, i + 1);
                return new Word<>(prefix);
            }
        }

        // Shouldn't reach here, but return the full word as fallback
        return testWord;
    }

}
