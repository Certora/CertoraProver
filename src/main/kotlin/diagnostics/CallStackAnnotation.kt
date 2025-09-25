/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

@file:OptIn(ExperimentalContracts::class)
@file:Suppress("NOTHING_TO_INLINE")
package diagnostics

import config.*
import kotlin.contracts.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import scene.*
import utils.*
import java.util.concurrent.ConcurrentHashMap

/*
    This file contains utilities for annotating the call stack with a string.  This can be useful for debugging, but is
    especially useful for profiling, as it allows you to view profile data "sliced" according to these annotations. For
    example, you might view all CPU usage due to transforms applied to contract method "foo".

    The main function is `annotateCallStack`, which takes a string and a lambda, and calls the lambda with the call
    stack annotated with the string.  There are some variations on this, for conditional annotations, and for annotating
    coroutines.

    The annotations are implemented by dynamically creating new classes with names derived from the annotation strings,
    using [ByteBuddy].  We should be careful about not overusing this facility, as it might lead to performance issues
    due to loading too many classes.  For the uses we have in mind - annotating stacks with transform names or contract
    method names - this should not be a problem.
 */

/**
    Calls the function [f], annotating the call stack with [annot].  This annotation will be visible in stack traces,
    e.g. in a profiler or debugger.
 */
inline fun <T> annotateCallStack(annot: String, noinline f: () -> T): T {
    contract {
        callsInPlace(f, InvocationKind.EXACTLY_ONCE)
    }
    return getCallStackAnnotator(annot).invoke(f)
}

/** Suspending version of [annotateCallStack] */
inline suspend fun <T> annotateCallStackAsync(annot: String, noinline f: suspend () -> T): T {
    contract {
        callsInPlace(f, InvocationKind.EXACTLY_ONCE)
    }
    return getCallStackAnnotator(annot).invokeAsync(f)
}

/** Conditionally annotates a call on the call stack.  See [annotateCallStack]. */
inline fun <T> annotateCallStackIf(pred: Boolean, annot: () -> String, crossinline f: () -> T): T {
    return if (!pred) {
        f()
    } else {
        annotateCallStack(annot()) { f() }
    }
}

/** Conditionally annotates a call on the call stack.  See [annotateCallStack]. */
inline suspend fun <T> annotateCallStackIfAsync(pred: Boolean, annot: () -> String, crossinline f: suspend () -> T): T {
    return if (!pred) {
        f()
    } else {
        annotateCallStackAsync(annot()) { f() }
    }
}

@PublishedApi
internal abstract class CallStackAnnotator {
    abstract fun <T> invoke(f: () -> T): T
    abstract suspend fun <T> invokeAsync(f: suspend () -> T): T
}

/**
    A template implementation of [CallStackAnnotator].  We never actually instantiate this class, but we duplicate
    its bytecode to make classes with different names.  These class names appear in the stack trace.
 */
@PublishedApi
internal class CallStackAnnotatorTemplate : CallStackAnnotator() {
    override fun <T> invoke(f: () -> T) = f()
    override suspend fun <T> invokeAsync(f: suspend () -> T) = f()
}

/** Cache an annotator for each annotation */
private val annotators = ConcurrentHashMap<String, CallStackAnnotator>()

/** Regex that matches characters that are invalid in a Java bytecode class name. */
@Suppress("ForbiddenMethodCall") // regex
private val invalidClassNameChars = "[^._\$a-zA-Z0-9]".toRegex()

private val templateReader = CallStackAnnotatorTemplate::class.java.let { cls ->
    val loader = cls.classLoader
    loader.getResourceAsStream(cls.name.replace('.', '/') + ".class")?.use {
        it.readAllBytes()
    }
}.let {
    ClassReader(it)
}

private val customLoader = object : ClassLoader(
    "AnnotationLoader", CallStackAnnotatorTemplate::class.java.classLoader
) {
    fun newAnnotationClass(classBytes: ByteArray, name: String) : Class<*> {
        return this.defineClass(name, classBytes, 0, classBytes.size, null /* default protection domain */)
    }
}

/**
    Produces an instance of a CallStackAnnotator-implementing class whose name is derived from [name].
 */
@PublishedApi
internal fun getCallStackAnnotator(name: String): CallStackAnnotator {
    val annotationClassName = name.replace(invalidClassNameChars, "\\$")
    val classBinaryName = "stackannot/${annotationClassName.replace('.', '/')}"
    return annotators.computeIfAbsent(classBinaryName) {
        val classWriter = ClassWriter(0 /* don't compute maxes or frames, we aren't doing any bytecode manipulation */)
        val classNameRenamer = object : ClassVisitor(Opcodes.ASM9 /* use the most recent, no real reason */, classWriter) {
            override fun visit(
                version: Int,
                access: Int,
                name: String?,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                /*
                 * For the non-asm familiar: the default implementation of ClassVisitor delegates to the class visitor delegate,
                 * which is classWriter, aka, a class visitor that writes out byteocde. So by simply passing the classBinaryName
                 * instead, that is the beginning (and end) of our instrumentation.
                 */
                super.visit(version, access, classBinaryName, signature, superName, interfaces)
            }
        }
        templateReader.accept(
            classNameRenamer, 0
        )
        val annotationClassBytes = classWriter.toByteArray()
        val annotationClass = customLoader.newAnnotationClass(
            annotationClassBytes, "stackannot.$annotationClassName"
        )
        annotationClass.getDeclaredConstructor().newInstance().uncheckedAs<CallStackAnnotator>()
    }
}

@PublishedApi internal fun NamedCode<ReportTypes>.annotation() = "code.${myName().replace('-', '.')}"
@PublishedApi internal fun ReportTypes.annotation() = "transform.$name"

@PublishedApi internal val codeProfileEnabled = ConfigType.StackAnnotation.CODE in Config.StackAnnotationConfig.get()
@PublishedApi internal val transformProfileEnabled = ConfigType.StackAnnotation.TRANSFORM in Config.StackAnnotationConfig.get()

/**
    Calls [f], potentially annotating the JVM stack trace with information about [method]
 */
@OptIn(ExperimentalContracts::class)
inline fun <T> inCode(method: NamedCode<ReportTypes>, crossinline f: () -> T): T {
    contract {
        callsInPlace(f, InvocationKind.EXACTLY_ONCE)
    }
    return annotateCallStackIf(codeProfileEnabled, { method.annotation() }, f)
}

/**
    See [inCode].
 */
inline suspend fun <T> inCodeAsync(method: NamedCode<ReportTypes>, crossinline f: suspend () -> T): T {
    contract {
        callsInPlace(f, InvocationKind.EXACTLY_ONCE)
    }
    return annotateCallStackIfAsync(codeProfileEnabled, { method.annotation() }, f)
}

/**
    Calls [f], potentially annotating the JVM stack trace with [reportType]
 */
inline fun <T> inTransform(reportType: ReportTypes, crossinline f: () -> T): T {
    contract {
        callsInPlace(f, InvocationKind.EXACTLY_ONCE)
    }
    return annotateCallStackIf(transformProfileEnabled, { reportType.annotation() }, f)
}
