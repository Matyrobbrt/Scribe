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

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.util.TypeConversionUtil

private const val INTERNAL_CONSTRUCTOR_NAME = "<init>"

// Type

val PsiPrimitiveType.internalName: Char
    get() = when (this) {
        PsiType.BYTE -> 'B'
        PsiType.CHAR -> 'C'
        PsiType.DOUBLE -> 'D'
        PsiType.FLOAT -> 'F'
        PsiType.INT -> 'I'
        PsiType.LONG -> 'J'
        PsiType.SHORT -> 'S'
        PsiType.BOOLEAN -> 'Z'
        PsiType.VOID -> 'V'
        else -> throw IllegalArgumentException("Unsupported primitive type: $this")
    }

fun getPrimitiveType(internalName: Char): PsiPrimitiveType? {
    return when (internalName) {
        'B' -> PsiType.BYTE
        'C' -> PsiType.CHAR
        'D' -> PsiType.DOUBLE
        'F' -> PsiType.FLOAT
        'I' -> PsiType.INT
        'J' -> PsiType.LONG
        'S' -> PsiType.SHORT
        'Z' -> PsiType.BOOLEAN
        'V' -> PsiType.VOID
        else -> null
    }
}

private fun PsiClassType.erasure() = TypeConversionUtil.erasure(this) as PsiClassType

@Throws(ClassNameResolutionFailedException::class)
private fun PsiClassType.appendInternalName(builder: StringBuilder): StringBuilder =
    erasure().resolve()?.appendInternalName(builder) ?: builder

@Throws(ClassNameResolutionFailedException::class)
private fun PsiType.appendDescriptor(builder: StringBuilder): StringBuilder {
    return when (this) {
        is PsiPrimitiveType -> builder.append(internalName)
        is PsiArrayType -> componentType.appendDescriptor(builder.append('['))
        is PsiClassType -> appendInternalName(builder.append('L')).append(';')
        else -> throw IllegalArgumentException("Unsupported PsiType: $this")
    }
}

// Class

@Throws(ClassNameResolutionFailedException::class)
private fun PsiClass.appendInternalName(builder: StringBuilder): StringBuilder {
    return outerQualifiedName?.let { builder.append(it.replace('.', '/')) } ?: buildInternalName(builder)
}

@Throws(ClassNameResolutionFailedException::class)
private fun PsiClass.buildInternalName(builder: StringBuilder): StringBuilder {
    buildInnerName(builder, { it.outerQualifiedName?.replace('.', '/') })
    return builder
}

// Method

val PsiMethod.internalName: String
    get() = if (isConstructor) INTERNAL_CONSTRUCTOR_NAME else name

val PsiMethod.descriptor: String?
    get() {
        return try {
            appendDescriptor(StringBuilder()).toString()
        } catch (e: ClassNameResolutionFailedException) {
            null
        }
    }

@Throws(ClassNameResolutionFailedException::class)
private fun PsiMethod.appendDescriptor(builder: StringBuilder): StringBuilder {
    builder.append('(')
    if (this.isEnumConstructor()) {
        // Append fixed, synthetic parameters for Enum constructors.
        builder.append("Ljava/lang/String;I")
    } else if (this.hasSyntheticOuterClassParameter()) {
        this.containingClass?.containingClass?.appendInternalName(builder.append('L'))?.append(';')
    }
    for (parameter in parameterList.parameters) {
        parameter.type.appendDescriptor(builder)
    }
    builder.append(')')
    return (returnType ?: PsiType.VOID).appendDescriptor(builder)
}

// Field
val PsiField.descriptor: String?
    get() {
        return try {
            appendDescriptor(StringBuilder()).toString()
        } catch (e: ClassNameResolutionFailedException) {
            null
        }
    }

@Throws(ClassNameResolutionFailedException::class)
private fun PsiField.appendDescriptor(builder: StringBuilder): StringBuilder = type.appendDescriptor(builder)
