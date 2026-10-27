# DocumentWindowImpl.isValid costs one host validity check per shred

Reported as [IJPL-259786](https://youtrack.jetbrains.com/issue/IJPL-259786).

Minimal reproduction for an IntelliJ Platform performance issue, on IntelliJ IDEA 2026.1.5 (`IU-261.27258.48`). Built from the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).

## Summary

**Symptom.** A `MultiHostInjector` that registers one place per line makes highlighting the host file quadratic in its line count. The same text injected as one place is linear. In the Elixir plugin ([intellij-elixir#2813](https://github.com/intellij-elixir/intellij-elixir/issues/2813)), a file with a 1,028-line documentation heredoc took 10,824 ms to highlight, against 386 ms with the heredoc bodies emptied.

**In this repro** (RegExp injected into one XML tag, N lines; [full tables](#results)):

| | one place per line | one place |
|---|---|---|
| `DocumentWindow.isValid()`, N = 1,000 / 2,000 | 35.3 / 71.7 µs | 0.09 / 0.09 µs |
| highlighting pass, N = 1,000 / 2,000 | 654 / 2,563 ms | 240 / 451 ms |
| pass after one keystroke, N = 2,000 | 2,619 ms | 478 ms |

**Cause** ([detail](#cause)). `DocumentWindowImpl.isValid()` loops over every shred. For each one it dereferences the host's smart pointer, which calls the host's `isValid()` and walks the host to its file root, and it reads the shred's range pointer. Every shred of a one-host injection holds the same host pointer, and nothing is cached within a call or across calls. So one call is O(places × host depth).

**Why a pass goes quadratic** ([detail](#where-the-time-goes)). Anything that asks an injected leaf for its file validates the injected file, through `LeafPsiElement.getContainingFile` → `PsiFileImpl.isValid` → `VirtualFileWindowImpl.isValid` → `DocumentWindowImpl.isValid`. A caller that does this once per injected element makes the pass cost elements × places, and with one place per line both grow with the line count. This repro shows two such callers, each on about 68% of the per-line pass's samples:

- the platform's own `LineMarkersPass` → `RunLineMarkerProvider.getLineMarkerInfo`, which is registered for every language and asks every injected element for its file only to skip it;
- an [annotator](#a-second-caller-an-annotator) that asks each injected leaf whether its file is injected, as the Elixir plugin's `UnicodeSecurity` annotator does.

**Suggested fix** ([detail](#fix)). Memoise a `true` result of the shred checks for the current read action, in `ReadActionCache`'s processing context, keyed by the window and its `Place`. Keep the O(1) view-provider lookup outside the memo. A memo keyed by modification stamps is not sound, because `InjectedLanguageUtilBase.clearCaches` drops view providers with no PSI event. Making `RunLineMarkerProvider` skip injected elements without validating them would remove only that caller's share.

## Run

```shell
./gradlew test
```

Gradle needs JDK 21. [`mise.toml`](mise.toml) pins JBR 21.0.11, which the figures here ran Gradle on. The tests themselves run on the IDE's bundled runtime, JBR 25.0.4.

`./gradlew test` fails on purpose. `testPerLineHighlightingScalesLinearly` and `testPerLineAnnotatorScalesLinearly` fail, and those failures are the bug. Gradle caching is on, so pass `--rerun` to measure again. Each test writes its table to `build/repro/`, and `testMainSweep` and `testPerLineAnnotatorScalesLinearly` write JFR recordings there too.

| Test | Result | What it shows |
|---|---|---|
| [`testSinglePlaceHighlightingScalesLinearly`][test-control] | passes | the control: with one place, doubling the lines from 1,000 to 2,000 at most doubles the pass time, within a margin of 1.5 |
| [`testPerLineHighlightingScalesLinearly`][test-per-line] | **fails** | with one place per line, the same doubling should grow the pass time no faster than the control does in the same run (× 1.5), but it grows × 3.8 against × 1.9 |
| [`testPerLineAnnotatorScalesLinearly`][test-annotator] | **fails** | with `RunLineMarkerProvider` masked and a leaf annotator added, the annotator's own time should grow no faster with one place per line than with one place when the lines double (× 1.5), but it grows × 4.0 against × 1.4 |
| [`testMainSweep`][test-main] | passes | reports the main table and records the JFR |
| [`testDepthSweep`][test-depth] | passes | reports the depth table |
| [`testKeystrokeInHost`][test-keystroke] | passes | reports the pass after typing one character into the host |

The failures from the run below:

- `doubling the lines of a per-line injection should grow the pass time no faster than doubling a single-place injection does (ratio 1.86), give or take a margin of 1.5, but it went from 659.50481 ms to 2494.969348 ms, a ratio of 3.78`
- `doubling the lines of a per-line injection should grow the time an annotator spends asking each injected leaf for its file no faster than doubling a single-place injection does (ratio 1.39), give or take a margin of 1.5, but it went from 594.2 ms to 2366.5 ms a pass, a ratio of 3.98`

The margin comes from six runs of the control on its own, whose ratios ranged from 1.27 to 1.77. The largest over the smallest is 1.39, which rounds up to 1.5. Every later run of the control stayed inside its bound of 3.0, the highest being 2.02.

## The fixture

A test-registered [`MultiHostInjector`][injector] injects RegExp into the text of one `<host>` tag in an XML file. The text is N copies of the line `ab+(cd|ef)*[0-9]x?`, so every line holds several RegExp elements, and nothing in it needs an entity or CDATA. The injector skips hosts inside an injected file. It injects in one of two shapes:

- **per-line:** one `startInjecting`, one `addPlace` per line (each place ending after its line's newline), then one `doneInjecting`;
- **single-place:** one `addPlace` over the whole text.

Both shapes inject the same text. Every cell asserts one injected file, N shreds for per-line and 1 for single-place, and the host's text as the injected text.

Every cell follows one protocol. The file is configured once and highlighted twice to warm up. Then come five timed passes over that same file, each after `DaemonCodeAnalyzer.restart(file, …)`: without the restart, the daemon finds the file up to date and a pass does almost nothing. After the passes, 1,000 warm-up and then 1,001 timed calls to `DocumentWindow.isValid()` on the injected document. The test thread holds read access throughout. Each figure is the median of the timed repetitions, with the min and max in brackets. The injected file is the same object before and after the passes, so they re-highlight one injection; they do not re-inject.

## Results

Measured with `./gradlew test --rerun` on IntelliJ IDEA 2026.1.5 (`IU-261.27258.48`), OpenJDK 64-Bit Server VM 25.0.4+1-b329.128, on an AMD Ryzen 9 9950X3D (24 threads, 47 GB) under WSL2 (Linux 6.18.54.1). No other build was running on the machine when the run started or when it ended.

### Main sweep: the host's tag directly under the root

| shape | N | shreds | `isValid()` µs | pass ms | background infos | other infos |
|---|---|---|---|---|---|---|
| per-line | 250 | 250 | 8.8 [8.6, 42.0] | 92 [89, 102] | 250 | 8,748 |
| per-line | 500 | 500 | 17.7 [17.5, 5,758.0] | 188 [180, 201] | 500 | 17,498 |
| per-line | 1,000 | 1,000 | 35.3 [35.0, 244.3] | 654 [647, 656] | 1,000 | 34,998 |
| per-line | 2,000 | 2,000 | 71.7 [70.8, 201.5] | 2,563 [2,530, 2,646] | 2,000 | 69,998 |
| single-place | 250 | 1 | 0.09 [0.08, 0.50] | 76 [76, 85] | 1 | 8,748 |
| single-place | 500 | 1 | 0.10 [0.09, 0.21] | 133 [123, 136] | 1 | 17,498 |
| single-place | 1,000 | 1 | 0.09 [0.09, 1.42] | 240 [229, 243] | 1 | 34,998 |
| single-place | 2,000 | 1 | 0.09 [0.09, 0.18] | 451 [432, 485] | 1 | 69,998 |

The per-line N = 2,000 passes ran with the JFR recording below. The same cell in `testPerLineHighlightingScalesLinearly`, in the same run and without a recording, took 2,495 [2,422, 2,503] ms.

One `isValid()` call grows linearly with the places, about 35 ns per shred, and stays flat for one place. Per doubling, the per-line pass grows × 2.1, × 3.5 and × 3.9, and the single-place pass × 1.7, × 1.8 and × 1.9. Besides the injected-background infos, one per place, both shapes produce the same number of infos: 35 per line, less two. So the per-line pass does no more highlighting work by that count. What it pays more for is validation.

### Depth sweep: N = 1,000, the host's tag inside D enclosing tags

D counts `<root>` and the `<d>` tags around the host's tag.

| shape | D | `isValid()` µs | pass ms |
|---|---|---|---|
| per-line | 1 | 39.0 [38.0, 130.5] | 660 [650, 675] |
| per-line | 8 | 42.2 [41.2, 311.7] | 735 [717, 742] |
| per-line | 32 | 53.5 [53.0, 98.9] | 996 [991, 1,053] |
| single-place | 1 | 0.09 [0.09, 0.16] | 234 [220, 255] |
| single-place | 8 | 0.10 [0.09, 7.86] | 233 [227, 240] |
| single-place | 32 | 0.19 [0.16, 13.09] | 235 [227, 239] |

Each shred's check walks the host to its file root, so the per-line cost also grows with the host's depth. An `XmlText` host's walk is cheap per step. A host whose `isValid()` is [`PsiElementBase`'s][psiElementBaseIsValid] calls `getParent()` and a virtual `isValid()` at each step, which this repro does not measure.

### Typing in the host: N = 2,000

The pass after inserting one character into the middle line and committing, with no restart: the edit makes the host dirty.

| shape | pass ms | same injected file after the edits | a restarted pass, same run |
|---|---|---|---|
| per-line | 2,619 [2,608, 3,154] | no | 2,495 [2,422, 2,503] |
| single-place | 478 [470, 535] | yes | 447 [442, 475] |

The restarted passes are the N = 2,000 cells of `testPerLineHighlightingScalesLinearly`, which runs with no recording, as the keystroke cell does. After the edits the per-line injection is a new injected file, while the single-place one is still the same object, so the per-line figure may include re-injecting the host as well as validating it. This repro does not separate the two.

A keystroke in the host costs about as much as one full pass.

### Where the time goes

A JFR recording (`settings=profile`, 512 frames per stack) around the five timed per-line passes at N = 2,000 holds 1,601 execution samples. A share is the samples with the frame anywhere on the stack, over all of them. Reading the recording at 64 frames gives the same share for every frame below; it cuts 3 stacks short, losing only their outermost frames.

| frame | share |
|---|---|
| `DocumentWindowImpl.isValid` | 72.3% |
| `ShredImpl.isValid` within it | 71.1% |
| `ShredImpl.getHost` → `SmartPsiElementPointerImpl.getElement` | 34.6% |
| of which the host's `isValid` | 30.9% |
| `ShredImpl.getHostRangeMarker` → `SmartPsiElementPointerImpl.getRange` | 36.5% |
| `InjectedLanguageManagerImpl.intersectWithAllEditableFragments` | 6.8% |
| `DocumentWindowImpl.injectedToHost` | 4.4% |
| `DocumentWindowImpl.getHostRange` | 1.1% |
| `DocumentWindowImpl.getTextLength`, `getLineNumber`, `hostToInjected` | 0% |
| any of the frames above | 83.5% |

`DocumentWindowImpl.isValid`'s share is steady across recordings, but how it splits between its two halves is not. Over four recordings of per-line passes at N = 2,000 (three of this cell, from this run and earlier ones, and [the annotator test's](#a-second-caller-an-annotator)), `isValid` held 71.7% to 73.2%, `getHost` 34.6% to 48.4%, and `getHostRangeMarker` 24.3% to 36.5%.

68.5% of all samples reach `DocumentWindowImpl.isValid` from the line-marker pass: `LineMarkersPass.queryLineMarkersForInjected` → `LineMarkersPass.queryProviders` → `RunLineMarkerProvider.getLineMarkerInfo` → `LeafPsiElement.getContainingFile` → `PsiFileImpl.isValid` → `VirtualFileWindowImpl.isValid`. Another 3.7% come from `HighlightInfoUpdaterImpl.recycleInvalidPsiElements` through `PsiElementBase.isValid`. `InjectedGeneralHighlightingPass.highlightInjectedBackground` calls `getHost()` once per place, which is too cheap to show: no sample has both.

### A second caller: an annotator

`RunLineMarkerProvider` is not the only caller that validates once per injected element. [`testPerLineAnnotatorScalesLinearly`][test-annotator] masks it and registers an annotator for RegExp that, for every leaf, asks for the leaf's file and whether that file is injected:

```kotlin
if (element.firstChild != null) return
val file = element.containingFile
if (file != null) InjectedLanguageManager.getInstance(element.project).isInjectedFragment(file)
```

The Elixir plugin's `UnicodeSecurity` annotator does exactly this on every leaf, through `Injection.of(element)`, to skip code it does not compile. It is correct code.

The annotator times those two calls itself and counts them, summed over the threads it runs on. (The counters are static: `AnnotatorRunner.cloneTemplate` runs a new instance of each annotator, not the registered one.) The test asserts on that time rather than on the pass time, so the rest of the pass and the load on the machine don't dilute it. Both shapes make exactly the same calls, which the test asserts, so if `isValid()` were O(1), the annotator's time would grow with the lines for one place per line just as it does for one place.

From the same run (the annotator's time is summed over its threads, per timed pass):

| shape | N | annotator calls per pass | annotator ms per pass | pass ms |
|---|---|---|---|---|
| single-place | 1,000 | 18,999 | 4.4 | 243 [232, 254] |
| single-place | 2,000 | 37,999 | 6.1 | 461 [447, 480] |
| per-line | 1,000 | 18,999 | 594 | 663 [649, 675] |
| per-line | 2,000 | 37,999 | 2,366 | 2,556 [2,535, 2,604] |

With one place per line, the same 37,999 calls take about 390 times as long as with one place, and their time grows × 4.0 per doubling against × 1.4. Four earlier runs of this test alone, with other test runs on the machine, gave × 3.9 to × 4.7 against × 1.2 to × 1.9.

A JFR recording of the per-line N = 2,000 passes holds 1,551 samples. `DocumentWindowImpl.isValid` is on 71.7% of them, and 68.2% reach it through the annotator: `InjectedLeafAnnotator.annotate` → `LeafPsiElement.getContainingFile` → `PsiFileImpl.isValid` → `VirtualFileWindowImpl.isValid`. With `RunLineMarkerProvider` masked, `LineMarkersPass` is on 0.3%.

## Cause

All links are to IntelliJ IDEA 2026.1.5. Every method linked here has the same code on `master` at [`ea3edc7`][master] (2026-10-27), apart from renames and these:

- `SmartPsiElementPointerImpl.getElement` adds an `isPossiblyChanged()` check;
- `PsiFileImpl.isValid` adds an early `return true` under `InternalPsiVersioning.isInsideVersioningButNotLocks()`, before the line the line-marker caller reaches. Whether highlighting runs inside it was not checked, and this repro was not run on master;
- `RunLineMarkerProvider.getLineMarkerInfo` adds an untrusted-file check after its contributor loop, and keeps the `getContainingFile()` call at its top;
- `SharedImplUtil.findFileElement` replaces its loop with `TreeUtil.findTopmostParent`.

1. [`DocumentWindowImpl.isValid`][isValid] loops over shreds 1 to n−1 calling [`ShredImpl.isValid`][shredIsValid], then checks shred 0's host and range by hand and looks up the injected file's cached view provider. Nothing is memoised.
2. `ShredImpl.isValid` is `getHostRangeMarker() != null && getHost() != null`. [`getHost`][getHost] is the host pointer's [`getElement`][getElement], which calls [`isValid()` on the cached host][getElementIsValid] on every dereference. [`getHostRangeMarker`][getHostRangeMarker] asks the shred's file-range pointer for [its range][getRange].
3. Every shred of a one-host injection holds the same host pointer. [`InjectionRegistrarImpl.createShred`][createShred] creates one per shred with `forInjected = true`, and [`SmartPointerManagerImpl`][cachedPointer] returns the pointer it already holds for that element. So one `isValid()` call asks the same host `isValid()` once per shred.
4. An `XmlText` host's `isValid()` is [`CompositePsiElement.isValid`][compositeIsValid] → [`SharedImplUtil.isValid`][sharedIsValid], which finds the containing file by walking the tree to its root ([`findFileElement`][findFileElement]) and asks whether that file is valid. That is the depth factor above.
5. The calls come once per injected element. [`LineMarkersPass.queryLineMarkersForInjected`][queryLineMarkersForInjected] queries every line-marker provider for every element of the injected file. [`RunLineMarkerProvider.getLineMarkerInfo`][runLineMarker] begins with `isInjectedFragment(element.getContainingFile())`, only to return `null` for injected elements. [`LeafPsiElement.getContainingFile`][getContainingFile] checks `file.isValid()`, and an injected [`PsiFileImpl.isValid`][psiFileIsValid] asks its [`VirtualFileWindowImpl.isValid`][vfwIsValid], which is `myDelegate.isValid() && myDocumentWindow.isValid()`. Any annotator or inspection that asks an injected leaf for its file takes the same path, as [the annotator above](#a-second-caller-an-annotator) does.

So one pass costs about elements × shreds × host depth, and with one place per line both factors grow with the line count.

## Fix

The O(places) part of `isValid()` is the shred loop and the shred-0 checks. The last two lines, the injected file's lookup and `findCachedViewProvider`, are O(1): together they hold 2 of the 1,601 samples above, 0.1%.

**Memoise the shred checks for the current read action.** `isValid()` already runs its body under `ReadActionCache.allowInWriteAction`. Store a `true` result of the shred checks in `ReadActionCache.getInstance().getProcessingContext()`, keyed by the window and its `Place`, and always run the two O(1) lines. On a reader thread that context lasts until the read action ends, or until a read action nested inside it ends, since each one clears it. Later calls in that span are O(1). Under the write-intent lock the context lasts only as long as the outermost `allowInWriteAction`, which is usually the call itself, so the memo does nothing there. Nothing that needs the write lock can change a host or a range marker while a read action runs. The line-marker pass runs on a reader thread inside one read action, `ApplicationImpl.tryRunReadAction`, which is on all 1,126 `LineMarkersPass` samples. None of those samples has a read action nested inside the pass. So for this pass the memo would remove most of the 72.3% that `DocumentWindowImpl.isValid` holds, leaving one full check per window per read action. A `false` result should not be memoised. `setShreds` replaces the `Place`, so the key changes with it.

A memo across read actions keyed by the host document's modification stamp and the PSI modification count is not sound. [`InjectedLanguageUtilBase.clearCaches`][clearCaches] drops the injected file's view provider with no PSI event, so neither changes: it runs when an injected editor window is disposed, and `InjectedLanguageManagerImpl.clearInjectorCache` disposes the open injected editor windows when an injector extension changes or a plugin unloads. The read-action memo is safe from this, because the view-provider lookup stays outside it.

**A lesser change: check each distinct host once per call.** Every shred of a one-host injection asks the same host's `isValid()`. Checking each distinct host once per call removes that part, 34.6% of the samples in the recording above (up to 48.4% in others), but keeps the loop over every shred's range marker, 36.5% (down to 24.3%), so each call stays O(places).

**The caller.** `RunLineMarkerProvider.getLineMarkerInfo` calls `element.getContainingFile()` only to skip injected elements, and that call validates the injected file. Asking whether the element's containing file is injected without validating it would remove this caller's 68.5% for any `isValid()` cost, but other callers would still pay. [The annotator above](#a-second-caller-an-annotator) is one, and only a change to `isValid()` itself helps there. Whether the read-action memo holds across the annotator pass, whose annotators run in parallel jobs, was not checked.

The other per-place frames are separate costs, each O(places) per call, and none of these fixes touches them: `intersectWithAllEditableFragments` (6.8%), `injectedToHost` (4.4%) and `getHostRange` (1.1%).

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)

[isValid]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/analysis-impl/src/com/intellij/psi/impl/source/tree/injected/DocumentWindowImpl.java#L866-L886
[shredIsValid]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/analysis-impl/src/com/intellij/psi/impl/source/tree/injected/ShredImpl.java#L113-L115
[getHost]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/analysis-impl/src/com/intellij/psi/impl/source/tree/injected/ShredImpl.java#L147-L149
[getHostRangeMarker]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/analysis-impl/src/com/intellij/psi/impl/source/tree/injected/ShredImpl.java#L76-L78
[getElement]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/SmartPsiElementPointerImpl.java#L90-L99
[getElementIsValid]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/SmartPsiElementPointerImpl.java#L94
[getRange]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/SmartPsiElementPointerImpl.java#L145-L147
[createShred]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/analysis-impl/src/com/intellij/psi/impl/source/tree/injected/InjectionRegistrarImpl.java#L258-L259
[cachedPointer]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/smartPointers/SmartPointerManagerImpl.java#L91-L96
[compositeIsValid]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/source/tree/CompositePsiElement.java#L119-L121
[sharedIsValid]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/source/tree/SharedImplUtil.java#L63-L67
[psiElementBaseIsValid]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/PsiElementBase.java#L255-L262
[findFileElement]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/source/tree/SharedImplUtil.java#L74-L89
[queryLineMarkersForInjected]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/lang-impl/src/com/intellij/codeInsight/daemon/impl/LineMarkersPass.java#L263-L297
[runLineMarker]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/execution-impl/src/com/intellij/execution/lineMarker/RunLineMarkerProvider.java#L62-L64
[getContainingFile]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/source/tree/LeafPsiElement.java#L76-L80
[psiFileIsValid]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/core-impl/src/com/intellij/psi/impl/source/PsiFileImpl.java#L203-L238
[vfwIsValid]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/analysis-impl/src/com/intellij/psi/impl/source/tree/injected/VirtualFileWindowImpl.java#L50-L52
[clearCaches]: https://github.com/JetBrains/intellij-community/blob/b1b4aca3b97cb0a770e3e8fc055dccdd6b1d6f92/platform/analysis-impl/src/com/intellij/psi/impl/source/tree/injected/InjectedLanguageUtilBase.java#L460-L487
[master]: https://github.com/JetBrains/intellij-community/commit/ea3edc7e0e4dacb437ebcae7de972e75beb64525

[injector]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L51-L80
[test-main]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L205-L214
[test-depth]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L216-L219
[test-keystroke]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L248-L261
[test-control]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L263-L275
[test-per-line]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L277-L298
[test-annotator]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L300-L387
