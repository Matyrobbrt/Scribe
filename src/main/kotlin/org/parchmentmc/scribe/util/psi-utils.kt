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

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.*
import com.intellij.psi.impl.light.*
import com.intellij.psi.impl.source.DummyHolderElement
import com.intellij.psi.impl.source.javadoc.PsiDocCommentImpl
import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.util.ui.tree.TreeUtil
import org.jetbrains.plugins.gradle.util.GradleUtil
import org.parchmentmc.feather.mapping.MappingDataContainer
import org.parchmentmc.feather.mapping.MappingDataContainer.ClassData
import org.parchmentmc.feather.mapping.MappingDataContainer.FieldData
import org.parchmentmc.feather.mapping.MappingDataContainer.MethodData

fun PsiElement.findModule(): Module? = ModuleUtilCore.findModuleForPsiElement(this)

@Suppress("UnstableApiUsage")
fun PsiFile.findGradleModule(): DataNode<ModuleData>? = findModule()?.let { GradleUtil.findGradleModuleData(it) }

fun PsiElement.findContainingClass(): PsiClass? = findParent(resolveReferences = false)

private val PsiElement.ancestors: Sequence<PsiElement>
    get() = generateSequence(this) { if (it is PsiFile) null else it.parent }

private inline fun <reified T : PsiElement> PsiElement.findParent(resolveReferences: Boolean): T? {
    return findParent({ false }, resolveReferences)
}

private inline fun <reified T : PsiElement> PsiElement.findParent(
    stop: (PsiElement) -> Boolean,
    resolveReferences: Boolean
): T? {
    var el: PsiElement = this

    while (true) {
        if (resolveReferences && el is PsiReference) {
            el = el.resolve() ?: return null
        }

        if (el is T) {
            return el
        }

        if (el is PsiFile || el is PsiDirectory || stop(el)) {
            return null
        }

        el = el.parent ?: return null
    }
}

private inline fun <reified T : PsiElement> PsiElement.findChild(): T? {
    return firstChild?.findSibling(strict = false)
}

private inline fun <reified T : PsiElement> PsiElement.findSibling(strict: Boolean): T? {
    var sibling = if (strict) nextSibling ?: return null else this
    while (true) {
        if (sibling is T) {
            return sibling
        }

        sibling = sibling.nextSibling ?: return null
    }
}

private inline fun PsiElement.forEachChild(func: (PsiElement) -> Unit) {
    firstChild?.forEachSibling(func, strict = false)
}

private inline fun PsiElement.forEachSibling(func: (PsiElement) -> Unit, strict: Boolean) {
    var sibling = if (strict) nextSibling ?: return else this
    while (true) {
        func(sibling)
        sibling = sibling.nextSibling ?: return
    }
}

val PsiElement.constantValue: Any?
    get() = JavaPsiFacade.getInstance(project).constantEvaluationHelper.computeConstantExpression(this)

private val ACCESS_MODIFIERS =
    listOf(PsiModifier.PUBLIC, PsiModifier.PROTECTED, PsiModifier.PRIVATE, PsiModifier.PACKAGE_LOCAL)

infix fun PsiElement.equivalentTo(other: PsiElement): Boolean {
    return manager.areElementsEquivalent(this, other)
}

fun PsiType?.isErasureEquivalentTo(other: PsiType?): Boolean {
    // TODO: Do more checks for generics instead
    return TypeConversionUtil.erasure(this) == TypeConversionUtil.erasure(other)
}

fun PsiMethod.findAllSuperMethods(): MutableList<PsiMethod> {
    val all = mutableListOf<PsiMethod>()
    val toFind = ArrayDeque<PsiMethod>()
    toFind.add(this)

    while (toFind.isNotEmpty()) {
        val next = toFind.removeLast()
        next.findSuperMethods().forEach {
            all.add(it)
            toFind.add(it)
        }
    }

    return all
}

fun PsiMethod.findAllSuperConstructors(): MutableList<PsiMethod> = this.descriptor?.let { this.containingClass?.findAllSuperConstructors(it) } ?: mutableListOf()

fun PsiClass.findAllSuperConstructors(descriptor: String): MutableList<PsiMethod> {
    val all = mutableListOf<PsiMethod>()

    val superClasses = mutableSetOf<PsiClass>()
    InheritanceUtil.getSuperClasses(this, superClasses, true)
    superClasses.flatMap {
        it.constructors.toList()
    }.filter {
        it.descriptor == descriptor
    }.let {
        all.addAll(it)
    }

    return all
}

fun PsiMethod.copyFromParchment(parchment: MethodData, withJdoc: Boolean = true): LightMethodBuilder {
    val params = LightParameterListBuilder(getManager(), getLanguage())
    for (parameter in getParameterList().getParameters()) {
        val pd = parchment.getParameter(parameter.jvmIndex)
        if (pd == null || pd.name == null) {
            params.addParameter(parameter)
        } else {
            params.addParameter(LightParameter(pd.name!!, parameter.type, this, parameter.language, parameter.isVarArgs))
        }
    }

    var jdoc: String? = null
    if ((parchment.javadoc.isNotEmpty() || parchment.parameters.any { it.javadoc != null }) && withJdoc) {
        jdoc = "/**\n"
        jdoc += parchment.javadoc.joinToString("\n")
        parchment.parameters.forEach {
            if (it.javadoc != null)
                jdoc += "\n@param ${it.name ?: getParameterByJvmIndex(it.index)!!.name} ${it.javadoc}"
        }
        jdoc += "\n*/"
    }

    val old = this
    return object : LightMethodBuilder(
        getManager(),
        getLanguage(),
        getName(),
        params,
        getModifierList(),
        getThrowsList(),
        getTypeParameterList()
    ) {
        var docs: PsiDocComment? = null
        override fun getDocComment(): PsiDocComment? {
            if (docs == null && jdoc != null) {
                docs = JavaPsiFacade.getInstance(project).elementFactory.createDocCommentFromText(jdoc, old);
            }
            return docs
        }
    }.setContainingClass(getContainingClass()).setConstructor(this.isConstructor)
}

fun PsiField.copyFromParchment(parchment: FieldData): PsiField {
    val old = this
    val oldModifiers = modifierList!!
    return object : LightFieldBuilder(name, type, navigationElement) {
        override fun isDeprecated(): Boolean {
            return old.isDeprecated
        }

        override fun getInitializer(): PsiExpression? {
            return old.initializer
        }

        init {
            setModifierList(object : LightModifierList(old) {
                override fun getAnnotations(): Array<PsiAnnotation> = oldModifiers.annotations

                override fun getApplicableAnnotations(): Array<PsiAnnotation> = oldModifiers.applicableAnnotations
            })

            if (parchment.javadoc.isNotEmpty()) {
                setDocComment(JavaPsiFacade.getInstance(project).elementFactory.createDocCommentFromText(
                    "/**\n" + parchment.javadoc.joinToString("\n") + "\n*/", old
                ))
            }
        }

        override fun getNavigationElement(): PsiElement = this
    }.setContainingClass(containingClass)
}

fun PsiClass.copyFromParchment(parchment: ClassData): PsiClass {
    val old = this
    return object : LightPsiClassBuilder(context!!, name!!) {
        var docs: PsiDocComment? = null
        override fun getDocComment(): PsiDocComment? {
            if (docs == null && parchment.javadoc.isNotEmpty()) {
                docs = JavaPsiFacade.getInstance(project).elementFactory.createDocCommentFromText("/**\n" + parchment.javadoc.joinToString("\n") + "\n*/", old);
            }
            return docs
        }

        override fun getAnnotations(): Array<PsiAnnotation> {
            return old.annotations
        }

        init {
            modifierList.copyModifiers(old.modifierList)
            old.implementsList?.referencedTypes?.forEach {
                implementsList.addReference(it)
            }
            old.extendsList?.referencedTypes?.forEach {
                extendsList.addReference(it)
            }
            old.typeParameterList?.typeParameters?.forEach {
                typeParameterList.addParameter(it)
            }
        }
    }
}
