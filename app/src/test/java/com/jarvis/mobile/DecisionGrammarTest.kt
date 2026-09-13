package com.jarvis.mobile

import com.jarvis.mobile.core.planner.DecisionGrammar
import com.jarvis.mobile.core.tools.ParamSpec
import com.jarvis.mobile.core.tools.Risk
import com.jarvis.mobile.core.tools.ToolSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the GBNF generator behind grammar-constrained decoding.
 * The generated text is parsed by llama.cpp on device; these tests pin the
 * structural properties llama.cpp silently depends on (every referenced rule
 * defined, tool names embedded as literals, no unbalanced quotes).
 */
class DecisionGrammarTest {

    private val specs = listOf(
        ToolSpec(
            "open_app", "open an app",
            listOf(ParamSpec("app", "string", true, "app name")),
            Risk.LOW,
        ),
        ToolSpec(
            "tap", "tap element",
            listOf(
                ParamSpec("elementIdx", "int", false, "idx"),
                ParamSpec("text", "string", false, "label"),
                ParamSpec("x", "int", false, "x"),
                ParamSpec("y", "int", false, "y"),
            ),
            Risk.LOW,
        ),
        ToolSpec(
            "type_text", "type text",
            listOf(
                ParamSpec("text", "string", true, "what"),
                ParamSpec("elementIdx", "int", false, "idx"),
            ),
            Risk.MEDIUM,
        ),
    )

    @Test
    fun `decision grammar contains every tool name as a literal`() {
        val g = DecisionGrammar.decisionGrammar(specs)!!
        for (t in specs.map { it.name }) {
            assertTrue("tool $t missing", g.contains("\"$t\""))
        }
    }

    @Test
    fun `decision grammar defines every rule it references`() {
        val g = DecisionGrammar.decisionGrammar(specs)!!
        val defined = g.lineSequence()
            .mapNotNull { Regex("^(\\w+)\\s*::=").find(it)?.groupValues?.get(1) }
            .toSet()
        val referenced = g.lineSequence()
            .filter { "::=" in it }
            .map { it.substringAfter("::=") }
            .flatMap { rhs ->
                // Strip GBNF char classes FIRST, then string literals - the other
                // order mis-consumes across boundaries (e.g. `["\\/bfnrt] | "u"`):
                // identifiers inside both (hex escapes, option chars) are not
                // rule references.
                val stripped = rhs
                    .replace(Regex("\\[[^\\]]*\\]"), " ")
                    .replace(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), " ")
                Regex("\\b[a-z][a-z0-9]*\\b").findAll(stripped).map { it.value }
            }
            .toSet()
        val missing = referenced - defined
        assertTrue("undefined rules: $missing", missing.isEmpty())
    }

    @Test
    fun `decision grammar covers both contract shapes`() {
        val g = DecisionGrammar.decisionGrammar(specs)!!
        // nested (full mode) + flat (compact mode) + final response + thought
        listOf("actionobj", "flattool", "flatargs", "response", "thought").forEach {
            assertTrue("rule/branch $it missing", g.contains(it))
        }
    }

    @Test
    fun `quotes inside the grammar are balanced`() {
        val g = DecisionGrammar.decisionGrammar(specs)!!
        // Strip GBNF escape sequences (\") first - they are literals, not delimiters.
        val structural = g.replace("\\\"", "")
        assertTrue("unbalanced quotes", structural.count { it == '"' } % 2 == 0)
    }

    @Test
    fun `plan grammar exposes steps array with tool-args-why`() {
        val g = DecisionGrammar.planGrammar(specs)!!
        listOf("\"steps\"", "step ::=", "\"tool\"", "\"args\"", "\"why\"").forEach {
            assertTrue("plan grammar missing $it", g.contains(it))
        }
    }

    @Test
    fun `empty tool list returns null (caller stays unconstrained)`() {
        assertNull(DecisionGrammar.decisionGrammar(emptyList()))
        assertNull(DecisionGrammar.planGrammar(emptyList()))
    }

    @Test
    fun `grammar is byte-size sane for a real registry`() {
        // 45-ish tools with ~4 params each must stay small enough that building
        // it per decide call is trivial and grammar init stays fast on device.
        val many = (1..60).map { i ->
            ToolSpec(
                "tool_$i", "d",
                listOf(ParamSpec("arg$i", "string", false, "a"), ParamSpec("shared", "int", false, "b")),
                Risk.LOW,
            )
        }
        val g = DecisionGrammar.decisionGrammar(many)
        assertNotNull(g)
        assertTrue("grammar unexpectedly large: ${g!!.length}", g.length < 32_000)
    }
}
