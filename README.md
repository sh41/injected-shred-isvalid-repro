# DocumentWindowImpl.isValid costs one host validity check per shred

Minimal reproduction for an IntelliJ Platform performance issue. Built from the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template), targeting IntelliJ IDEA 2026.1.5.

`DocumentWindowImpl.isValid()` walks every shred of an injected document, and for each one dereferences the host's smart pointer, which calls the host's `isValid()`, and the shred's host range pointer. Nothing is cached across shreds or across calls. The line-marker pass validates the injected file once per injected PSI element. So an injector that registers one place per line makes one highlighting pass cost about lines × elements, and the pass grows quadratically with the line count. The same text injected as one place grows linearly.

## Run

```shell
./gradlew test
```

`./gradlew check` is red on purpose: `testPerLineHighlightingScalesLinearly` fails, and that failure is the bug. Gradle caching is on, so pass `--rerun` to measure again. Each test writes its table to `build/repro/`, and `testMainSweep` writes a JFR recording there too.

| Test | Result | What it shows |
|---|---|---|
| [`testSinglePlaceHighlightingScalesLinearly`][test-control] | passes | the control: with one place, doubling the lines from 1,000 to 2,000 at most doubles the pass time, within a margin of 1.5 |
| [`testPerLineHighlightingScalesLinearly`][test-per-line] | **fails** | with one place per line, the same doubling should grow the pass time no faster than the control does in the same run (× 1.5), but it grows 3.7 times |
| [`testMainSweep`][test-main] | passes | reports the main table and records the JFR |
| [`testDepthSweep`][test-depth] | passes | reports the depth table |
| [`testKeystrokeInHost`][test-keystroke] | passes | reports the pass after typing one character into the host |

The failure from the run below: `doubling the lines of a per-line injection should grow the pass time no faster than doubling a single-place injection does (ratio 1.82), give or take a margin of 1.5, but it went from 716.857098 ms to 2657.316271 ms, a ratio of 3.71`.

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
| per-line | 250 | 250 | 8.2 [8.1, 33.4] | 89 [89, 91] | 250 | 8,748 |
| per-line | 500 | 500 | 16.7 [16.6, 41.0] | 207 [198, 218] | 500 | 17,498 |
| per-line | 1,000 | 1,000 | 33.3 [32.7, 88.6] | 703 [684, 768] | 1,000 | 34,998 |
| per-line | 2,000 | 2,000 | 67.8 [67.2, 141.0] | 2,746 [2,694, 2,858] | 2,000 | 69,998 |
| single-place | 250 | 1 | 0.10 [0.09, 0.26] | 81 [79, 89] | 1 | 8,748 |
| single-place | 500 | 1 | 0.10 [0.09, 0.24] | 137 [120, 150] | 1 | 17,498 |
| single-place | 1,000 | 1 | 0.10 [0.09, 2.71] | 235 [229, 270] | 1 | 34,998 |
| single-place | 2,000 | 1 | 0.10 [0.09, 0.30] | 490 [473, 681] | 1 | 69,998 |

The per-line N = 2,000 passes ran with the JFR recording below. The same cell in `testPerLineHighlightingScalesLinearly`, in the same run and without a recording, took 2,657 [2,628, 2,697] ms.

One `isValid()` call grows linearly with the places, about 34 ns per shred, and stays flat for one place. Per doubling, the per-line pass grows × 2.3, × 3.4 and × 3.9, and the single-place pass × 1.7, × 1.7 and × 2.1. Besides the injected-background infos, one per place, both shapes produce the same number of infos: 35 per line, less two. So the per-line pass does no more highlighting work by that count. What it pays more for is validation.

### Depth sweep: N = 1,000, the host's tag inside D enclosing tags

D counts `<root>` and the `<d>` tags around the host's tag.

| shape | D | `isValid()` µs | pass ms |
|---|---|---|---|
| per-line | 1 | 26.0 [25.5, 67.4] | 673 [656, 688] |
| per-line | 8 | 28.5 [27.8, 70.1] | 740 [724, 820] |
| per-line | 32 | 42.8 [42.2, 109.5] | 1,081 [1,024, 1,101] |
| single-place | 1 | 0.09 [0.08, 2.17] | 217 [207, 249] |
| single-place | 8 | 0.10 [0.08, 2.38] | 245 [230, 255] |
| single-place | 32 | 0.16 [0.15, 1.45] | 212 [209, 234] |

Each shred's check walks the host to its file root, so the per-line cost also grows with the host's depth. An `XmlText` host's walk is cheap per step. A host whose `isValid()` is [`PsiElementBase`'s][psiElementBaseIsValid] calls `getParent()` and a virtual `isValid()` at each step, which this repro does not measure.

### Typing in the host: N = 2,000

The pass after inserting one character into the middle line and committing, with no restart: the edit makes the host dirty.

| shape | pass ms | same injected file after the edits | a restarted pass, same run |
|---|---|---|---|
| per-line | 2,848 [2,800, 2,892] | no | 2,657 [2,628, 2,697] |
| single-place | 506 [477, 534] | yes | 492 [459, 563] |

The restarted passes are the N = 2,000 cells of `testPerLineHighlightingScalesLinearly`, which runs with no recording, as the keystroke cell does.

A keystroke in the host costs about as much as one full pass.

### Where the time goes

A JFR recording (`settings=profile`, 512 frames per stack) around the five timed per-line passes at N = 2,000 holds 1,739 execution samples. A share is the samples with the frame anywhere on the stack, over all of them. Reading the recording at 64 frames gives the same counts.

| frame | share |
|---|---|
| `DocumentWindowImpl.isValid` | 73.2% |
| `ShredImpl.isValid` within it | 72.7% |
| `ShredImpl.getHost` → `SmartPsiElementPointerImpl.getElement` → the host's `isValid` | 48.3% |
| `ShredImpl.getHostRangeMarker` → `SmartPsiElementPointerImpl.getRange` | 24.3% |
| `InjectedLanguageManagerImpl.intersectWithAllEditableFragments` | 6.2% |
| `DocumentWindowImpl.injectedToHost` | 3.9% |
| `DocumentWindowImpl.getHostRange` | 1.2% |
| `DocumentWindowImpl.getTextLength`, `getLineNumber`, `hostToInjected` | 0% |
| any of the frames above | 83.3% |

69.6% of all samples reach `DocumentWindowImpl.isValid` from the line-marker pass: `LineMarkersPass.queryLineMarkersForInjected` → `LineMarkersPass.queryProviders` → `RunLineMarkerProvider.getLineMarkerInfo` → `LeafPsiElement.getContainingFile` → `PsiFileImpl.isValid` → `VirtualFileWindowImpl.isValid`. Another 3.6% come from `HighlightInfoUpdaterImpl.recycleInvalidPsiElements` through `PsiElementBase.isValid`. `InjectedGeneralHighlightingPass.highlightInjectedBackground` calls `getHost()` once per place, which is too cheap to show: no sample has both.

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
5. The calls come once per injected element. [`LineMarkersPass.queryLineMarkersForInjected`][queryLineMarkersForInjected] queries every line-marker provider for every element of the injected file. [`RunLineMarkerProvider.getLineMarkerInfo`][runLineMarker] begins with `isInjectedFragment(element.getContainingFile())`, only to return `null` for injected elements. [`LeafPsiElement.getContainingFile`][getContainingFile] checks `file.isValid()`, and an injected [`PsiFileImpl.isValid`][psiFileIsValid] asks its [`VirtualFileWindowImpl.isValid`][vfwIsValid], which is `myDelegate.isValid() && myDocumentWindow.isValid()`.

So one pass costs about elements × shreds × host depth, and with one place per line both factors grow with the line count.

## Fix

The O(places) part of `isValid()` is the shred loop and the shred-0 checks. The last two lines, the injected file's lookup and `findCachedViewProvider`, are O(1): together they hold 5 of the 1,739 samples above, 0.3%.

**Memoise the shred checks for the current read action.** `isValid()` already runs its body under `ReadActionCache.allowInWriteAction`. Store a `true` result of the shred checks in `ReadActionCache.getInstance().getProcessingContext()`, keyed by the window and its `Place`, and always run the two O(1) lines. On a reader thread that context lasts until the read action ends, or until a read action nested inside it ends, since each one clears it. Later calls in that span are O(1). Under the write-intent lock the context lasts only as long as the outermost `allowInWriteAction`, which is usually the call itself, so the memo does nothing there. Nothing that needs the write lock can change a host or a range marker while a read action runs. The line-marker pass runs on a reader thread inside one read action, `ApplicationImpl.tryRunReadAction`, which is on all 1,236 `LineMarkersPass` samples. None of those samples has a read action nested inside the pass. So for this pass the memo would remove most of the 73.2% that `DocumentWindowImpl.isValid` holds, leaving one full check per window per read action. A `false` result should not be memoised. `setShreds` replaces the `Place`, so the key changes with it.

A memo across read actions keyed by the host document's modification stamp and the PSI modification count is not sound. [`InjectedLanguageUtilBase.clearCaches`][clearCaches] drops the injected file's view provider with no PSI event, so neither changes: it runs when an injected editor window is disposed, and `InjectedLanguageManagerImpl.clearInjectorCache` disposes the open injected editor windows when an injector extension changes or a plugin unloads. The read-action memo is safe from this, because the view-provider lookup stays outside it.

**A lesser change: check each distinct host once per call.** Every shred of a one-host injection asks the same host's `isValid()`. Checking each distinct host once per call removes that part, 48.3% of the samples, but keeps the loop over every shred's range marker, 24.3%, so each call stays O(places).

**The caller.** `RunLineMarkerProvider.getLineMarkerInfo` calls `element.getContainingFile()` only to skip injected elements, and that call validates the injected file. Asking whether the element's containing file is injected without validating it would remove this caller's 69.6% for any `isValid()` cost, but other callers would still pay.

The other per-place frames are separate costs, each O(places) per call, and none of these fixes touches them: `intersectWithAllEditableFragments` (6.2%), `injectedToHost` (3.9%) and `getHostRange` (1.2%).

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

[injector]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L44-L73
[test-main]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L198-L207
[test-depth]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L209-L212
[test-keystroke]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L241-L254
[test-control]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L256-L268
[test-per-line]: https://github.com/sh41/injected-shred-isvalid-repro/blob/main/src/test/kotlin/com/github/sh41/injectedshredisvalidrepro/InjectedShredIsValidTest.kt#L270-L291
