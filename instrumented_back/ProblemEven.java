import java.io.BufferedReader;
import java.io.InputStreamReader;
import nl.tudelft.instrumentation.fuzzing.*;
import nl.tudelft.instrumentation.runner.CallableTraceRunner;

public class ProblemEven implements CallableTraceRunner<Void> {

    static BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));

    private String[] inputs = { "0", "1" };

    public boolean cf = true;

    public boolean even0s = true;

    public boolean even1s = true;

    public void calculateOutput(String input) {
        cf = true;
        DistanceTracker.myIf(DistanceTracker.equals(DistanceTracker.MyVar(inputs[0]), DistanceTracker.MyVar(input)), input.equals(inputs[0]), 15);
        if (input.equals(inputs[0])) {
            cf = false;
            even0s = !even0s;
        }
        DistanceTracker.myIf(DistanceTracker.equals(DistanceTracker.MyVar(inputs[1]), DistanceTracker.MyVar(input)), input.equals(inputs[1]), 19);
        if (input.equals(inputs[1])) {
            cf = false;
            even1s = !even1s;
        }
        DistanceTracker.myIf(DistanceTracker.binaryExpr(DistanceTracker.MyVar(even0s), DistanceTracker.MyVar(even1s), "&&"), even0s && even1s, 23);
        if (even0s && even1s) {
            DistanceTracker.output("EVEN");
        } else {
            DistanceTracker.output("ODD");
        }
        DistanceTracker.myIf(DistanceTracker.MyVar(cf), cf, 29);
        if (cf) {
            throw new IllegalArgumentException("Current state has no transition for this input!");
        }
    }

    public static void main(String[] args) throws Exception {
        // init system and input reader
        ProblemEven eca = new ProblemEven();
        DistanceTracker.run(eca.inputs, eca);
    }

    public String[] sequence;

    public Void call() {
        ProblemEven cp = new ProblemEven();
        for (String s : sequence) {
            try {
                cp.calculateOutput(s);
            } catch (Exception e) {
                FuzzingLab.output("Invalid input: " + e.getMessage());
            }
        }
        return null;
    }

    public void setSequence(String[] trace) {
        sequence = trace;
    }
}

