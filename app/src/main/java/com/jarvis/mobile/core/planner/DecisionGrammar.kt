package com.jarvis.mobile.core.planner

import com.jarvis.mobile.core.tools.ToolSpec

/**
 * GBNF grammars for llama.cpp grammar-constrained decoding (expert-review
 * deferred item). When the LOCAL route generates the planner's JSON decision,
 * the native sampler masks every token that would break the output contract:
 *
 *   - the output can ONLY be the contracted JSON shapes (no prose, no echoed
 *     screen block, no markdown fences - the entire v1.6.0 small-model
 *     failure class becomes syntactically impossible),
 *   - "tool" can ONLY be a name that is actually registered (no hallucinated
 *     tools),
 *   - arg keys are pulled toward the real spec params (a generic string key
 *     stays allowed so an underspecified arg never dead-ends the decode).
 *
 * Grammar-constrained decoding constrains SYNTAX, not probabilities - a good
 * model output is unchanged, a sloppy one is repaired token-by-token instead
 * of falling into the salvage/retry pipeline.
 *
 * Pure Kotlin (JVM-testable): grammars are built from injected specs; callers
 * pass ToolRegistry.available().map { it.spec }.
 */
object DecisionGrammar {

    /** GBNF JSON string literal (excludes raw quotes, backslashes, control chars). */
    private const val JSTR =
        "jstr ::= \"\\\"\" ( [^\\x22\\x5c\\x00-\\x1f] | \"\\\\\" ( [\"\\\\/bfnrt] | \"u\" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] ) )* \"\\\"\""

    private const val JNUM =
        "jnum ::= \"-\"? [0-9]+ ( \".\" [0-9]+ )? ( [eE] [-+]? [0-9]+ )?"

    private const val JBOOL = "jbool ::= \"true\" | \"false\""

    private const val WS = "ws ::= [ \\t\\n\\r]*"

    private val JSON_PRIMITIVES = """
        jval ::= jstr | jnum | jbool | "null" | jarr | jobj
        jarr ::= "[" ws ( jval ( ws "," ws jval )* )? ws "]"
        jobj ::= "{" ws ( jpair ( ws "," ws jpair )* )? ws "}"
        jpair ::= jstr ws ":" ws jval
    """.trimIndent()

    /** Sanity filter: registry names are [a-z0-9_] already, but never trust upstream. */
    private fun ok(s: String) = s.isNotBlank() && s.length <= 48 && s.all { it.isLetterOrDigit() || it == '_' }

    private fun quotedLiterals(names: Collection<String>): String? {
        val clean = names.filter { ok(it) }.map { "\"$it\"" }.distinct().sorted()
        return if (clean.isEmpty()) null else clean.joinToString(" | ")
    }

    /** The full OUTPUT-CONTRACT grammar for the decide stage (flat v1.9 + legacy nested forms). */
    fun decisionGrammar(specs: List<ToolSpec>): String? {
        val tools = quotedLiterals(specs.map { it.name }) ?: return null
        val argKeys = quotedLiterals(specs.flatMap { s -> s.params.map { p -> p.name } }) ?: "\"x\""
        val flatTypes = "\"tap\" | \"click\" | \"long_press\" | \"longpress\" | \"double_tap\" | \"type_text\" | \"type\" | \"scroll\" | \"swipe\" | \"button\" | \"press\" | \"open_app\" | \"launch\" | \"wait\" | \"tool\" | \"done\" | \"complete\""
        val directions = "\"up\" | \"down\" | \"left\" | \"right\" | \"fwd\" | \"back\""
        val buttons = "\"back\" | \"home\" | \"recents\" | \"recent\" | \"recent_apps\" | \"overview\" | \"notifications\""
        return buildString {
            append("root ::= \"{\" ws ( fmember ( ws \",\" ws fmember )* )? ws \"}\"\n")
            append("fmember ::= thought | response | summary | action | flattool | flatargs | ftype | fx | fy | ftext | fdir | fname | fapp | fms | fdesc\n")
            append("thought ::= \"\\\"thought\\\"\" ws \":\" ws jstr\n")
            append("response ::= \"\\\"response\\\"\" ws \":\" ws jstr\n")
            append("summary ::= \"\\\"summary\\\"\" ws \":\" ws jstr\n")
            append("action ::= \"\\\"action\\\"\" ws \":\" ws actionobj\n")
            append("flattool ::= \"\\\"tool\\\"\" ws \":\" ws toolname\n")
            append("flatargs ::= \"\\\"args\\\"\" ws \":\" ws argsobj\n")
            append("ftype ::= \"\\\"type\\\"\" ws \":\" ws ftypeval\n")
            append("fx ::= \"\\\"x\\\"\" ws \":\" ws jnum\n")
            append("fy ::= \"\\\"y\\\"\" ws \":\" ws jnum\n")
            append("ftext ::= \"\\\"text\\\"\" ws \":\" ws jstr\n")
            append("fdir ::= \"\\\"direction\\\"\" ws \":\" ws fdirval\n")
            append("fname ::= \"\\\"name\\\"\" ws \":\" ws ( fbtnval | toolname )\n")
            append("fapp ::= \"\\\"app\\\"\" ws \":\" ws jstr\n")
            append("fms ::= \"\\\"ms\\\"\" ws \":\" ws jnum\n")
            append("fdesc ::= \"\\\"description\\\"\" ws \":\" ws jstr\n")
            append("ftypeval ::= $flatTypes\n")
            append("fdirval ::= $directions | jstr\n")
            append("fbtnval ::= $buttons\n")
            append("actionobj ::= \"{\" ws \"\\\"tool\\\"\" ws \":\" ws toolname ws \",\" ws \"\\\"args\\\"\" ws \":\" ws argsobj ws \"}\"\n")
            append("toolname ::= $tools\n")
            append("argsobj ::= \"{\" ws ( argpair ( ws \",\" ws argpair )* )? ws \"}\"\n")
            append("argpair ::= argkey ws \":\" ws jval\n")
            append("argkey ::= $argKeys | jstr\n")
            append(JSON_PRIMITIVES).append('\n')
            append(JSTR).append('\n')
            append(JNUM).append('\n')
            append(JBOOL).append('\n')
            append(WS)
        }
    }

    /** Grammar for the one-shot PLAN stage: {"thought":"...","steps":[{"tool","args","why"}]}. */
    fun planGrammar(specs: List<ToolSpec>): String? {
        val tools = quotedLiterals(specs.map { it.name }) ?: return null
        val argKeys = quotedLiterals(specs.flatMap { s -> s.params.map { p -> p.name } }) ?: "\"x\""
        return buildString {
            append("root ::= \"{\" ws ( pmember ( ws \",\" ws pmember )* )? ws \"}\"\n")
            append("pmember ::= thought | steps\n")
            append("thought ::= \"\\\"thought\\\"\" ws \":\" ws jstr\n")
            append("steps ::= \"\\\"steps\\\"\" ws \":\" ws \"[\" ws ( step ( ws \",\" ws step )* )? ws \"]\"\n")
            append("step ::= \"{\" ws \"\\\"tool\\\"\" ws \":\" ws toolname ws \",\" ws \"\\\"args\\\"\" ws \":\" ws argsobj ( ws \",\" ws \"\\\"why\\\"\" ws \":\" ws jstr )? ws \"}\"\n")
            append("toolname ::= $tools\n")
            append("argsobj ::= \"{\" ws ( argpair ( ws \",\" ws argpair )* )? ws \"}\"\n")
            append("argpair ::= argkey ws \":\" ws jval\n")
            append("argkey ::= $argKeys | jstr\n")
            append(JSON_PRIMITIVES).append('\n')
            append(JSTR).append('\n')
            append(JNUM).append('\n')
            append(JBOOL).append('\n')
            append(WS)
        }
    }
}
