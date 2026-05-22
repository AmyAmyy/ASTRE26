### Problem11_buggy
- status: `STOP_BY_PATCH_FOUND`  total_time: 15.981s  generations: 4  patches: 1

**Patch 0 hunk** (line 576, operator `IfExpresionMutOp`, suspiciousness 0,577):
```diff
--- \/src\/main\/java\/nl\/tudelft\/mutated_rers\/Problem11.java
+++ \/src\/main\/java\/nl\/tudelft\/mutated_rers\/Problem11.java
@@ -576 +576 @@
-		} 		if ((a1537379265 < 9) && (((cf && a2007907442.equals(\"g\")) && input.equals(\"C\")) && (a919251806 == 11))) {
+		} 		if ((!(a1537379265 < 9)) && (((cf && a2007907442.equals(\"g\")) && input.equals(\"C\")) && (a919251806 == 11))) {
```

### Problem12_buggy
- status: `STOP_BY_PATCH_FOUND`  total_time: 1.924s  generations: 1  patches: 1

**Patch 0 hunk** (line 1112, operator `IfExpresionMutOp`, suspiciousness 0,535):
```diff
--- \/src\/main\/java\/nl\/tudelft\/mutated_rers\/Problem12.java
+++ \/src\/main\/java\/nl\/tudelft\/mutated_rers\/Problem12.java
@@ -1112 +1112 @@
-		} 		if (((a7711982 < 5) && ((a1129073644 == 11) && a2034787802.equals(\"g\"))) && ((a1537202397 == 5) && (a1812297667.equals(\"g\") && (((15 < a1234479259) && (173 >= a1234479259)) && (cf && input.equals(\"J\")))))) {
+		} 		if (((a7711982 <= 5) && ((a1129073644 == 11) && a2034787802.equals(\"g\"))) && ((a1537202397 == 5) && (a1812297667.equals(\"g\") && (((15 < a1234479259) && (173 >= a1234479259)) && (cf && input.equals(\"J\")))))) {
```

### Problem13_buggy
- status: `STOP_BY_PATCH_FOUND`  total_time: 151.793s  generations: 34  patches: 1

**Patch 0 hunk** (line 705, operator `IfExpresionMutOp`, suspiciousness 0,447):
```diff
--- \/src\/main\/java\/nl\/tudelft\/mutated_rers\/Problem13.java
+++ \/src\/main\/java\/nl\/tudelft\/mutated_rers\/Problem13.java
@@ -705 +705 @@
-		} 		if (((84 < a505937283[5]) && ((a421304736.equals(\"e\") && a1525279227.equals(\"e\")) && (a1127751596 == a1314340093[0]))) && (((a1039246420 <= 189) && (((a439993392 == 5) && (input.equals(inputs[2]) && ((a1290874579 == 7) && cf))) && a839337571.equals(\"e\"))) && (a1534261430 <= (-184)))) {
+		} 		if (((84 <= a505937283[5]) && ((a421304736.equals(\"e\") && a1525279227.equals(\"e\")) && (a1127751596 == a1314340093[0]))) && (((a1039246420 <= 189) && (((a439993392 == 5) && (input.equals(inputs[2]) && ((a1290874579 == 7) && cf))) && a839337571.equals(\"e\"))) && (a1534261430 <= (-184)))) {
```

### Problem14_buggy
- status: `STOP_BY_PATCH_FOUND`  total_time: 57.636s  generations: 10  patches: 1

**Patch 0 hunk** (line 2282, operator `IfExpresionMutOp`, suspiciousness 0,2):
```diff
--- \/src\/main\/java\/nl\/tudelft\/mutated_rers\/Problem14.java
+++ \/src\/main\/java\/nl\/tudelft\/mutated_rers\/Problem14.java
@@ -2282 +2282 @@
-		if (cf && (a1410369705 < 1)) {
+		if (cf && (a1410369705 >= 1)) {
```

### Problem1_buggy
- status: `STOP_BY_PATCH_FOUND`  total_time: 23.607s  generations: 7  patches: 1

**Patch 0 hunk** (line 30, operator `IfExpresionMutOp`, suspiciousness 0,109):
```diff
--- \/src\/main\/java\/nl\/tudelft\/mutated_rers\/Problem1.java
+++ \/src\/main\/java\/nl\/tudelft\/mutated_rers\/Problem1.java
@@ -30 +30 @@
-		} 		if (((a691849188 != 4) && (input.equals(\"iB\") && cf)) && a547336540.equals(\"e\")) {
+		} 		if (((a691849188 != 4) || (input.equals(\"iB\") && cf)) && a547336540.equals(\"e\")) {
```

### Problem2_buggy
_No astor_output.json (run did not complete or no patch)._

### Problem3_buggy
_No astor_output.json (run did not complete or no patch)._

### Problem5_buggy
_No astor_output.json (run did not complete or no patch)._

### Problem7_buggy
- status: `TIME_OUT`  total_time: 301.014s  generations: 36  patches: 0
