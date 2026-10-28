package com.github.sh41.injectedshredisvalidrepro

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.LineMarkerProviders
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.execution.lineMarker.RunLineMarkerProvider
import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.javadoc.PsiSnippetDocTag
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import jdk.jfr.Configuration
import jdk.jfr.Recording
import org.intellij.lang.regexp.RegExpLanguage
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Times `DocumentWindow.isValid()` and one highlighting pass over a RegExp injection into one XML text node of N lines,
 * injected either with one place per line or with one place over the whole node.
 *
 * Protocol for every sweep cell: configure the file once, highlight it [WARM_UP_PASSES] times, then time [TIMED_PASSES] more
 * passes over the same file, and [WARM_UP_CALLS] then [TIMED_CALLS] single `isValid()` calls. Each figure is the median
 * of the timed repetitions, with their min and max. The injected file is checked to be the same object before and after,
 * so the timed passes re-highlight one injection rather than re-inject. [testKeystrokeInHost] times the pass after an
 * edit instead. [testJavadocSnippetSweep] times the same lines injected by the platform's own `JavadocInjector`.
 */
class InjectedShredIsValidTest : BasePlatformTestCase() {

    private enum class Shape(val label: String) { PER_LINE("per-line"), SINGLE_PLACE("single-place") }

    /** Injects RegExp into the text of every `<host>` tag, in the shape currently set. */
    private class ShapedInjector : MultiHostInjector {
        @Volatile
        var shape: Shape = Shape.PER_LINE

        override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
            val host = context as? XmlText ?: return
            if (InjectedLanguageManager.getInstance(host.project).isInjectedFragment(host.containingFile)) return
            if ((host.parent as? XmlTag)?.name != HOST_TAG) return
            val text = host.text
            if (text.isEmpty()) return

            registrar.startInjecting(RegExpLanguage.INSTANCE)
            when (shape) {
                Shape.SINGLE_PLACE -> registrar.addPlace(null, null, host as PsiLanguageInjectionHost, TextRange(0, text.length))
                Shape.PER_LINE -> {
                    // Each place ends after its line's newline, so the places concatenate to the whole text.
                    var start = 0
                    while (start < text.length) {
                        val newline = text.indexOf('\n', start)
                        val end = if (newline < 0) text.length else newline + 1
                        registrar.addPlace(null, null, host as PsiLanguageInjectionHost, TextRange(start, end))
                        start = end
                    }
                }
            }
            registrar.doneInjecting()
        }

        override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(XmlText::class.java)
    }

    private data class Stats(val median: Double, val min: Double, val max: Double) {
        override fun toString(): String = "%.3f [%.3f, %.3f]".format(median, min, max)

        companion object {
            fun of(samples: List<Double>): Stats {
                val sorted = samples.sorted()
                val mid = sorted.size / 2
                val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
                return Stats(median, sorted.first(), sorted.last())
            }
        }
    }

    private data class Cell(
        val label: String,
        val lines: Int,
        val depth: Int,
        val shreds: Int,
        val isValidMicros: Stats,
        val passMillis: Stats,
        val backgroundInfos: Int,
        val otherInfos: Int,
    ) {
        fun row(): String =
            "| $label | $lines | $depth | $shreds | $isValidMicros | $passMillis | $backgroundInfos | $otherInfos |"
    }

    private val injector = ShapedInjector()

    override fun setUp() {
        super.setUp()
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(injector, testRootDisposable)
    }

    /**
     * The host's tag sits inside [depth] enclosing tags: `<root>` and `depth - 1` `<d>` tags. Every line holds several
     * RegExp elements, and no character needs an XML entity or CDATA.
     */
    private fun hostFileText(lines: Int, depth: Int): String {
        val body = (0 until lines).joinToString("\n") { LINE }
        return "<root>" + "<d>".repeat(depth - 1) + "<$HOST_TAG>" + body + "</$HOST_TAG>" + "</d>".repeat(depth - 1) + "</root>"
    }

    private fun measure(shape: Shape, lines: Int, depth: Int, around: ((() -> Unit) -> Unit)? = null): Cell {
        injector.shape = shape
        val fileText = hostFileText(lines, depth)
        myFixture.configureByText("${shape.label}-$lines-d$depth.xml", fileText)
        assertTrue("the test thread must hold read access", ApplicationManager.getApplication().isReadAccessAllowed)

        repeat(WARM_UP_PASSES) { highlight() }

        val host = hostText()
        val injected = injectedFiles(host)
        assertEquals("$shape at N=$lines, D=$depth: injected files", 1, injected.size)
        val (injectedFile, shreds) = injected.single()
        assertEquals(
            "$shape at N=$lines, D=$depth: shreds",
            if (shape == Shape.PER_LINE) lines else 1,
            shreds
        )
        assertEquals("$shape at N=$lines, D=$depth: injected text", host.text, injectedFile.text)
        return time(shape.label, lines, depth, host, injectedFile, shreds, around)
    }

    /**
     * The timed part of a cell: [TIMED_PASSES] passes, each after a restart, then the `isValid()` calls on the injected
     * document, and a check that [host] still has [injectedFile] as its one injection.
     */
    private fun time(
        label: String,
        lines: Int,
        depth: Int,
        host: PsiElement,
        injectedFile: PsiFile,
        shreds: Int,
        around: ((() -> Unit) -> Unit)?,
    ): Cell {
        val window = PsiDocumentManager.getInstance(project).getCachedDocument(injectedFile) as DocumentWindow
        assertEquals("$label at N=$lines, D=$depth: host ranges", shreds, window.hostRanges.size)

        var lastInfos: List<HighlightInfo> = emptyList()
        val passMillis = mutableListOf<Double>()
        val timedPasses = {
            repeat(TIMED_PASSES) {
                DaemonCodeAnalyzer.getInstance(project).restart(myFixture.file, "timed pass")
                val start = System.nanoTime()
                lastInfos = myFixture.doHighlighting()
                passMillis.add((System.nanoTime() - start) / 1e6)
            }
        }
        if (around == null) timedPasses() else around(timedPasses)

        repeat(WARM_UP_CALLS) { assertTrue(window.isValid) }
        val isValidMicros = (0 until TIMED_CALLS).map {
            val start = System.nanoTime()
            val valid = window.isValid
            val micros = (System.nanoTime() - start) / 1e3
            assertTrue(valid)
            micros
        }

        val after = injectedFiles(host)
        assertSame("$label at N=$lines, D=$depth: the passes must re-highlight one injection", injectedFile, after.single().first)

        val background = lastInfos.count { it.type == HighlightInfoType.INJECTED_LANGUAGE_BACKGROUND }
        return Cell(label, lines, depth, shreds, Stats.of(isValidMicros), Stats.of(passMillis), background, lastInfos.size - background)
    }

    /** One full pass: without the restart, the daemon finds the file up to date and skips its passes. */
    private fun highlight(): List<HighlightInfo> {
        DaemonCodeAnalyzer.getInstance(project).restart(myFixture.file, "warm-up pass")
        return myFixture.doHighlighting()
    }

    private fun hostText(): XmlText =
        PsiTreeUtil.findChildrenOfType(myFixture.file, XmlText::class.java).single { (it.parent as? XmlTag)?.name == HOST_TAG }

    /** Each injected file over [host], with its shred count. */
    private fun injectedFiles(host: PsiElement): List<Pair<PsiFile, Int>> {
        val result = mutableListOf<Pair<PsiFile, Int>>()
        InjectedLanguageManager.getInstance(project).enumerate(host) { injectedPsi, places -> result.add(injectedPsi to places.size) }
        return result
    }

    private fun report(name: String, cells: List<Cell>, extra: List<String> = emptyList()) {
        val text = buildString {
            appendLine("# $name, ${LocalDateTime.now()}")
            appendLine(environment())
            appendLine("warm-up passes $WARM_UP_PASSES, timed passes $TIMED_PASSES; warm-up calls $WARM_UP_CALLS, timed calls $TIMED_CALLS")
            appendLine()
            appendLine("| shape | N | D | shreds | isValid µs median [min, max] | pass ms median [min, max] | background infos | other infos |")
            appendLine("|---|---|---|---|---|---|---|---|")
            cells.forEach { appendLine(it.row()) }
            extra.forEach { appendLine(it) }
        }
        print(text)
        outputDir().resolve("$name-$RUN_ID.md").writeText(text)
    }

    fun testMainSweep() {
        val jfrLines = mutableListOf<String>()
        val cells = Shape.entries.flatMap { shape ->
            MAIN_SWEEP_LINES.map { lines ->
                val record = shape == Shape.PER_LINE && lines == MAIN_SWEEP_LINES.max()
                measure(shape, lines, 1, if (record) { passes -> jfrLines.add(recordJfr("per-line-$lines", passes)) } else null)
            }
        }
        report("main-sweep", cells, listOf("") + jfrLines)
    }

    fun testDepthSweep() {
        val cells = Shape.entries.flatMap { shape -> DEPTHS.map { depth -> measure(shape, DEPTH_SWEEP_LINES, depth) } }
        report("depth-sweep", cells)
    }

    /**
     * What typing in the host costs: insert one character into the middle line, commit, and time the next pass. Unlike
     * the sweeps, nothing restarts the daemon; the edit itself makes the host dirty.
     */
    private fun keystroke(shape: Shape, lines: Int): String {
        injector.shape = shape
        myFixture.configureByText("${shape.label}-$lines-keystroke.xml", hostFileText(lines, 1))
        highlight()
        val host = hostText()
        val before = injectedFiles(host).single().first
        val offset = host.textRange.startOffset + (lines / 2) * (LINE.length + 1)
        val document = myFixture.editor.document
        val passMillis = mutableListOf<Double>()
        repeat(WARM_UP_PASSES + TIMED_PASSES) { i ->
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(offset, "a") }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val start = System.nanoTime()
            myFixture.doHighlighting()
            if (i >= WARM_UP_PASSES) passMillis.add((System.nanoTime() - start) / 1e6)
        }
        val newHost = hostText()
        val after = injectedFiles(newHost)
        assertEquals("$shape keystroke at N=$lines: injected files", 1, after.size)
        assertEquals("$shape keystroke at N=$lines: shreds", if (shape == Shape.PER_LINE) lines else 1, after.single().second)
        return "| ${shape.label} | $lines | 1 | ${after.single().second} | ${Stats.of(passMillis)} | ${after.single().first === before} |"
    }

    fun testKeystrokeInHost() {
        val rows = Shape.entries.map { keystroke(it, MAIN_SWEEP_LINES.max()) }
        val text = buildString {
            appendLine("# keystroke, ${LocalDateTime.now()}")
            appendLine(environment())
            appendLine("warm-up keystrokes $WARM_UP_PASSES, timed keystrokes $TIMED_PASSES")
            appendLine()
            appendLine("| shape | N | D | shreds | pass ms median [min, max] | same injected PsiFile after the edits |")
            appendLine("|---|---|---|---|---|---|")
            rows.forEach { appendLine(it) }
        }
        print(text)
        outputDir().resolve("keystroke-$RUN_ID.md").writeText(text)
    }

    fun testSinglePlaceHighlightingScalesLinearly() {
        val small = measure(Shape.SINGLE_PLACE, 1000, 1)
        val large = measure(Shape.SINGLE_PLACE, 2000, 1)
        val ratio = large.passMillis.median / small.passMillis.median
        report("single-place-ratio", listOf(small, large), listOf("", "ratio %.3f".format(ratio)))

        assertTrue(
            "doubling the lines of a single-place injection should at most double the pass time, give or take a " +
                "margin of $MARGIN, but it went from ${small.passMillis.median} ms to ${large.passMillis.median} ms, " +
                "a ratio of %.2f".format(ratio),
            ratio <= 2 * MARGIN
        )
    }

    fun testPerLineHighlightingScalesLinearly() {
        val singleSmall = measure(Shape.SINGLE_PLACE, 1000, 1)
        val singleLarge = measure(Shape.SINGLE_PLACE, 2000, 1)
        val perLineSmall = measure(Shape.PER_LINE, 1000, 1)
        val perLineLarge = measure(Shape.PER_LINE, 2000, 1)
        val singleRatio = singleLarge.passMillis.median / singleSmall.passMillis.median
        val perLineRatio = perLineLarge.passMillis.median / perLineSmall.passMillis.median
        report(
            "per-line-ratio",
            listOf(singleSmall, singleLarge, perLineSmall, perLineLarge),
            listOf("", "single-place ratio %.3f, per-line ratio %.3f".format(singleRatio, perLineRatio))
        )

        assertTrue(
            "doubling the lines of a per-line injection should grow the pass time no faster than doubling a " +
                "single-place injection does (ratio %.2f), give or take a margin of $MARGIN, but it went from "
                    .format(singleRatio) +
                "${perLineSmall.passMillis.median} ms to ${perLineLarge.passMillis.median} ms, a ratio of %.2f"
                    .format(perLineRatio),
            perLineRatio <= singleRatio * MARGIN
        )
    }

    /**
     * A Java class whose Javadoc holds one `{@snippet lang=RegExp : ...}` of [lines] lines of [LINE]. No injector of
     * this test's is involved: the platform's `JavadocInjector` injects the snippet, one place per content line,
     * because each line's ` * ` prefix and indent are not part of the snippet.
     */
    private fun javadocSnippetFileText(lines: Int): String {
        val body = (0 until lines).joinToString("") { "     * $LINE\n" }
        return "class Snippet {\n    /**\n     * {@snippet lang=RegExp :\n" + body + "     * }\n     */\n    void m() {}\n}\n"
    }

    private fun measureJavadocSnippet(lines: Int, around: ((() -> Unit) -> Unit)? = null): Cell {
        myFixture.configureByText("Snippet$lines.java", javadocSnippetFileText(lines))
        repeat(WARM_UP_PASSES) { highlight() }

        val host = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiSnippetDocTag::class.java).single()
        val injected = injectedFiles(host)
        assertEquals("javadoc snippet at N=$lines: injected files", 1, injected.size)
        val (injectedFile, shreds) = injected.single()
        assertEquals("javadoc snippet at N=$lines: injected language", RegExpLanguage.INSTANCE, injectedFile.language)
        assertEquals("javadoc snippet at N=$lines: shreds", lines, shreds)
        assertEquals("javadoc snippet at N=$lines: injected lines", List(lines) { LINE }, injectedFile.text.lines().filter { it.isNotEmpty() })
        return time(JAVADOC_SNIPPET, lines, 1, host, injectedFile, shreds, around)
    }

    /**
     * Reported, not asserted: the per-line cost with the platform's own injector, in a Java file any user can open.
     * The N = 2,000 cell is recorded.
     */
    fun testJavadocSnippetSweep() {
        val jfrLines = mutableListOf<String>()
        val cells = MAIN_SWEEP_LINES.map { lines ->
            val record = lines == MAIN_SWEEP_LINES.max()
            measureJavadocSnippet(lines, if (record) { passes -> jfrLines.add(recordJfr("javadoc-snippet-$lines", passes)) } else null)
        }
        val ratio = cells.last().passMillis.median / cells[cells.size - 2].passMillis.median
        report(
            "javadoc-snippet-sweep",
            cells,
            listOf("", "pass ratio %d → %d lines: %.3f".format(cells[cells.size - 2].lines, cells.last().lines, ratio), "") + jfrLines
        )
    }

    /**
     * Asks every leaf whether it is in an injected fragment, as the Elixir plugin's `UnicodeSecurity` annotator does
     * through `Injection.of`: `element.containingFile`, then `isInjectedFragment`. Sums the time those two calls take
     * and counts them, across the threads the annotator runs on. The counters are static because `AnnotatorRunner`
     * runs a new instance of each annotator (`cloneTemplate`), not the one registered.
     */
    private class InjectedLeafAnnotator : Annotator {
        companion object {
            val nanos = AtomicLong()
            val calls = AtomicLong()
        }

        override fun annotate(element: PsiElement, holder: AnnotationHolder) {
            if (element.firstChild != null) return
            val start = System.nanoTime()
            val file = element.containingFile
            if (file != null) InjectedLanguageManager.getInstance(element.project).isInjectedFragment(file)
            nanos.addAndGet(System.nanoTime() - start)
            calls.incrementAndGet()
        }
    }

    /** One [measure] cell, with the [InjectedLeafAnnotator] time and calls per timed pass. */
    private data class AnnotatorCell(val cell: Cell, val millisPerPass: Double, val callsPerPass: Long)

    /**
     * [testPerLineHighlightingScalesLinearly] with the caller moved from the line-marker pass to the annotator pass:
     * `RunLineMarkerProvider` is masked, and [InjectedLeafAnnotator] is registered for RegExp. The assertion is on the
     * annotator's own time, not the pass time, so the rest of the pass and the load on the machine do not dilute it.
     * Both shapes give the annotator the same leaves, so with an O(1) `isValid()` its time would at most double when
     * the lines double, as it does for the single-place control in the same run. The per-line N = 2,000 cell is recorded.
     */
    fun testPerLineAnnotatorScalesLinearly() {
        ExtensionTestUtil.maskExtensions(
            LineMarkerProviders.EP_NAME,
            LineMarkerProviders.EP_NAME.extensionList.filter { it.implementationClass != RunLineMarkerProvider::class.java.name },
            testRootDisposable
        )
        LanguageAnnotators.INSTANCE.addExplicitExtension(RegExpLanguage.INSTANCE, InjectedLeafAnnotator(), testRootDisposable)
        val annotator = InjectedLeafAnnotator
        val jfrLines = mutableListOf<String>()

        fun measureAnnotator(shape: Shape, lines: Int, record: Boolean = false): AnnotatorCell {
            var millisPerPass = 0.0
            var callsPerPass = 0L
            val cell = measure(shape, lines, 1) { passes ->
                annotator.nanos.set(0)
                annotator.calls.set(0)
                if (record) jfrLines.add(recordJfr("annotator-per-line-$lines", passes)) else passes()
                millisPerPass = annotator.nanos.get() / 1e6 / TIMED_PASSES
                callsPerPass = annotator.calls.get() / TIMED_PASSES
            }
            return AnnotatorCell(cell, millisPerPass, callsPerPass)
        }

        // Discarded: run on its own, the test's first cell is also the JVM's first highlighting, and comes out slow.
        measureAnnotator(Shape.SINGLE_PLACE, 1000)
        val singleSmall = measureAnnotator(Shape.SINGLE_PLACE, 1000)
        val singleLarge = measureAnnotator(Shape.SINGLE_PLACE, 2000)
        val perLineSmall = measureAnnotator(Shape.PER_LINE, 1000)
        val perLineLarge = measureAnnotator(Shape.PER_LINE, 2000, record = true)
        assertEquals("the annotator's calls per pass at N=1000", singleSmall.callsPerPass, perLineSmall.callsPerPass)
        assertEquals("the annotator's calls per pass at N=2000", singleLarge.callsPerPass, perLineLarge.callsPerPass)

        val singleRatio = singleLarge.millisPerPass / singleSmall.millisPerPass
        val perLineRatio = perLineLarge.millisPerPass / perLineSmall.millisPerPass
        val cells = listOf(singleSmall, singleLarge, perLineSmall, perLineLarge)
        report(
            "annotator-ratio",
            cells.map { it.cell },
            listOf("", "| shape | N | annotator calls per pass | annotator ms per pass |", "|---|---|---|---|") +
                cells.map { "| ${it.cell.label} | ${it.cell.lines} | ${it.callsPerPass} | %.3f |".format(it.millisPerPass) } +
                listOf(
                    "",
                    "annotator time: single-place ratio %.3f, per-line ratio %.3f".format(singleRatio, perLineRatio),
                    "",
                ) + jfrLines
        )

        assertTrue(
            "doubling the lines of a per-line injection should grow the time an annotator spends asking each injected " +
                "leaf for its file no faster than doubling a single-place injection does (ratio %.2f), give or take a "
                    .format(singleRatio) +
                "margin of $MARGIN, but it went from %.1f ms to %.1f ms a pass, a ratio of %.2f"
                    .format(perLineSmall.millisPerPass, perLineLarge.millisPerPass, perLineRatio),
            perLineRatio <= singleRatio * MARGIN
        )
    }

    /** Runs [passes] under a JFR recording with the `profile` settings and returns where the recording was written. */
    private fun recordJfr(name: String, passes: () -> Unit): String {
        val file = outputDir().resolve("$name-$RUN_ID.jfr")
        Recording(Configuration.getConfiguration("profile")).use { recording ->
            recording.start()
            passes()
            recording.stop()
            recording.dump(file.toPath())
        }
        return "JFR: ${file.absolutePath}"
    }

    private fun environment(): String =
        "${ApplicationInfo.getInstance().build.asString()}; ${System.getProperty("java.vm.name")} " +
            "${System.getProperty("java.runtime.version")}; ${Runtime.getRuntime().availableProcessors()} processors; " +
            "${System.getProperty("os.name")} ${System.getProperty("os.version")}"

    private fun outputDir(): File =
        File(System.getProperty("repro.outputDir") ?: error("repro.outputDir is set by the Gradle test task")).apply { mkdirs() }

    companion object {
        private const val HOST_TAG = "host"
        private const val JAVADOC_SNIPPET = "javadoc-snippet"
        private const val LINE = "ab+(cd|ef)*[0-9]x?"

        private val MAIN_SWEEP_LINES = listOf(250, 500, 1000, 2000)
        private const val DEPTH_SWEEP_LINES = 1000
        private val DEPTHS = listOf(1, 8, 32)

        private const val WARM_UP_PASSES = 2
        private const val TIMED_PASSES = 5
        private const val WARM_UP_CALLS = 1000
        private const val TIMED_CALLS = 1001

        /**
         * Six recorded runs of [testSinglePlaceHighlightingScalesLinearly] gave 1,000 → 2,000 ratios from 1.27 to 1.77;
         * the largest over the smallest is 1.39, rounded up.
         */
        private const val MARGIN = 1.5

        private val RUN_ID: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    }
}
