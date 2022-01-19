/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package org.parchmentmc.scribe.util

import com.intellij.navigation.AnonymousElementProvider
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameterList
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.search.GlobalSearchScope
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.lang.reflect.Method

private val LOAD_CLASS_FILE_BYTES: Method? = runCatching {
    com.intellij.byteCodeViewer.ByteCodeViewerManager::class.java
        .getDeclaredMethod("loadClassFileBytes", PsiClass::class.java)
        .let { it.isAccessible = true; it }
}.getOrNull()

// Type

val PsiClassType.fullQualifiedName
    get() = resolve()?.fullQualifiedName // this can be null if the type import is missing

// Class

val PsiClass.outerQualifiedName
    get() = if (containingClass == null) qualifiedName else null

val PsiClass.fullQualifiedName
    get(): String? {
        return try {
            outerQualifiedName ?: buildQualifiedName(StringBuilder()).toString()
        } catch (e: ClassNameResolutionFailedException) {
            null
        }
    }

fun PsiClass.findClassBytes() = LOAD_CLASS_FILE_BYTES?.invoke(null, this) as? ByteArray

fun PsiClass.findClassNode(): ClassNode? {
    val bytes = findClassBytes() ?: return null
    val classNode = ClassNode()
    ClassReader(bytes).accept(classNode, 0)
    return classNode
}

@Throws(ClassNameResolutionFailedException::class)
private fun PsiClass.buildQualifiedName(builder: StringBuilder): StringBuilder {
    if (this is PsiTypeParameter) {
        throw ClassNameResolutionFailedException()
    }
    buildInnerName(builder, PsiClass::outerQualifiedName)
    return builder
}

private val PsiClass.outerShortName
    get() = if (containingClass == null) name else null

@Throws(ClassNameResolutionFailedException::class)
inline fun PsiClass.buildInnerName(builder: StringBuilder, getName: (PsiClass) -> String?, separator: Char = '$') {
    var currentClass: PsiClass = this
    var parentClass: PsiClass?
    var name: String?
    val list = ArrayList<String>()

    do {
        parentClass = currentClass.containingClass
        if (parentClass != null) {
            // Add named inner class
            list.add(currentClass.name ?: throw ClassNameResolutionFailedException())
        } else {
            parentClass = currentClass.parent.findContainingClass() ?: throw ClassNameResolutionFailedException()

            // Add index of anonymous class to list
            list.add(parentClass.getAnonymousIndex(currentClass).toString())
        }

        currentClass = parentClass
        name = getName(currentClass)
    } while (name == null)

    // Append name of outer class
    builder.append(name)

    // Append names for all inner classes
    for (i in list.lastIndex downTo 0) {
        builder.append(separator).append(list[i])
    }
}

fun findQualifiedClass(
    project: Project,
    fullQualifiedName: String,
    scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
): PsiClass? {
    var innerPos = fullQualifiedName.indexOf('$')
    if (innerPos == -1) {
        return JavaPsiFacade.getInstance(project).findClass(fullQualifiedName, scope)
    }

    var currentClass = JavaPsiFacade.getInstance(project)
        .findClass(fullQualifiedName.substring(0, innerPos), scope) ?: return null
    var outerPos: Int

    while (true) {
        outerPos = innerPos + 1
        innerPos = fullQualifiedName.indexOf('$', outerPos)

        if (innerPos == -1) {
            return currentClass.findInnerClass(fullQualifiedName.substring(outerPos))
        } else {
            currentClass = currentClass.findInnerClass(fullQualifiedName.substring(outerPos, innerPos)) ?: return null
        }
    }
}

private fun PsiClass.findInnerClass(name: String): PsiClass? {
    val anonymousIndex = name.toIntOrNull()
    return if (anonymousIndex == null) {
        // Named inner class
        findInnerClassByName(name, false)
    } else {
        if (anonymousIndex > 0 && anonymousIndex <= anonymousElements.size) {
            anonymousElements[anonymousIndex - 1] as PsiClass
        } else {
            null
        }
    }
}

@Throws(ClassNameResolutionFailedException::class)
fun PsiElement.getAnonymousIndex(anonymousElement: PsiElement): Int {
    // Attempt to find name for anonymous class
    for ((i, element) in anonymousElements.withIndex()) {
        if (element equivalentTo anonymousElement) {
            return i + 1
        }
    }

    throw ClassNameResolutionFailedException("Failed to determine anonymous class for $anonymousElement")
}

val PsiElement.anonymousElements: Array<PsiElement>
    get() {
        for (provider in AnonymousElementProvider.EP_NAME.extensionList) {
            val elements = provider.getAnonymousElements(this)
            if (elements.isNotEmpty()) {
                return elements
            }
        }

        return emptyArray()
    }

// Inheritance

// Member

fun PsiMethod.isMatchingMethod(pattern: PsiMethod, constructor: Boolean = pattern.isConstructor): Boolean {
    return this.isConstructor == constructor &&
            areReallyOnlyParametersErasureEqual(this.parameterList, pattern.parameterList) &&
            (this.isConstructor || constructor || this.returnType.isErasureEquivalentTo(pattern.returnType))
}

fun PsiField.isMatchingField(pattern: PsiField): Boolean {
    return type.isErasureEquivalentTo(pattern.type)
}

private fun areReallyOnlyParametersErasureEqual(
    parameterList1: PsiParameterList,
    parameterList2: PsiParameterList
): Boolean {
    // Similar to MethodSignatureUtil.areParametersErasureEqual, but doesn't check method name
    if (parameterList1.parametersCount != parameterList2.parametersCount) {
        return false
    }

    val parameters1 = parameterList1.parameters
    val parameters2 = parameterList2.parameters
    for (i in parameters1.indices) {
        val type1 = parameters1[i].type
        val type2 = parameters2[i].type

        if (type1 is PsiPrimitiveType && (type2 !is PsiPrimitiveType || type1 != type2)) {
            return false
        }

        if (!type1.isErasureEquivalentTo(type2)) {
            return false
        }
    }

    return true
}

class ClassNameResolutionFailedException : Exception {
    constructor() : super()
    constructor(message: String) : super(message)
}
