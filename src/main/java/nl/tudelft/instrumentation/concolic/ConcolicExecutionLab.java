package nl.tudelft.instrumentation.concolic;

import java.util.*;
import java.util.stream.Collectors;
import java.io.*;

import com.microsoft.z3.*;

/**
 * You should write your solution using this class.
 * 
 * Z3 API: https://z3prover.github.io/api/html/classcom_1_1microsoft_1_1z3_1_1_context.html
 */
public class ConcolicExecutionLab {

    // Seed from -Dseed=N for reproducible independent runs
    static final long seed = Long.parseLong(System.getProperty("seed",
            String.valueOf(System.nanoTime() % 1_000_000L)));
    static Random r = new Random(seed);
    static Boolean isFinished = false;
    static List<String> currentTrace;
    static int traceLength = 10;

    private static Set<String> visitedBranches = new HashSet<>();
    private static Set<String> satBranches = new HashSet<>();
    private static Set<String> unsatBranches = new HashSet<>();
    private static Deque<List<String>> traceQueue = new ArrayDeque<>();
    private static String currentBranchKey = null;
    private static boolean sat = false;
    private static int iterations = 0;

    private static Set<String> triggeredErrors = new HashSet<>();
    private static List<long[]> errorTimeline = new ArrayList<>(); // {time_ms, unique_error_count}
    private static List<long[]> branchTimeline = new ArrayList<>(); // {time_ms, unique_branch_count}
    private static long startTime;
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;

    // Coverage-guided concrete seeding.
    // Pool of ordered trace fragments that previously increased branch coverage.
    // These fragments are reused (in order) as seeds for future fuzzed traces.
    private static List<List<String>> goodFragments = new ArrayList<>();
    private static final int MAX_FRAGMENT_POOL = 30;
    // Length of the solver-derived prefix of the current trace (the part pulled
    // from traceQueue, before any padding). Used to attribute new coverage to the
    // ordered prefix that actually reached it, rather than to the random padding.
    private static int currentPrefixLength = 0;
    // Ablation knob: probability of seeding from a good fragment instead of padding
    // randomly. Set -DreuseProb=0.0 to disable the improvement (= base concolic).
    private static final double REUSE_PROB =
            Double.parseDouble(System.getProperty("reuseProb", "0.7"));

    static void initialize(String[] inputSymbols){
        // Initialise a random trace from the input symbols of the problem.
        currentTrace = generateRandomTrace(inputSymbols);
        startTime = System.currentTimeMillis();
    }

    // Called from run() after each fuzzed sequence. If the trace just executed
    // increased branch coverage, remember the ordered solver-derived prefix (the
    // part pulled from traceQueue, before padding) as a reusable seed fragment.
    // The new coverage is reached by that prefix driving the program into a
    // specific state; the random padding tail did not cause it, so we exclude it
    // to avoid diluting the pool with symbols that contributed nothing.
    static void recordIfGood(List<String> trace, int branchesBefore) {
        if (visitedBranches.size() > branchesBefore) {
            // Attribute coverage to the solver-derived prefix only. Fall back to
            // the whole trace when there was no prefix (purely random trace).
            int len = currentPrefixLength > 0
                    ? Math.min(currentPrefixLength, trace.size())
                    : trace.size();
            if (len >= 2) {
                goodFragments.add(new ArrayList<>(trace.subList(0, len)));
                if (goodFragments.size() > MAX_FRAGMENT_POOL) {
                    goodFragments.remove(0); // keep pool fresh, bound memory
                }
            }
        }
    }

    static MyVar createVar(String name, Expr value, Sort s){
        Context c = PathTracker.ctx;
        /**
         * Create var, assign value and add to path constraint.
         * We show how to do it for creating new symbols, please
         * add similar steps to the functions below in order to
         * obtain a path constraint.
         */
        Expr z3var = c.mkConst(c.mkSymbol(name + "_" + PathTracker.z3counter++), s);
        PathTracker.addToModel(c.mkEq(z3var, value));
        return new MyVar(z3var, name);
    }

    static MyVar createInput(String name, Expr value, Sort s){
        // Create an input var, these should be free variables!
        Context c = PathTracker.ctx;

        Expr z3var = c.mkConst(c.mkSymbol(name + "_" + PathTracker.z3counter++), s);

        // The following code is to add an additional constraint on the input variable.
        // The input variable must have a value that is equal to one of the input symbols.
        BoolExpr constraint = c.mkFalse();
        for (String input: PathTracker.inputSymbols) {
            constraint = c.mkOr(c.mkEq(z3var, c.mkString(input)), constraint);
        }

        PathTracker.addToModel(constraint);

        MyVar mv = new MyVar(z3var, name);
        PathTracker.inputs.add(mv);
        return mv;
    }

    static MyVar createBoolExpr(BoolExpr var, String operator){
        // Handle the following unary operators: !
        Context c = PathTracker.ctx;
        if (operator.equals("!")) return new MyVar(c.mkNot(var));
        return new MyVar(c.mkFalse());
    }

    static MyVar createBoolExpr(BoolExpr left_var, BoolExpr right_var, String operator){
        // Handle the following binary operators: &, &&, |, ||
        Context c = PathTracker.ctx;
        switch (operator) {
            case "&":
            case "&&": return new MyVar(c.mkAnd(left_var, right_var));
            case "|":
            case "||": return new MyVar(c.mkOr(left_var, right_var));
            case "^":  return new MyVar(c.mkXor(left_var, right_var));
            case "==": return new MyVar(c.mkEq(left_var, right_var));
            case "!=": return new MyVar(c.mkNot(c.mkEq(left_var, right_var)));
        }
        return new MyVar(c.mkFalse());
    }

    static MyVar createIntExpr(IntExpr var, String operator){
        // Handle the following unary operators for numerical operations: +, -
        Context c = PathTracker.ctx;
        if (operator.equals("+")) return new MyVar(var);
        if (operator.equals("-")) return new MyVar(c.mkUnaryMinus(var));
        return new MyVar(c.mkFalse());
    }

    static MyVar createIntExpr(IntExpr left_var, IntExpr right_var, String operator){
        // Handle the following binary operators for numerical operations: +, -, /, *, %, ^, ==, <=, <, >= and >
        Context c = PathTracker.ctx;
        switch (operator) {
            case "+":  return new MyVar(c.mkAdd(left_var, right_var));
            case "-":  return new MyVar(c.mkSub(left_var, right_var));
            case "*":  return new MyVar(c.mkMul(left_var, right_var));
            case "/":  return new MyVar(c.mkDiv(left_var, right_var));
            case "%":  return new MyVar(c.mkMod(left_var, right_var));
            case "^":  return new MyVar(c.mkPower(left_var, right_var));
            case "==": return new MyVar(c.mkEq(left_var, right_var));
            case "!=": return new MyVar(c.mkNot(c.mkEq(left_var, right_var)));
            case "<":  return new MyVar(c.mkLt(left_var, right_var));
            case "<=": return new MyVar(c.mkLe(left_var, right_var));
            case ">":  return new MyVar(c.mkGt(left_var, right_var));
            case ">=": return new MyVar(c.mkGe(left_var, right_var));
        }
        return new MyVar(c.mkFalse());
    }

    static MyVar createStringExpr(SeqExpr left_var, SeqExpr right_var, String operator){
        // We only support String.equals
        return new MyVar(PathTracker.ctx.mkEq(left_var, right_var));
    }

    static void assign(MyVar var, String name, Expr value, Sort s){
        // All variable assignments, use single static assignment
        Context c = PathTracker.ctx;
        Expr fresh = c.mkConst(c.mkSymbol(name + "_" + PathTracker.z3counter++), s);
        PathTracker.addToModel(c.mkEq(fresh, value));
        var.z3var = fresh;
        var.name = name;
    }

    static void encounteredNewBranch(MyVar condition, boolean value, int line_nr){
        // Call the solver
        Context c = PathTracker.ctx;
        BoolExpr cond = (BoolExpr) condition.z3var;
        BoolExpr taken    = value ? cond : c.mkNot(cond);
        BoolExpr opposite = value ? c.mkNot(cond) : cond;

        String key = line_nr + ":" + value;
        if (!visitedBranches.contains(key)) {
            visitedBranches.add(key);
            // Record first time we cover this (line, side) pair
            branchTimeline.add(new long[]{
                System.currentTimeMillis() - startTime,
                visitedBranches.size()
            });
            currentBranchKey = line_nr + ":" + (!value);
            sat = false;
            PathTracker.solve(opposite, false);
            if (sat) {
                satBranches.add(currentBranchKey);
            } else {
                unsatBranches.add(currentBranchKey);
            }
            currentBranchKey = null;
        }

        PathTracker.addToBranches(taken);
    }

    static void newSatisfiableInput(LinkedList<String> new_inputs) {
        // Hurray! found a new branch using these new inputs!
        // Remove the extra quotes from the inputs that were find by the solver.
        List<String> trimmed_new_inputs = new_inputs.stream()
                .map(s -> s.replaceAll("\"", ""))
                .collect(Collectors.toList());

        sat = true;
        Set<String> legal = new HashSet<>(Arrays.asList(PathTracker.inputSymbols));
        for (String s : trimmed_new_inputs) {
            if (!legal.contains(s)) return;
        }

        if (!trimmed_new_inputs.isEmpty()) {
            traceQueue.addLast(trimmed_new_inputs);
        }
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
         * more branches using concolic execution. Right now we just generate
         * a complete random sequence using the given input symbols. Please
         * change it to your own code.
         */
        if (!traceQueue.isEmpty()) {
            List<String> t = new ArrayList<>(traceQueue.pollFirst());
            // Remember how much of this trace is the solver-derived prefix, so
            // recordIfGood can credit new coverage to the prefix, not the padding.
            currentPrefixLength = t.size();
            // Pad the solver-derived prefix up to traceLength using coverage-guided
            // concrete seeding (ordered fragments that previously reached new
            // branches), falling back to random exploration.
            seedPad(t, inputSymbols);
            return t;
        }

        // Empty queue: the solver is not currently producing new satisfiable
        // prefixes (the steady state once shallow branches are exhausted). Rather
        // than reverting to a fully random trace, build one from the same
        // coverage-increasing fragments (when available), so the improvement also
        // drives this dominant code path. With reuseProb=0 (or an empty pool) this
        // degrades exactly to the base random trace.
        currentPrefixLength = 0; // no solver-derived prefix
        if (goodFragments.isEmpty()) {
            return generateRandomTrace(inputSymbols);
        }
        List<String> t = new ArrayList<>();
        seedPad(t, inputSymbols);
        return t;
    }

    // Pad trace t up to traceLength. With probability REUSE_PROB (and a non-empty
    // pool) splice in an ordered fragment that previously increased coverage,
    // appended from a random offset so we vary the alignment while keeping the
    // relative symbol order intact; otherwise append a single random symbol to
    // retain exploration. Shared by both fuzz() code paths.
    static void seedPad(List<String> t, String[] inputSymbols) {
        while (t.size() < traceLength) {
            if (!goodFragments.isEmpty() && r.nextDouble() < REUSE_PROB) {
                List<String> frag = goodFragments.get(r.nextInt(goodFragments.size()));
                int start = r.nextInt(frag.size());
                for (int i = start; i < frag.size() && t.size() < traceLength; i++) {
                    t.add(frag.get(i));
                }
            } else {
                t.add(inputSymbols[r.nextInt(inputSymbols.length)]);
            }
        }
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

    static volatile boolean logWritten = false;

    static void run() {
        // Shutdown hook: write CSV even if killed externally by a timeout wrapper
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!logWritten && startTime != 0) writeLog();
        }));

        initialize(PathTracker.inputSymbols);
        PathTracker.runNextFuzzedSequence(currentTrace.toArray(new String[0]));
        // Place here your code to guide your fuzzer with its search using Concolic Execution.
        while(!isFinished && System.currentTimeMillis() - startTime < TIMEOUT_MS) {
            iterations++;
            PathTracker.reset();
            currentTrace = fuzz(PathTracker.inputSymbols);
            int branchesBefore = visitedBranches.size();
            PathTracker.runNextFuzzedSequence(currentTrace.toArray(new String[0]));
            recordIfGood(currentTrace, branchesBefore);

            if (iterations % 200 == 0) {
                System.out.printf("[concolic] iter=%d branches=%d sat=%d unsat=%d queued=%d errors=%d%n",
                        iterations, visitedBranches.size(),
                        satBranches.size(), unsatBranches.size(),
                        traceQueue.size(), triggeredErrors.size());
            }
        }
        writeLog();
        System.exit(0);
    }
    static void writeLog() {
        if (logWritten) return;
        logWritten = true;

        String name = PathTracker.problem != null
                ? PathTracker.problem.getClass().getSimpleName() : "unknown";

        // --- legacy file (backward-compatible, errors only) ---
        File dir = new File("logs");
        if (!dir.exists()) dir.mkdirs();
        File legacy = new File(dir, name + "_concolic.csv");
        try (PrintWriter pw = new PrintWriter(new FileWriter(legacy))) {
            pw.println("time_ms,unique_errors");
            pw.println("0,0");
            for (long[] row : errorTimeline) {
                pw.println(row[0] + "," + row[1]);
            }
            pw.println((System.currentTimeMillis() - startTime) + "," + triggeredErrors.size());
        } catch (IOException e) {
            e.printStackTrace();
        }

        // --- task1 file (errors + branches, seed-based name) ---
        // Output dir defaults to logs/task1; override with -DoutDir=... (used by
        // the Task 3 ablation to separate base/improved runs at the same seed).
        File dir1 = new File(System.getProperty("outDir", "logs/task1"));
        dir1.mkdirs();
        File f = new File(dir1, name + "_concolic_seed" + seed + ".csv");

        // Merge error and branch timelines into one sorted sequence
        List<long[]> events = new ArrayList<>();
        for (long[] e : errorTimeline)  events.add(new long[]{e[0],  e[1], -1});
        for (long[] b : branchTimeline) events.add(new long[]{b[0], -1, b[1]});
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
            pw.println(finalTime + "," + triggeredErrors.size() + "," + visitedBranches.size());
        } catch (IOException e) {
            e.printStackTrace();
        }

        System.out.println("Wrote " + f.getPath()
                + " (errors=" + triggeredErrors.size()
                + " branches=" + visitedBranches.size() + ")");
    }

    public static void output(String out){
        int idx = out.indexOf("error_");
        if (idx < 0) return;
        int end = idx;
        while (end < out.length() && (Character.isLetterOrDigit(out.charAt(end)) || out.charAt(end) == '_')) end++;
        String err = out.substring(idx, end);
        if (triggeredErrors.add(err)) {
            long t = System.currentTimeMillis() - startTime;
            errorTimeline.add(new long[]{t, triggeredErrors.size()});
            System.out.printf("[%.1fs] %s (total %d)%n", t/1000.0, err, triggeredErrors.size());
        }
    }

}