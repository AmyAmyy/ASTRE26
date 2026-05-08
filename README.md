# ASTRE26 - Lab 1: Fuzzing
Amy van der Meijden (5301513) and Jimmy Oei (6540031)

# Using Dev Containers To Run The Tool
We have included a `devcontainer.json` file in this repository. This file can be used to run the tool in a Docker container. This is useful if you do not want to install all the dependencies on your machine. To use this file, you need to have the following installed on your machine:
- Docker
- Visual Studio Code
- Remote Extension Pack for Visual Studio Code

Once you have installed these dependencies, you can open this repository in VS Code and start a Dev Container. This will automatically install all the dependencies and build the project. You can then use the pre-configured tasks to instrument a Java file and run it. 


# Manually build and run the tool 
To build the project, make sure you have navigated to the root of this project and run the following Maven command:

`mvn clean package`

To instrument a given Java file, use the following command:

`java -cp target/aistr.jar nl.tudelft.instrumentation.Main --type=*TypeOfInstrumentation* --file=*PathToJavaFile* > *OutputPath*`

Where `*PathToJavaFile*` is the path to the Java file to instrument, `*OutputPath*` is the file (file name and path) where you want to save the instrumented Java file. The `*TypeOfInstrumentation*` is the type of instrumentation that you want to do. You can choose between the following options: `line`, `branch`, `fuzzing`, `concolic`, `patching`, and `learning`.
Note that the flags `--file` and `--type` are required for instrumenting a Java file.

# Examples illustrating how to compile and run the instrumented files
In this section, we present you an example for each lab on how to instrument RERS problem and how to run the instrumented Java file. For the sake of simplicity, we will use the directory structure of this repository to how a RERS problem is instrumented. These examples do assume that the project has already been built using Maven.

## Lab 1 - Fuzzing
Say we want to instrument `Problem1.java` of the RERS 2020 problem. We move the `Problem1.java` to the root directory to get the following structure:
```
JavaInstrumentation
  |- docs
  |- lib
  |- src
  |- .gitignore
  |- libz3java.dylib
  |- pom.xml
  |- Problem1.java
  |- README.md
```

We then create a new directory in which we want to store the instrumented Java file, let's call it `instrumented`. Let's now instrument the file by running the following command at the **root** directory: 

`java -cp target/aistr.jar nl.tudelft.instrumentation.Main --type=fuzzing --file=Problem1.java > instrumented/Problem1.java`

We should now have the following structure:
```
JavaInstrumentation
  |- docs
  |- instrumented
    |- Problem1.java
  |- lib
  |- src
  |- .gitignore
  |- libz3java.dylib
  |- pom.xml
  |- Problem1.java
  |- README.md
```

Let's now compile the instrumented file using the following command:

`javac -cp target/aistr.jar:. instrumented/Problem1.java`

Let's now run the instrumented Java file using the following command:

`java -cp target/aistr.jar:./instrumented:. Problem1 `

Because the file is in a folder, we need to add this to the classpath so that Java knows where to look for the class.

You should see the following output in the terminal:

```
Found a new branch
(((e) == (g)) && (true))
Found a new branch
(((f) == (g)) && (true))
Found a new branch
(((g) == (g)) && (true))
Found a new branch
(((8) == (4)) && (true))
Found a new branch
((true) && ((8) == (5)))
Found a new branch
(((8) == (6)) && (true))
Found a new branch
(((8) == (7)) && (true))
Found a new branch
(((8) == (8)) && (true))
.
.
.
Woohoo, looping!
```

### Switching between Random Fuzzer and Hill Climber

In `FuzzingLab.java`, toggle the `useHillClimber` flag:

```java
static final boolean useHillClimber = true;   // Hill Climber (Task 2)
static final boolean useHillClimber = false;  // Random Fuzzer (Task 1)
```

The fuzzer runs for 5 minutes (300 seconds) per problem and outputs:
- Unique branches visited
- Error codes triggered
- The best input trace (most branches visited)

### AFL

The AFL setup is in the `afl/` directory. Each problem has its own subdirectory:

```
afl/
├── run_afl.sh           # Script to compile, fuzz, and analyze one problem
├── extract_traces.py    # Extract input traces that triggered each error
├── 11/
│   ├── Problem11.c      # C source (modified for AFL)
│   ├── tests/           # Seed inputs (one file per valid input symbol)
│   └── findings/        # AFL output (created during fuzzing)
├── 12/ ...
├── 13/ ...
├── 14/ ...
├── 15/ ...
└── 17/ ...
```

### Running AFL on a problem

```bash
cd afl/
./run_afl.sh 11         # Compiles, fuzzes for 5 minutes, then analyzes results for problem 11
```

### Analyzing results

The `run_afl.sh` script automatically calls `scripts/analyze_afl.py` after fuzzing.
To re-analyze without re-fuzzing:

```bash
python3 scripts/analyze_afl.py afl/11/findings/default afl/11/Problem11
```

### Extracting traces for comparison with Hill Climber

```bash
python3 afl/extract_traces.py 11
```

This outputs the input trace (in both integer and letter format) that triggered each error,
allowing direct comparison with the Hill Climber traces from Task 2.
